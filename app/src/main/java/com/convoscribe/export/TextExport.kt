package com.convoscribe.export

import com.convoscribe.data.TranscriptFormatter
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Pieces the text exports share. */
internal object ExportText {
    private val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** When [session] was recorded, `yyyy-MM-dd HH:mm` in its zone. */
    fun recordedAt(session: ExportSession): String =
        WHEN.format(Instant.ofEpochMilli(session.createdAt).atZone(session.zone))

    /** The speaker names, in speaker order, without blanks or repeats. */
    fun speakerNames(session: ExportSession): List<String> =
        session.speakers.sortedBy { it.index }.map { it.label.trim() }.filter { it.isNotEmpty() }.distinct()

    /** `" (Élodie, due 2026-11-10)"` for an item with an owner or a due date, else `""`. */
    fun itemDetails(item: ExportActionItem): String {
        val parts = listOfNotNull(
            item.owner?.trim()?.takeIf { it.isNotEmpty() },
            item.dueDate?.trim()?.takeIf { it.isNotEmpty() }?.let { "due $it" }
        )
        return if (parts.isEmpty()) "" else " (${parts.joinToString(", ")})"
    }

    /** `[x] Send the deck (Élodie, due 2026-11-10)`: a ticked or open task. */
    fun itemText(item: ExportActionItem): String =
        "${if (item.done) "[x]" else "[ ]"} ${oneLine(item.text)}${itemDetails(item)}"

    /** One line of text: line breaks and runs of spaces become one space. */
    fun oneLine(text: String): String = text.replace(Regex("\\s+"), " ").trim()
}

/**
 * A session as plain text (issue #29): a header, then each included
 * section under its name. The transcript is [TranscriptFormatter.toText],
 * as the share button sends it.
 */
object TextExport {
    fun render(session: ExportSession, options: ExportOptions): String {
        val out = mutableListOf<String>()
        out += ExportText.oneLine(session.title)
        out += listOfNotNull(
            ExportText.recordedAt(session),
            session.durationMs.takeIf { it > 0 }?.let { TranscriptFormatter.timestamp(it) }
        ).joinToString(" · ")
        ExportText.speakerNames(session).takeIf { it.isNotEmpty() }?.let { out += "Speakers: ${it.joinToString(", ")}" }
        session.tags.takeIf { it.isNotEmpty() }?.let { out += "Tags: ${it.joinToString(", ")}" }

        if (options.has(ExportSection.SUMMARY)) {
            session.summary?.let { summary ->
                out += ""
                out += "Summary"
                out += ""
                out += summary.content.trim()
            }
            if (session.actionItems.isNotEmpty()) {
                out += ""
                out += "Action items"
                session.actionItems.forEach { item ->
                    out += ExportText.itemText(item)
                }
            }
            if (session.talkingPoints.isNotEmpty()) {
                out += ""
                out += "Talking points"
                session.talkingPoints.forEach { point ->
                    out += "${if (point.covered) "[x]" else "[ ]"} ${ExportText.oneLine(point.text)}"
                }
            }
        }
        if (options.has(ExportSection.TRANSCRIPT) && (session.lines.isNotEmpty() || session.marks.isNotEmpty())) {
            out += ""
            out += "Transcript"
            out += ""
            out += TranscriptFormatter.toText(session.lines, session.marks)
        }
        if (options.has(ExportSection.QUESTIONS) && session.questions.isNotEmpty()) {
            out += ""
            out += "Questions"
            session.questions.forEach { qa ->
                out += ""
                out += "Q: ${qa.question.trim()}"
                qa.answer?.let { out += "A: ${it.trim()}" }
            }
        }
        return out.joinToString("\n") + "\n"
    }
}
