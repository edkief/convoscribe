package com.convoscribe.export

import com.convoscribe.data.TranscriptFormatter

/** A piece of styled text: [bold] or regular. */
data class Run(val text: String, val bold: Boolean = false)

/** A block of a formatted document (DOCX, PDF). */
sealed interface Block {
    /** The document's title. */
    data class Title(val text: String) : Block

    /** A small, grey line under the title: date, speakers, tags. */
    data class Meta(val text: String) : Block

    /** A heading, [level] 1 (a section) to 3. */
    data class Heading(val level: Int, val text: String) : Block

    data class Paragraph(val runs: List<Run>) : Block

    /**
     * A list item: [marker] is `•`, `1.` or a task box (`[x]`, `[ ]`);
     * [depth] is its nesting, 0 at the top.
     */
    data class Item(val marker: String, val runs: List<Run>, val depth: Int = 0) : Block

    /** Quoted text, such as a mark and its note. */
    data class Quote(val runs: List<Run>) : Block
}

/**
 * A session as document blocks (issue #29), shared by [DocxWriter] and
 * [PdfWriter]: the same sections as [MarkdownExport], with the summary's
 * and the answers' Markdown read into blocks ([MarkdownBlocks]).
 */
object ExportDocument {
    fun build(session: ExportSession, options: ExportOptions): List<Block> {
        val out = mutableListOf<Block>()
        out += Block.Title(ExportText.oneLine(session.title))
        out += Block.Meta(
            listOfNotNull(
                ExportText.recordedAt(session),
                session.durationMs.takeIf { it > 0 }?.let { TranscriptFormatter.timestamp(it) }
            ).joinToString(" · ")
        )
        ExportText.speakerNames(session).takeIf { it.isNotEmpty() }?.let {
            out += Block.Meta("Speakers: ${it.joinToString(", ")}")
        }
        session.tags.takeIf { it.isNotEmpty() }?.let { out += Block.Meta("Tags: ${it.joinToString(", ")}") }

        if (options.has(ExportSection.SUMMARY)) {
            session.summary?.let { summary ->
                out += Block.Heading(1, "Summary")
                out += MarkdownBlocks.parse(summary.content, topLevel = 2)
            }
            if (session.actionItems.isNotEmpty()) {
                out += Block.Heading(1, "Action items")
                session.actionItems.forEach { item ->
                    out += Block.Item(
                        marker = if (item.done) "[x]" else "[ ]",
                        runs = listOf(Run(ExportText.oneLine(item.text) + ExportText.itemDetails(item)))
                    )
                }
            }
            if (session.talkingPoints.isNotEmpty()) {
                out += Block.Heading(1, "Talking points")
                session.talkingPoints.forEach { point ->
                    out += Block.Item(if (point.covered) "[x]" else "[ ]", listOf(Run(ExportText.oneLine(point.text))))
                }
            }
        }
        if (options.has(ExportSection.TRANSCRIPT) && (session.lines.isNotEmpty() || session.marks.isNotEmpty())) {
            out += Block.Heading(1, "Transcript")
            val byLine = session.marks.sortedBy { it.atMs }
                .groupBy { TranscriptFormatter.markLineIndex(session.lines, it.atMs) }
            val mark: (com.convoscribe.data.TranscriptMark) -> Unit = { mark ->
                val note = mark.note?.takeIf { it.isNotBlank() }?.let { ": ${ExportText.oneLine(it)}" }.orEmpty()
                val stamp = TranscriptFormatter.timestamp(mark.atMs)
                out += Block.Quote(listOf(Run("${TranscriptFormatter.MARK_FLAG} [$stamp] Mark$note", bold = true)))
                mark.expandedNote?.lines()?.filter { it.isNotBlank() }?.forEach {
                    out += Block.Quote(listOf(Run(it.trim())))
                }
            }
            byLine[null]?.forEach(mark)
            session.lines.forEachIndexed { index, line ->
                byLine[index]?.forEach(mark)
                val stamp = TranscriptFormatter.timestamp(line.startMs)
                val head = if (line.speaker.isNullOrBlank()) {
                    "[$stamp] "
                } else {
                    "[$stamp] ${ExportText.oneLine(
                        line.speaker
                    )}: "
                }
                out += Block.Paragraph(listOf(Run(head, bold = true), Run(ExportText.oneLine(line.text))))
            }
        }
        if (options.has(ExportSection.QUESTIONS) && session.questions.isNotEmpty()) {
            out += Block.Heading(1, "Questions")
            session.questions.forEach { qa ->
                out += Block.Paragraph(listOf(Run("Q: " + ExportText.oneLine(qa.question), bold = true)))
                qa.answer?.let { out += MarkdownBlocks.parse(it, topLevel = 3) }
            }
        }
        return out
    }
}

/**
 * Reads the Markdown an LLM writes (summaries, answers) into [Block]s:
 * ATX headings (the highest becomes level [topLevel], capped at 3),
 * bulleted, numbered and task lists, quotes and paragraphs, with
 * `**bold**` runs. Other inline markup is kept as text, minus code
 * backticks; fenced code becomes plain paragraphs.
 */
object MarkdownBlocks {
    private val heading = Regex("^(#{1,6})\\s+(.*?)\\s*#*$")
    private val bullet = Regex("^(\\s*)[-*+]\\s+(?:\\[([ xX])]\\s+)?(.*)$")
    private val numbered = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")

    fun parse(markdown: String, topLevel: Int): List<Block> {
        val lines = markdown.replace("\r\n", "\n").lines()
        val top = lines.mapNotNull { heading.find(it)?.groupValues?.get(1)?.length }.minOrNull() ?: 1
        val out = mutableListOf<Block>()
        val paragraph = mutableListOf<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) out += Block.Paragraph(inline(paragraph.joinToString(" ")))
            paragraph.clear()
        }
        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```")) {
                flush()
                continue
            }
            val h = heading.find(line)
            val b = bullet.find(line)
            val n = numbered.find(line)
            when {
                line.isBlank() -> flush()
                h != null -> {
                    flush()
                    out += Block.Heading(
                        minOf(3, topLevel + h.groupValues[1].length - top),
                        inline(h.groupValues[2]).joinToString("") { it.text }
                    )
                }
                b != null -> {
                    flush()
                    val marker = when (b.groupValues[2]) {
                        "" -> "•"
                        " " -> "[ ]"
                        else -> "[x]"
                    }
                    out += Block.Item(marker, inline(b.groupValues[3]), depth(b.groupValues[1]))
                }
                n != null -> {
                    flush()
                    out += Block.Item("${n.groupValues[2]}.", inline(n.groupValues[3]), depth(n.groupValues[1]))
                }
                line.trimStart().startsWith(">") -> {
                    flush()
                    out += Block.Quote(inline(line.trimStart().removePrefix(">").trim()))
                }
                else -> paragraph += line.trim()
            }
        }
        flush()
        return out
    }

    private fun depth(indent: String): Int = minOf(3, indent.replace("\t", "    ").length / 2)

    /** [text] split into regular and `**bold**` (or `__bold__`) runs, without code backticks. */
    internal fun inline(text: String): List<Run> {
        val runs = mutableListOf<Run>()
        val parts = Regex("(\\*\\*|__)(.+?)\\1").findAll(text)
        var at = 0
        for (match in parts) {
            if (match.range.first > at) runs += Run(text.substring(at, match.range.first).replace("`", ""))
            runs += Run(match.groupValues[2].replace("`", ""), bold = true)
            at = match.range.last + 1
        }
        if (at < text.length) runs += Run(text.substring(at).replace("`", ""))
        return runs.filter { it.text.isNotEmpty() }
    }
}
