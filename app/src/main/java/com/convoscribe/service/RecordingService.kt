package com.convoscribe.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import com.convoscribe.ConvoApp
import com.convoscribe.MainActivity
import com.convoscribe.R
import com.convoscribe.diarization.DiarizeSessionUseCase
import com.convoscribe.llm.SummaryTemplates
import com.convoscribe.overlay.FloatingControl
import com.convoscribe.recording.ConversationOptions
import com.convoscribe.recording.ImportSource
import com.convoscribe.recording.ImportState
import com.convoscribe.recording.ImportStep
import com.convoscribe.recording.RecordingController
import com.convoscribe.recording.RecordingPhase
import com.convoscribe.recording.RetranscribeState
import java.io.FileNotFoundException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the [RecordingController] alive
 * while the screen is off or the app is in the background. Started
 * from the recording screen and from future widgets/quick-tile
 * entries, the service is the only owner of the session in the
 * foreground state — once started, killing the UI does not stop
 * the recording.
 *
 * Mandatory on Android 14+: a `microphone`-typed foreground
 * service with the matching permission and a low-importance
 * notification. The service deliberately does no audio work itself
 * — it delegates that to [RecordingController]; its only jobs
 * are:
 *
 *  1. promote itself to a `microphone`-typed foreground service;
 *  2. publish + update the persistent notification so the user can
 *     see, mark (issue #30), pause / resume and stop the recording
 *     without re-opening the app;
 *  3. hold a partial [android.os.PowerManager.WakeLock] so the
 *     CPU stays awake during the capture loop (released while the
 *     session is paused, when there is nothing to process);
 *  4. observe [RecordingController.state] and tear everything down
 *     when the controller reports an `IDLE` + `error` so a failing
 *     session does not silently leave the service in the
 *     foreground.
 *
 * A paused session (issue #7) keeps the service in the foreground
 * with the `microphone` type: Android 14+ only lets a service open
 * the microphone from the background if its microphone foreground
 * started while the app was visible and has lasted since, so
 * downgrading it during a pause would break Resume from the
 * notification, the floating control or the tile.
 *
 * The service is intentionally not unit-tested beyond the text
 * helper — instantiating it would pull in the native sherpa
 * recognizer through [com.convoscribe.AppContainer].
 *
 * @see com.convoscribe.recording.RecordingController for the
 *   audio + ASR pipeline this service wraps.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Tag used for [PowerManager.WakeLock] acquisition / release. */
    private val wakeLockTag = "ConvoScribe:recording"

    /**
     * Held while the service is in the foreground so the audio
     * capture loop is never interrupted by the CPU going to sleep
     * between chunks. `null` outside an active session.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Job that follows [RecordingController.state] to update the
     * notification text and to detect an error-driven idle.
     * Cancelled in [onDestroy].
     */
    private var stateObserver: Job? = null

    /**
     * The floating control over other apps (issue #7), created with
     * the first recording. [overlayObserver] shows it while a session
     * is open, the setting is on and ConvoScribe is in the background.
     */
    private var floatingControl: FloatingControl? = null
    private var overlayObserver: Job? = null

    /**
     * Last notification-text update timestamp (elapsedRealtime).
     * Used to throttle [recordingNotificationText] to at most one
     * rebuild per second so the notification icon does not flap
     * while the state flow emits every audio frame.
     */
    private var lastNotificationUpdateMs: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(
                resumeSessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L).takeIf { it > 0L },
                options = optionsFrom(intent)
            )
            ACTION_STOP -> handleStop()
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_MARK -> handleMark()
            ACTION_TOGGLE_PAUSE -> when (controller().state.value.phase) {
                RecordingPhase.RECORDING -> handlePause()
                RecordingPhase.PAUSED -> handleResume()
                else -> if (idle()) stopSelf()
            }
            ACTION_DIARIZE -> {
                val id = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
                if (id <= 0L) {
                    // Defensive: a malformed intent must not crash
                    // the service. Bail out cleanly.
                    if (idle()) stopSelf()
                } else {
                    val count = intent.getIntExtra(EXTRA_NUM_SPEAKERS, NUM_SPEAKERS_UNSET)
                    handleDiarize(id, count.takeIf { it != NUM_SPEAKERS_UNSET })
                }
            }
            ACTION_RETRANSCRIBE -> {
                val id = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
                val language = intent.getStringExtra(EXTRA_LANGUAGE)
                if (id > 0L && language != null) {
                    handleRetranscribe(id, language, intent.getBooleanExtra(EXTRA_CLEAN, false))
                } else if (idle()) {
                    stopSelf()
                }
            }
            ACTION_CANCEL_RETRANSCRIBE -> {
                val job = retranscribeJob
                if (job != null) {
                    job.cancel()
                } else if (idle()) {
                    stopSelf()
                }
            }
            ACTION_IMPORT -> {
                val uri = intent.data
                if (uri != null) {
                    handleImport(uri)
                } else if (idle()) {
                    stopSelf()
                }
            }
            ACTION_CANCEL_IMPORT -> {
                val job = importJob
                val state = (application as ConvoApp).container.importAudioUseCase.state.value
                if (job != null && (state as? ImportState.Running)?.cancellable != false) {
                    job.cancel()
                } else if (idle()) {
                    stopSelf()
                }
            }
            // Defensive: an intent with an unknown action is a
            // client bug. Don't crash; just stay in foreground if
            // we already are, otherwise exit cleanly.
            else -> if (idle()) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Order matters: release the wake lock so we don't keep the
        // CPU pinned after the service has gone, then cancel the
        // scope so the state observer and any pending stop() work
        // finish. Cancellation is cooperative; next start will spin
        // up a fresh scope.
        releaseWakeLock()
        floatingControl?.setWanted(false)
        scope.cancel()
        super.onDestroy()
    }

    private fun handleStart(resumeSessionId: Long?, options: ConversationOptions?) {
        // Only allow one START — if the service is already driving
        // a session, second callers get the live notification and
        // nothing else. The controller's `start` is also idempotent
        // (it no-ops unless phase is IDLE).
        val controller = controller()
        // One heavy job at a time: a re-transcription keeps the
        // foreground (and the CPU) until it ends. The Record screen
        // disables Start meanwhile; this guards a stray intent.
        if (retranscribeJob != null || importJob != null) return
        ensureNotificationChannel()
        // A START for an already open session (e.g. a second tap)
        // must still promote the service, but keeps its live text.
        val current = controller.state.value
        val notification = buildNotification(
            current.elapsedMs,
            paused = current.phase == RecordingPhase.PAUSED,
            marks = current.markCount
        )
        // ServiceCompat ignores the foregroundServiceType argument
        // below API 29; the project's `minSdk = 29` so we always
        // get the typed call. On Android 14+ the type must be
        // `microphone` for mic capture to keep working in the
        // background.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        acquireWakeLock()
        controller.start(scope, resumeSessionId, options)
        startStateObserver()
        startOverlayObserver()
    }

    /**
     * Keep the floating control in step with the session, the
     * Settings switch and whether ConvoScribe is in the foreground.
     * Its buttons call the same handlers as the notification actions.
     */
    private fun startOverlayObserver() {
        if (overlayObserver?.isActive == true) return
        val container = (application as ConvoApp).container
        val control = floatingControl ?: FloatingControl(
            context = this,
            scope = scope,
            state = controller().state,
            settings = container.settingsRepository.settings,
            onOpen = { startActivity(MainActivity.openRecordIntent(this)) },
            onMark = ::handleMark,
            onPauseResume = {
                if (controller().state.value.phase == RecordingPhase.PAUSED) handleResume() else handlePause()
            },
            onStop = ::handleStop,
            onShapeChange = { shape -> container.settingsRepository.update { it.copy(floatingShape = shape.key) } }
        ).also { floatingControl = it }
        overlayObserver = combine(
            controller().state.map { it.phase.active }.distinctUntilChanged(),
            container.settingsRepository.settings.map { it.floatingControl }.distinctUntilChanged(),
            container.appInForeground
        ) { active, enabled, foreground -> active && enabled && !foreground }
            .distinctUntilChanged()
            .onEach { control.setWanted(it) }
            .launchIn(scope)
    }

    private fun handleStop() {
        val controller = controller()
        // stop() is suspend; hop onto the service scope so we do
        // not block onStartCommand on the audio+ASR teardown.
        scope.launch {
            // A session stopped while paused gave its wake lock back;
            // diarization needs it again.
            acquireWakeLock()
            val completedId = controller.stop()
            // Mic capture has ended; the service stays in the
            // foreground but switches to a `dataSync` type while
            // diarization runs. The microphone is no longer in use,
            // so the microphone-typed foreground would not be
            // allowed past Android 14 anyway. The notification
            // text changes to "Identifying speakers…" so the user
            // sees the service is still working.
            ensureNotificationChannel()
            ServiceCompat.startForeground(
                this@RecordingService,
                NOTIFICATION_ID,
                buildDiarizingNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            if (completedId != null) {
                // Now, or left pending for the charger / the user
                // (Settings › Identify speakers, issue #11).
                diarizerUseCase().runOrDefer(completedId)
            }
            // Diarization is done (or there was nothing to
            // diarize). Release the wake lock first so we don't
            // keep the CPU pinned after the service has gone, then
            // tear down the foreground state in one go so we don't
            // briefly leave a stale "Identifying…" notification
            // behind.
            // An import started meanwhile owns the foreground now.
            if (importJob == null) {
                releaseWakeLock()
                ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * Pause the session (issue #7). The service stays in the
     * microphone-typed foreground so Resume can reopen the mic from
     * the background; the wake lock is released since nothing runs
     * while paused. A no-op when no session is recording.
     */
    private fun handlePause() {
        val controller = controller()
        if (controller.state.value.phase != RecordingPhase.RECORDING) {
            if (idle()) stopSelf()
            return
        }
        scope.launch {
            controller.pause()
            if (controller.state.value.phase == RecordingPhase.PAUSED) releaseWakeLock()
        }
    }

    /**
     * Mark the current moment of the open session (issue #30), from the
     * notification or the floating control. A no-op without one; the
     * notification's mark count shows it was taken.
     */
    private fun handleMark() {
        val controller = controller()
        if (!controller.state.value.phase.active) {
            if (idle()) stopSelf()
            return
        }
        scope.launch { controller.mark() }
    }

    /** Resume a paused session on the same transcriber and WAV; no-op otherwise. */
    private fun handleResume() {
        val controller = controller()
        if (controller.state.value.phase != RecordingPhase.PAUSED) {
            if (idle()) stopSelf()
            return
        }
        scope.launch {
            acquireWakeLock()
            controller.resume(scope)
        }
    }

    /**
     * Run the diarization use case for [sessionId] in the
     * background. This is the manual "retry" entry point the
     * session-detail screen uses after a `DIARIZATION_FAILED` row.
     * The service promotes itself to a `dataSync`-typed
     * foreground (the microphone is not used here) with the
     * "Identifying speakers…" notification, runs the use case,
     * and stops.
     */
    private fun handleDiarize(sessionId: Long, numSpeakers: Int?) {
        ensureNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildDiarizingNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        scope.launch {
            diarizerUseCase().run(sessionId, numSpeakers = numSpeakers)
            if (idle()) {
                ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /** The running re-transcription, so [ACTION_CANCEL_RETRANSCRIBE] can stop it. */
    private var retranscribeJob: Job? = null

    /**
     * Re-transcribe [sessionId] in [language], then re-identify its
     * speakers, in a `dataSync` foreground with a progress
     * notification and a Cancel action. Refused while a recording or
     * another re-transcription runs (the service is already in the
     * foreground then). Cancel or failure keeps the old transcript.
     * With [clean] the audio is cleaned first (issue #37). Once the
     * transcript is swapped in, the notification turns into the
     * "Identifying speakers…" one while speakers are identified.
     */
    private fun handleRetranscribe(sessionId: Long, language: String, clean: Boolean) {
        val useCase = (application as ConvoApp).container.retranscribeUseCase
        if (!idle() || useCase.isRunning) return
        ensureNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildRetranscribeNotification(0f, cleaning = clean),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        acquireWakeLock()
        retranscribeJob = scope.launch {
            var started = false
            var lastCleaning: Boolean? = null
            val progress = useCase.state
                .onEach { state ->
                    if (state is RetranscribeState.Running) {
                        started = true
                        // Cleaning → transcribing shows at once (issue #43).
                        maybeRefreshRetranscribeNotification(
                            state.progress,
                            state.cleaning,
                            force = state.cleaning != lastCleaning
                        )
                        lastCleaning = state.cleaning
                    } else if (started && state is RetranscribeState.Idle && diarizerUseCase().runsNow()) {
                        // The new transcript is in and speakers are identified
                        // next, inside run(): say so instead of leaving the
                        // last throttled percentage on screen (issue #43).
                        // A cancel cancels this collector too, and a failure
                        // ends in Failed, so neither shows it.
                        ServiceCompat.startForeground(
                            this@RecordingService,
                            NOTIFICATION_ID,
                            buildDiarizingNotification(),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        )
                    }
                }
                .launchIn(this)
            try {
                useCase.run(sessionId, language, clean)
            } finally {
                progress.cancel()
                retranscribeJob = null
                releaseWakeLock()
                // A recording started meanwhile owns the foreground now.
                if (idle()) {
                    ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    /** The running import, so [ACTION_CANCEL_IMPORT] can stop it. */
    private var importJob: Job? = null

    /**
     * No recording, re-transcription or import holds the service; it
     * may leave the foreground.
     */
    private fun idle(): Boolean =
        controller().state.value.phase == RecordingPhase.IDLE && retranscribeJob == null && importJob == null

    /**
     * Import the audio file at [uri] as a new session (issue #3), in a
     * `dataSync` foreground with a progress notification and a Cancel
     * action. Refused while a recording, a re-transcription or another
     * import runs; the UI checks first and says so. The intent carries
     * a read grant for [uri], which the use case uses to copy the file
     * before anything else.
     */
    private fun handleImport(uri: Uri) {
        val useCase = (application as ConvoApp).container.importAudioUseCase
        if (!idle() || useCase.isRunning) return
        ensureNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildImportNotification(ImportState.Running(null, ImportStep.COPYING, 0f)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        acquireWakeLock()
        val source = importSource(uri)
        importJob = scope.launch {
            var lastStep: ImportStep? = null
            val progress = useCase.state
                .onEach { state ->
                    if (state is ImportState.Running) {
                        // A new step shows at once; its progress only once a second.
                        maybeRefreshImportNotification(state, force = state.step != lastStep)
                        lastStep = state.step
                    }
                }
                .launchIn(this)
            try {
                useCase.run(source)
            } finally {
                progress.cancel()
                importJob = null
                releaseWakeLock()
                if (idle()) {
                    ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    /** [uri] as an [ImportSource]: its display name and size from the provider, if it has them. */
    private fun importSource(uri: Uri): ImportSource {
        var name: String? = null
        var size: Long? = null
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                    }
                }
        }
        val fallbackName = name ?: uri.lastPathSegment?.substringAfterLast('/')
        return ImportSource(fallbackName, size) {
            contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
        }
    }

    private fun maybeRefreshImportNotification(state: ImportState.Running, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotificationUpdateMs < NOTIFICATION_REFRESH_PERIOD_MS) return
        lastNotificationUpdateMs = now
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildImportNotification(state),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun buildImportNotification(state: ImportState.Running): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val percent = (state.progress * 100).toInt().coerceIn(0, 100)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(state.name ?: getString(R.string.notification_import_title))
            .setContentText(getString(importStepText(state.step), percent))
            .setProgress(100, percent, false)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (state.cancellable) {
            val cancelIntent = PendingIntent.getService(
                this,
                REQUEST_CANCEL_IMPORT,
                cancelImportIntent(this),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_launcher_foreground,
                    getString(R.string.notification_import_cancel),
                    cancelIntent
                ).build()
            )
        }
        return builder.build()
    }

    private fun maybeRefreshRetranscribeNotification(progress: Float, cleaning: Boolean, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotificationUpdateMs < NOTIFICATION_REFRESH_PERIOD_MS) return
        lastNotificationUpdateMs = now
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildRetranscribeNotification(progress, cleaning),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun buildRetranscribeNotification(progress: Float, cleaning: Boolean = false): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelIntent = PendingIntent.getService(
            this,
            REQUEST_CANCEL_RETRANSCRIBE,
            cancelRetranscribeIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val percent = (progress * 100).toInt().coerceIn(0, 100)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_retranscribe_title))
            .setContentText(
                getString(
                    if (cleaning) R.string.notification_cleanup_text else R.string.notification_retranscribe_text,
                    percent
                )
            )
            .setProgress(100, percent, false)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_launcher_foreground,
                    getString(R.string.notification_retranscribe_cancel),
                    cancelIntent
                ).build()
            )
            .build()
    }

    private fun startStateObserver() {
        // Cancel any previous observer — handleStart() can be
        // called more than once if the client hits START twice
        // before the first ACTION_STOP propagates.
        stateObserver?.cancel()
        var lastPhase: RecordingPhase? = null
        var lastMarks = 0
        stateObserver = controller().state
            .onEach { state ->
                // A phase change (paused / resumed) or a new mark shows
                // at once; the timer text only once a second.
                val changed = state.phase != lastPhase || state.markCount != lastMarks
                lastPhase = state.phase
                lastMarks = state.markCount
                // LOADING / STOPPING: the start / stop paths own the
                // notification.
                if (state.phase.active) {
                    maybeRefreshNotification(
                        state.elapsedMs,
                        state.phase == RecordingPhase.PAUSED,
                        state.markCount,
                        changed
                    )
                } else if (state.phase == RecordingPhase.IDLE && state.error != null) {
                    // The controller fell back to IDLE because of
                    // an error: surface a final teardown so we do
                    // not leak the foreground notification while
                    // showing a dead service.
                    releaseWakeLock()
                    ServiceCompat.stopForeground(
                        this@RecordingService,
                        ServiceCompat.STOP_FOREGROUND_REMOVE
                    )
                    stopSelf()
                }
            }
            .launchIn(scope)
    }

    /**
     * Re-render the notification text at most once per second
     * ([recordingNotificationText] is cheap but the state flow
     * emits on every chunk and the OS would otherwise drop frames
     * of unrelated work while we re-bind builders).
     */
    private fun maybeRefreshNotification(elapsedMs: Long, paused: Boolean, marks: Int, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotificationUpdateMs < NOTIFICATION_REFRESH_PERIOD_MS) return
        lastNotificationUpdateMs = now
        val notification = buildNotification(elapsedMs = elapsedMs, paused = paused, marks = marks)
        // `startForeground` is documented to be cheap to re-invoke
        // with the same id and is the supported way to update the
        // contents of a foreground notification.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService<PowerManager>() ?: return
        val lock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            wakeLockTag
        ).apply {
            // Set a generous timeout as a safety net: even if
            // releaseWakeLock() is missed (e.g. process killed),
            // the lock will free in ~1 h and stop pinning the CPU.
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        wakeLock = lock
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        if (lock.isHeld) {
            runCatching { lock.release() }
        }
    }

    private fun ensureNotificationChannel() = ensureChannel(this)

    /**
     * The recording notification: elapsed recorded time and [marks],
     * then Mark, Pause or Resume, and Stop. [paused] swaps the title,
     * text and action; Mark works either way.
     */
    private fun buildNotification(elapsedMs: Long, paused: Boolean = false, marks: Int = 0): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val mark = NotificationCompat.Action.Builder(
            R.drawable.ic_flag,
            getString(R.string.notification_recording_action_mark),
            PendingIntent.getService(
                this,
                REQUEST_MARK,
                markIntent(this),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        val pauseOrResume = if (paused) {
            NotificationCompat.Action.Builder(
                R.drawable.ic_play,
                getString(R.string.notification_recording_action_resume),
                PendingIntent.getService(
                    this,
                    REQUEST_RESUME,
                    resumeIntent(this),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        } else {
            NotificationCompat.Action.Builder(
                R.drawable.ic_pause,
                getString(R.string.notification_recording_action_pause),
                PendingIntent.getService(
                    this,
                    REQUEST_PAUSE,
                    pauseIntent(this),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(
                getString(if (paused) R.string.notification_paused_title else R.string.notification_recording_title)
            )
            .setContentText(recordingNotificationText(elapsedMs, paused, marks))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(mark.build())
            .addAction(pauseOrResume.build())
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_stop,
                    getString(R.string.notification_recording_action_stop),
                    stopIntent
                ).build()
            )
            .build()
    }

    /**
     * Resolve the recording controller off the [ConvoApp]
     * container. The service is created by the OS, not by us, so
     * this is the one piece of bootstrap we need.
     */
    private fun controller(): RecordingController {
        return (application as ConvoApp).container.recordingController
    }

    /**
     * Resolve the diarization use case off the [ConvoApp]
     * container. Used by both the post-stop auto-diarization and
     * the manual [ACTION_DIARIZE] path.
     */
    private fun diarizerUseCase(): DiarizeSessionUseCase {
        return (application as ConvoApp).container.diarizeSessionUseCase
    }

    /**
     * Build the "Identifying speakers…" notification used while
     * the diarization use case is in flight. Re-uses the
     * recording channel and the same small icon, so the user only
     * ever sees one notification id; the text is the only thing
     * that changes.
     */
    private fun buildDiarizingNotification(): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_diarizing_title))
            .setContentText(getString(R.string.notification_diarizing_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        /** Create the `Recording` notification channel if it does not exist yet. */
        fun ensureChannel(context: Context) {
            val nm = context.getSystemService<NotificationManager>() ?: return
            // `getNotificationChannel` returns the existing channel if
            // already created — re-creating would be wasted work but
            // not an error. We still guard against duplicates.
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                // Name shown in system Settings → Apps → ConvoScribe →
                // Notifications. Lowercased to match Material guidance;
                // the label sits next to the description and does not
                // need to scream.
                context.getString(R.string.notification_channel_recording),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_recording_description)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        /** Notification id used for the foreground notification. */
        private const val NOTIFICATION_ID = 1

        /**
         * Notification channel id for the `Recording` channel; also used
         * by the charging job's notice ([DiarizationJobService]).
         */
        const val CHANNEL_ID = "recording"

        /** `requestCode` for the content (open-app) pending intent. */
        private const val REQUEST_OPEN = 1001

        /** `requestCode` for the stop pending intent. */
        private const val REQUEST_STOP = 1002

        /** `requestCode` for the cancel-re-transcription pending intent. */
        private const val REQUEST_CANCEL_RETRANSCRIBE = 1003

        /** `requestCode` for the cancel-import pending intent. */
        private const val REQUEST_CANCEL_IMPORT = 1004

        /** `requestCode` for the pause pending intent. */
        private const val REQUEST_PAUSE = 1005

        /** `requestCode` for the resume pending intent. */
        private const val REQUEST_RESUME = 1006

        /** `requestCode` for the mark pending intent. */
        private const val REQUEST_MARK = 1007

        /** Upper bound on the wake-lock acquired for one session. */
        private const val WAKE_LOCK_TIMEOUT_MS = 60L * 60L * 1_000L // 1 h

        /** Throttle window for notification text refreshes. */
        private const val NOTIFICATION_REFRESH_PERIOD_MS = 1_000L

        /** Action sent to [RecordingService] to start a session. */
        const val ACTION_START = "com.convoscribe.START"

        /** Action sent to [RecordingService] to stop the session. */
        const val ACTION_STOP = "com.convoscribe.STOP"

        /** Action to pause the recording session, keeping it open (issue #7). */
        const val ACTION_PAUSE = "com.convoscribe.PAUSE"

        /** Action to resume a paused recording session. */
        const val ACTION_RESUME = "com.convoscribe.RESUME"

        /** Action to mark the current moment of the open session (issue #30). */
        const val ACTION_MARK = "com.convoscribe.MARK"

        /** Action to pause a recording session or resume a paused one (the Quick Settings tile). */
        const val ACTION_TOGGLE_PAUSE = "com.convoscribe.TOGGLE_PAUSE"

        /**
         * Action sent to [RecordingService] to re-run diarization
         * for a session that ended in `DIARIZATION_FAILED`. The
         * session id is passed in [EXTRA_SESSION_ID]. The caller
         * is responsible for `startForegroundService` (Android 8+)
         * and the `POST_NOTIFICATIONS` runtime permission
         * (Android 13+).
         */
        const val ACTION_DIARIZE = "com.convoscribe.DIARIZE"

        /**
         * Action to re-transcribe [EXTRA_SESSION_ID]'s audio in the
         * language in [EXTRA_LANGUAGE], then re-identify speakers.
         */
        const val ACTION_RETRANSCRIBE = "com.convoscribe.RETRANSCRIBE"

        /**
         * Action to import the audio file in the intent's data URI as a
         * new session (issue #3); the intent must grant read access.
         */
        const val ACTION_IMPORT = "com.convoscribe.IMPORT"

        /** Action to cancel a running [ACTION_IMPORT] before its session exists. */
        const val ACTION_CANCEL_IMPORT = "com.convoscribe.CANCEL_IMPORT"

        /** Action to cancel a running [ACTION_RETRANSCRIBE]; the old transcript is kept. */
        const val ACTION_CANCEL_RETRANSCRIBE = "com.convoscribe.CANCEL_RETRANSCRIBE"

        /**
         * Long extra key for the session id: required by
         * [ACTION_DIARIZE] and [ACTION_RETRANSCRIBE]; optional on [ACTION_START], where it
         * names a finished session to continue recording into.
         */
        const val EXTRA_SESSION_ID = "com.convoscribe.EXTRA_SESSION_ID"

        /**
         * Optional int extra for [ACTION_DIARIZE]: the speaker count
         * to save on the session and use (`-1` automatic). Also part
         * of a new session's options on [ACTION_START].
         */
        const val EXTRA_NUM_SPEAKERS = "com.convoscribe.EXTRA_NUM_SPEAKERS"

        private const val NUM_SPEAKERS_UNSET = Int.MIN_VALUE

        /**
         * Extras for [ACTION_START] carrying a new session's
         * [ConversationOptions]; absent means the Settings defaults.
         * [EXTRA_PARTICIPANTS] is absent for anyone.
         */
        private const val EXTRA_LANGUAGE = "com.convoscribe.EXTRA_LANGUAGE"
        private const val EXTRA_CLEAN = "com.convoscribe.EXTRA_CLEAN"
        private const val EXTRA_PARTICIPANTS = "com.convoscribe.EXTRA_PARTICIPANTS"
        private const val EXTRA_TITLE = "com.convoscribe.EXTRA_TITLE"
        private const val EXTRA_CLEAN_AUDIO = "com.convoscribe.EXTRA_CLEAN_AUDIO"
        private const val EXTRA_SUMMARY_TEMPLATE = "com.convoscribe.EXTRA_SUMMARY_TEMPLATE"

        /** The [ConversationOptions] [startIntent] put on [intent], or `null` for the Settings defaults. */
        internal fun optionsFrom(intent: Intent): ConversationOptions? {
            val language = intent.getStringExtra(EXTRA_LANGUAGE) ?: return null
            return ConversationOptions(
                language = language,
                numSpeakers = intent.getIntExtra(EXTRA_NUM_SPEAKERS, -1),
                participants = intent.getStringArrayExtra(EXTRA_PARTICIPANTS)?.toList(),
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                summaryTemplate = intent.getStringExtra(EXTRA_SUMMARY_TEMPLATE) ?: SummaryTemplates.MEETING_KEY,
                cleanAudio = intent.getBooleanExtra(EXTRA_CLEAN_AUDIO, false)
            )
        }

        /**
         * Build an [Intent] that, when sent to [RecordingService],
         * starts a new recording session with [options] (the Settings
         * defaults when `null`), or continues [resumeSessionId] by
         * appending to its audio and transcript.
         * The caller is responsible for `startForegroundService`
         * (Android 8+) and the `POST_NOTIFICATIONS` runtime
         * permission (Android 13+).
         */
        fun startIntent(context: Context, resumeSessionId: Long? = null, options: ConversationOptions? = null): Intent =
            Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .apply {
                    if (resumeSessionId != null) putExtra(EXTRA_SESSION_ID, resumeSessionId)
                    if (options != null) {
                        putExtra(EXTRA_LANGUAGE, options.language)
                        putExtra(EXTRA_NUM_SPEAKERS, options.numSpeakers)
                        options.participants?.let { putExtra(EXTRA_PARTICIPANTS, it.toTypedArray()) }
                        putExtra(EXTRA_TITLE, options.title)
                        putExtra(EXTRA_CLEAN_AUDIO, options.cleanAudio)
                        putExtra(EXTRA_SUMMARY_TEMPLATE, options.summaryTemplate)
                    }
                }

        /**
         * Build an [Intent] that re-transcribes [sessionId] in
         * [language], cleaning its audio first when [clean] is set
         * (*Clean up audio*, issue #37). Send it with
         * `startForegroundService`.
         */
        fun retranscribeIntent(context: Context, sessionId: Long, language: String, clean: Boolean = false): Intent =
            Intent(context, RecordingService::class.java)
                .setAction(ACTION_RETRANSCRIBE)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_LANGUAGE, language)
                .putExtra(EXTRA_CLEAN, clean)

        /**
         * Build an [Intent] that imports [uri] as a new session, passing
         * on the caller's read grant. Send it with
         * `startForegroundService`.
         */
        fun importIntent(context: Context, uri: Uri): Intent = Intent(context, RecordingService::class.java)
            .setAction(ACTION_IMPORT)
            .setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        /** Build an [Intent] that cancels a running import; plain `startService`. */
        fun cancelImportIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_CANCEL_IMPORT)

        /** Build an [Intent] that cancels a running re-transcription; plain `startService`. */
        fun cancelRetranscribeIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_CANCEL_RETRANSCRIBE)

        /**
         * Build an [Intent] that, when sent to [RecordingService],
         * stops the active session. Safe to call from anywhere —
         * no-op if the service is not running.
         */
        fun stopIntent(context: Context): Intent = Intent(context, RecordingService::class.java).setAction(ACTION_STOP)

        /** Build an [Intent] that pauses the active session; plain `startService`. */
        fun pauseIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_PAUSE)

        /** Build an [Intent] that resumes a paused session; plain `startService`. */
        fun resumeIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_RESUME)

        /** Build an [Intent] that marks the current moment of the session; plain `startService`. */
        fun markIntent(context: Context): Intent = Intent(context, RecordingService::class.java).setAction(ACTION_MARK)

        /** Build an [Intent] that pauses or resumes the session; plain `startService`. */
        fun togglePauseIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_TOGGLE_PAUSE)

        /**
         * Build an [Intent] that, when sent to [RecordingService],
         * re-runs diarization for [sessionId], optionally with a new
         * speaker count for that session ([numSpeakers], `-1`
         * automatic). The service promotes to a `dataSync`-typed
         * foreground while it works, then stops itself when done.
         */
        fun diarizeIntent(context: Context, sessionId: Long, numSpeakers: Int? = null): Intent {
            return Intent(context, RecordingService::class.java)
                .setAction(ACTION_DIARIZE)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .apply { if (numSpeakers != null) putExtra(EXTRA_NUM_SPEAKERS, numSpeakers) }
        }
    }
}
