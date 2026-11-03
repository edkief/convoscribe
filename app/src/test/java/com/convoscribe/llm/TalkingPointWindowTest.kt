package com.convoscribe.llm

import com.convoscribe.data.TranscriptLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkingPointWindowTest {

    private fun line(startS: Long, endS: Long, text: String) =
        TranscriptLine(startMs = startS * 1_000L, endMs = endS * 1_000L, speaker = null, text = text)

    private val lines = listOf(
        line(0, 10, "a"),
        line(20, 35, "b"),
        line(45, 50, "c"),
        line(60, 70, "d")
    )

    @Test
    fun nothingNewSinceTheLastCheckIsNoWindow() {
        assertNull(TalkingPointWindow.select(lines, sinceMs = 70_000L, maxChars = 1_000))
        assertNull(TalkingPointWindow.select(emptyList(), sinceMs = 0L, maxChars = 1_000))
    }

    @Test
    fun takesTheNewSpeechAndTwentySecondsBeforeIt() {
        // Since 50 s: "d" is new; 30..50 s adds "b" (ends at 35 s) and "c".
        val window = TalkingPointWindow.select(lines, sinceMs = 50_000L, maxChars = 1_000)!!
        assertEquals(listOf("b", "c", "d"), window.lines.map { it.text })
        assertEquals("[00:20] b\n[00:45] c\n[01:00] d", window.text)
        assertEquals(70_000L, window.endMs)
        assertEquals(20_000L, window.firstMs)
        assertEquals(60_000L, window.lastMs)
    }

    @Test
    fun theFirstCheckStartsAtTheBeginning() {
        val window = TalkingPointWindow.select(lines, sinceMs = 0L, maxChars = 1_000)!!
        assertEquals(listOf("a", "b", "c", "d"), window.lines.map { it.text })
    }

    @Test
    fun overBudgetLeavesOutTheOldestLines() {
        val long = (0 until 50).map { line(it * 10L, it * 10L + 5, "x".repeat(40)) }
        val window = TalkingPointWindow.select(long, sinceMs = 0L, maxChars = 200)!!
        assertTrue(window.text.length <= 200)
        assertEquals(long.last(), window.lines.last())
        assertTrue(window.lines.size < long.size)
        // Each line is "[mm:ss] " + 40 characters = 48, joined by newlines: 4 lines fit in 200.
        assertEquals(4, window.lines.size)
    }

    @Test
    fun aSingleLineLongerThanTheBudgetKeepsItsEnd() {
        val window = TalkingPointWindow.select(listOf(line(0, 5, "y".repeat(300) + "END")), 0L, maxChars = 100)!!
        assertEquals(100, window.text.length)
        assertTrue(window.text.endsWith("END"))
    }
}
