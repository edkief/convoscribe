package com.convoscribe.asr

import com.convoscribe.data.TokenAligner

/**
 * Gives word times to lines from a recognizer that reports none
 * (Whisper through sherpa-onnx, issue #158), so tap-to-seek, playback
 * highlighting and splitting a line at a speaker change keep working.
 *
 * The times are borrowed from [anchors]: word tokens with absolute
 * times from another transcript of the same audio, normally the live
 * streaming one. For each line, the anchors within [WINDOW_MS] of it
 * are lined up with the line's words by [TokenAligner.align]: a matched
 * word keeps the anchor's time, any other word gets a time between its
 * timed neighbours. A line with no anchors gets its words spread evenly
 * over its span. Whisper's own word times are issue #161.
 */
object WordTimer {

    /** Anchors this close to a line's start or end still count for it. */
    const val WINDOW_MS: Long = 250L

    private val WORD = Regex("\\S+")

    /**
     * [finals] with word tokens; a line that already has tokens is
     * kept as it is. Every token lies within its line's
     * `[startMs, endMs]`, in order.
     */
    fun retime(finals: List<TranscriptEvent.Final>, anchors: List<TimedToken>): List<TranscriptEvent.Final> {
        val sorted = anchors.sortedBy { it.ms }
        return finals.map { line ->
            if (line.tokens.isNotEmpty()) return@map line
            val start = line.startMs
            val end = maxOf(line.startMs, line.endMs)
            val near = sorted.filter { it.ms in (start - WINDOW_MS)..(end + WINDOW_MS) }
            val tokens = TokenAligner.align(near, line.text, start, end).ifEmpty { spread(line.text, start, end) }
            line.copy(tokens = tokens.map { it.copy(ms = it.ms.coerceIn(start, end)) })
        }
    }

    /** One token per word of [text], spread evenly from [startMs] towards [endMs]. */
    internal fun spread(text: String, startMs: Long, endMs: Long): List<TimedToken> {
        val words = WORD.findAll(text).map { it.value }.toList()
        if (words.isEmpty()) return emptyList()
        val span = endMs - startMs
        return words.mapIndexed { i, word -> TimedToken(" $word", startMs + span * i / words.size) }
    }

    /** Every token of [lines], in time order: the anchors a transcript offers. */
    fun anchorsOf(lines: List<List<TimedToken>>): List<TimedToken> = lines.flatten().sortedBy { it.ms }
}
