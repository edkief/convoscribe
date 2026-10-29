package com.convoscribe.service

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import androidx.core.content.getSystemService
import com.convoscribe.TAG
import com.convoscribe.settings.SettingsRepository

/**
 * Keeps the sync job ([SyncJobService]) scheduled while sync is on
 * (issue #68): it runs once a network is available, and again after
 * every change to a session. [syncNow] is *Sync now*.
 *
 * [sync] is called at app start, when the sync settings change and,
 * debounced, whenever a session changes. While the job runs it only
 * notes the change, since scheduling the same job id would stop it;
 * the job asks for another run when it finishes.
 *
 * When only recordings are left to download and the network is
 * metered (issue #69), [syncWhenUnmetered] schedules the next run for
 * Wi-Fi; a session changing meanwhile replaces it with a run on any
 * network, which schedules the Wi-Fi one again if still needed.
 */
class SyncScheduler(
    private val context: Context,
    private val settings: SettingsRepository
) {
    /** Set by [SyncJobService] while its work runs. */
    @Volatile
    var jobRunning: Boolean = false
        set(value) {
            field = value
            if (value) changedWhileRunning = false
        }

    /** A session changed during the running job; read when it finishes. */
    @Volatile
    var changedWhileRunning: Boolean = false
        private set

    /** Schedule a run for when a network is available, or cancel it if sync is off. */
    fun sync() = schedule(now = false)

    /** Run straight away, replacing a run waiting for its turn. */
    fun syncNow() = schedule(now = true)

    /** Run once an unmetered network is available, for recordings to download (#69). */
    fun syncWhenUnmetered() = schedule(now = true, unmetered = true)

    private fun schedule(now: Boolean, unmetered: Boolean = false) {
        val jobs = context.getSystemService<JobScheduler>() ?: return
        if (!settings.settings.value.syncActive) {
            jobs.cancel(JOB_ID)
            return
        }
        if (jobRunning) {
            changedWhileRunning = true
            return
        }
        val pending = jobs.getPendingJob(JOB_ID)
        if (!now && pending != null && !pending.extras.getBoolean(EXTRA_UNMETERED)) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, SyncJobService::class.java))
            .setRequiredNetworkType(if (unmetered) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            .setExtras(PersistableBundle().apply { putBoolean(EXTRA_UNMETERED, unmetered) })
            .setBackoffCriteria(BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setPersisted(true)
            .build()
        runCatching { jobs.schedule(job) }.onFailure { Log.w(TAG, "could not schedule the sync job", it) }
    }

    companion object {
        /** Its own id, after the speaker, update and semantic index jobs (1101 to 1103). */
        const val JOB_ID = 1104

        /** First retry after a failed run; JobScheduler doubles it each time. */
        const val BACKOFF_MS = 60_000L

        private const val EXTRA_UNMETERED = "unmetered"
    }
}
