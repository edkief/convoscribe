package com.convoscribe.service

import android.app.Notification
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
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import com.convoscribe.ConvoApp
import com.convoscribe.R
import com.convoscribe.backup.BackupKind
import com.convoscribe.backup.BackupState
import com.convoscribe.backup.BackupUseCase
import com.convoscribe.backup.backupResultText
import com.convoscribe.backup.backupResultTitle
import java.io.FileNotFoundException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backs up or restores sessions (issue #25) while the screen is off or
 * the user is in another app: a `dataSync` foreground service with a
 * progress notification and Cancel, like [UpdateDownloadService]. The
 * work runs in [BackupUseCase]; this service opens the file the user
 * picked, mirrors the progress in the notification, and ends with a
 * notification saying what was saved or restored, or why it failed.
 *
 * A backup that fails or is cancelled deletes its partial file. A
 * restore keeps the sessions it finished, each complete.
 *
 * Refused while a recording, an import, a re-transcription or another
 * backup runs; Settings checks first and says so. Started only from the
 * app in the foreground, as Android requires for a `dataSync`
 * foreground service.
 */
class BackupService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationUpdateMs = 0L

    private fun useCase(): BackupUseCase = (application as ConvoApp).container.backupUseCase

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uri = intent?.data
        when (intent?.action) {
            ACTION_BACKUP -> if (uri != null) start(BackupKind.BACKUP, uri, restoreSettings = false)
            ACTION_RESTORE -> if (uri != null) {
                start(BackupKind.RESTORE, uri, intent.getBooleanExtra(EXTRA_RESTORE_SETTINGS, true))
            }
            ACTION_CANCEL -> job?.cancel()
        }
        if (job?.isActive != true) stopSelf()
        // A killed process leaves no half-restored session; the user restores again.
        return START_NOT_STICKY
    }

    /** Android 15+: a `dataSync` service has run out of its daily time. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun start(kind: BackupKind, uri: Uri, restoreSettings: Boolean) {
        val container = (application as ConvoApp).container
        val useCase = useCase()
        val running = useCase.state.value as? BackupState.Running
        // Started with startForegroundService: go to the foreground even to refuse.
        ModelDownloadService.ensureDownloadsChannel(this)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildProgressNotification(running?.kind ?: kind, running?.progress),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        if (job?.isActive == true) return
        if (running != null || container.busyForBackup()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            return
        }
        acquireWakeLock()
        job = scope.launch {
            val progress = useCase.state
                .onEach { state -> if (state is BackupState.Running) maybeRefreshNotification(state) }
                .launchIn(this)
            var ok = false
            try {
                ok = when (kind) {
                    BackupKind.BACKUP -> {
                        val out = withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri, "wt") }
                            ?: throw FileNotFoundException(uri.toString())
                        useCase.backup(out) != null
                    }
                    BackupKind.RESTORE -> {
                        val (input, size) = withContext(Dispatchers.IO) {
                            contentResolver.openInputStream(uri) to sizeOf(uri)
                        }
                        input ?: throw FileNotFoundException(uri.toString())
                        useCase.restore(input, size, restoreSettings) != null
                    }
                }
            } catch (e: FileNotFoundException) {
                // The provider no longer has the file; the use case never ran.
            } finally {
                progress.cancel()
                if (kind == BackupKind.BACKUP && !ok) deletePartial(uri)
                finish(useCase.state.value)
            }
        }
    }

    private fun finish(state: BackupState) {
        job = null
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        buildResultNotification(state)?.let { notification ->
            runCatching { getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, notification) }
        }
        stopSelf()
    }

    private fun sizeOf(uri: Uri): Long? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
        }
    }.getOrNull()

    /** A failed or cancelled backup leaves no half-written file behind. */
    private fun deletePartial(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
    }

    private fun maybeRefreshNotification(state: BackupState.Running) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotificationUpdateMs < NOTIFICATION_REFRESH_PERIOD_MS) return
        lastNotificationUpdateMs = now
        runCatching {
            getSystemService<NotificationManager>()?.notify(
                NOTIFICATION_ID,
                buildProgressNotification(state.kind, state.progress)
            )
        }
    }

    private fun buildProgressNotification(kind: BackupKind, progress: Float?): Notification {
        val cancel = PendingIntent.getService(
            this,
            REQUEST_CANCEL,
            cancelIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = getString(
            if (kind == BackupKind.BACKUP) R.string.notification_backup_title else R.string.notification_restore_title
        )
        val builder = NotificationCompat.Builder(this, ModelDownloadService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentIntent(UpdateDownloadService.openAppIntent(this))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, getString(R.string.backup_cancel), cancel)
        if (progress == null) {
            builder.setProgress(0, 0, true)
        } else {
            val percent = (progress * 100).toInt().coerceIn(0, 100)
            builder.setContentText(
                getString(R.string.notification_backup_progress, percent)
            ).setProgress(100, percent, false)
        }
        return builder.build()
    }

    /** What was saved or restored, or why it failed; nothing after Cancel. */
    private fun buildResultNotification(state: BackupState): Notification? {
        val title = backupResultTitle(this, state) ?: return null
        val text = backupResultText(this, state).orEmpty()
        return NotificationCompat.Builder(this, ModelDownloadService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(UpdateDownloadService.openAppIntent(this))
            .setAutoCancel(true)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService<PowerManager>()
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ConvoScribe:backup")
            ?.apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    companion object {
        /** Own id, after the recording (1), model (2), update (3) and speaker (4) notifications. */
        private const val NOTIFICATION_ID = 5

        private const val REQUEST_CANCEL = 5002

        private const val NOTIFICATION_REFRESH_PERIOD_MS = 1_000L

        /** Upper bound on the wake lock for one backup or restore. */
        private const val WAKE_LOCK_TIMEOUT_MS = 3L * 60L * 60L * 1_000L

        const val ACTION_BACKUP = "com.convoscribe.BACKUP"
        const val ACTION_RESTORE = "com.convoscribe.RESTORE"
        const val ACTION_CANCEL = "com.convoscribe.CANCEL_BACKUP"
        const val EXTRA_RESTORE_SETTINGS = "com.convoscribe.extra.RESTORE_SETTINGS"

        /** Back up every session to [uri], passing on the write grant; `startForegroundService`, app in front. */
        fun backupIntent(context: Context, uri: Uri): Intent = Intent(context, BackupService::class.java)
            .setAction(ACTION_BACKUP)
            .setData(uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)

        /** Restore the backup at [uri], passing on the read grant; `startForegroundService`, app in front. */
        fun restoreIntent(context: Context, uri: Uri, restoreSettings: Boolean): Intent =
            Intent(context, BackupService::class.java)
                .setAction(ACTION_RESTORE)
                .setData(uri)
                .putExtra(EXTRA_RESTORE_SETTINGS, restoreSettings)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        /** Cancel the running backup or restore; plain `startService`. */
        fun cancelIntent(context: Context): Intent = Intent(context, BackupService::class.java).setAction(ACTION_CANCEL)
    }
}
