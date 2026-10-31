package com.convoscribe.llm.embedding

import android.util.Log
import com.convoscribe.TAG
import com.convoscribe.data.SemanticIndexDao
import com.convoscribe.data.toFloats
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Fusion of word and meaning rankings (issue #61). */
object SemanticRanking {

    /** Reciprocal-rank fusion constant: a result ranked r adds `1 / (RRF_K + r)`. */
    const val RRF_K: Int = 60

    /**
     * Cosine below which a passage does not count as matching the
     * question. EmbeddingGemma gives about 0.5–0.7 for a paraphrase
     * (also EN↔FR) and 0.2–0.3 for unrelated text
     * (`OnnxEmbeddingGemmaLiveTest`).
     */
    const val MIN_COSINE: Double = 0.4

    /** [rankings] (best first, each without repeats) merged by reciprocal-rank fusion, best first. */
    fun <T> fuse(vararg rankings: List<T>): List<T> {
        val scores = LinkedHashMap<T, Double>()
        for (ranking in rankings) {
            ranking.forEachIndexed { rank, item -> scores[item] = (scores[item] ?: 0.0) + 1.0 / (RRF_K + rank + 1) }
        }
        return scores.entries.sortedByDescending { it.value }.map { it.key }
    }

    /**
     * One score per chunk from its [bm25] score (0 = no shared word) and
     * its [cosine] (`null` = its session is not indexed): the sum of
     * `1 / (RRF_K + rank)` over the rankings it takes part in. A chunk
     * takes part in the word ranking when it shares a word, and in the
     * meaning ranking when its cosine reaches [MIN_COSINE]; otherwise it
     * scores 0, as a chunk with no shared word does under BM25 alone.
     */
    fun fusedScores(bm25: DoubleArray, cosine: Array<Double?>): DoubleArray {
        val out = DoubleArray(bm25.size)
        bm25.indices.filter { bm25[it] > 0.0 }.sortedByDescending { bm25[it] }
            .forEachIndexed { rank, i -> out[i] += 1.0 / (RRF_K + rank + 1) }
        cosine.indices.filter { (cosine[it] ?: 0.0) >= MIN_COSINE }.sortedByDescending { cosine[it]!! }
            .forEachIndexed { rank, i -> out[i] += 1.0 / (RRF_K + rank + 1) }
        return out
    }
}

/** A stored passage's place and its cosine with the question. */
data class ScoredPassage(val firstLine: Int, val lastLine: Int, val cosine: Double)

/**
 * How close each indexed session's passages are to one question (issue
 * #61). [sessions] are the sessions with a passage reaching
 * [SemanticRanking.MIN_COSINE], closest first.
 */
class SemanticMatches(private val passages: Map<Long, List<ScoredPassage>>) {

    val sessions: List<Long> = passages.entries
        .map { (id, list) -> id to list.maxOf { it.cosine } }
        .filter { it.second >= SemanticRanking.MIN_COSINE }
        .sortedByDescending { it.second }
        .map { it.first }

    /** Whether [sessionId] has vectors, so [score] means something for it. */
    fun isIndexed(sessionId: Long): Boolean = sessionId in passages

    /**
     * [sessionId]'s passages reaching [SemanticRanking.MIN_COSINE],
     * closest first (issue #63, for Search); empty when it has none.
     */
    fun passages(sessionId: Long): List<ScoredPassage> = passages[sessionId].orEmpty()
        .filter { it.cosine >= SemanticRanking.MIN_COSINE }
        .sortedByDescending { it.cosine }

    /**
     * The best cosine among [sessionId]'s passages overlapping lines
     * [firstLine] to [lastLine]; the summary chunk (both
     * [SemanticPassage.SUMMARY_LINE]) matches the summary passage.
     * `null` when the session is not indexed or nothing overlaps.
     */
    fun score(sessionId: Long, firstLine: Int, lastLine: Int): Double? {
        val own = passages[sessionId] ?: return null
        val summary = firstLine == SemanticPassage.SUMMARY_LINE
        return own.filter { p ->
            if (summary) {
                p.firstLine == SemanticPassage.SUMMARY_LINE
            } else {
                p.firstLine >= 0 && p.firstLine <= lastLine && firstLine <= p.lastLine
            }
        }.maxOfOrNull { it.cosine }
    }
}

/**
 * Semantic search over the stored passage vectors (issue #61), for
 * *Ask across sessions*. The model is loaded on the first question and
 * kept until [close]; the vectors are read once and read again only
 * when the index changes (a session indexed again or deleted).
 *
 * Every failure (no model, a broken model file, out of memory) is
 * logged and gives `null`, so the caller falls back to words alone.
 */
class SemanticSearch(
    private val index: SemanticIndexDao,
    private val embedders: TextEmbedderFactory,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : Closeable {

    private class Stored(val sessionId: Long, val firstLine: Int, val lastLine: Int, val vector: FloatArray)

    private val lock = Mutex()
    private var embedder: TextEmbedder? = null
    private var cacheKey: Int? = null
    private var cache: List<Stored> = emptyList()

    @Volatile
    private var closed = false

    /** Whether the model is installed. */
    fun isAvailable(): Boolean = embedders.isAvailable()

    /** How close every indexed session is to [question], or `null` when that cannot be told. */
    suspend fun match(question: String): SemanticMatches? {
        if (closed || !embedders.isAvailable()) return null
        return try {
            withContext(dispatcher) {
                lock.withLock {
                    if (closed) return@withLock null
                    val model = embedder ?: embedders.create().also { embedder = it }
                    val query = model.embedQuery(question)
                    val stored = vectors(model.modelId)
                    val matches = SemanticMatches(
                        stored.groupBy(
                            { it.sessionId }
                        ) { ScoredPassage(it.firstLine, it.lastLine, Vectors.cosine(query, it.vector)) }
                    )
                    // Closed while this question was embedded.
                    if (closed) release()
                    matches
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "semantic search failed; using words only", t)
            null
        }
    }

    /** How many of [sessionIds] have no vectors from the installed model yet; 0 without the model. */
    suspend fun unindexed(sessionIds: Collection<Long>): Int {
        if (!embedders.isAvailable()) return 0
        val modelId = embedders.modelId()
        val indexed = index.states().filter { it.modelId == modelId }.mapTo(HashSet()) { it.sessionId }
        return sessionIds.count { it !in indexed }
    }

    private suspend fun vectors(modelId: String): List<Stored> {
        // Every indexing writes a session's state row, so the states tell when vectors changed.
        val key = index.states().filter { it.modelId == modelId }.sortedBy { it.sessionId }
            .map { it.sessionId to it.textHash }.hashCode() * 31 + modelId.hashCode()
        if (key != cacheKey) {
            cache = index.vectors(modelId).map { Stored(it.sessionId, it.firstLine, it.lastLine, it.vector.toFloats()) }
            cacheKey = key
        }
        return cache
    }

    /** Release the model: now if no question is being embedded, otherwise as soon as it is. */
    override fun close() {
        closed = true
        if (lock.tryLock()) {
            try {
                release()
            } finally {
                lock.unlock()
            }
        }
    }

    private fun release() {
        embedder?.close()
        embedder = null
    }
}
