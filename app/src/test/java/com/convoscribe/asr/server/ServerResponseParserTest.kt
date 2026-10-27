package com.convoscribe.asr.server

import com.convoscribe.asr.TimedToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerResponseParserTest {

    @Test
    fun segmentsBecomeLinesWithTheirWordsInMilliseconds() {
        val finals = ServerResponseParser.parse(
            """
            {"task":"transcribe","language":"en","duration":9.0,"text":"Ask not. Hello there.",
             "segments":[{"id":0,"start":0.066,"end":3.838,"text":" Ask not."},
                         {"id":1,"start":5.218,"end":9.022,"text":"Hello there."}],
             "words":[{"word":"Ask","start":0.066,"end":0.466},{"word":"not.","start":0.466,"end":0.9},
                      {"word":"Hello","start":5.218,"end":5.6},{"word":"there.","start":5.6,"end":9.0}]}
            """.trimIndent()
        )

        assertEquals(2, finals.size)
        assertEquals("Ask not.", finals[0].text)
        assertEquals(66L, finals[0].startMs)
        assertEquals(3838L, finals[0].endMs)
        assertEquals(listOf(TimedToken(" Ask", 66), TimedToken(" not.", 466)), finals[0].tokens)
        assertEquals(5218L, finals[1].startMs)
        assertEquals(listOf(" Hello", " there."), finals[1].tokens.map { it.text })
        assertTrue(finals.flatMap { it.tokens }.all { it.isWordStart })
    }

    @Test
    fun wordInAGapStaysWithThePreviousSegmentAndAnEarlyWordWithTheFirst() {
        val finals = ServerResponseParser.parse(
            """
            {"segments":[{"start":1.0,"end":2.0,"text":"one two"},{"start":5.0,"end":6.0,"text":"three"}],
             "words":[{"word":"one","start":0.95},{"word":"two","start":2.5},{"word":"three","start":5.0}]}
            """.trimIndent()
        )

        assertEquals(listOf(" one", " two"), finals[0].tokens.map { it.text })
        assertEquals(listOf(" three"), finals[1].tokens.map { it.text })
    }

    @Test
    fun blankSegmentsAreDroppedAndTextIsNormalised() {
        val finals = ServerResponseParser.parse(
            """{"segments":[{"start":0,"end":1,"text":"  "},{"start":1,"end":2,"text":"hello   world"}]}"""
        )

        assertEquals(1, finals.size)
        assertEquals("Hello world", finals[0].text)
    }

    @Test
    fun silenceGivesNoLines() {
        val finals = ServerResponseParser.parse(
            """{"task":"transcribe","language":"unknown","duration":1.0,"text":"","segments":[],"words":[]}"""
        )

        assertTrue(finals.isEmpty())
    }

    @Test
    fun textWithoutSegmentsIsOneLineOverTheDuration() {
        val finals = ServerResponseParser.parse("""{"text":"Bonjour à tous.","duration":2.5}""")

        assertEquals(1, finals.size)
        assertEquals("Bonjour à tous.", finals[0].text)
        assertEquals(0L, finals[0].startMs)
        assertEquals(2500L, finals[0].endMs)
        assertTrue(finals[0].tokens.isEmpty())
    }

    @Test
    fun segmentsWithoutWordsHaveNoTokens() {
        val finals = ServerResponseParser.parse("""{"segments":[{"start":0.5,"end":1.5,"text":"Hi."}]}""")

        assertEquals(500L, finals.single().startMs)
        assertTrue(finals.single().tokens.isEmpty())
    }

    @Test
    fun notJsonIsAServerError() {
        val e = assertThrows(ServerAsrException::class.java) {
            ServerResponseParser.parse("<html>Bad gateway</html>")
        }
        assertTrue(e.message!!.contains("<html>Bad gateway"))
    }
}
