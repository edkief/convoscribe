package com.convoscribe.llm

import kotlin.math.ln

/**
 * The parts of a long transcript picked for one question (issue #28).
 *
 * @property chunks the picked chunks in time order.
 * @property coveredMs how much of the conversation they span.
 * @property totalMs how long the whole conversation is.
 */
data class Excerpts(
    val chunks: List<TranscriptChunk>,
    val coveredMs: Long,
    val totalMs: Long
)

/**
 * Picks the transcript chunks most relevant to a question, on the
 * device and without a model (issue #28): chunks are ranked with
 * BM25 over the question's words, then the best are taken until the
 * character budget is full.
 */
object ChunkRetriever {

    /** BM25 term-frequency saturation. */
    private const val K1: Double = 1.2

    /** BM25 length normalisation. */
    private const val B: Double = 0.75

    /**
     * Room the gap marker between two non-adjacent excerpts takes in
     * the prompt (see [PromptBuilder.EXCERPT_GAP]); counted for every
     * chunk so the joined text never goes over budget.
     */
    private const val SEPARATOR_CHARS: Int = 5

    /** Share of the best score a chunk needs to count as a strong match. */
    private const val STRONG_SHARE: Double = 0.5

    private val WORD = Regex("[\\p{L}\\p{N}]+")

    /** Lower-cased words of [text], two characters or longer. */
    fun words(text: String): List<String> =
        WORD.findAll(text.lowercase()).map { it.value }.filter { it.length >= 2 }.toList()

    /** BM25 score of each of [chunks] for [query], in the same order. */
    fun scores(chunks: List<TranscriptChunk>, query: String): DoubleArray {
        val terms = words(query).toSet()
        val docs = chunks.map { chunk -> words(chunk.text).groupingBy { it }.eachCount() }
        val lengths = docs.map { doc -> doc.values.sum() }
        val average = lengths.average().takeIf { it > 0.0 } ?: 1.0
        val n = chunks.size.toDouble()
        val idf = terms.associateWith { term ->
            val df = docs.count { term in it }
            ln(1.0 + (n - df + 0.5) / (df + 0.5))
        }
        return DoubleArray(chunks.size) { i ->
            terms.sumOf { term ->
                val tf = (docs[i][term] ?: 0).toDouble()
                if (tf == 0.0) {
                    0.0
                } else {
                    idf.getValue(term) * tf * (K1 + 1) / (tf + K1 * (1 - B + B * lengths[i] / average))
                }
            }
        }
    }

    /**
     * The chunks to answer [query] from, within [maxChars] once joined.
     *
     * The strong matches go in first, best first; budget left over goes
     * to their neighbours, which often hold the rest of the exchange,
     * then to the weaker matches.
     * When no chunk shares a word with the query ("what was this
     * about?") chunks spread evenly over the conversation are taken
     * instead, so the answer still sees all of it in outline.
     */
    fun select(chunks: List<TranscriptChunk>, query: String, maxChars: Int): Excerpts {
        if (chunks.isEmpty()) return Excerpts(emptyList(), 0L, 0L)
        val scores = scores(chunks, query)
        val ranked = chunks.indices.filter { scores[it] > 0.0 }.sortedByDescending { scores[it] }
        val order = if (ranked.isEmpty()) {
            spread(chunks, maxChars)
        } else {
            // Chunks that only share a common word ("the", "we") score
            // far below a real match; the strong matches' neighbours
            // are worth more than those.
            val (strong, weak) = ranked.partition { scores[it] >= scores[ranked.first()] * STRONG_SHARE }
            strong + neighbours(strong, chunks.size) + weak
        }

        val picked = sortedSetOf<Int>()
        var used = 0
        for (index in order) {
            if (index in picked) continue
            val cost = chunks[index].text.length + SEPARATOR_CHARS
            if (used + cost > maxChars) continue
            picked += index
            used += cost
        }
        val selected = picked.map { chunks[it] }
        return Excerpts(selected, covered(selected), chunks.last().endMs)
    }

    /** The chunks right before and after each of [ranked], in rank order. */
    private fun neighbours(ranked: List<Int>, size: Int): List<Int> =
        ranked.flatMap { listOf(it - 1, it + 1) }.filter { it in 0 until size }

    /** Chunk indices spaced evenly over the conversation, as many as about fit [maxChars]. */
    private fun spread(chunks: List<TranscriptChunk>, maxChars: Int): List<Int> {
        val average = chunks.sumOf { it.text.length + SEPARATOR_CHARS } / chunks.size
        val count = (maxChars / maxOf(1, average)).coerceIn(1, chunks.size)
        val step = chunks.size.toDouble() / count
        return (0 until count).map { (it * step + step / 2).toInt().coerceAtMost(chunks.size - 1) }
    }

    /** Length of the union of the [chunks]' time spans; they are in time order. */
    private fun covered(chunks: List<TranscriptChunk>): Long {
        var total = 0L
        var reached = Long.MIN_VALUE
        for (chunk in chunks) {
            val from = maxOf(chunk.startMs, reached)
            if (chunk.endMs > from) total += chunk.endMs - from
            reached = maxOf(reached, chunk.endMs)
        }
        return total
    }
}
