package com.convoscribe.llm.embedding

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class GemmaTokenizerTest {

    /** A piece of the synthetic model: text, score, SentencePiece type. */
    private data class Piece(val text: String, val score: Float = 0f, val type: Int = 1)

    // Ids: 0 pad, 1 eos, 2 bos, 3 unk, 4 `<t>`, 5..260 bytes, then the normal pieces.
    private val normal = listOf(
        Piece("▁"), Piece("a"), Piece("b"), Piece("c"),
        Piece("ab", score = -1f), Piece("bc", score = -2f), Piece("abc", score = -3f),
        Piece("▁a", score = -5f), Piece("▁abc", score = -0.5f)
    )
    private val special = listOf(
        Piece("<pad>", type = 3),
        Piece("<eos>", type = 3),
        Piece("<bos>", type = 3),
        Piece("<unk>", type = 2),
        Piece("<t>", type = 4)
    )
    private val pieces = special +
        (0 until 256).map { Piece("<0x%02X>".format(it), type = 6) } +
        normal
    private val tokenizer = GemmaTokenizer.parse(model(pieces))

    private fun id(text: String): Int = pieces.indexOfFirst { it.text == text }
    private fun byte(value: Int): Int = 5 + value

    @Test
    fun mergesTheBestScoringPairFirst() {
        // "ab" (-1) beats "bc" (-2), then "abc" (-3) forms from "ab" + "c".
        assertArrayEquals(intArrayOf(2, id("abc"), 1), tokenizer.encode("abc"))
    }

    @Test
    fun spacesBecomeTheWordMarkerAndMerge() {
        // "ab" merges first, then "abc", and only then can "▁" join it as "▁abc".
        assertArrayEquals(intArrayOf(2, id("▁abc"), 1), tokenizer.encode(" abc"))
        assertArrayEquals(intArrayOf(2, id("ab"), id("▁"), id("c"), 1), tokenizer.encode("ab c"))
    }

    @Test
    fun userDefinedPiecesStayWhole() {
        assertArrayEquals(intArrayOf(2, id("a"), id("<t>"), id("bc"), 1), tokenizer.encode("a<t>bc"))
    }

    @Test
    fun unknownCharactersFallBackToBytes() {
        // "é" is 0xC3 0xA9 in UTF-8.
        assertArrayEquals(intArrayOf(2, byte(0xC3), byte(0xA9), id("a"), 1), tokenizer.encode("éa"))
    }

    @Test
    fun cutsLongTextKeepingBosAndEos() {
        assertArrayEquals(intArrayOf(2, id("ab"), 1), tokenizer.encode("ab▁ab▁ab".replace('▁', ' '), maxTokens = 3))
        assertArrayEquals(intArrayOf(2, 1), tokenizer.encode(""))
    }

    /** [pieces] as a SentencePiece `ModelProto`: field 1, repeated. */
    private fun model(pieces: List<Piece>): ByteArray {
        val out = ByteArrayOutputStream()
        for (piece in pieces) {
            val body = ByteArrayOutputStream()
            val text = piece.text.toByteArray(Charsets.UTF_8)
            body.write(0x0A)
            varint(body, text.size)
            body.write(text)
            body.write(0x15)
            val bits = piece.score.toRawBits()
            repeat(4) { body.write((bits ushr (8 * it)) and 0xFF) }
            body.write(0x18)
            varint(body, piece.type)
            out.write(0x0A)
            varint(out, body.size())
            out.write(body.toByteArray())
        }
        // A trainer_spec-like field the reader must skip.
        out.write(0x12)
        varint(out, 2)
        out.write(byteArrayOf(0x08, 0x02))
        return out.toByteArray()
    }

    private fun varint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v >= 0x80) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v)
    }
}
