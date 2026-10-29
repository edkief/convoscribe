package com.convoscribe.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import com.convoscribe.ConvoApp
import com.convoscribe.TAG
import com.convoscribe.sync.SyncEngine
import com.convoscribe.sync.SyncOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The sync job (issues #68, #69): syncs sessions both ways through
 * [SyncEngine] once a network is available, even when ConvoScribe is
 * not running. Scheduled by [SyncScheduler]; recordings that wait for
 * Wi-Fi get a run of their own on an unmetered network.
 *
 * A run that the network or the server failed is retried with
 * JobScheduler's backoff. A run that met a recording ends without
 * retrying: the recording stopping changes its session, which
 * schedules the next one.
 */
class SyncJobService : JobService() {

    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as ConvoApp).container
        val scheduler = container.syncScheduler
        scheduler.jobRunning = true
        work = container.applicationScope.launch {
            container.recoveryDone.await()
            val outcome = try {
                container.syncEngine.run()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "sync job failed", t)
                SyncOutcome.RETRY
            } finally {
                scheduler.jobRunning = false
            }
            val retry = outcome == SyncOutcome.RETRY
            jobFinished(params, retry)
            when {
                retry -> Unit
                scheduler.changedWhileRunning -> scheduler.sync()
                outcome == SyncOutcome.AUDIO_WAITING -> scheduler.syncWhenUnmetered()
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        work = null
        // Sessions already uploaded are kept; the rest go next time.
        return true
    }
}
