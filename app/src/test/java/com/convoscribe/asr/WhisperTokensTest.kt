package com.convoscribe.asr

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WhisperTokensTest {

    private fun line(text: String, id: Int) = "${Base64.getEncoder().encodeToString(text.toByteArray())} $id"

    private fun rawLine(bytes: ByteArray, id: Int) = "${Base64.getEncoder().encodeToString(bytes)} $id"

    @Test
    fun decode_joinsTokenBytes_andDropsSpecialTokens() {
        val tokens = WhisperTokens.parse(listOf(line("Hello", 0), line(" world", 1), line("!", 2)))
        assertEquals(
            "Hello world!",
            tokens.decode(listOf(WhisperTokens.SOT, 0, 1, WhisperTokens.NO_TIMESTAMPS, 2, WhisperTokens.EOT))
        )
    }

    @Test
    fun decode_keepsAMultiByteCharacterSplitAcrossTokens() {
        val e = "é".toByteArray() // 0xC3 0xA9
        val tokens = WhisperTokens.parse(
            listOf(line(" caf", 0), rawLine(byteArrayOf(e[0]), 1), rawLine(byteArrayOf(e[1]), 2))
        )
        assertEquals(" café", tokens.decode(listOf(0, 1, 2)))
    }

    @Test
    fun parse_skipsMalformedLines_andUnknownIdsDecodeToNothing() {
        val tokens = WhisperTokens.parse(listOf("", "garbage", "!!! 3", line("a", 5)))
        assertEquals("a", tokens.decode(listOf(0, 5, 9, 100_000)))
    }

    @Test
    fun languageTokens_followWhispersOrder() {
        assertEquals(50259, WhisperTokens.languageToken("en"))
        assertEquals(50265, WhisperTokens.languageToken("fr"))
        assertEquals(50261, WhisperTokens.languageToken("DE"))
        // yue is the last of Large-v3's 100 languages, just before <|translate|>.
        assertEquals(WhisperTokens.TRANSLATE - 1, WhisperTokens.languageToken("yue"))
        assertEquals(100, WhisperTokens.LANGUAGES.size)
        assertNull(WhisperTokens.languageToken("xx"))
    }

    @Test
    fun prompt_isStartLanguageTranscribeNoTimestamps() {
        assertArrayEquals(intArrayOf(50258, 50265, 50360, 50364), WhisperTokens.prompt("fr"))
        assertThrows(IllegalArgumentException::class.java) { WhisperTokens.prompt("xx") }
    }
}
