package com.convoscribe.service

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.settings.InMemorySecretStore
import com.convoscribe.settings.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncSchedulerTest {
    private lateinit var jobs: JobScheduler
    private lateinit var settings: SettingsRepository
    private lateinit var scheduler: SyncScheduler

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        jobs = context.getSystemService<JobScheduler>()!!
        jobs.cancelAll()
        val prefs = context.getSharedPreferences("sync-scheduler-${System.nanoTime()}", 0)
        settings = SettingsRepository(prefs, InMemorySecretStore())
        scheduler = SyncScheduler(context, settings)
    }

    private fun pending(): JobInfo? = jobs.getPendingJob(SyncScheduler.JOB_ID)

    @Test
    fun schedulesNothingWhileSyncIsOff() {
        scheduler.sync()
        scheduler.syncNow()
        assertNull(pending())
    }

    @Test
    fun schedulesAPersistedJobThatWaitsForANetwork() {
        settings.update { it.copy(syncEnabled = true, syncServerUrl = "https://sync.example.com") }

        scheduler.sync()

        val job = pending()
        assertNotNull(job)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job!!.networkType)
        assertTrue(job.isPersisted)
        assertEquals(SyncScheduler.BACKOFF_MS, job.initialBackoffMillis)
    }

    @Test
    fun turningSyncOffCancelsTheJob() {
        settings.update { it.copy(syncEnabled = true, syncServerUrl = "https://sync.example.com") }
        scheduler.sync()

        settings.update { it.copy(syncEnabled = false) }
        scheduler.sync()

        assertNull(pending())
    }

    @Test
    fun aChangeDuringARunAsksForAnotherInsteadOfStoppingIt() {
        settings.update { it.copy(syncEnabled = true, syncServerUrl = "https://sync.example.com") }
        scheduler.jobRunning = true

        scheduler.sync()

        assertNull(pending())
        assertTrue(scheduler.changedWhileRunning)
        scheduler.jobRunning = true
        assertFalse(scheduler.changedWhileRunning)
    }

    @Test
    fun recordingsWaitForWiFiUntilASessionChanges() {
        settings.update { it.copy(syncEnabled = true, syncServerUrl = "https://sync.example.com") }

        scheduler.syncWhenUnmetered()
        assertEquals(JobInfo.NETWORK_TYPE_UNMETERED, pending()!!.networkType)

        scheduler.sync()
        assertEquals(JobInfo.NETWORK_TYPE_ANY, pending()!!.networkType)
    }
}
