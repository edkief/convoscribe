package com.convoscribe.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import com.convoscribe.ConvoApp
import com.convoscribe.MainActivity
import com.convoscribe.R
import com.convoscribe.update.UpdateManager
import com.convoscribe.update.UpdateOffer
import com.convoscribe.update.UpdateState
import com.convoscribe.update.updateFailureText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps an app update's download alive while the screen is off or the
 * user is in another app (issue #15), the way [ModelDownloadService]
 * does for models (issue #10): a `dataSync` foreground service with a
 * progress notification and Cancel, and the [DownloadLocks]. The
 * download runs in [UpdateManager]; this service starts it, mirrors
 * its state in the notification and stops once it ends, saying "ready
 * to install" or why it failed. Tapping either opens the app, whose
 * Home banner has *Install*.
 *
 * Started only from the app in the foreground (the *Update* tap), as
 * Android requires for a `dataSync` foreground service.
 */
class UpdateDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private val locks = DownloadLocks(this, "ConvoScribe:updateDownload")
    private var lastNotificationUpdateMs = 0L

    private fun manager(): UpdateManager = (application as ConvoApp).container.updateManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_CANCEL -> {
                manager().cancel()
                if (observer == null) stopSelf()
            }
        }
        // A killed process keeps the part file; the next Update resumes it.
        return START_NOT_STICKY
    }

    /** Android 15+: a `dataSync` service has run out of its daily time. Keep the part for a retry. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        manager().interrupt(getString(R.string.download_timeout))
        finish(manager().state.value)
    }

    override fun onDestroy() {
        locks.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun handleStart() {
        ModelDownloadService.ensureDownloadsChannel(this)
        val manager = manager()
        // Attaches to a download already running instead of starting a second.
        manager.download()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildProgressNotification(manager.state.value as? UpdateState.Downloading),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        if (!manager.isDownloading) {
            // Nothing to fetch (no offer, or already downloaded): leave quietly.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        locks.acquire()
        if (observer?.isActive == true) return
        observer = scope.launch {
            manager.state.collect { state ->
                if (state is UpdateState.Downloading) maybeRefreshNotification(state) else finish(state)
            }
        }
    }

    private fun finish(state: UpdateState) {
        observer?.cancel()
        observer = null
        locks.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        buildResultNotification(state)?.let { notification ->
            getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, notification)
        }
        stopSelf()
    }

    private fun maybeRefreshNotification(state: UpdateState.Downloading) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotificationUpdateMs < NOTIFICATION_REFRESH_PERIOD_MS) return
        lastNotificationUpdateMs = now
        getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, buildProgressNotification(state))
    }

    private fun buildProgressNotification(state: UpdateState.Downloading?): Notification {
        val cancel = PendingIntent.getService(
            this,
            REQUEST_CANCEL,
            cancelIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = state?.let { getString(R.string.notification_update_title, it.offer.versionName) }
            ?: getString(R.string.notification_update_title_generic)
        val builder = NotificationCompat.Builder(this, ModelDownloadService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentIntent(openAppIntent(this))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, getString(R.string.notification_download_cancel), cancel)
        if (state == null || state.bytesTotal <= 0) {
            builder.setProgress(0, 0, true)
        } else {
            val percent = (state.bytesDone * 100 / state.bytesTotal).toInt()
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
        return builder.build()
    }

    /** "Ready to install" or "Update failed"; nothing after Cancel. */
    private fun buildResultNotification(state: UpdateState): Notification? {
        val (title, text) = when (state) {
            is UpdateState.ReadyToInstall ->
                getString(R.string.notification_update_ready_title, state.offer.versionName) to
                    getString(R.string.notification_update_ready_text)
            is UpdateState.Failed ->
                getString(R.string.notification_update_failed_title) to updateFailureText(this, state)
            else -> return null
        }
        return NotificationCompat.Builder(this, ModelDownloadService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(this))
            .setAutoCancel(true)
            .build()
    }

    companion object {
        /** Own id, next to the recording (1) and model download (2) notifications. */
        const val NOTIFICATION_ID = 3

        private const val REQUEST_OPEN = 3001
        private const val REQUEST_CANCEL = 3002

        private const val NOTIFICATION_REFRESH_PERIOD_MS = 1_000L

        const val ACTION_START = "com.convoscribe.DOWNLOAD_UPDATE"
        const val ACTION_CANCEL = "com.convoscribe.CANCEL_UPDATE_DOWNLOAD"

        /** Download the offered update, or attach to the running download; `startForegroundService`, app in front. */
        fun startIntent(context: Context): Intent =
            Intent(context, UpdateDownloadService::class.java).setAction(ACTION_START)

        /** Cancel the download and delete what it fetched; plain `startService`. */
        fun cancelIntent(context: Context): Intent =
            Intent(context, UpdateDownloadService::class.java).setAction(ACTION_CANCEL)

        /**
         * *ConvoScribe X.Y.Z is available*, from the background check
         * (issue #41); `false` when notifications are off. Shares
         * [NOTIFICATION_ID], so a download that follows replaces it.
         */
        fun notifyAvailable(context: Context, offer: UpdateOffer): Boolean {
            val notifications = NotificationManagerCompat.from(context)
            if (!notifications.areNotificationsEnabled()) return false
            ModelDownloadService.ensureDownloadsChannel(context)
            val notification = NotificationCompat.Builder(context, ModelDownloadService.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.update_available_title, offer.versionName))
                .setContentText(context.getString(R.string.notification_update_available_text))
                .setContentIntent(openAppIntent(context))
                .setAutoCancel(true)
                .build()
            return runCatching {
                context.getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, notification) != null
            }.getOrDefault(false)
        }

        fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
