package com.convoscribe.llm

import com.convoscribe.data.TranscriptFormatter
import com.convoscribe.data.TranscriptLine

/**
 * A run of consecutive transcript lines rendered as text (issue #28).
 *
 * [text] is the lines as [TranscriptFormatter.lineText] writes them,
 * joined with `\n`. [startMs] is the first line's start; [endMs] is
 * where the next chunk's new content starts (the start of the first
 * line after this chunk), or the end of the last line for the final
 * chunk, so chunks without overlap tile the conversation's timeline.
 * [firstLine] and [lastLine] are indices into the input lines.
 */
data class TranscriptChunk(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val firstLine: Int,
    val lastLine: Int
)

/**
 * Splits a transcript that does not fit the model's character budget
 * into chunks that do (issue #28). Chunks hold whole lines; a line is
 * cut only when it alone is longer than the budget, and then at a
 * space where possible.
 */
object TranscriptChunker {

    /** One line, or one slice of a line too long for a chunk. */
    private class Piece(val text: String, val line: Int, val startMs: Long, val endMs: Long)

    /**
     * Pack [lines] into chunks of at most [maxChars] characters each.
     * Every chunk after the first opens with the last lines of the
     * previous one, up to [overlapChars] characters, so a point made
     * across a boundary is seen whole by at least one chunk. Empty
     * for no lines.
     */
    fun chunk(lines: List<TranscriptLine>, maxChars: Int, overlapChars: Int = maxChars / 20): List<TranscriptChunk> {
        require(maxChars > 0) { "maxChars must be positive" }
        val pieces = lines.flatMapIndexed { index, line ->
            split(TranscriptFormatter.lineText(line), maxChars).map { Piece(it, index, line.startMs, line.endMs) }
        }
        if (pieces.isEmpty()) return emptyList()
        val lastEnd = pieces.maxOf { maxOf(it.startMs, it.endMs) }

        val chunks = ArrayList<TranscriptChunk>()
        var start = 0
        while (true) {
            var end = start
            var length = 0
            while (end < pieces.size) {
                val added = pieces[end].text.length + if (end > start) 1 else 0
                if (length + added > maxChars) break
                length += added
                end++
            }
            val slice = pieces.subList(start, end)
            chunks += TranscriptChunk(
                text = slice.joinToString(separator = "\n") { it.text },
                startMs = slice.first().startMs,
                endMs = if (end < pieces.size) pieces[end].startMs else lastEnd,
                firstLine = slice.first().line,
                lastLine = slice.last().line
            )
            if (end == pieces.size) return chunks
            start = overlapStart(pieces, start, end, overlapChars, maxChars)
        }
    }

    /**
     * Where the chunk after `pieces[start, end)` starts: the earliest
     * piece after [start] such that the overlap is at most
     * [overlapChars] long and still leaves room for `pieces[end]`, so
     * every chunk adds new content.
     */
    private fun overlapStart(pieces: List<Piece>, start: Int, end: Int, overlapChars: Int, maxChars: Int): Int {
        val room = minOf(overlapChars, maxChars - pieces[end].text.length - 1)
        var next = end
        var length = 0
        while (next - 1 > start) {
            val added = pieces[next - 1].text.length + if (next < end) 1 else 0
            if (length + added > room) break
            length += added
            next--
        }
        return next
    }

    /** [text] as is when it fits [maxChars], else slices that do, cut at a space where there is one. */
    private fun split(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val out = ArrayList<String>()
        var rest = text
        while (rest.length > maxChars) {
            val space = rest.lastIndexOf(' ', maxChars)
            val cut = if (space > 0) space else maxChars
            out += rest.substring(0, cut)
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }
}
