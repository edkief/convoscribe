package com.convoscribe.llm

import com.convoscribe.data.TranscriptLine
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkWindowTest {

    private fun line(startS: Long, endS: Long, text: String) =
        TranscriptLine(startMs = startS * 1_000L, endMs = endS * 1_000L, speaker = null, text = text)

    private val lines = listOf(
        line(0, 10, "a"),
        line(50, 70, "b"),
        line(100, 110, "c"),
        line(150, 170, "d"),
        line(175, 180, "e"),
        line(300, 310, "f")
    )

    private fun texts(atS: Long) = MarkWindow.lines(lines, atS * 1_000L).map { it.text }

    @Test
    fun takesAMinuteBeforeAndHalfAMinuteAfterInWholeLines() {
        // 115 s: 55..145 s. "b" ends at 70 s so it is in whole; "d" starts at 150 s, out.
        assertEquals(listOf("b", "c"), texts(115))
    }

    @Test
    fun reachesFurtherBeforeThanAfter() {
        // 160 s: 100..190 s.
        assertEquals(listOf("c", "d", "e"), texts(160))
    }

    @Test
    fun alwaysKeepsTheLineBeingSpokenAtTheMark() {
        // 260 s: 200..290 s holds nothing, but "e" was the last line started.
        assertEquals(listOf("e"), texts(260))
    }

    @Test
    fun aMarkBeforeAnythingWasSaidGetsTheOpening() {
        val later = listOf(line(120, 130, "late"), line(200, 210, "later"))
        assertEquals(listOf("late"), MarkWindow.lines(later, 5_000L).map { it.text })
    }

    @Test
    fun noLinesMeansNoWindow() {
        assertEquals(emptyList<TranscriptLine>(), MarkWindow.lines(emptyList(), 5_000L))
    }
}
