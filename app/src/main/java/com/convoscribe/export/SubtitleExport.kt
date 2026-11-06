package com.convoscribe.export

import com.convoscribe.data.TranscriptLine

/** A word of a transcript line with its time on the recording's clock. */
data class TimedWord(val text: String, val startMs: Long, val endMs: Long)

/** Words from a line's per-token timings. */
object LineWords {
    /**
     * [line]'s words from its tokens: a token that starts a word
     * ([com.convoscribe.asr.TimedToken.isWordStart]) opens one, the others
     * join it. A word ends where the next begins, the last at the line's
     * end. Empty when the line has no timings (recorded before schema v3).
     */
    fun of(line: TranscriptLine): List<TimedWord> {
        val starts = mutableListOf<Pair<StringBuilder, Long>>()
        for (token in line.tokens) {
            if (starts.isEmpty() || token.isWordStart) starts += StringBuilder() to token.ms
            starts.last().first.append(token.text)
        }
        val words = starts.map { (text, ms) -> text.toString().replace('▁', ' ').trim() to ms }
            .filter { it.first.isNotEmpty() }
        return words.mapIndexed { i, (text, ms) ->
            val end = if (i + 1 < words.size) words[i + 1].second else maxOf(line.endMs, ms)
            TimedWord(text, ms, maxOf(end, ms))
        }
    }
}

/** One subtitle: shown from [startMs] to [endMs], [lines] of text, said by [speaker]. */
data class Cue(val startMs: Long, val endMs: Long, val lines: List<String>, val speaker: String?)

/**
 * A session's transcript as subtitles (issue #29), SubRip or WebVTT. Each
 * transcript line is a cue headed by its speaker (`Élodie: …`). A line
 * too long for one subtitle ([LINES_PER_CUE] lines of [MAX_LINE] characters)
 * is split at word boundaries into several cues, timed from its words
 * when they match its text, else in proportion to their length.
 *
 * Cues come in order of their start; each ends after it starts and, when
 * the next cue starts later, no later than that.
 */
object SubtitleExport {
    const val MAX_LINE: Int = 42
    const val LINES_PER_CUE: Int = 2

    /** A cue with nothing after it lasts this long when its line has no end. */
    private const val DEFAULT_CUE_MS = 1_000L

    fun srt(session: ExportSession): String = cues(session.lines).mapIndexed { i, cue ->
        "${i + 1}\n${clock(cue.startMs, ',')} --> ${clock(cue.endMs, ',')}\n${cue.lines.joinToString("\n")}\n"
    }.joinToString("\n")

    fun vtt(session: ExportSession): String {
        val cues = cues(session.lines).map { cue ->
            val text = cue.lines.joinToString("\n") { escape(it) }
            val body = cue.speaker?.let { "<v ${escape(it)}>$text" } ?: text
            "${clock(cue.startMs, '.')} --> ${clock(cue.endMs, '.')}\n$body\n"
        }
        return (listOf("WEBVTT\n") + cues).joinToString("\n")
    }

    /** The cues of [lines] (ordered by start), as [srt] and [vtt] write them. */
    fun cues(lines: List<TranscriptLine>): List<Cue> {
        val spoken = lines.filter { it.text.isNotBlank() }
        val raw = spoken.flatMapIndexed { i, line ->
            val end = when {
                line.endMs > line.startMs -> line.endMs
                i + 1 < spoken.size && spoken[i + 1].startMs > line.startMs -> spoken[i + 1].startMs
                else -> line.startMs + DEFAULT_CUE_MS
            }
            split(line, end)
        }.sortedBy { it.startMs }
        return raw.mapIndexed { i, cue ->
            val nextStart = raw.getOrNull(i + 1)?.startMs
            var end = cue.endMs
            if (nextStart != null && nextStart > cue.startMs) end = minOf(end, nextStart)
            if (end <= cue.startMs) end = cue.startMs + 1
            cue.copy(endMs = end)
        }
    }

    private fun split(line: TranscriptLine, endMs: Long): List<Cue> {
        val speaker = line.speaker?.let(ExportText::oneLine)?.takeIf { it.isNotEmpty() }
        val prefix = speaker?.let { "$it: " }.orEmpty()
        val words = ExportText.oneLine(line.text).split(' ')
        val chunks = chunk(words, prefix)
        val timed = LineWords.of(line)
        val starts: List<Long> = if (timed.size == words.size) {
            var at = 0
            chunks.map { chunk -> timed[at].startMs.also { at += chunk.size } }
        } else {
            val total = words.sumOf { it.length + 1 }.coerceAtLeast(1)
            var done = 0
            chunks.map { chunk ->
                (line.startMs + (endMs - line.startMs) * done / total).also {
                    done += chunk.sumOf { it.length + 1 }
                }
            }
        }
        return chunks.mapIndexed { i, chunk ->
            val start = maxOf(starts[i], line.startMs)
            val end = if (i + 1 < chunks.size) starts[i + 1] else endMs
            Cue(start, end, wrap(prefix + chunk.joinToString(" ")), speaker)
        }
    }

    /**
     * [words] in as few cues as fit, each [prefix] then its words in at
     * most [LINES_PER_CUE] lines, with the words spread evenly over them
     * so the last cue is not a stray word or two.
     */
    private fun chunk(words: List<String>, prefix: String): List<List<String>> {
        fun fits(chunk: List<String>) = wrap(prefix + chunk.joinToString(" ")).size <= LINES_PER_CUE
        val greedy = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        for (word in words) {
            if (current.isNotEmpty() && !fits(current + word)) {
                greedy += current
                current = mutableListOf()
            }
            current += word
        }
        if (current.isNotEmpty()) greedy += current
        if (greedy.size < 2) return greedy
        // The same number of cues, cut where the running length crosses each share.
        val total = words.sumOf { it.length + 1 }
        val balanced = mutableListOf<List<String>>()
        var start = 0
        var length = 0
        for ((i, word) in words.withIndex()) {
            length += word.length + 1
            val share = total.toLong() * (balanced.size + 1) / greedy.size
            if (balanced.size < greedy.size - 1 && length >= share && i + 1 < words.size) {
                balanced += words.subList(start, i + 1)
                start = i + 1
            }
        }
        balanced += words.subList(start, words.size)
        return if (balanced.size == greedy.size && balanced.all(::fits)) balanced else greedy
    }

    /**
     * [text] in lines of at most [MAX_LINE] characters, broken at spaces;
     * a word longer than a line is cut. Two lines are balanced when they fit.
     */
    internal fun wrap(text: String): List<String> {
        if (text.length <= MAX_LINE) return listOf(text)
        if (text.length <= MAX_LINE * LINES_PER_CUE) {
            // Break at the space nearest the middle that leaves both halves short enough.
            val breaks = text.indices.filter { text[it] == ' ' }
                .filter { it <= MAX_LINE && text.length - it - 1 <= MAX_LINE }
            val best = breaks.minByOrNull { kotlin.math.abs(it - text.length / 2) }
            if (best != null) return listOf(text.substring(0, best), text.substring(best + 1))
        }
        val out = mutableListOf<String>()
        var line = StringBuilder()
        for (word in text.split(' ')) {
            var rest = word
            while (rest.length > MAX_LINE) {
                if (line.isNotEmpty()) {
                    out += line.toString()
                    line = StringBuilder()
                }
                out += rest.substring(0, MAX_LINE)
                rest = rest.substring(MAX_LINE)
            }
            if (line.isEmpty()) {
                line.append(rest)
            } else if (line.length + 1 + rest.length <= MAX_LINE) {
                line.append(' ').append(rest)
            } else {
                out += line.toString()
                line = StringBuilder(rest)
            }
        }
        if (line.isNotEmpty()) out += line.toString()
        return out
    }

    /** `HH:MM:SS,mmm` (SubRip) or `HH:MM:SS.mmm` (WebVTT). */
    internal fun clock(ms: Long, separator: Char): String {
        val t = maxOf(0L, ms)
        return "%02d:%02d:%02d%c%03d".format(
            java.util.Locale.US,
            t / 3_600_000,
            t % 3_600_000 / 60_000,
            t % 60_000 / 1_000,
            separator,
            t % 1_000
        )
    }

    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
