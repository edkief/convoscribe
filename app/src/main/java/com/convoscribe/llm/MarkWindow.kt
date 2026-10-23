package com.convoscribe.llm

import com.convoscribe.data.TranscriptFormatter
import com.convoscribe.data.TranscriptLine

/**
 * The transcript around a mark that its full note is written from
 * (issue #48). Most of it comes before the mark, since people mark a
 * moment once it has been said.
 */
object MarkWindow {

    /** How far before the mark the window reaches. */
    const val BEFORE_MS: Long = 60_000L

    /** How far after the mark the window reaches. */
    const val AFTER_MS: Long = 30_000L

    /**
     * The whole [lines] that overlap `atMs - beforeMs .. atMs + afterMs`,
     * in order, always including the line being spoken at [atMs]
     * ([TranscriptFormatter.markLineIndex]). Empty when there are no
     * lines. [lines] must be ordered by `startMs`.
     */
    fun lines(
        lines: List<TranscriptLine>,
        atMs: Long,
        beforeMs: Long = BEFORE_MS,
        afterMs: Long = AFTER_MS
    ): List<TranscriptLine> {
        val at = TranscriptFormatter.markLineIndex(lines, atMs) ?: return emptyList()
        val from = atMs - beforeMs
        val to = atMs + afterMs
        return lines.filterIndexed { index, line ->
            index == at || (line.endMs.coerceAtLeast(line.startMs) >= from && line.startMs <= to)
        }
    }
}
