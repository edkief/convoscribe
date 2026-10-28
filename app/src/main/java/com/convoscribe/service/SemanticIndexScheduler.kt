package com.convoscribe.service

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.content.getSystemService
import com.convoscribe.TAG
import com.convoscribe.llm.embedding.SemanticIndexRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the semantic search job ([SemanticIndexJobService]) scheduled
 * for the next charge while a session waits to be indexed (issue #61),
 * as [PendingDiarizationScheduler] does for speakers.
 *
 * [sync] is called at app start, when the model is installed or
 * removed, and when a transcript, its speakers or its summary change.
 * It never schedules while the job runs: scheduling the same job id
 * would stop it, and the running job picks up those changes anyway.
 */
class SemanticIndexScheduler(
    private val context: Context,
    private val runner: () -> SemanticIndexRunner,
    private val scope: CoroutineScope
) {
    /** Set by [SemanticIndexJobService] while its work runs. */
    @Volatile
    var jobRunning: Boolean = false

    private val lock = Mutex()

    /** Bring the job in line with the model and the sessions waiting, in the background. */
    fun sync() {
        scope.launch {
            runCatching { syncNow() }.onFailure {
                Log.w(
                    TAG,
                    "could not schedule the semantic index job",
                    it
                )
            }
        }
    }

    private suspend fun syncNow() = lock.withLock {
        val jobs = context.getSystemService<JobScheduler>() ?: return@withLock
        if (jobRunning) return@withLock
        if (!runner().hasPending()) {
            jobs.cancel(JOB_ID)
            return@withLock
        }
        if (jobs.getPendingJob(JOB_ID) != null) return@withLock
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, SemanticIndexJobService::class.java))
            .setRequiresCharging(true)
            .setPersisted(true)
            .build()
        jobs.schedule(job)
        Log.i(TAG, "semantic index job scheduled for the next charge")
    }

    companion object {
        /** Its own id, so it never replaces the speaker or update jobs (1101, 1102). */
        const val JOB_ID = 1103
    }
}
