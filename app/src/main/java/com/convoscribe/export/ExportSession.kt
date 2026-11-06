package com.convoscribe.export

import com.convoscribe.data.ChatMessageEntity
import com.convoscribe.data.MessageKind
import com.convoscribe.data.MessageRole
import com.convoscribe.data.ParticipantsCodec
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.TranscriptFormatter
import com.convoscribe.data.TranscriptLine
import com.convoscribe.data.TranscriptMark
import java.time.ZoneId
import kotlinx.coroutines.flow.first

/**
 * Everything an export can contain about one session (issue #29), read
 * once from the database so the formatters stay pure. [lines] carry the
 * current speaker names (renames included) and their word timings;
 * [zone] is where dates are written for, the device's time zone.
 */
data class ExportSession(
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    val language: String,
    val speakers: List<ExportSpeaker>,
    val participants: List<String>,
    val tags: List<String>,
    val lines: List<TranscriptLine>,
    val marks: List<TranscriptMark>,
    val summary: ExportSummary?,
    val actionItems: List<ExportActionItem>,
    val talkingPoints: List<ExportTalkingPoint>,
    val questions: List<ExportQuestion>,
    val zone: ZoneId
)

/** A speaker of the session: its 0-based [index] and the name shown for it. */
data class ExportSpeaker(val index: Int, val label: String)

/** The saved summary, in Markdown as the LLM wrote it, and the template it used. */
data class ExportSummary(val content: String, val templateName: String?, val createdAt: Long)

/** An action item of the summary (issue #31); [atMs] where it was agreed, when known. */
data class ExportActionItem(
    val text: String,
    val owner: String?,
    val dueDate: String?,
    val atMs: Long?,
    val done: Boolean
)

/** A talking point (issue #104) and whether, and when, it was covered. */
data class ExportTalkingPoint(val text: String, val covered: Boolean, val coveredAtMs: Long?)

/** One question asked about the session and its answer, `null` when none was saved. */
data class ExportQuestion(val question: String, val answer: String?, val askedAt: Long)

/** Reads an [ExportSession] from the [repository]. */
class SessionExportLoader(
    private val repository: SessionRepository,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) {
    /** The session [sessionId], or `null` when it no longer exists. */
    suspend fun load(sessionId: Long): ExportSession? {
        val session = repository.getSession(sessionId) ?: return null
        val speakers = repository.getSpeakers(sessionId)
        val summary = repository.getMessages(sessionId, MessageKind.SUMMARY)
            .lastOrNull { it.role == MessageRole.ASSISTANT }
        return ExportSession(
            title = session.title,
            createdAt = session.createdAt,
            durationMs = session.durationMs,
            language = session.language,
            speakers = speakers.map { ExportSpeaker(it.speakerIndex, it.label) },
            participants = ParticipantsCodec.decode(session.participants).orEmpty(),
            tags = repository.observeTagsFor(sessionId).first().map { it.name },
            lines = TranscriptFormatter.lines(repository.getSegments(sessionId), speakers),
            marks = TranscriptFormatter.marks(repository.getMarks(sessionId)),
            summary = summary?.let { ExportSummary(it.content, it.templateName, it.createdAt) },
            actionItems = repository.observeActionItems(sessionId).first()
                .filter { !it.deleted }
                .map { ExportActionItem(it.text, it.owner, it.dueDate, it.atMs, it.done) },
            talkingPoints = repository.getTalkingPoints(sessionId)
                .map { ExportTalkingPoint(it.text, it.covered, it.coveredAtMs) },
            questions = pairQuestions(repository.getMessages(sessionId, MessageKind.QA)),
            zone = zone()
        )
    }

    companion object {
        /**
         * The Q&A history as question/answer pairs, oldest first: each
         * question takes the first answer after it, before the next
         * question. An answer with no question before it is dropped.
         */
        fun pairQuestions(messages: List<ChatMessageEntity>): List<ExportQuestion> {
            val out = mutableListOf<ExportQuestion>()
            for (message in messages) {
                when (message.role) {
                    MessageRole.USER -> out += ExportQuestion(message.content, null, message.createdAt)
                    MessageRole.ASSISTANT -> {
                        val last = out.lastOrNull()
                        if (last != null && last.answer == null) {
                            out[out.lastIndex] = last.copy(answer = message.content)
                        }
                    }
                }
            }
            return out
        }
    }
}
