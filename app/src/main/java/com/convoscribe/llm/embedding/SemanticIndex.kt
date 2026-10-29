package com.convoscribe.llm.embedding

import android.util.Log
import com.convoscribe.TAG
import com.convoscribe.data.PassageVectorEntity
import com.convoscribe.data.SearchDao
import com.convoscribe.data.SegmentEntity
import com.convoscribe.data.SemanticIndexDao
import com.convoscribe.data.SessionIndexStateEntity
import com.convoscribe.data.SessionStatus
import com.convoscribe.data.TranscriptFormatter
import com.convoscribe.data.TranscriptLine
import com.convoscribe.data.toBytes
import com.convoscribe.llm.TranscriptChunker
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A passage of a session to embed (issue #61): lines [firstLine] to
 * [lastLine], or the summary when both are [SUMMARY_LINE]. [text] is
 * what is embedded: the lines as `Speaker: text`, without times, which
 * carry no meaning and cost tokens.
 */
data class SemanticPassage(val firstLine: Int, val lastLine: Int, val text: String) {
    companion object {
        const val SUMMARY_LINE: Int = -1
    }
}

/**
 * Cuts a session into [SemanticPassage]s. Unlike the chunks a question
 * is answered from, whose size follows the user's
 * `maxTranscriptChars`, passages have a fixed size, so stored vectors
 * stay valid when that setting changes.
 */
object SemanticPassages {

    /** About 300 tokens: well within the model's [OnnxEmbeddingGemma.MAX_TOKENS] with the prompt. */
    const val PASSAGE_CHARS: Int = 1200

    /** The summary passage (if [summary] is not blank) then the transcript passages, in time order. */
    fun of(lines: List<TranscriptLine>, summary: String?): List<SemanticPassage> {
        val summaryPassage = summary?.trim()?.takeIf { it.isNotEmpty() }?.let {
            SemanticPassage(SemanticPassage.SUMMARY_LINE, SemanticPassage.SUMMARY_LINE, it.take(PASSAGE_CHARS * 2))
        }
        val transcript = TranscriptChunker.chunk(lines, PASSAGE_CHARS, overlapChars = 0)
            .map { chunk ->
                val text = (chunk.firstLine..chunk.lastLine).joinToString("\n") { i ->
                    val line = lines[i]
                    if (line.speaker.isNullOrBlank()) line.text else "${line.speaker}: ${line.text}"
                }
                SemanticPassage(chunk.firstLine, chunk.lastLine, text)
            }
        return listOfNotNull(summaryPassage) + transcript
    }

    /** A hash of [passages]: it changes whenever their text or boundaries do. */
    fun hash(passages: List<SemanticPassage>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (passage in passages) {
            digest.update("${passage.firstLine}:${passage.lastLine}:".toByteArray())
            digest.update(passage.text.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** [indexed] of [total] sessions have vectors from the installed model (issue #61). */
data class IndexProgress(val indexed: Int, val total: Int)

/**
 * The charging job's work for semantic search (issue #61): embed the
 * passages of every session whose index is missing or out of date,
 * newest first, until none is left.
 *
 * A session is out of date when its [SessionIndexStateEntity] was made
 * by another model or from other text ([SemanticPassages.hash]); that
 * catches every way a transcript changes (edits, split and merge,
 * speakers, re-transcription, a new summary) without hooking each one.
 * Vectors of another model are dropped first.
 *
 * Kept free of Android services so it can be unit-tested;
 * [com.convoscribe.service.SemanticIndexJobService] runs it and cancels
 * it when the device is unplugged, which leaves the current session
 * for the next run.
 *
 * [index] does the same for one session, straight away, from its menu
 * (issue #65). One run at a time, so only one model is ever loaded.
 *
 * @property segments a session's transcript segments, in time order.
 * @property busy whether a recording, import, re-transcription or the
 *   speaker job holds the device; the run then gives way.
 */
class SemanticIndexRunner(
    private val search: SearchDao,
    private val index: SemanticIndexDao,
    private val segments: suspend (Long) -> List<SegmentEntity>,
    private val embedders: TextEmbedderFactory,
    private val awaitRecovery: suspend () -> Unit = {},
    private val busy: () -> Boolean = { false }
) {
    /** Why [run] returned. */
    enum class Outcome {
        /** Every session is indexed. */
        DONE,

        /** Something else holds the device; retry later. */
        BUSY,

        /** The model is not installed. */
        DISABLED
    }

    /** A session to index: its passages and their hash. */
    private class Work(val sessionId: Long, val passages: List<SemanticPassage>, val hash: String)

    private val lock = Mutex()

    private val _indexing = MutableStateFlow<Set<Long>>(emptySet())

    /** The sessions [index] was asked for and has not finished yet. */
    val indexing: StateFlow<Set<Long>> = _indexing.asStateFlow()

    suspend fun run(): Outcome = lock.withLock {
        awaitRecovery()
        if (!embedders.isAvailable()) return@withLock Outcome.DISABLED
        val modelId = embedders.modelId()
        index.deleteOtherModels(modelId)
        embedEach(modelId, pending(modelId))
    }

    /**
     * Index [sessionId] now if it is not indexed yet (issue #65),
     * waiting for a run in progress. [Outcome.DONE] also when there was
     * nothing to do.
     */
    suspend fun index(sessionId: Long): Outcome {
        _indexing.update { it + sessionId }
        try {
            return lock.withLock {
                awaitRecovery()
                if (!embedders.isAvailable()) return@withLock Outcome.DISABLED
                val modelId = embedders.modelId()
                index.deleteOtherModels(modelId)
                embedEach(modelId, pending(modelId, only = sessionId))
            }
        } finally {
            _indexing.update { it - sessionId }
        }
    }

    /** Embed and save each of [works] in turn; the model is loaded for the first one. */
    private suspend fun embedEach(modelId: String, works: List<Work>): Outcome {
        var embedder: TextEmbedder? = null
        try {
            for (work in works) {
                if (busy()) return Outcome.BUSY
                val model = embedder ?: embedders.create().also { embedder = it }
                Log.i(TAG, "semantic index: session=${work.sessionId} passages=${work.passages.size}")
                val vectors = if (work.passages.isEmpty()) {
                    emptyList()
                } else {
                    model.embedPassages(
                        work.passages.map { it.text }
                    )
                }
                index.replace(
                    work.sessionId,
                    work.passages.mapIndexed { i, passage ->
                        PassageVectorEntity(
                            work.sessionId,
                            i,
                            passage.firstLine,
                            passage.lastLine,
                            modelId,
                            vectors[i].toBytes()
                        )
                    },
                    SessionIndexStateEntity(work.sessionId, modelId, work.hash)
                )
            }
            return Outcome.DONE
        } finally {
            embedder?.close()
        }
    }

    /** How many sessions have vectors from the installed model, of how many there are. */
    suspend fun progress(): IndexProgress {
        val sessions = search.sessions().mapTo(HashSet()) { it.id }
        val modelId = embedders.modelId()
        val indexed = index.states().count { it.modelId == modelId && it.sessionId in sessions }
        return IndexProgress(indexed, sessions.size)
    }

    /** Forget every vector, as when the model is removed. */
    suspend fun clear() = index.clear()

    /** Whether any session waits to be indexed by the installed model. */
    suspend fun hasPending(): Boolean = embedders.isAvailable() && pending(embedders.modelId()).isNotEmpty()

    /** Whether [sessionId] waits to be indexed by the installed model, for its menu. */
    suspend fun isPending(sessionId: Long): Boolean =
        embedders.isAvailable() && pending(embedders.modelId(), only = sessionId).isNotEmpty()

    /**
     * The sessions whose index is missing or out of date for [modelId],
     * newest first; only [only] when given.
     */
    private suspend fun pending(modelId: String, only: Long? = null): List<Work> {
        val states = index.states().associateBy { it.sessionId }
        val speakers = search.speakers().groupBy { it.sessionId }
        val summaries = search.summaries().associate { it.sessionId to it.content }
        return search.sessions()
            .filter { only == null || it.id == only }
            .filter { it.status != SessionStatus.RECORDING && it.status != SessionStatus.DIARIZING }
            .mapNotNull { session ->
                val lines = TranscriptFormatter.lines(segments(session.id), speakers[session.id].orEmpty())
                val passages = SemanticPassages.of(lines, summaries[session.id])
                val hash = SemanticPassages.hash(passages)
                val state = states[session.id]
                if (state != null && state.modelId == modelId && state.textHash == hash) {
                    null
                } else {
                    Work(session.id, passages, hash)
                }
            }
    }
}
