package com.convoscribe.service

import android.app.job.JobParameters
import android.app.job.JobService
import com.convoscribe.ConvoApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The periodic update check (issue #41): runs
 * [com.convoscribe.update.UpdateManager.checkIfDue] even when
 * ConvoScribe is not open, so a new release is announced by a
 * notification. Scheduled by [UpdateCheckScheduler].
 *
 * The check is skipped like any automatic one (not due yet, switched
 * off, a recording open) and the job simply waits for its next period.
 */
class UpdateCheckJobService : JobService() {

    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as ConvoApp).container
        work = container.applicationScope.launch {
            container.updateManager.checkIfDue()?.join()
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        work = null
        // The next period checks again.
        return false
    }
}
