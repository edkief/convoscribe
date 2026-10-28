package com.convoscribe.llm

import com.convoscribe.data.TranscriptLine
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [CrossSessionRetriever] (issue #49). */
class CrossSessionRetrieverTest {

    /** [count] minutes of small talk, with [special] lines replaced by index. */
    private fun lines(count: Int, special: Map<Int, String> = emptyMap()): List<TranscriptLine> =
        (0 until count).map { i ->
            TranscriptLine(
                startMs = i * 60_000L,
                speaker = if (i % 2 == 0) "Alice" else "Bob",
                text = special[i] ?: "we talked about the weather and the traffic again today",
                id = i + 1L
            )
        }

    private fun source(id: Long, lines: List<TranscriptLine>, summary: String? = null, createdAt: Long = 0L) =
        SessionSource(id, "Session $id", createdAt, "en", lines, summary)

    @Test
    fun aFactOnlyInAnOlderSessionIsPicked() {
        val recent = source(1L, lines(40))
        val older = source(2L, lines(40, mapOf(17 to "the Kowalski contract is signed for 40k")))
        val result = CrossSessionRetriever.select(listOf(recent, older), "Kowalski contract?", maxChars = 1_200)

        val session = result.sessions.first { it.sessionId == 2L }
        assertTrue(session.chunks.any { "Kowalski" in it.text })
        assertTrue(session.chunks.all { it.sessionId == 2L })
    }

    @Test
    fun noSessionTakesMoreThanItsShare() {
        val long = source(1L, lines(200).map { it.copy(text = "Kowalski Kowalski budget") })
        val other = source(2L, lines(20, mapOf(3 to "Kowalski budget")))
        val maxChars = 3_000
        val result = CrossSessionRetriever.select(listOf(long, other), "Kowalski budget", maxChars)

        for (session in result.sessions) {
            val used = session.chunks.sumOf { it.text.length + 5 }
            assertTrue("${session.sessionId}: $used", used <= maxChars * CrossSessionRetriever.SESSION_SHARE)
        }
        assertTrue(result.sessions.any { it.sessionId == 2L })
    }

    @Test
    fun summariesAreShortChunksAndComeFirst() {
        val withSummary = source(1L, lines(30), summary = "We agreed the Kowalski budget.\nNext steps.")
        val result = CrossSessionRetriever.select(listOf(withSummary), "Kowalski budget", maxChars = 1_200)

        val first = result.sessions.single().chunks.first()
        assertEquals(CrossSessionRetriever.SUMMARY_LINE, first.firstLine)
        assertEquals("Summary: We agreed the Kowalski budget. Next steps.", first.text)
        assertTrue(first.text.length <= 1_200 / CrossSessionRetriever.CHUNKS_PER_BUDGET)
    }

    @Test
    fun sessionsWithNothingPickedAreLeftOut_andCountsAddUp() {
        val a = source(1L, lines(3, mapOf(1 to "Kowalski")))
        val b = source(2L, emptyList())
        val result = CrossSessionRetriever.select(listOf(a, b), "Kowalski", maxChars = 10_000)

        assertEquals(listOf(1L), result.sessions.map { it.sessionId })
        assertEquals(1, result.sessionCount)
        assertEquals(result.sessions.single().chunks.size, result.excerptCount)
    }

    @Test
    fun dateIsTheLocalDay() {
        assertEquals("1970-01-01", CrossSessionRetriever.date(0L, ZoneOffset.UTC))
        assertEquals("1969-12-31", CrossSessionRetriever.date(0L, ZoneOffset.ofHours(-1)))
    }
}
