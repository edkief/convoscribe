package com.convoscribe.update

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.convoscribe.ConvoApp
import com.convoscribe.R
import com.convoscribe.service.ModelDownloadService
import com.convoscribe.service.UpdateDownloadService

/**
 * `MY_PACKAGE_REPLACED`: the app was just updated (issue #15). Android
 * stops the app for the update and does not let it start an activity
 * from the background, so this posts "Updated to X.Y.Z, tap to open"
 * in its place, and deletes the downloaded APK. Only when there was
 * one: an `adb install -r` posts nothing. Sessions live in the
 * app's database and files, which an in-place update keeps.
 */
class UpdatedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val container = (context.applicationContext as ConvoApp).container
        val version = container.installedApp.versionName
        val pending = goAsync()
        Thread {
            try {
                // Only an update this app downloaded leaves an APK behind; an adb install does not.
                if (container.updateDownloads.cleanUp(SemVer.parse(version))) notifyUpdated(context, version)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun notifyUpdated(context: Context, version: String) {
        ModelDownloadService.ensureDownloadsChannel(context)
        val notification = NotificationCompat.Builder(context, ModelDownloadService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notification_updated_title, version))
            .setContentText(context.getString(R.string.notification_updated_text))
            .setContentIntent(UpdateDownloadService.openAppIntent(context))
            .setAutoCancel(true)
            .build()
        // Notifications may be off; the update happened either way.
        runCatching {
            context.getSystemService<NotificationManager>()?.notify(UpdateDownloadService.NOTIFICATION_ID, notification)
        }
    }
}
