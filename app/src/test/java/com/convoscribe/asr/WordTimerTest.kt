package com.convoscribe.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordTimerTest {

    private fun line(text: String, start: Long, end: Long) = TranscriptEvent.Final(text, start, end)

    @Test
    fun matchedWords_keepTheLiveTimes() {
        val anchors = listOf(TimedToken(" hello", 1_100), TimedToken(" there", 1_600), TimedToken(" world", 2_300))
        val out = WordTimer.retime(listOf(line("Hello there, world.", 1_000, 3_000)), anchors).single()
        assertEquals(listOf(1_100L, 1_600L, 2_300L), out.tokens.map { it.ms })
        assertEquals(listOf(" Hello", " there,", " world."), out.tokens.map { it.text })
    }

    @Test
    fun unmatchedWords_fallBetweenTheirTimedNeighbours() {
        val anchors = listOf(TimedToken(" the", 1_000), TimedToken(" cat", 2_000))
        val out = WordTimer.retime(listOf(line("The big cat", 1_000, 3_000)), anchors).single()
        val times = out.tokens.map { it.ms }
        assertEquals(1_000L, times[0])
        assertTrue(times[1] in 1_000L..2_000L)
        assertEquals(2_000L, times[2])
    }

    @Test
    fun noAnchors_spreadsWordsEvenly() {
        val out = WordTimer.retime(listOf(line("one two three four", 0, 4_000)), emptyList()).single()
        assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L), out.tokens.map { it.ms })
    }

    @Test
    fun eachLine_onlyUsesAnchorsNearIt() {
        // "alpha" at 9 s belongs to the second line, not the first.
        val anchors = listOf(TimedToken(" alpha", 9_000))
        val out = WordTimer.retime(
            listOf(line("alpha beta", 0, 2_000), line("alpha gamma", 8_000, 10_000)),
            anchors
        )
        assertEquals(listOf(0L, 1_000L), out[0].tokens.map { it.ms })
        assertEquals(9_000L, out[1].tokens[0].ms)
    }

    @Test
    fun anchorsJustOutsideTheLine_areClampedIntoIt() {
        val anchors = listOf(TimedToken(" so", 4_900))
        val out = WordTimer.retime(listOf(line("So", 5_000, 6_000)), anchors).single()
        assertEquals(5_000L, out.tokens.single().ms)
    }

    @Test
    fun lineWithTokens_isLeftAlone() {
        val timed = TranscriptEvent.Final("hi", 0, 500, listOf(TimedToken(" hi", 100)))
        assertEquals(timed, WordTimer.retime(listOf(timed), listOf(TimedToken(" hi", 400))).single())
    }

    @Test
    fun tokens_areWordStartsInOrder() {
        val out = WordTimer.retime(listOf(line("a b c d e", 0, 1_000)), listOf(TimedToken(" c", 600))).single()
        assertTrue(out.tokens.all { it.isWordStart })
        assertTrue(out.tokens.zipWithNext().all { (a, b) -> b.ms >= a.ms })
    }
}
