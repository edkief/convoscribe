package com.convoscribe.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import com.convoscribe.ConvoApp
import com.convoscribe.TAG
import com.convoscribe.llm.embedding.SemanticIndexRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The semantic search charging job (issue #61): embeds the sessions
 * waiting to be indexed while the device is charging, through
 * [SemanticIndexRunner], even when ConvoScribe is not running.
 * Scheduled by [SemanticIndexScheduler].
 *
 * Each session is saved as soon as it is embedded, so when unplugging
 * or the system's time limit stops the job, only the session in
 * progress is redone on the next charge.
 */
class SemanticIndexJobService : JobService() {

    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as ConvoApp).container
        val scheduler = container.semanticIndexScheduler
        scheduler.jobRunning = true
        work = container.applicationScope.launch {
            val reschedule = try {
                container.semanticIndexRunner.run() == SemanticIndexRunner.Outcome.BUSY
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "semantic index job failed", t)
                true
            } finally {
                scheduler.jobRunning = false
            }
            jobFinished(params, reschedule)
            // A session changed while the job was finishing.
            if (!reschedule) scheduler.sync()
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        work = null
        // Retry on the next charge; finished sessions are kept.
        return true
    }
}
