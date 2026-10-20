package com.convoscribe.data

/**
 * The row arithmetic behind editing a transcript by hand (issue #26),
 * kept apart from Room so it can be unit-tested: each function takes
 * the lines involved and returns what they become. Edited text keeps
 * its word timings through [TokenAligner]; [SessionRepository] writes
 * the result.
 */
object TranscriptEditor {

    private val WHITESPACE = Regex("\\s+")

    /** [text] as a transcript line stores it: trimmed, one space between words. */
    fun clean(text: String): String = text.trim().replace(WHITESPACE, " ")

    /**
     * [line] with [text] instead of its own, re-timed; `null` when the
     * new text is blank or the same as before.
     */
    fun editText(line: SegmentEntity, text: String): SegmentEntity? {
        val cleaned = clean(text)
        if (cleaned.isEmpty() || cleaned == line.text) return null
        val tokens = TokenAligner.align(TokenTimingCodec.decode(line.tokens), cleaned, line.startMs, line.endMs)
        return line.copy(text = cleaned, tokens = TokenTimingCodec.encode(tokens), textEdited = true)
    }

    /**
     * [first] and the [next] line joined into one row (keeping
     * [first]'s id and speaker). The speaker counts as chosen by hand
     * when the two lines had different speakers.
     */
    fun merge(first: SegmentEntity, next: SegmentEntity): SegmentEntity {
        val text = clean("${first.text} ${next.text}")
        val endMs = maxOf(first.endMs, next.endMs)
        val tokens = TokenTimingCodec.decode(first.tokens) + TokenTimingCodec.decode(next.tokens)
        return first.copy(
            endMs = endMs,
            text = text,
            tokens = TokenTimingCodec.encode(TokenAligner.align(tokens, text, first.startMs, endMs)),
            textEdited = true,
            speakerEdited = first.speakerEdited || next.speakerEdited || first.speakerIndex != next.speakerIndex
        )
    }

    /**
     * [line] split in two at the word boundary nearest [offset] (a
     * character position in its text). The first part keeps the row's
     * id; the second is a new row (id 0) starting when its first word
     * was spoken, or at the matching fraction of the line when there
     * are no timings. `null` when the line has fewer than two words.
     */
    fun split(line: SegmentEntity, offset: Int): Pair<SegmentEntity, SegmentEntity>? {
        val starts = TokenAligner.wordStarts(line.text)
        if (starts.size < 2) return null
        val at = (1 until starts.size).minBy { b -> distanceToBoundary(line.text, starts, b, offset) }
        val firstText = line.text.substring(0, starts[at]).trimEnd()
        val secondText = line.text.substring(starts[at]).trim()

        val tokens = TokenTimingCodec.decode(line.tokens)
        val words = TokenAligner.words(tokens)
        val lined = words.size == starts.size
        val splitMs = if (lined) {
            words[at].first().ms
        } else {
            line.startMs + (line.endMs - line.startMs) * starts[at] / line.text.length.coerceAtLeast(1)
        }.coerceIn(line.startMs, maxOf(line.startMs, line.endMs))
        val firstTokens = if (lined) words.subList(0, at).flatten() else tokens.filter { it.ms < splitMs }
        val secondTokens = if (lined) words.subList(at, words.size).flatten() else tokens.filter { it.ms >= splitMs }
        val first = line.copy(
            endMs = splitMs,
            text = firstText,
            tokens = TokenTimingCodec.encode(
                if (lined) firstTokens else TokenAligner.align(firstTokens, firstText, line.startMs, splitMs)
            ),
            textEdited = true
        )
        val second = line.copy(
            id = 0,
            startMs = splitMs,
            text = secondText,
            tokens = TokenTimingCodec.encode(
                if (lined) secondTokens else TokenAligner.align(secondTokens, secondText, splitMs, line.endMs)
            ),
            textEdited = true
        )
        return first to second
    }

    /** [line] given [speakerIndex] by hand. */
    fun setSpeaker(line: SegmentEntity, speakerIndex: Int): SegmentEntity =
        line.copy(speakerIndex = speakerIndex, speakerEdited = true)

    /**
     * How far [offset] is from the gap before word [b]: zero inside the
     * gap, else the distance to its nearer edge.
     */
    private fun distanceToBoundary(text: String, starts: List<Int>, b: Int, offset: Int): Int {
        var previousEnd = starts[b - 1]
        while (previousEnd < text.length && !text[previousEnd].isWhitespace()) previousEnd++
        return when {
            offset < previousEnd -> previousEnd - offset
            offset > starts[b] -> offset - starts[b]
            else -> 0
        }
    }
}
