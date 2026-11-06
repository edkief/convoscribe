package com.convoscribe.export

import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A session as JSON for scripts (issue #29). The schema is documented in
 * `docs/export-json.md`: [FORMAT] names it and [VERSION] changes when a
 * field is removed or changes meaning (new fields keep the version).
 * Sections the user left out are absent, and so are fields with no value.
 */
object JsonExport {
    const val FORMAT: String = "convoscribe-transcript"
    const val VERSION: Int = 1

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        explicitNulls = false
        encodeDefaults = true
    }

    private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun render(session: ExportSession, options: ExportOptions): String {
        val transcript = options.has(ExportSection.TRANSCRIPT)
        val summary = options.has(ExportSection.SUMMARY)
        val document = Document(
            format = FORMAT,
            version = VERSION,
            session = Session(
                title = session.title,
                createdAt = iso(session, session.createdAt),
                createdAtMs = session.createdAt,
                durationMs = session.durationMs,
                language = session.language,
                participants = session.participants,
                tags = session.tags
            ),
            speakers = session.speakers.sortedBy { it.index }.map { Speaker(it.index, it.label) },
            segments = if (!transcript) {
                null
            } else {
                session.lines.map { line ->
                    Segment(
                        startMs = line.startMs,
                        endMs = line.endMs,
                        speakerIndex = line.speakerIndex,
                        speaker = line.speaker,
                        text = line.text,
                        edited = line.edited,
                        words = LineWords.of(line).map { Word(it.text, it.startMs, it.endMs) }
                    )
                }
            },
            marks = if (!transcript) {
                null
            } else {
                session.marks.sortedBy { it.atMs }.map { Mark(it.atMs, it.note, it.expandedNote) }
            },
            summary = if (!summary) {
                null
            } else {
                session.summary?.let { Summary(it.content, it.templateName, iso(session, it.createdAt)) }
            },
            actionItems = if (!summary) {
                null
            } else {
                session.actionItems.map { ActionItem(it.text, it.owner, it.dueDate, it.atMs, it.done) }
            },
            talkingPoints = if (!summary) {
                null
            } else {
                session.talkingPoints.map { TalkingPoint(it.text, it.covered, it.coveredAtMs) }
            },
            questions = if (!options.has(ExportSection.QUESTIONS)) {
                null
            } else {
                session.questions.map { Question(it.question, it.answer, iso(session, it.askedAt)) }
            }
        )
        return json.encodeToString(Document.serializer(), document) + "\n"
    }

    private fun iso(session: ExportSession, ms: Long): String =
        ISO.format(Instant.ofEpochMilli(ms).atZone(session.zone))

    @Serializable
    internal data class Document(
        val format: String,
        val version: Int,
        val session: Session,
        val speakers: List<Speaker>,
        val segments: List<Segment>? = null,
        val marks: List<Mark>? = null,
        val summary: Summary? = null,
        val actionItems: List<ActionItem>? = null,
        val talkingPoints: List<TalkingPoint>? = null,
        val questions: List<Question>? = null
    )

    @Serializable
    internal data class Session(
        val title: String,
        val createdAt: String,
        val createdAtMs: Long,
        val durationMs: Long,
        val language: String,
        val participants: List<String>,
        val tags: List<String>
    )

    @Serializable
    internal data class Speaker(val index: Int, val label: String)

    @Serializable
    internal data class Segment(
        val startMs: Long,
        val endMs: Long,
        val speakerIndex: Int? = null,
        val speaker: String? = null,
        val text: String,
        val edited: Boolean,
        val words: List<Word>
    )

    @Serializable
    internal data class Word(val text: String, val startMs: Long, val endMs: Long)

    @Serializable
    internal data class Mark(val atMs: Long, val note: String? = null, val fullNote: String? = null)

    @Serializable
    internal data class Summary(val content: String, val template: String? = null, val createdAt: String)

    @Serializable
    internal data class ActionItem(
        val text: String,
        val owner: String? = null,
        val dueDate: String? = null,
        val atMs: Long? = null,
        val done: Boolean
    )

    @Serializable
    internal data class TalkingPoint(val text: String, val covered: Boolean, val coveredAtMs: Long? = null)

    @Serializable
    internal data class Question(val question: String, val answer: String? = null, val askedAt: String)
}
