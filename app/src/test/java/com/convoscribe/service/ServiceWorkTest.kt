package com.convoscribe.service

import com.convoscribe.recording.RecordingPhase
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
}
