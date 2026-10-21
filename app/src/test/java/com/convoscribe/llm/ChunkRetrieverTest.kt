package com.convoscribe.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [ChunkRetriever] (issue #28). */
class ChunkRetrieverTest {

    /** Ten one-minute chunks of filler; [special] replaces chunk texts by index. */
    private fun chunks(special: Map<Int, String> = emptyMap()): List<TranscriptChunk> = (0 until 10).map { i ->
        TranscriptChunk(
            text = special[i] ?: "[0$i:00] Alice: we talked about the weather and the traffic again",
            startMs = i * 60_000L,
            endMs = (i + 1) * 60_000L,
            firstLine = i,
            lastLine = i
        )
    }

    @Test
    fun words_lowerCasesAndKeepsAccentedLetters() {
        assertEquals(listOf("le", "budget", "été", "2026"), ChunkRetriever.words("Le BUDGET a été: 2026!"))
    }

    @Test
    fun scores_rareTermOutranksFiller() {
        val list = chunks(mapOf(5 to "[05:00] Bob: the invoice for Kowalski is overdue"))
        val scores = ChunkRetriever.scores(list, "What about the Kowalski invoice?")
        val best = scores.indices.maxBy { scores[it] }
        assertEquals(5, best)
        assertTrue(scores[0] < scores[5] / 10)
    }

    @Test
    fun select_takesTheMatchingChunkAndItsNeighboursInTimeOrder() {
        val list = chunks(mapOf(5 to "[05:00] Bob: the invoice for Kowalski is overdue"))
        val budget = list[0].text.length * 3 + 20
        val result = ChunkRetriever.select(list, "Kowalski invoice", budget)
        assertEquals(listOf(4, 5, 6), result.chunks.map { it.firstLine })
        assertEquals(180_000L, result.coveredMs)
        assertEquals(600_000L, result.totalMs)
    }

    @Test
    fun select_prefersNeighboursOfAStrongMatchOverCommonWordMatches() {
        val list = chunks(mapOf(5 to "[05:00] Bob: the invoice for Kowalski is overdue"))
        val budget = list[0].text.length * 3 + 20
        val result = ChunkRetriever.select(list, "What did they say about the Kowalski invoice?", budget)
        assertEquals(listOf(4, 5, 6), result.chunks.map { it.firstLine })
    }

    @Test
    fun select_staysWithinBudget() {
        val list = chunks()
        val budget = 200
        val result = ChunkRetriever.select(list, "weather", budget)
        assertTrue(result.chunks.isNotEmpty())
        assertTrue(result.chunks.sumOf { it.text.length + 5 } <= budget)
    }

    @Test
    fun select_noMatchingWordSpreadsOverTheConversation() {
        val list = chunks()
        val budget = (list[0].text.length + 5) * 3
        val result = ChunkRetriever.select(list, "zzz", budget)
        val picked = result.chunks.map { it.firstLine }
        assertEquals(3, picked.size)
        assertTrue(picked.first() <= 2)
        assertTrue(picked.last() >= 7)
    }

    @Test
    fun select_noChunksGivesNothing() {
        val result = ChunkRetriever.select(emptyList(), "anything", 1000)
        assertEquals(Excerpts(emptyList(), 0L, 0L), result)
    }
}
