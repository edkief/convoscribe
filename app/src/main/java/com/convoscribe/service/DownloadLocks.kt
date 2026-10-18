package com.convoscribe.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.core.content.getSystemService

/**
 * The locks a download service holds while it runs (issues #10, #15):
 * a partial wake lock keeps the CPU running with the screen off; the
 * Wi-Fi lock keeps the radio awake (it does nothing on mobile data).
 * The wake lock times out as a safety net if [release] is missed.
 */
class DownloadLocks(private val context: Context, private val tag: String) {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    fun acquire() {
        if (wakeLock?.isHeld != true) {
            wakeLock = context.getSystemService<PowerManager>()
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
                ?.apply { acquire(LOCK_TIMEOUT_MS) }
        }
        if (wifiLock?.isHeld != true) {
            @Suppress("DEPRECATION")
            wifiLock = context.getSystemService<WifiManager>()
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, tag)
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
    }

    fun release() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) runCatching { it.release() } }
        wifiLock = null
    }

    private companion object {
        /** Upper bound on the wake lock for one download. */
        const val LOCK_TIMEOUT_MS = 2L * 60L * 60L * 1_000L // 2 h
    }
}
