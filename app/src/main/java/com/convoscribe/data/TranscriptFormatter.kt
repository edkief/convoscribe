package com.convoscribe.data

import com.convoscribe.asr.TimedToken
import java.util.Locale

/**
 * One transcript line displayed to the user. [startMs] is the
 * segment's start time in milliseconds; [speaker] is the user-facing
 * label (`"Speaker 1"`, `"Alice"`, …) or `null` for an unlabelled
 * segment, and [speakerIndex] the 0-based speaker it belongs to (set
 * together with [speaker]); the UI keys the speaker's colour and the
 * rename dialog on the index, so both survive a rename. [text] is the
 * ASR text verbatim.
 *
 * [id] is the segment's row id (a stable list key), [endMs] its end
 * time, and [tokens] its per-word timings with absolute times (empty
 * for lines recorded before schema v3); the audio player uses them to
 * follow the transcript.
 */
data class TranscriptLine(
    val startMs: Long,
    val speaker: String?,
    val text: String,
    val id: Long = 0,
    val endMs: Long = startMs,
    val tokens: List<TimedToken> = emptyList(),
    val speakerIndex: Int? = null,
    /** The user changed this line's text or speaker (issue #26). */
    val edited: Boolean = false
)

/**
 * A marked moment as the session screen and the exports show it
 * (issue #30): [atMs] in the recorded audio and the optional [note].
 */
data class TranscriptMark(
    val id: Long,
    val atMs: Long,
    val note: String? = null
)

/**
 * Pure helpers that turn the persisted [SegmentEntity] / [SpeakerEntity]
 * rows into the user-facing transcript: formatted timestamps,
 * resolved speaker labels and a plain-text export suitable for the
 * system share sheet.
 *
 * Nothing in here touches Room or the UI layer; the screen and the
 * ViewModel both call into it, and the unit test exercises it
 * directly. The formatter is stateless so it lives as an `object`.
 */
object TranscriptFormatter {
    /**
     * Format [ms] as `mm:ss` (zero-padded minutes and seconds) for
     * durations under one hour and `h:mm:ss` (unpadded hours) once
     * the hour boundary is crossed. Negative inputs are clamped to
     * zero — the diarizer only ever produces non-negative offsets
     * but a hostile clock skew could in principle hand us one, and
     * formatting `-1` as `"-1:00:00"` would render badly in the
     * transcript.
     */
    fun timestamp(ms: Long): String {
        val safe = if (ms < 0L) 0L else ms
        val totalSeconds = safe / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    /**
     * Resolve each segment's speaker label from [speakers] by
     * matching [SegmentEntity.speakerIndex] to the
     * [SpeakerEntity.speakerIndex] with the same index. Segments
     * with a `null` speaker index, or an index with no speaker row,
     * stay unlabelled (no label and no [TranscriptLine.speakerIndex]). The returned
     * list mirrors the input order: callers pass the segments
     * already ordered by `startMs ASC` and get the same order
     * back.
     *
     * [speakers] is the per-session speaker list, already ordered
     * by `speakerIndex ASC` (see [SpeakerDao.getForSession]); the
     * lookup walks it directly instead of building a map because
     * the list is small (1..5 entries in practice).
     */
    fun lines(segments: List<SegmentEntity>, speakers: List<SpeakerEntity>): List<TranscriptLine> =
        segments.map { segment ->
            val speaker = segment.speakerIndex?.let { idx ->
                speakers.firstOrNull { it.speakerIndex == idx }
            }
            TranscriptLine(
                startMs = segment.startMs,
                speaker = speaker?.label,
                speakerIndex = speaker?.speakerIndex,
                text = segment.text,
                id = segment.id,
                endMs = segment.endMs,
                tokens = TokenTimingCodec.decode(segment.tokens),
                edited = segment.textEdited || segment.speakerEdited
            )
        }

    /** The [MarkEntity] rows as the UI shows them, in the same order. */
    fun marks(rows: List<MarkEntity>): List<TranscriptMark> = rows.map { TranscriptMark(it.id, it.atMs, it.note) }

    /**
     * Index in [lines] of the line a mark at [atMs] belongs to: the
     * line being spoken then (the last one that had started), or the
     * first line for a mark before anything was said. `null` when
     * there are no lines. [lines] must be ordered by `startMs`.
     */
    fun markLineIndex(lines: List<TranscriptLine>, atMs: Long): Int? {
        if (lines.isEmpty()) return null
        val index = lines.indexOfLast { it.startMs <= atMs }
        return if (index < 0) 0 else index
    }

    /**
     * One mark as a plain-text line, `"[<timestamp>] ⚑ Mark: <note>"`,
     * or `"[<timestamp>] ⚑ Mark"` without a note.
     */
    fun markText(mark: TranscriptMark): String {
        val head = "[${timestamp(mark.atMs)}] $MARK_FLAG Mark"
        return if (mark.note.isNullOrBlank()) head else "$head: ${mark.note}"
    }

    /**
     * Render [lines] as plain text suitable for an email body or a
     * share-sheet payload. Each line is either
     * `"[<timestamp>] <speaker>: <text>"` or
     * `"[<timestamp>] <text>"` when the segment is unlabelled, and
     * lines are joined with `\n`. Consecutive lines from the same
     * speaker are **not** merged — the user gets a per-segment
     * entry that mirrors what the screen shows, so the shared
     * transcript reads the same way on the recipient's side.
     *
     * Each of [marks] (issue #30) is a [markText] line just above the
     * line it belongs to ([markLineIndex]), as the screen shows its
     * flag; with no lines the marks are listed alone.
     */
    fun toText(lines: List<TranscriptLine>, marks: List<TranscriptMark> = emptyList()): String {
        val byLine = marks.sortedBy { it.atMs }.groupBy { markLineIndex(lines, it.atMs) }
        val out = ArrayList<String>(lines.size + marks.size)
        byLine[null]?.forEach { out += markText(it) }
        lines.forEachIndexed { index, line ->
            byLine[index]?.forEach { out += markText(it) }
            out += lineText(line)
        }
        return out.joinToString(separator = "\n")
    }

    /**
     * One transcript line as [toText] writes it:
     * `"[<timestamp>] <speaker>: <text>"`, or `"[<timestamp>] <text>"`
     * when the segment is unlabelled.
     */
    fun lineText(line: TranscriptLine): String {
        val stamp = timestamp(line.startMs)
        return if (line.speaker.isNullOrEmpty()) "[$stamp] ${line.text}" else "[$stamp] ${line.speaker}: ${line.text}"
    }

    /** The flag in front of a mark in plain text. */
    const val MARK_FLAG: String = "\u2691"
}
