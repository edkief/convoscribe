package com.convoscribe.export

import com.convoscribe.data.TranscriptFormatter
import com.convoscribe.data.TranscriptMark
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * A session as Markdown (issue #29), ready for a notes app such as
 * Obsidian: YAML front matter (title, date, duration, language, speakers,
 * participants, tags), then `# title` and each included section: the
 * summary (its headings moved under `## Summary`), action items and
 * talking points as task lists, the transcript with its marks as quotes,
 * and the questions.
 */
object MarkdownExport {
    private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun render(session: ExportSession, options: ExportOptions): String {
        val out = mutableListOf<String>()
        frontMatter(session, out)
        out += "# ${ExportText.oneLine(session.title)}"
        out += ""
        if (options.has(ExportSection.SUMMARY)) {
            session.summary?.let { summary ->
                out += "## Summary"
                out += ""
                out += demoteHeadings(summary.content.trim(), minLevel = 3)
                out += ""
            }
            if (session.actionItems.isNotEmpty()) {
                out += "## Action items"
                out += ""
                session.actionItems.forEach { item ->
                    out += "- ${ExportText.itemText(item)}"
                }
                out += ""
            }
            if (session.talkingPoints.isNotEmpty()) {
                out += "## Talking points"
                out += ""
                session.talkingPoints.forEach { point ->
                    out += "- [${if (point.covered) "x" else " "}] ${ExportText.oneLine(point.text)}"
                }
                out += ""
            }
        }
        if (options.has(ExportSection.TRANSCRIPT) && (session.lines.isNotEmpty() || session.marks.isNotEmpty())) {
            out += "## Transcript"
            out += ""
            val byLine = session.marks.sortedBy { it.atMs }
                .groupBy { TranscriptFormatter.markLineIndex(session.lines, it.atMs) }
            byLine[null]?.forEach { mark(it, out) }
            session.lines.forEachIndexed { index, line ->
                byLine[index]?.forEach { mark(it, out) }
                val stamp = TranscriptFormatter.timestamp(line.startMs)
                val head = if (line.speaker.isNullOrBlank()) {
                    "**[$stamp]**"
                } else {
                    "**[$stamp] ${ExportText.oneLine(line.speaker)}:**"
                }
                out += "$head ${escape(ExportText.oneLine(line.text))}"
                out += ""
            }
        }
        if (options.has(ExportSection.QUESTIONS) && session.questions.isNotEmpty()) {
            out += "## Questions"
            out += ""
            session.questions.forEach { qa ->
                out += "**Q:** ${ExportText.oneLine(qa.question)}"
                out += ""
                qa.answer?.let {
                    out += demoteHeadings(it.trim(), minLevel = 4)
                    out += ""
                }
            }
        }
        return out.joinToString("\n").trimEnd() + "\n"
    }

    private fun frontMatter(session: ExportSession, out: MutableList<String>) {
        out += "---"
        out += "title: ${yaml(ExportText.oneLine(session.title))}"
        out += "date: ${ISO.format(Instant.ofEpochMilli(session.createdAt).atZone(session.zone))}"
        if (session.durationMs > 0) out += "duration: ${yaml(TranscriptFormatter.timestamp(session.durationMs))}"
        out += "language: ${yaml(session.language)}"
        list("speakers", ExportText.speakerNames(session), out)
        list("participants", session.participants, out)
        list("tags", session.tags, out)
        out += "---"
        out += ""
    }

    private fun list(key: String, values: List<String>, out: MutableList<String>) {
        if (values.isEmpty()) return
        out += "$key:"
        values.forEach { out += "  - ${yaml(ExportText.oneLine(it))}" }
    }

    /** Transcript text as Markdown text: a `<` would start an HTML tag. */
    private fun escape(text: String): String = text.replace("<", "&lt;")

    /** [value] as a double-quoted YAML scalar. */
    internal fun yaml(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun mark(mark: TranscriptMark, out: MutableList<String>) {
        val note = mark.note?.takeIf { it.isNotBlank() }?.let { ": ${escape(ExportText.oneLine(it))}" }.orEmpty()
        out += "> ${TranscriptFormatter.MARK_FLAG} [${TranscriptFormatter.timestamp(mark.atMs)}] Mark$note"
        mark.expandedNote?.lines()?.filter { it.isNotBlank() }?.forEach { out += "> ${it.trim()}" }
        out += ""
    }

    /**
     * [markdown] with its ATX headings moved down so the highest one is at
     * [minLevel] (capped at 6), keeping their relative levels; text inside
     * fenced code blocks is left alone.
     */
    internal fun demoteHeadings(markdown: String, minLevel: Int): String {
        val heading = Regex("^(#{1,6})(\\s+.*)$")
        val lines = markdown.lines()
        var fenced = false
        var top = Int.MAX_VALUE
        for (line in lines) {
            if (line.trimStart().startsWith("```")) fenced = !fenced
            if (!fenced) heading.find(line)?.let { top = minOf(top, it.groupValues[1].length) }
        }
        if (top == Int.MAX_VALUE || top >= minLevel) return markdown
        val shift = minLevel - top
        fenced = false
        return lines.joinToString("\n") { line ->
            if (line.trimStart().startsWith("```")) fenced = !fenced
            val match = if (fenced) null else heading.find(line)
            if (match == null) {
                line
            } else {
                "#".repeat(minOf(6, match.groupValues[1].length + shift)) + match.groupValues[2]
            }
        }
    }
}
