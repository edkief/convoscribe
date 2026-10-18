package com.convoscribe.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import com.convoscribe.ConvoApp
import com.convoscribe.MainActivity
import com.convoscribe.R
import com.convoscribe.diarization.DiarizeSessionUseCase
import com.convoscribe.recording.ConversationOptions
import com.convoscribe.recording.RecordingController
import com.convoscribe.recording.RecordingPhase
import com.convoscribe.recording.RetranscribeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
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
 *     see (and stop) the recording without re-opening the app;
 *  3. hold a partial [android.os.PowerManager.WakeLock] so the
 *     CPU stays awake during the capture loop;
 *  4. observe [RecordingController.state] and tear everything down
 *     when the controller reports an `IDLE` + `error` so a failing
 *     session does not silently leave the service in the
 *     foreground.
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
            ACTION_DIARIZE -> {
                val id = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
                if (id <= 0L) {
                    // Defensive: a malformed intent must not crash
                    // the service. Bail out cleanly.
                    if (controller().state.value.phase == RecordingPhase.IDLE) stopSelf()
                } else {
                    val count = intent.getIntExtra(EXTRA_NUM_SPEAKERS, NUM_SPEAKERS_UNSET)
                    handleDiarize(id, count.takeIf { it != NUM_SPEAKERS_UNSET })
                }
            }
            ACTION_RETRANSCRIBE -> {
                val id = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
                val language = intent.getStringExtra(EXTRA_LANGUAGE)
                if (id > 0L && language != null) {
                    handleRetranscribe(id, language)
                } else if (controller().state.value.phase == RecordingPhase.IDLE && retranscribeJob == null) {
                    stopSelf()
                }
            }
            ACTION_CANCEL_RETRANSCRIBE -> {
                val job = retranscribeJob
                if (job != null) {
                    job.cancel()
                } else if (controller().state.value.phase == RecordingPhase.IDLE) {
                    stopSelf()
                }
            }
            // Defensive: an intent with an unknown action is a
            // client bug. Don't crash; just stay in foreground if
            // we already are, otherwise exit cleanly.
            else -> if (controller().state.value.phase == RecordingPhase.IDLE) stopSelf()
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
        if (retranscribeJob != null) return
        ensureNotificationChannel()
        val notification = buildNotification(elapsedMs = 0L)
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
    }

    private fun handleStop() {
        val controller = controller()
        // stop() is suspend; hop onto the service scope so we do
        // not block onStartCommand on the audio+ASR teardown.
        scope.launch {
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
                diarizerUseCase().run(completedId)
            }
            // Diarization is done (or there was nothing to
            // diarize). Release the wake lock first so we don't
            // keep the CPU pinned after the service has gone, then
            // tear down the foreground state in one go so we don't
            // briefly leave a stale "Identifying…" notification
            // behind.
            releaseWakeLock()
            ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
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
            ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
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
     */
    private fun handleRetranscribe(sessionId: Long, language: String) {
        val useCase = (application as ConvoApp).container.retranscribeUseCase
        val busy = controller().state.value.phase != RecordingPhase.IDLE || retranscribeJob != null
        if (busy || useCase.isRunning) return
        ensureNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildRetranscribeNotification(0f),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        acquireWakeLock()
        retranscribeJob = scope.launch {
            val progress = useCase.state
                .onEach { state ->
                    if (state is RetranscribeState.Running) maybeRefreshRetranscribeNotification(state.progress)
                }
                .launchIn(this)
            try {
                useCase.run(sessionId, language)
            } finally {
                progress.cancel()
                retranscribeJob = null
                releaseWakeLock()
                // A recording started meanwhile owns the foreground now.
                if (controller().state.value.phase == RecordingPhase.IDLE) {
                    ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun maybeRefreshRetranscribeNotification(progress: Float) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotificationUpdateMs < NOTIFICATION_REFRESH_PERIOD_MS) return
        lastNotificationUpdateMs = now
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildRetranscribeNotification(progress),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun buildRetranscribeNotification(progress: Float): android.app.Notification {
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
            .setContentText(getString(R.string.notification_retranscribe_text, percent))
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
        stateObserver = controller().state
            .onEach { state ->
                if (state.phase != RecordingPhase.IDLE) {
                    maybeRefreshNotification(state.elapsedMs)
                } else if (state.error != null) {
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
    private fun maybeRefreshNotification(elapsedMs: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotificationUpdateMs < NOTIFICATION_REFRESH_PERIOD_MS) return
        lastNotificationUpdateMs = now
        val notification = buildNotification(elapsedMs = elapsedMs)
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

    private fun ensureNotificationChannel() {
        val nm = getSystemService<NotificationManager>() ?: return
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
            getString(R.string.notification_channel_recording),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_recording_description)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(elapsedMs: Long): android.app.Notification {
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_recording_title))
            .setContentText(recordingNotificationText(elapsedMs))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_launcher_foreground,
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
        /** Notification id used for the foreground notification. */
        private const val NOTIFICATION_ID = 1

        /** Notification channel id for the `Recording` channel. */
        private const val CHANNEL_ID = "recording"

        /** `requestCode` for the content (open-app) pending intent. */
        private const val REQUEST_OPEN = 1001

        /** `requestCode` for the stop pending intent. */
        private const val REQUEST_STOP = 1002

        /** `requestCode` for the cancel-re-transcription pending intent. */
        private const val REQUEST_CANCEL_RETRANSCRIBE = 1003

        /** Upper bound on the wake-lock acquired for one session. */
        private const val WAKE_LOCK_TIMEOUT_MS = 60L * 60L * 1_000L // 1 h

        /** Throttle window for notification text refreshes. */
        private const val NOTIFICATION_REFRESH_PERIOD_MS = 1_000L

        /** Action sent to [RecordingService] to start a session. */
        const val ACTION_START = "com.convoscribe.START"

        /** Action sent to [RecordingService] to stop the session. */
        const val ACTION_STOP = "com.convoscribe.STOP"

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
        private const val EXTRA_PARTICIPANTS = "com.convoscribe.EXTRA_PARTICIPANTS"
        private const val EXTRA_TITLE = "com.convoscribe.EXTRA_TITLE"

        private fun optionsFrom(intent: Intent): ConversationOptions? {
            val language = intent.getStringExtra(EXTRA_LANGUAGE) ?: return null
            return ConversationOptions(
                language = language,
                numSpeakers = intent.getIntExtra(EXTRA_NUM_SPEAKERS, -1),
                participants = intent.getStringArrayExtra(EXTRA_PARTICIPANTS)?.toList(),
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
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
                    }
                }

        /**
         * Build an [Intent] that re-transcribes [sessionId] in
         * [language]. Send it with `startForegroundService`.
         */
        fun retranscribeIntent(context: Context, sessionId: Long, language: String): Intent =
            Intent(context, RecordingService::class.java)
                .setAction(ACTION_RETRANSCRIBE)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_LANGUAGE, language)

        /** Build an [Intent] that cancels a running re-transcription; plain `startService`. */
        fun cancelRetranscribeIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_CANCEL_RETRANSCRIBE)

        /**
         * Build an [Intent] that, when sent to [RecordingService],
         * stops the active session. Safe to call from anywhere —
         * no-op if the service is not running.
         */
        fun stopIntent(context: Context): Intent = Intent(context, RecordingService::class.java).setAction(ACTION_STOP)

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
