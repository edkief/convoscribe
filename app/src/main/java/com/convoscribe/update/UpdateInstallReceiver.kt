package com.convoscribe.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat
import com.convoscribe.ConvoApp
import com.convoscribe.TAG

/**
 * Status of the install session [PackageUpdateInstaller] committed
 * (issue #15). `STATUS_PENDING_USER_ACTION` carries the system's
 * confirmation screen, which is shown at once (the user just tapped
 * *Install*, so the app is in front); every other status goes to
 * [UpdateManager.onInstallResult]. Not exported: only the session's
 * PendingIntent reaches it.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STATUS) return
        val manager = (context.applicationContext as ConvoApp).container.updateManager
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i(TAG, "update install status=$status message=$message")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    manager.onInstallResult(InstallResult.FAILED, message)
                    return
                }
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { manager.onInstallResult(InstallResult.FAILED, it.message) }
            }
            PackageInstaller.STATUS_SUCCESS -> manager.onInstallResult(InstallResult.SUCCESS, null)
            PackageInstaller.STATUS_FAILURE_ABORTED -> manager.onInstallResult(InstallResult.CANCELLED, message)
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> manager.onInstallResult(InstallResult.INCOMPATIBLE, message)
            else -> manager.onInstallResult(InstallResult.FAILED, message)
        }
    }

    companion object {
        const val ACTION_STATUS = "com.convoscribe.UPDATE_INSTALL_STATUS"
    }
}
