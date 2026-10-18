package com.convoscribe.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import com.convoscribe.ConvoApp
import com.convoscribe.MainActivity
import com.convoscribe.R
import com.convoscribe.models.ModelBundle
import com.convoscribe.models.ModelCatalog
import com.convoscribe.models.ModelDownloadManager
import com.convoscribe.models.ModelDownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the model download alive while the screen is off or the user
 * is in another app (issue #10): a `dataSync` foreground service with a
 * progress notification and a Cancel action, a partial wake lock and a
 * Wi-Fi lock. The download itself runs in [ModelDownloadManager]; this
 * service starts it, mirrors its state in the notification, and stops
 * once it is no longer running.
 *
 * Separate from [RecordingService] (its own notification and channel),
 * so a download started from Settings can run next to a recording.
 * Started only from the app in the foreground (the Download tap), as
 * Android requires for a `dataSync` foreground service.
 */
class ModelDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private val locks = DownloadLocks(this, "ConvoScribe:modelDownload")
    private var lastNotificationUpdateMs = 0L

    private fun manager(): ModelDownloadManager = (application as ConvoApp).container.modelDownloadManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(bundlesFrom(intent))
            ACTION_CANCEL -> {
                manager().cancel()
                // Started by the Cancel action alone: nothing to watch.
                if (observer == null) stopSelf()
            }
        }
        // A killed process keeps the part files; the user resumes with
        // Retry rather than the system restarting a download unasked.
        return START_NOT_STICKY
    }

    /**
     * Android 15+: a `dataSync` service has run out of its daily time.
     * Stop now and keep what was downloaded, so Retry resumes it.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        manager().interrupt(getString(R.string.download_timeout))
        finish(manager().state.value)
    }

    override fun onDestroy() {
        locks.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun handleStart(bundles: List<ModelBundle>) {
        ensureDownloadsChannel(this)
        val manager = manager()
        // Attaches to a download already running instead of starting a second.
        manager.start(bundles)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildProgressNotification(manager.state.value as? ModelDownloadState.Running),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        locks.acquire()
        if (observer?.isActive == true) return
        observer = scope.launch {
            manager.state.collect { state ->
                if (state is ModelDownloadState.Running) {
                    maybeRefreshNotification(state)
                } else {
                    finish(state)
                }
            }
        }
    }

    /** Leave the foreground, say how the download ended, and stop. */
    private fun finish(state: ModelDownloadState) {
        observer?.cancel()
        observer = null
        locks.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        buildResultNotification(state)?.let { notification ->
            getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, notification)
        }
        stopSelf()
    }

    private fun maybeRefreshNotification(state: ModelDownloadState.Running) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotificationUpdateMs < NOTIFICATION_REFRESH_PERIOD_MS) return
        lastNotificationUpdateMs = now
        getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, buildProgressNotification(state))
    }

    private fun buildProgressNotification(state: ModelDownloadState.Running?): Notification {
        val cancel = PendingIntent.getService(
            this,
            REQUEST_CANCEL,
            cancelIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_download_title))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, getString(R.string.notification_download_cancel), cancel)
        val unpacking = state?.unpacking
        when {
            state == null -> builder.setProgress(0, 0, true)
            unpacking != null -> {
                val percent = (unpacking * 100).toInt()
                builder.setContentText(getString(R.string.notification_download_unpacking, percent))
                    .setProgress(100, percent, false)
            }
            else -> {
                val percent = if (state.bytesTotal > 0) (state.bytesDone * 100 / state.bytesTotal).toInt() else 0
                val text = if (state.waiting) {
                    getString(R.string.notification_download_waiting)
                } else {
                    getString(
                        R.string.notification_download_progress,
                        state.bytesDone / 1_000_000,
                        state.bytesTotal / 1_000_000
                    )
                }
                builder.setContentText(text).setProgress(100, percent, false)
            }
        }
        return builder.build()
    }

    /** "Models downloaded" or "Download failed: …"; nothing after Cancel. */
    private fun buildResultNotification(state: ModelDownloadState): Notification? {
        val (title, text) = when (state) {
            is ModelDownloadState.Completed ->
                getString(R.string.notification_download_done_title) to
                    getString(R.string.notification_download_done_text)
            is ModelDownloadState.Failed ->
                getString(R.string.notification_download_failed_title) to state.message
            else -> return null
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_OPEN,
        Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        /** Own id, so a download and a recording each keep their notification. */
        private const val NOTIFICATION_ID = 2

        /** "Downloads" channel, shared with [UpdateDownloadService]. */
        const val CHANNEL_ID = "downloads"

        /** Create the [CHANNEL_ID] channel unless it exists. */
        fun ensureDownloadsChannel(context: Context) {
            val nm = context.getSystemService<NotificationManager>() ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_downloads),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_downloads_description)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        private const val REQUEST_OPEN = 2001
        private const val REQUEST_CANCEL = 2002

        private const val NOTIFICATION_REFRESH_PERIOD_MS = 1_000L

        const val ACTION_START = "com.convoscribe.DOWNLOAD_MODELS"
        const val ACTION_CANCEL = "com.convoscribe.CANCEL_MODEL_DOWNLOAD"

        /** String-array extra of [ACTION_START]: the [ModelBundle.id]s to download. */
        const val EXTRA_BUNDLE_IDS = "com.convoscribe.extra.BUNDLE_IDS"

        private fun bundlesFrom(intent: Intent): List<ModelBundle> {
            val ids = intent.getStringArrayExtra(EXTRA_BUNDLE_IDS)?.toSet().orEmpty()
            return ModelCatalog.all.filter { it.id in ids }
        }

        /**
         * Build an [Intent] that downloads [bundles], or attaches to the
         * download already running. Send it with `startForegroundService`
         * while the app is in the foreground.
         */
        fun startIntent(context: Context, bundles: List<ModelBundle>): Intent =
            Intent(context, ModelDownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_BUNDLE_IDS, bundles.map { it.id }.toTypedArray())

        /** Build an [Intent] that cancels the download and deletes what it fetched; plain `startService`. */
        fun cancelIntent(context: Context): Intent =
            Intent(context, ModelDownloadService::class.java).setAction(ACTION_CANCEL)
    }
}
