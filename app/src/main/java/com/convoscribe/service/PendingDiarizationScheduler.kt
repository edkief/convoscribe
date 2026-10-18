package com.convoscribe.service

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.content.getSystemService
import com.convoscribe.TAG
import com.convoscribe.diarization.PendingDiarizationRunner
import com.convoscribe.settings.SettingsRepository
import com.convoscribe.settings.SpeakerIdMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the charging job ([DiarizationJobService]) scheduled while a
 * session waits for its speakers (issue #11).
 *
 * The job needs the device to be charging and is persisted across
 * reboots, so it runs on the next charge even when ConvoScribe is not
 * running: Android 8+ no longer delivers `ACTION_POWER_CONNECTED` to a
 * manifest receiver, and Android 12+ does not let the app start a
 * foreground service from the background.
 *
 * [sync] is called after a session becomes pending, at app start and
 * when the *Identify speakers* setting changes. Under *On demand* it
 * cancels the job. It never schedules while the job runs: scheduling
 * the same job id would stop it, and the running job picks up sessions
 * that became pending meanwhile anyway.
 */
class PendingDiarizationScheduler(
    private val context: Context,
    private val settings: SettingsRepository,
    private val runner: () -> PendingDiarizationRunner,
    private val scope: CoroutineScope
) {
    /** Set by [DiarizationJobService] while its work runs. */
    @Volatile
    var jobRunning: Boolean = false

    private val lock = Mutex()

    /** Bring the job in line with the setting and the pending sessions, in the background. */
    fun sync() {
        scope.launch { runCatching { syncNow() }.onFailure { Log.w(TAG, "could not schedule the speaker job", it) } }
    }

    private suspend fun syncNow() = lock.withLock {
        val jobs = context.getSystemService<JobScheduler>() ?: return@withLock
        if (SpeakerIdMode.of(settings.settings.value.speakerIdMode) == SpeakerIdMode.MANUAL) {
            jobs.cancel(JOB_ID)
            return@withLock
        }
        if (jobRunning) return@withLock
        if (!runner().hasPending()) {
            jobs.cancel(JOB_ID)
            return@withLock
        }
        if (jobs.getPendingJob(JOB_ID) != null) return@withLock
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, DiarizationJobService::class.java))
            .setRequiresCharging(true)
            .setPersisted(true)
            .build()
        jobs.schedule(job)
        Log.i(TAG, "speaker job scheduled for the next charge")
    }

    companion object {
        /** The one charging job's id. */
        const val JOB_ID = 1101
    }
}
