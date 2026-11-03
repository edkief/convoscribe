package com.convoscribe.llm

import com.convoscribe.data.TranscriptFormatter
import com.convoscribe.data.TranscriptLine

/**
 * The stretch of a recording's transcript one talking-point check
 * reads (issue #103): the speech since the last check, plus a little
 * before it so a point raised across the boundary is still seen.
 *
 * @property lines the whole lines sent, oldest first.
 * @property text [lines] as the model reads them ([TranscriptFormatter.lineText]).
 * @property endMs where the next check starts: the end of the newest line.
 */
data class TalkingPointWindow(
    val lines: List<TranscriptLine>,
    val text: String,
    val endMs: Long
) {
    /** Recording time of the first and the last line sent. */
    val firstMs: Long get() = lines.first().startMs
    val lastMs: Long get() = lines.last().startMs

    companion object {

        /** How far before the last check the window reaches back. */
        const val OVERLAP_MS: Long = 20_000L

        /**
         * The window for a check of everything said since [sinceMs], or
         * `null` when nothing has been said since then. Reaches back
         * [overlapMs] before [sinceMs]; when that is more than
         * [maxChars], the oldest lines are left out, since the newest
         * speech is what has not been checked yet. A single line longer
         * than [maxChars] keeps its end. [lines] must be ordered by
         * `startMs`.
         */
        fun select(
            lines: List<TranscriptLine>,
            sinceMs: Long,
            maxChars: Int,
            overlapMs: Long = OVERLAP_MS
        ): TalkingPointWindow? {
            if (lines.none { it.startMs >= sinceMs }) return null
            val from = sinceMs - overlapMs
            val candidates = lines.filter { it.endMs.coerceAtLeast(it.startMs) >= from }
            val kept = ArrayDeque<TranscriptLine>()
            val texts = ArrayDeque<String>()
            var length = 0
            for (line in candidates.asReversed()) {
                val text = TranscriptFormatter.lineText(line)
                val added = text.length + if (texts.isEmpty()) 0 else 1
                if (texts.isNotEmpty() && length + added > maxChars) break
                kept.addFirst(line)
                texts.addFirst(text)
                length += added
            }
            val joined = texts.joinToString(separator = "\n")
            val text = if (joined.length > maxChars) joined.takeLast(maxChars) else joined
            val newest = candidates.last()
            return TalkingPointWindow(kept.toList(), text, newest.endMs.coerceAtLeast(newest.startMs))
        }
    }
}
