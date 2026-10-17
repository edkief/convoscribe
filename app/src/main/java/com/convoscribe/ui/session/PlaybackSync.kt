package com.convoscribe.ui.session

import com.convoscribe.data.TranscriptLine

/**
 * A word of a transcript line: [start] until [end] are its characters
 * in [TranscriptLine.text] (end exclusive) and [startMs] is when it
 * was spoken.
 */
data class WordSpan(val start: Int, val end: Int, val startMs: Long)

/**
 * Pure mapping between the playback position and the transcript, kept
 * apart from the player and the Compose code so it can be unit-tested.
 */
object PlaybackSync {

    private val WORD = Regex("\\S+")

    /** Playback speeds the speed button cycles through. */
    val SPEEDS: List<Float> = listOf(1f, 1.5f, 2f)

    /** Skip back / forward step of the player buttons. */
    const val SKIP_MS: Long = 10_000L

    /**
     * Index of the line being spoken at [positionMs]: the last line
     * that has started. The line stays active through a pause until
     * the next one starts. `null` before the first line starts or
     * when there are no lines. [lines] must be ordered by `startMs`.
     */
    fun activeLineIndex(lines: List<TranscriptLine>, positionMs: Long): Int? {
        var low = 0
        var high = lines.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].startMs <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return if (found < 0) null else found
    }

    /**
     * The words of [line] with their start times, or `null` when the
     * line has no timings or they do not line up with the text (the
     * UI then highlights the whole line only). Displayed text is the
     * normalised token text, which only changes case and whitespace,
     * so the n-th whitespace-separated word of the text is the n-th
     * word of the tokens (a new word starts at each word-start token).
     */
    fun words(line: TranscriptLine): List<WordSpan>? {
        if (line.tokens.isEmpty()) return null
        val wordStarts = ArrayList<Long>()
        for ((i, token) in line.tokens.withIndex()) {
            if (i == 0 || token.isWordStart) wordStarts.add(token.ms)
        }
        val matches = WORD.findAll(line.text).toList()
        if (matches.size != wordStarts.size) return null
        return matches.mapIndexed { i, m -> WordSpan(m.range.first, m.range.last + 1, wordStarts[i]) }
    }

    /**
     * Index into [words] of the word spoken at [positionMs]: the last
     * word that has started. `null` when the line has no usable
     * timings, [positionMs] is before the first word, or it is past
     * the line's end.
     */
    fun activeWordIndex(line: TranscriptLine, positionMs: Long): Int? {
        val words = words(line) ?: return null
        if (positionMs > line.endMs) return null
        val index = words.indexOfLast { it.startMs <= positionMs }
        return if (index < 0) null else index
    }

    /** Where playback starts when [line] is tapped. */
    fun seekTargetMs(line: TranscriptLine): Long = line.startMs.coerceAtLeast(0L)

    /** The speed after [current] in [SPEEDS], wrapping to the first. */
    fun nextSpeed(current: Float): Float {
        val index = SPEEDS.indexOf(current)
        return SPEEDS[(index + 1) % SPEEDS.size]
    }
}
