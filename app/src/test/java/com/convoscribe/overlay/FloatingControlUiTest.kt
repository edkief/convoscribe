package com.convoscribe.overlay

import com.convoscribe.recording.RecordingPhase
import com.convoscribe.recording.RecordingState
import com.convoscribe.settings.FloatingShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What each floating control shape redraws on (issue #21); pure, no Android. */
class FloatingControlUiTest {

    private val recording = RecordingState(
        phase = RecordingPhase.RECORDING,
        elapsedMs = 12_340L,
        partialText = "and then",
        finalLines = listOf("First sentence.", "Second sentence.")
    )

    /** The state a few audio chunks later: more time, a longer partial, a new final line, a mark (#30). */
    private val later = recording.copy(
        elapsedMs = 75_910L,
        partialText = "and then we",
        finalLines = recording.finalLines + "Third sentence.",
        markCount = recording.markCount + 1
    )

    private fun ui(state: RecordingState, shape: FloatingShape, eink: Boolean = false) =
        FloatingControlUi.of(state, shape, eink)

    @Test
    fun minimal_doesNotChangeWhileRecording() {
        for (eink in listOf(true, false)) {
            assertEquals(ui(recording, FloatingShape.MINIMAL, eink), ui(later, FloatingShape.MINIMAL, eink))
        }
    }

    @Test
    fun noShape_changesOnAMarkAlone() {
        val marked = recording.copy(markCount = 4)
        for (shape in FloatingShape.entries) {
            for (eink in listOf(true, false)) {
                assertEquals(ui(recording, shape, eink), ui(marked, shape, eink))
            }
        }
    }

    @Test
    fun minimal_changesOnPauseAndResume() {
        val paused = recording.copy(phase = RecordingPhase.PAUSED)
        assertNotEquals(ui(recording, FloatingShape.MINIMAL), ui(paused, FloatingShape.MINIMAL))
    }

    @Test
    fun standard_changesOnlyOnWholeSeconds() {
        val sameSecond = recording.copy(elapsedMs = 12_990L, partialText = "other", finalLines = emptyList())
        assertEquals(ui(recording, FloatingShape.STANDARD), ui(sameSecond, FloatingShape.STANDARD))
        assertEquals(12_000L, ui(recording, FloatingShape.STANDARD).elapsedMs)
        assertNotEquals(
            ui(recording, FloatingShape.STANDARD),
            ui(recording.copy(elapsedMs = 13_000L), FloatingShape.STANDARD)
        )
        assertNull(ui(recording, FloatingShape.STANDARD).transcript)
    }

    @Test
    fun expanded_onEink_ignoresPartialsAndChangesOnANewFinalLine() {
        val shape = FloatingShape.EXPANDED
        val morePartial = recording.copy(partialText = "and then we went")
        assertEquals(ui(recording, shape, eink = true).transcript, ui(morePartial, shape, eink = true).transcript)
        assertEquals("First sentence. Second sentence.", ui(recording, shape, eink = true).transcript)
        assertEquals("Second sentence. Third sentence.", ui(later, shape, eink = true).transcript)
    }

    @Test
    fun expanded_outsideEink_showsThePartialLive() {
        assertEquals("First sentence. Second sentence. and then", ui(recording, FloatingShape.EXPANDED).transcript)
        val paused = recording.copy(phase = RecordingPhase.PAUSED)
        assertEquals("First sentence. Second sentence.", ui(paused, FloatingShape.EXPANDED).transcript)
    }

    @Test
    fun maxCharsFor_shrinksWithALargerFont() {
        assertEquals(110, FloatingControlUi.maxCharsFor(1f))
        assertEquals(110, FloatingControlUi.maxCharsFor(0.85f))
        assertEquals(55, FloatingControlUi.maxCharsFor(2f))
    }

    @Test
    fun transcriptTail_isNullBeforeAnything() {
        assertNull(FloatingControlUi.transcriptTail(emptyList(), ""))
        assertNull(FloatingControlUi.transcriptTail(listOf("  "), " "))
    }

    @Test
    fun transcriptTail_keepsTheEndAndCutsAtAWord() {
        val tail = FloatingControlUi.transcriptTail(listOf("one two three four five six"), "", maxChars = 12)!!
        assertEquals("…five six", tail)
        assertTrue(tail.length <= 13)
    }
}
