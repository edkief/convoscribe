package com.convoscribe.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Hands an APK to the system installer; a fake in tests. */
fun interface ApkInstaller {
    suspend fun install(apk: File)
}

/**
 * Installs an update through a `PackageInstaller` session (issue #15):
 * the APK is streamed into the session (no FileProvider needed) and the
 * commit reports to [UpdateInstallReceiver], which shows the system's
 * confirmation prompt and passes the result to [UpdateManager]. The
 * user always confirms; nothing is installed silently.
 */
class PackageUpdateInstaller(private val context: Context) : ApkInstaller {

    override suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("update.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                session.commit(statusIntent().intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    /** Mutable: the installer fills in the status extras. */
    private fun statusIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context,
            REQUEST_STATUS,
            Intent(context, UpdateInstallReceiver::class.java).setAction(UpdateInstallReceiver.ACTION_STATUS),
            flags
        )
    }

    private companion object {
        const val REQUEST_STATUS = 3101
    }
}
