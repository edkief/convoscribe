package com.convoscribe.data

import com.convoscribe.asr.TimedToken
import com.convoscribe.ui.session.PlaybackSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for [TokenAligner]. */
class TokenAlignerTest {

    // "the budget for march": "budget" is two pieces, as sherpa-onnx returns them.
    private val tokens = listOf(
        TimedToken(" the", 1_000),
        TimedToken(" bud", 1_200),
        TimedToken("get", 1_350),
        TimedToken(" for", 1_600),
        TimedToken(" march", 1_800)
    )

    private fun times(text: String, start: Long = 1_000, end: Long = 2_200) =
        TokenAligner.align(tokens, text, start, end).map { it.ms }

    @Test
    fun unchangedWords_keepTheirStartTimes_evenWithNewCaseAndPunctuation() {
        assertEquals(listOf(1_000L, 1_200L, 1_600L, 1_800L), times("The budget, for March."))
    }

    @Test
    fun changedWord_isTimedBetweenItsNeighbours() {
        // "march" -> "April": between "for" (1600) and the line end (2200).
        assertEquals(listOf(1_000L, 1_200L, 1_600L, 1_900L), times("the budget for April"))
    }

    @Test
    fun insertedWords_spreadEvenlyInTheGap() {
        assertEquals(listOf(1_000L, 1_200L, 1_333L, 1_466L, 1_600L, 1_800L), times("the budget plan draft for march"))
    }

    @Test
    fun deletedWords_dropOut() {
        assertEquals(listOf(1_000L, 1_800L), times("the march"))
    }

    @Test
    fun newFirstWords_startAtTheLineStart() {
        assertEquals(listOf(900L, 950L, 1_000L), times("so um the", start = 900).take(3))
    }

    @Test
    fun whollyNewText_isSpreadOverTheLine() {
        assertEquals(listOf(1_000L, 1_400L, 1_800L), times("completely different words", end = 2_200))
    }

    @Test
    fun result_hasOneWordStartTokenPerWord_soPlaybackHighlightingLinesUp() {
        val text = "The budget, for April - and May"
        val aligned = TokenAligner.align(tokens, text, 1_000, 2_200)
        assertEquals(7, aligned.size)
        assertTrue(aligned.all { it.isWordStart })
        val line = TranscriptLine(startMs = 1_000, endMs = 2_200, speaker = null, text = text, tokens = aligned)
        assertNotNull(PlaybackSync.words(line))
        assertEquals(1, PlaybackSync.activeWordIndex(line, 1_300))
    }

    @Test
    fun times_neverGoBackwards() {
        val aligned = times("march the for budget extra the")
        assertEquals(aligned.sorted(), aligned)
    }

    @Test
    fun noOldTimings_giveNoTokens() {
        assertTrue(TokenAligner.align(emptyList(), "hello there", 0, 1_000).isEmpty())
    }
}
