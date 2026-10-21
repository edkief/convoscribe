package com.convoscribe.service

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.content.getSystemService
import com.convoscribe.TAG
import com.convoscribe.settings.SettingsRepository
import com.convoscribe.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Keeps the periodic update check ([UpdateCheckJobService]) scheduled
 * while *Check for updates automatically* is on (issue #41), and
 * cancels it when it is off.
 *
 * The job needs a network and is persisted across reboots, so updates
 * are found even when ConvoScribe is not opened. It runs every
 * [PERIOD_MS], less often than [UpdateManager.CHECK_INTERVAL_MS]
 * allows, to spare the battery; the check on return to the app is the
 * quick path. A job scheduled by an older build with another period is
 * replaced on the next start. Android may run the job later than asked
 * (Doze, a rarely used app).
 *
 * [sync] is called at app start and when the setting changes.
 */
class UpdateCheckScheduler(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope
) {
    /** Bring the job in line with the setting, in the background. */
    fun sync() {
        scope.launch { runCatching { syncNow() }.onFailure { Log.w(TAG, "could not schedule the update check", it) } }
    }

    private fun syncNow() {
        val jobs = context.getSystemService<JobScheduler>() ?: return
        if (!settings.settings.value.updateCheckAuto) {
            jobs.cancel(JOB_ID)
            return
        }
        // Scheduling an unchanged job again would restart its period.
        if (jobs.getPendingJob(JOB_ID)?.intervalMillis == PERIOD_MS) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckJobService::class.java))
            .setPeriodic(PERIOD_MS, FLEX_MS)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()
        jobs.schedule(job)
        Log.i(TAG, "update check job scheduled every ${PERIOD_MS / 60_000L} min")
    }

    companion object {
        /** The periodic update check's id, next to the charging job's 1101. */
        const val JOB_ID = 1102

        const val PERIOD_MS = 60L * 60L * 1_000L
        private const val FLEX_MS = 15L * 60L * 1_000L
    }
}
