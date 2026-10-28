package com.convoscribe.llm.embedding

import java.io.File
import java.util.PriorityQueue

/**
 * Gemma's SentencePiece BPE tokenizer (issue #61), read from the
 * model's `tokenizer.model`, so EmbeddingGemma sees the same ids the
 * reference tokenizer gives.
 *
 * Spaces become `▁` and nothing else is normalised. User-defined
 * pieces (`<start_of_turn>`, runs of spaces, …) are matched first and
 * never merged. The rest of the text starts as single characters, and
 * the adjacent pair whose joined piece scores highest is merged until
 * no joined pair is in the vocabulary. A symbol with no piece is
 * written as its UTF-8 bytes (`<0x41>` pieces).
 */
class GemmaTokenizer private constructor(
    private val ids: Map<String, Int>,
    private val scores: FloatArray,
    private val userDefined: Map<Char, List<String>>,
    private val byteIds: IntArray
) {
    /**
     * The ids of [text], `<bos>` first and `<eos>` last, at most
     * [maxTokens] in all: a longer text is cut, keeping its start.
     */
    fun encode(text: String, maxTokens: Int = Int.MAX_VALUE): IntArray {
        val body = pieces(text.replace(' ', SPACE))
        val kept = body.take(maxOf(0, maxTokens - 2))
        return (listOf(BOS) + kept + EOS).toIntArray()
    }

    /** One symbol of the text being merged, in a linked list. */
    private class Symbol(var text: String, val frozen: Boolean) {
        var prev: Symbol? = null
        var next: Symbol? = null
        var alive = true
    }

    /** A pair that may merge into a piece of [score]; [left] breaks ties, leftmost first. */
    private class Pair(val left: Symbol, val right: Symbol, val joined: String, val score: Float, val order: Int)

    private fun pieces(text: String): List<Int> {
        if (text.isEmpty()) return emptyList()
        val symbols = split(text)
        for (i in 1 until symbols.size) {
            symbols[i - 1].next = symbols[i]
            symbols[i].prev = symbols[i - 1]
        }
        val queue = PriorityQueue<Pair>(compareByDescending<Pair> { it.score }.thenBy { it.order })
        val positions = HashMap<Symbol, Int>(symbols.size * 2)
        symbols.forEachIndexed { index, symbol -> positions[symbol] = index }
        fun offer(left: Symbol?, right: Symbol?) {
            if (left == null || right == null || left.frozen || right.frozen) return
            val joined = left.text + right.text
            val id = ids[joined] ?: return
            queue += Pair(left, right, joined, scores[id], positions.getValue(left))
        }
        for (i in 1 until symbols.size) offer(symbols[i - 1], symbols[i])
        while (queue.isNotEmpty()) {
            val pair = queue.poll()!!
            val (left, right) = pair.left to pair.right
            // Stale: one side has merged since this pair was queued.
            if (!left.alive || !right.alive || left.next !== right || left.text + right.text != pair.joined) continue
            left.text = pair.joined
            right.alive = false
            left.next = right.next
            right.next?.prev = left
            offer(left.prev, left)
            offer(left, left.next)
        }
        val out = ArrayList<Int>(symbols.size)
        var symbol: Symbol? = symbols.first()
        while (symbol != null) {
            val id = ids[symbol.text]
            if (id != null) {
                out += id
            } else {
                symbol.text.toByteArray(Charsets.UTF_8).forEach { out += byteIds[it.toInt() and 0xFF] }
            }
            symbol = symbol.next
        }
        return out
    }

    /** [text] as user-defined pieces (longest match first; [userDefined] is keyed by first character) and single characters. */
    private fun split(text: String): List<Symbol> {
        val out = ArrayList<Symbol>(text.length)
        var i = 0
        while (i < text.length) {
            val match = userDefined[text[i]]?.firstOrNull { text.startsWith(it, i) }
            if (match != null) {
                out += Symbol(match, frozen = true)
                i += match.length
            } else {
                val end = i + Character.charCount(text.codePointAt(i))
                out += Symbol(text.substring(i, end), frozen = false)
                i = end
            }
        }
        return out
    }

    companion object {
        const val BOS: Int = 2
        const val EOS: Int = 1
        const val PAD: Int = 0

        private const val SPACE = '▁'

        // SentencePiece piece types (sentencepiece_model.proto).
        private const val TYPE_NORMAL = 1
        private const val TYPE_USER_DEFINED = 4
        private const val TYPE_BYTE = 6

        /** Reads [file], a SentencePiece `tokenizer.model`. */
        fun load(file: File): GemmaTokenizer = parse(file.readBytes())

        /** Parses a SentencePiece `ModelProto`, keeping only its pieces. */
        fun parse(bytes: ByteArray): GemmaTokenizer {
            val ids = HashMap<String, Int>(300_000)
            val scores = ArrayList<Float>(300_000)
            val userDefined = ArrayList<String>()
            val byteIds = IntArray(256) { -1 }
            val reader = ProtoReader(bytes, 0, bytes.size)
            while (reader.hasMore()) {
                val (field, wire) = reader.tag()
                if (field != 1 || wire != ProtoReader.LENGTH) {
                    reader.skip(wire)
                    continue
                }
                val piece = reader.message()
                var text = ""
                var score = 0f
                var type = TYPE_NORMAL
                while (piece.hasMore()) {
                    val (f, w) = piece.tag()
                    when {
                        f == 1 && w == ProtoReader.LENGTH -> text = piece.string()
                        f == 2 && w == ProtoReader.FIXED32 -> score = piece.float()
                        f == 3 && w == ProtoReader.VARINT -> type = piece.varint().toInt()
                        else -> piece.skip(w)
                    }
                }
                val id = scores.size
                scores += score
                when (type) {
                    TYPE_NORMAL -> ids[text] = id
                    TYPE_USER_DEFINED -> {
                        ids[text] = id
                        if (text.isNotEmpty()) userDefined += text
                    }
                    TYPE_BYTE -> byteIds[text.substring(3, 5).toInt(16)] = id
                }
            }
            check(byteIds.all { it >= 0 }) { "tokenizer.model has no byte pieces" }
            val byFirst = userDefined.sortedByDescending { it.length }.groupBy { it.first() }
            return GemmaTokenizer(ids, scores.toFloatArray(), byFirst, byteIds)
        }
    }
}

/** Just enough of the protobuf wire format to read a SentencePiece model. */
private class ProtoReader(private val bytes: ByteArray, private var pos: Int, private val end: Int) {

    fun hasMore(): Boolean = pos < end

    fun tag(): kotlin.Pair<Int, Int> {
        val tag = varint().toInt()
        return (tag ushr 3) to (tag and 7)
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = bytes[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    fun float(): Float {
        val bits = (bytes[pos].toInt() and 0xFF) or
            ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
            ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
            ((bytes[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return Float.fromBits(bits)
    }

    fun string(): String {
        val length = varint().toInt()
        val text = String(bytes, pos, length, Charsets.UTF_8)
        pos += length
        return text
    }

    fun message(): ProtoReader {
        val length = varint().toInt()
        val inner = ProtoReader(bytes, pos, pos + length)
        pos += length
        return inner
    }

    fun skip(wire: Int) {
        when (wire) {
            VARINT -> varint()
            FIXED64 -> pos += 8
            LENGTH -> {
                // Read the length first: `pos += varint()` would add it to the old `pos`.
                val length = varint().toInt()
                pos += length
            }
            FIXED32 -> pos += 4
            else -> error("unsupported protobuf wire type $wire")
        }
    }

    companion object {
        const val VARINT = 0
        const val FIXED64 = 1
        const val LENGTH = 2
        const val FIXED32 = 5
    }
}
