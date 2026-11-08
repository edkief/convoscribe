package com.convoscribe.service

import com.convoscribe.recording.RecordingPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When [RecordingService] may stop (issue #121) and when it keeps its wake lock (issue #124). */
class ServiceWorkTest {

    private val nothing = ServiceWork(
        phase = RecordingPhase.IDLE,
        retranscribing = false,
        importing = false,
        processing = 0
    )

    @Test
    fun idle_whenNothingRuns() {
        assertTrue(nothing.idle)
    }

    @Test
    fun notIdle_whileASessionIsOpen() {
        for (phase in RecordingPhase.entries - RecordingPhase.IDLE) {
            assertFalse("$phase", nothing.copy(phase = phase).idle)
        }
    }

    @Test
    fun notIdle_whileReTranscribing() {
        assertFalse(nothing.copy(retranscribing = true).idle)
    }

    @Test
    fun notIdle_whileImporting() {
        assertFalse(nothing.copy(importing = true).idle)
    }

    @Test
    fun notIdle_whileSpeakersAreIdentified() {
        assertFalse(nothing.copy(processing = 1).idle)
        assertFalse(nothing.copy(processing = 2).idle)
    }

    @Test
    fun aRecordingStartedDuringSpeakerIdentification_keepsTheServiceWhenItEnds() {
        // The earlier session's post-Stop job has just finished
        // (processing back to 0) while the next meeting records.
        val afterIdentification = nothing.copy(phase = RecordingPhase.RECORDING, processing = 0)
        assertFalse(afterIdentification.idle)
        assertFalse(afterIdentification.copy(phase = RecordingPhase.PAUSED).idle)
    }

    @Test
    fun identifyingSpeakers_doesNotBlockAReTranscriptionOrAnImport() {
        val identifying = nothing.copy(processing = 1)
        assertTrue(identifying.acceptsJob)
        assertFalse(identifying.idle)
    }

    @Test
    fun anOpenSessionOrAnotherJob_blocksAReTranscriptionOrAnImport() {
        for (phase in RecordingPhase.entries - RecordingPhase.IDLE) {
            assertFalse("$phase", nothing.copy(phase = phase).acceptsJob)
        }
        assertFalse(nothing.copy(retranscribing = true).acceptsJob)
        assertFalse(nothing.copy(importing = true).acceptsJob)
        assertTrue(nothing.acceptsJob)
    }

    @Test
    fun aSecondStopJob_keepsTheServiceWhenTheFirstEnds() {
        // Two sessions stopped back to back: the first job ends while
        // the second still waits for the speaker engine.
        assertFalse(nothing.copy(processing = 1).idle)
    }

    @Test
    fun needsWakeLock_whileASessionStartsRecordsOrStops() {
        for (phase in listOf(RecordingPhase.LOADING, RecordingPhase.RECORDING, RecordingPhase.STOPPING)) {
            assertTrue("$phase", nothing.copy(phase = phase).needsWakeLock)
        }
    }

    @Test
    fun noWakeLock_whenNothingRunsOrTheSessionIsOnlyPaused() {
        assertFalse(nothing.needsWakeLock)
        assertFalse(nothing.copy(phase = RecordingPhase.PAUSED).needsWakeLock)
    }

    @Test
    fun needsWakeLock_whileAJobRuns() {
        assertTrue(nothing.copy(retranscribing = true).needsWakeLock)
        assertTrue(nothing.copy(importing = true).needsWakeLock)
        assertTrue(nothing.copy(processing = 1).needsWakeLock)
    }

    @Test
    fun pausingWhileAnEarlierSessionsSpeakersAreIdentified_keepsTheWakeLock() {
        val paused = nothing.copy(phase = RecordingPhase.PAUSED, processing = 1)
        assertTrue(paused.needsWakeLock)
        // Once that identification ends, the paused session gives it back.
        assertFalse(paused.copy(processing = 0).needsWakeLock)
        assertFalse(paused.copy(processing = 0).idle)
    }

    @Test
    fun startingWhileAReTranscriptionStops_holdsTheService() {
        // The re-transcription has just been cancelled and has let go of
        // retranscribing; the recording has not opened yet (issue #123).
        val starting = nothing.copy(starting = true)
        assertFalse(starting.idle)
        assertTrue(starting.needsWakeLock)
        assertFalse(starting.acceptsJob)
        assertEquals(ServiceForeground.RECORDING, starting.foreground)
    }

    @Test
    fun anOpenSession_alwaysShowsTheRecordingNotification() {
        val everything = nothing.copy(retranscribing = true, importing = true, processing = 2)
        for (phase in RecordingPhase.entries - RecordingPhase.IDLE) {
            assertEquals("$phase", ServiceForeground.RECORDING, everything.copy(phase = phase).foreground)
        }
    }

    @Test
    fun withoutASession_reTranscriptionThenImportThenSpeakersShow() {
        assertEquals(
            ServiceForeground.RETRANSCRIBING,
            nothing.copy(retranscribing = true, importing = true, processing = 1).foreground
        )
        assertEquals(ServiceForeground.IMPORTING, nothing.copy(importing = true, processing = 1).foreground)
        assertEquals(ServiceForeground.IDENTIFYING, nothing.copy(processing = 1).foreground)
        assertEquals(ServiceForeground.NONE, nothing.foreground)
    }

    @Test
    fun aRecordingStartedDuringAServerTranscript_keepsTheServiceWhenEitherEnds() {
        val both = nothing.copy(phase = RecordingPhase.RECORDING, retranscribing = true, processing = 1)
        assertFalse(both.idle)
        // The server transcript and the earlier session's speakers end first.
        assertFalse(both.copy(retranscribing = false, processing = 0).idle)
        // Or the recording is stopped first: its own Stop job holds the service.
        assertFalse(both.copy(phase = RecordingPhase.IDLE, processing = 2).idle)
        assertEquals(
            ServiceForeground.RETRANSCRIBING,
            both.copy(phase = RecordingPhase.IDLE, processing = 2).foreground
        )
    }
}
