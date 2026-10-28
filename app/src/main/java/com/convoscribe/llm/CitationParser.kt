package com.convoscribe.llm

import com.convoscribe.data.SearchText
import kotlinx.serialization.Serializable

/**
 * A place an answer across sessions cites (issue #49): the session,
 * the transcript line to open it at ([segmentId] and [atMs], `null`
 * when the cited time is not in the lines that were sent), and the
 * session's [title] and [date] as they were when the answer was
 * written, for a session deleted since.
 */
@Serializable
data class Citation(
    val sessionId: Long,
    val title: String,
    val date: String,
    val segmentId: Long? = null,
    val atMs: Long? = null
)

/**
 * Reads the `(<title>, <date>, mm:ss)` citations [PromptBuilder.questionAcrossSessions]
 * asks for back out of an answer (issue #49), and matches each against
 * the excerpts that were actually sent. A citation of a session that
 * was not sent, or that cannot be told apart, is left out: the answer
 * keeps it as plain text.
 */
object CitationParser {

    /**
     * `(title, yyyy-MM-dd, [h:]mm:ss)`; the title may hold commas, and
     * the time may be in brackets as the excerpt lines write it.
     */
    private val CITATION = Regex(
        "\\(([^()]+?),\\s*(\\d{4}-\\d{2}-\\d{2}),\\s*\\[?((?:\\d{1,2}:)?\\d{1,2}:\\d{2})]?\\s*\\)"
    )

    /** The citations in [answer], in the order first cited, without repeats. */
    fun parse(answer: String, excerpts: CrossExcerpts): List<Citation> = CITATION.findAll(answer).mapNotNull { match ->
        val (title, date, time) = match.destructured
        val session = session(excerpts, title, date) ?: return@mapNotNull null
        val line = line(session, seconds(time))
        Citation(
            sessionId = session.sessionId,
            title = session.title,
            date = session.date,
            segmentId = line?.id,
            atMs = line?.startMs
        )
    }.distinct().toList()

    /**
     * The session cited as [title] on [date]: the one with that title
     * (ignoring case and accents), or the only one sent for that day.
     */
    private fun session(excerpts: CrossExcerpts, title: String, date: String): SessionExcerpts? {
        val sameDay = excerpts.sessions.filter { it.date == date }
        val wanted = SearchText.fold(title.trim())
        return sameDay.firstOrNull { SearchText.fold(it.title.trim()) == wanted } ?: sameDay.singleOrNull()
    }

    /**
     * The transcript line sent for [session] that was being spoken at
     * [seconds]: the last one starting at or before it, else the first
     * one sent; `null` when only the summary was sent.
     */
    private fun line(session: SessionExcerpts, seconds: Long) = session.chunks
        .filter { it.firstLine != CrossSessionRetriever.SUMMARY_LINE }
        .flatMap { session.lines.subList(it.firstLine, it.lastLine + 1) }
        .sortedBy { it.startMs }
        .let { sent -> sent.lastOrNull { it.startMs / 1_000L <= seconds } ?: sent.firstOrNull() }

    /** `mm:ss` or `h:mm:ss` in seconds. */
    private fun seconds(time: String): Long = time.split(':').fold(0L) { total, part -> total * 60 + part.toLong() }
}
