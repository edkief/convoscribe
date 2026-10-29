package com.convoscribe.service

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import androidx.core.content.getSystemService
import com.convoscribe.TAG
import com.convoscribe.llm.embedding.SemanticIndexRunner
import com.convoscribe.settings.SemanticIndexMode
import com.convoscribe.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the semantic search job ([SemanticIndexJobService]) scheduled
 * while a session waits to be indexed (issue #61), as
 * [PendingDiarizationScheduler] does for speakers.
 *
 * The job waits for the charger, or only for the battery not to be low,
 * as the *Index sessions* setting says ([SemanticIndexMode], issue #65).
 * [indexNow] schedules it with no constraint at all; that job is left
 * alone until it has run, and the next [sync] goes back to the setting.
 *
 * [sync] is called at app start, when the model is installed or
 * removed, when the setting changes and when a transcript, its speakers
 * or its summary change. It never schedules while the job runs:
 * scheduling the same job id would stop it, and the running job picks
 * up those changes anyway.
 */
class SemanticIndexScheduler(
    private val context: Context,
    private val settings: SettingsRepository,
    private val runner: () -> SemanticIndexRunner,
    private val scope: CoroutineScope
) {
    /** Set by [SemanticIndexJobService] while its work runs. */
    @Volatile
    var jobRunning: Boolean = false

    private val lock = Mutex()

    /** Bring the job in line with the setting, the model and the sessions waiting, in the background. */
    fun sync() {
        scope.launch {
            runCatching { syncNow(now = false) }.onFailure {
                Log.w(
                    TAG,
                    "could not schedule the semantic index job",
                    it
                )
            }
        }
    }

    /** Index every waiting session straight away, charging or not (*Index now*). */
    fun indexNow() {
        scope.launch {
            runCatching { syncNow(now = true) }.onFailure { Log.w(TAG, "could not start the semantic index job", it) }
        }
    }

    private suspend fun syncNow(now: Boolean) = lock.withLock {
        val jobs = context.getSystemService<JobScheduler>() ?: return@withLock
        if (jobRunning) return@withLock
        if (!runner().hasPending()) {
            jobs.cancel(JOB_ID)
            return@withLock
        }
        val mode = SemanticIndexMode.of(settings.settings.value.semanticIndexMode)
        val pending = jobs.getPendingJob(JOB_ID)
        if (pending != null) {
            val pendingNow = pending.extras.getBoolean(EXTRA_NOW, false)
            if (pendingNow || (!now && pending.matches(mode))) return@withLock
        }
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, SemanticIndexJobService::class.java))
            .apply {
                if (now) {
                    setExtras(PersistableBundle().apply { putBoolean(EXTRA_NOW, true) })
                } else {
                    setRequiresCharging(mode == SemanticIndexMode.CHARGING)
                    setRequiresBatteryNotLow(mode == SemanticIndexMode.ANY_TIME)
                }
            }
            .setPersisted(true)
            .build()
        jobs.schedule(job)
        Log.i(TAG, "semantic index job scheduled now=$now mode=${mode.key}")
    }

    /** Whether this job waits for what [mode] asks for. */
    private fun JobInfo.matches(mode: SemanticIndexMode): Boolean =
        isRequireCharging == (mode == SemanticIndexMode.CHARGING) &&
            isRequireBatteryNotLow == (mode == SemanticIndexMode.ANY_TIME)

    companion object {
        /** Its own id, so it never replaces the speaker or update jobs (1101, 1102). */
        const val JOB_ID = 1103

        /** Set on the job [indexNow] schedules. */
        private const val EXTRA_NOW = "now"
    }
}
