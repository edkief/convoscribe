package com.convoscribe.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.convoscribe.ConvoApp
import com.convoscribe.MainActivity
import com.convoscribe.R
import com.convoscribe.TAG
import com.convoscribe.diarization.PendingDiarizationRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The charging job (issue #11): identifies the speakers of pending
 * sessions while the device is charging, through
 * [PendingDiarizationRunner], even when ConvoScribe is not running.
 * Scheduled by [PendingDiarizationScheduler].
 *
 * Unplugging, or the system stopping the job, calls [onStopJob]: the
 * work is cancelled, the session in progress goes back to pending and
 * the job is retried on the next charge. Android 14+ gives a regular
 * job about 10 minutes, so a very long session may be stopped every
 * time; on that timeout a notification asks the user to open the app
 * and start it there (*Identify speakers now*, a foreground service).
 */
class DiarizationJobService : JobService() {

    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as ConvoApp).container
        val scheduler = container.pendingDiarizationScheduler
        scheduler.jobRunning = true
        work = container.applicationScope.launch {
            val reschedule = try {
                container.pendingDiarizationRunner.run() == PendingDiarizationRunner.Outcome.BUSY
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "charging job failed", t)
                true
            } finally {
                scheduler.jobRunning = false
            }
            jobFinished(params, reschedule)
            // A session deferred while the job was finishing.
            if (!reschedule) scheduler.sync()
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        work = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && params.stopReason == JobParameters.STOP_REASON_TIMEOUT) {
            Log.w(TAG, "charging job timed out; asking the user to identify speakers in the app")
            notifyTimedOut()
        }
        // Retry on the next charge; the session is pending again.
        return true
    }

    private fun notifyTimedOut() {
        RecordingService.ensureChannel(this)
        val open = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, RecordingService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_speakers_pending_title))
            .setContentText(getString(R.string.notification_speakers_pending_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.notification_speakers_pending_text)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // Notifications may be off; the session stays pending either way.
        runCatching { getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, notification) }
    }

    private companion object {
        const val NOTIFICATION_ID = 4
        const val REQUEST_OPEN = 1101
    }
}
