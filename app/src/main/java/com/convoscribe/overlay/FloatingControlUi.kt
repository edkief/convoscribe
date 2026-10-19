package com.convoscribe.overlay

import com.convoscribe.recording.RecordingPhase
import com.convoscribe.recording.RecordingState
import com.convoscribe.settings.FloatingShape

/**
 * What the floating control draws (issue #21): only the parts of
 * [RecordingState] its [FloatingShape] shows. [FloatingControl] feeds
 * the content through `distinctUntilChanged`, so a field left `null`
 * here can never cause a redraw. That is what keeps the minimal shape
 * still on e-ink.
 *
 * @property elapsedMs recorded time in whole seconds (as ms), or
 *   `null` when the shape shows no time
 * @property transcript the end of the transcript ([transcriptTail]),
 *   or `null` when the shape shows none or nothing is transcribed yet
 */
data class FloatingControlUi(
    val paused: Boolean,
    val elapsedMs: Long? = null,
    val transcript: String? = null
) {
    companion object {
        /**
         * The [shape]'s view of [state]. The expanded shape shows the
         * text being recognised ([RecordingState.partialText]) only
         * outside e-ink ([eink]), so that on e-ink it changes once per
         * sentence rather than per word. [maxChars] is the transcript
         * budget, smaller with a larger font ([maxCharsFor]).
         */
        fun of(
            state: RecordingState,
            shape: FloatingShape,
            eink: Boolean,
            maxChars: Int = MAX_CHARS
        ): FloatingControlUi {
            val paused = state.phase == RecordingPhase.PAUSED
            val seconds = state.elapsedMs / 1_000L * 1_000L
            return when (shape) {
                FloatingShape.MINIMAL -> FloatingControlUi(paused)
                FloatingShape.STANDARD -> FloatingControlUi(paused, elapsedMs = seconds)
                FloatingShape.EXPANDED -> {
                    val partial = if (eink || paused) "" else state.partialText
                    FloatingControlUi(
                        paused,
                        elapsedMs = seconds,
                        transcript = transcriptTail(state.finalLines, partial, maxChars)
                    )
                }
            }
        }

        /**
         * The last [LINES] final [lines] and the [partial] text, joined,
         * cut to at most [maxChars] from the end: the newest words are
         * the ones to keep, and Compose can only ellipsize the end of
         * multi-line text. The cut falls on a word boundary, behind an
         * ellipsis. `null` when there is nothing yet.
         */
        fun transcriptTail(lines: List<String>, partial: String, maxChars: Int = MAX_CHARS): String? {
            val text = (lines.takeLast(LINES) + partial)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            if (text.isEmpty()) return null
            if (text.length <= maxChars) return text
            val cut = text.substring(text.length - maxChars)
            val space = cut.indexOf(' ')
            val words = if (space in 0 until cut.length - 1) cut.substring(space + 1) else cut
            return "…$words"
        }

        /** The transcript budget at [fontScale]: what fits the card's three lines, so the end is never cut. */
        fun maxCharsFor(fontScale: Float): Int = (MAX_CHARS / fontScale.coerceAtLeast(1f)).toInt()

        private const val LINES = 2

        /** About three lines of the expanded card at the default font size. */
        private const val MAX_CHARS = 110
    }
}
