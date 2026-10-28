package com.convoscribe.llm

import com.convoscribe.data.TranscriptLine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A session a cross-session question may be answered from (issue #49):
 * its transcript [lines] in time order and its latest saved [summary].
 */
data class SessionSource(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val language: String,
    val lines: List<TranscriptLine>,
    val summary: String? = null
)

/**
 * What one session contributes to a cross-session answer: its [chunks]
 * in time order, the summary chunk (if picked) first. [date] is the
 * session's day as the prompt shows it and citations repeat it,
 * `yyyy-MM-dd`. [lines] are the session's transcript lines, which the
 * chunks' `firstLine` / `lastLine` index into.
 */
data class SessionExcerpts(
    val sessionId: Long,
    val title: String,
    val date: String,
    val language: String,
    val chunks: List<TranscriptChunk>,
    val lines: List<TranscriptLine>
)

/** The excerpts picked for a cross-session question, by session, the most relevant session first. */
data class CrossExcerpts(val sessions: List<SessionExcerpts>) {
    val excerptCount: Int get() = sessions.sumOf { it.chunks.size }
    val sessionCount: Int get() = sessions.size
}

/**
 * Picks the excerpts of several sessions to answer one question from
 * (issue #49), without a model: every session is chunked as the Ask
 * tab chunks a long transcript, its summary becomes one more short
 * chunk, and [ChunkRetriever] ranks them all together with BM25, so a
 * word rare across sessions (a name, a project) counts for more. No
 * session gets more than [SESSION_SHARE] of the budget.
 */
object CrossSessionRetriever {

    /** Chunks a sixth of the budget long, as in the Ask tab. */
    const val CHUNKS_PER_BUDGET: Int = 6

    /** Most of the budget one session may take. */
    const val SESSION_SHARE: Double = 0.4

    /** `firstLine` / `lastLine` of a summary chunk: it is no transcript line. */
    const val SUMMARY_LINE: Int = -1

    /** Opening of a summary chunk's text. */
    const val SUMMARY_PREFIX: String = "Summary: "

    private val DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /** [createdAt] as the `yyyy-MM-dd` day shown in prompts and citations. */
    fun date(createdAt: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(createdAt).atZone(zone).toLocalDate().format(DATE)

    /**
     * The excerpts of [sources] for [question] within [maxChars], or
     * for [question] and [previous] (the question before it, for
     * follow-ups). With [speaker] (the person filter), lines naming
     * that speaker rank higher.
     */
    fun select(
        sources: List<SessionSource>,
        question: String,
        maxChars: Int,
        previous: String? = null,
        speaker: String? = null,
        zone: ZoneId = ZoneId.systemDefault()
    ): CrossExcerpts {
        val chunkChars = maxOf(1, maxChars / CHUNKS_PER_BUDGET)
        val chunks = sources.flatMap { source -> chunks(source, chunkChars) }
        val query = listOfNotNull(question, previous, speaker).joinToString(" ")
        val picked = ChunkRetriever.select(chunks, query, maxChars, (maxChars * SESSION_SHARE).toInt())
            .chunks.groupBy { it.sessionId }
        return CrossExcerpts(
            sources.mapNotNull { source ->
                val own = picked[source.id] ?: return@mapNotNull null
                SessionExcerpts(
                    sessionId = source.id,
                    title = source.title,
                    date = date(source.createdAt, zone),
                    language = source.language,
                    chunks = own,
                    lines = source.lines
                )
            }
        )
    }

    /** [source]'s summary chunk, if it has a summary, then its transcript chunks. */
    private fun chunks(source: SessionSource, chunkChars: Int): List<TranscriptChunk> {
        val summary = source.summary?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            TranscriptChunk(
                text = (SUMMARY_PREFIX + text.replace('\n', ' ')).take(chunkChars),
                startMs = 0L,
                endMs = 0L,
                firstLine = SUMMARY_LINE,
                lastLine = SUMMARY_LINE,
                sessionId = source.id
            )
        }
        val transcript = TranscriptChunker.chunk(source.lines, chunkChars, overlapChars = 0)
            .map { it.copy(sessionId = source.id) }
        return listOfNotNull(summary) + transcript
    }
}
