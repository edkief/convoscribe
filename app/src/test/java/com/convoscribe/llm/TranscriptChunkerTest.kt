package com.convoscribe.llm

import com.convoscribe.data.TranscriptFormatter
import com.convoscribe.data.TranscriptLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [TranscriptChunker.chunk] (issue #28). */
class TranscriptChunkerTest {

    /** [count] lines ten seconds apart, each about 60 characters. */
    private fun lines(count: Int): List<TranscriptLine> = (0 until count).map { i ->
        TranscriptLine(
            startMs = i * 10_000L,
            speaker = if (i % 2 == 0) "Alice" else "Bob",
            text = "line number $i says something worth keeping here.",
            endMs = i * 10_000L + 9_000L
        )
    }

    @Test
    fun chunk_noLinesGivesNoChunks() {
        assertEquals(emptyList<TranscriptChunk>(), TranscriptChunker.chunk(emptyList(), 1000))
    }

    @Test
    fun chunk_transcriptUnderBudgetIsOneChunkEqualToTheText() {
        val input = lines(5)
        val chunks = TranscriptChunker.chunk(input, 10_000)
        assertEquals(1, chunks.size)
        assertEquals(TranscriptFormatter.toText(input), chunks.single().text)
        assertEquals(0L, chunks.single().startMs)
        assertEquals(49_000L, chunks.single().endMs)
        assertEquals(0, chunks.single().firstLine)
        assertEquals(4, chunks.single().lastLine)
    }

    @Test
    fun chunk_everyChunkIsWithinBudgetAndHoldsWholeLines() {
        val input = lines(200)
        val rendered = input.map { TranscriptFormatter.lineText(it) }.toSet()
        val chunks = TranscriptChunker.chunk(input, 1_000)
        assertTrue(chunks.size > 10)
        chunks.forEach { chunk ->
            assertTrue("chunk of ${chunk.text.length} chars", chunk.text.length <= 1_000)
            chunk.text.split('\n').forEach { assertTrue("cut line: $it", it in rendered) }
        }
    }

    @Test
    fun chunk_coversEveryLineInOrder() {
        val input = lines(200)
        val chunks = TranscriptChunker.chunk(input, 1_000)
        assertEquals(0, chunks.first().firstLine)
        assertEquals(199, chunks.last().lastLine)
        chunks.zipWithNext().forEach { (a, b) ->
            assertTrue("gap between ${a.lastLine} and ${b.firstLine}", b.firstLine <= a.lastLine + 1)
            assertTrue("no progress", b.lastLine > a.lastLine)
        }
    }

    @Test
    fun chunk_nextChunkOpensWithTheLastLinesOfThePrevious() {
        val chunks = TranscriptChunker.chunk(lines(200), 1_000, overlapChars = 150)
        chunks.zipWithNext().forEach { (a, b) ->
            assertTrue(b.firstLine <= a.lastLine)
            val shared = a.text.split('\n').takeLast(a.lastLine - b.firstLine + 1).joinToString("\n")
            assertTrue(b.text.startsWith(shared))
            assertTrue(shared.length <= 150)
        }
    }

    @Test
    fun chunk_withoutOverlapChunksTileTheTimeline() {
        val chunks = TranscriptChunker.chunk(lines(100), 800, overlapChars = 0)
        chunks.zipWithNext().forEach { (a, b) ->
            assertEquals(a.lastLine + 1, b.firstLine)
            assertEquals(b.startMs, a.endMs)
        }
        assertEquals(999_000L, chunks.last().endMs)
    }

    @Test
    fun chunk_aLineLongerThanTheBudgetIsSplitAtSpaces() {
        val long = TranscriptLine(startMs = 0, speaker = null, text = "word ".repeat(100).trim(), endMs = 5_000)
        val chunks = TranscriptChunker.chunk(listOf(long), 120, overlapChars = 0)
        assertTrue(chunks.size >= 4)
        chunks.forEach { chunk ->
            assertTrue(chunk.text.length <= 120)
            assertTrue(chunk.text.split(' ').all { it == "word" || it == "[00:00]" })
        }
        assertEquals(100, chunks.sumOf { c -> c.text.split(' ').count { it == "word" } })
    }
}
