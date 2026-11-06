package com.convoscribe.export

import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * A file format a session exports to (issue #29). [key] is stored in
 * settings; [mime] is what the share sheet and the saved document are
 * typed as. [sections] says which parts of a session the format can hold:
 * subtitles are the transcript alone.
 */
enum class ExportFormat(
    val key: String,
    val extension: String,
    val mime: String,
    val sections: Set<ExportSection> = ExportSection.entries.toSet()
) {
    TEXT("txt", "txt", "text/plain"),
    MARKDOWN("md", "md", "text/markdown"),
    SRT("srt", "srt", "application/x-subrip", setOf(ExportSection.TRANSCRIPT)),
    VTT("vtt", "vtt", "text/vtt", setOf(ExportSection.TRANSCRIPT)),
    JSON("json", "json", "application/json"),
    DOCX("docx", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    PDF("pdf", "pdf", "application/pdf");

    /**
     * The type to create a document with in a chosen folder. Some
     * storage providers add the extension they know for a type, so the
     * formats Android may not know by type are created untyped and keep
     * the name as given.
     */
    val documentMime: String
        get() = when (this) {
            MARKDOWN, SRT, VTT -> "application/octet-stream"
            else -> mime
        }

    companion object {
        fun of(key: String?): ExportFormat = entries.firstOrNull { it.key == key } ?: MARKDOWN
    }
}

/** The parts of a session an export can include. */
enum class ExportSection(val key: String) {
    /** The transcript lines, with the marks among them. */
    TRANSCRIPT("transcript"),

    /** The summary, its action items and the talking points. */
    SUMMARY("summary"),

    /** The questions asked about the session and their answers. */
    QUESTIONS("questions");

    companion object {
        /** The sections named in [keys] (comma-separated), unknown keys ignored. */
        fun parse(keys: String?): Set<ExportSection> =
            keys.orEmpty().split(',').mapNotNull { key -> entries.firstOrNull { it.key == key.trim() } }.toSet()

        fun encode(sections: Set<ExportSection>): String =
            entries.filter { it in sections }.joinToString(",") { it.key }
    }
}

/** What to export: the [format] and the [sections] the user ticked. */
data class ExportOptions(
    val format: ExportFormat,
    val sections: Set<ExportSection> = ExportSection.entries.toSet()
) {
    /** The ticked sections the format can hold. */
    val effective: Set<ExportSection> get() = sections intersect format.sections

    /** Whether there is anything to export. */
    val valid: Boolean get() = effective.isNotEmpty()

    fun has(section: ExportSection): Boolean = section in effective
}

/** File names for exports. */
object ExportNames {
    private const val MAX_TITLE = 60
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /**
     * [title] made safe as a file name: characters awkward in file names
     * become `_`, runs of spaces one space, the result capped; a blank
     * title becomes [fallback].
     */
    fun safe(title: String, fallback: String): String {
        val cleaned = title.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.', '_')
            .take(MAX_TITLE)
            .trim(' ', '.', '_')
        return cleaned.ifEmpty { fallback }
    }

    /**
     * `yyyy-MM-dd <title>.<extension>` for [session] in [format]: the
     * recording's local day first, so a folder lists sessions in order,
     * unless the title already holds it (as a default title does).
     */
    fun fileName(session: ExportSession, format: ExportFormat): String {
        val day = DAY.format(Instant.ofEpochMilli(session.createdAt).atZone(session.zone))
        val title = safe(session.title, "Conversation")
        val base = if (title.contains(day)) title else "$day $title"
        return "$base.${format.extension}"
    }
}
