package com.convoscribe.ui.session

import com.convoscribe.asr.TimedToken
import com.convoscribe.data.TranscriptLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSyncTest {

    private fun line(startMs: Long, endMs: Long, text: String, tokens: List<TimedToken> = emptyList()) =
        TranscriptLine(startMs = startMs, speaker = null, text = text, endMs = endMs, tokens = tokens)

    private val lines = listOf(
        line(1_000, 3_000, "First"),
        line(4_000, 6_000, "Second"),
        line(6_500, 8_000, "Third")
    )

    @Test
    fun activeLine_isNullBeforeTheFirstLineAndForNoLines() {
        assertNull(PlaybackSync.activeLineIndex(lines, 0))
        assertNull(PlaybackSync.activeLineIndex(lines, 999))
        assertNull(PlaybackSync.activeLineIndex(emptyList(), 5_000))
    }

    @Test
    fun activeLine_startsExactlyAtStartMs() {
        assertEquals(0, PlaybackSync.activeLineIndex(lines, 1_000))
        assertEquals(1, PlaybackSync.activeLineIndex(lines, 4_000))
        assertEquals(2, PlaybackSync.activeLineIndex(lines, 6_500))
    }

    @Test
    fun activeLine_staysOnTheLastStartedLineThroughAPause() {
        assertEquals(0, PlaybackSync.activeLineIndex(lines, 3_500))
        assertEquals(1, PlaybackSync.activeLineIndex(lines, 6_200))
    }

    @Test
    fun activeLine_staysOnTheLastLineAfterItEnds() {
        assertEquals(2, PlaybackSync.activeLineIndex(lines, 60_000))
    }

    private val timed = line(
        startMs = 1_000,
        endMs = 3_000,
        text = "Hello there world",
        tokens = listOf(
            TimedToken(" He", 1_000),
            TimedToken("llo", 1_200),
            TimedToken(" there", 1_800),
            TimedToken(" wor", 2_300),
            TimedToken("ld", 2_500)
        )
    )

    @Test
    fun words_mapTokenWordsToTextRanges() {
        val words = PlaybackSync.words(timed)!!
        assertEquals(
            listOf(
                WordSpan(0, 5, 1_000),
                WordSpan(6, 11, 1_800),
                WordSpan(12, 17, 2_300)
            ),
            words
        )
    }

    @Test
    fun words_acceptTheSentencepieceWordMarker() {
        val marked = line(
            0,
            1_000,
            "Hi you",
            listOf(TimedToken("▁Hi", 0), TimedToken("▁you", 500))
        )
        assertEquals(listOf(WordSpan(0, 2, 0), WordSpan(3, 6, 500)), PlaybackSync.words(marked))
    }

    @Test
    fun words_areNullWithoutTokensOrWhenCountsDiffer() {
        assertNull(PlaybackSync.words(line(0, 1_000, "No timings")))
        val mismatched = timed.copy(text = "Hello there")
        assertNull(PlaybackSync.words(mismatched))
        assertNull(PlaybackSync.activeWordIndex(mismatched, 2_000))
    }

    @Test
    fun activeWord_followsTheLastStartedWord() {
        assertEquals(0, PlaybackSync.activeWordIndex(timed, 1_000))
        assertEquals(0, PlaybackSync.activeWordIndex(timed, 1_799))
        assertEquals(1, PlaybackSync.activeWordIndex(timed, 1_800))
        assertEquals(2, PlaybackSync.activeWordIndex(timed, 2_900))
    }

    @Test
    fun activeWord_isNullBeforeTheFirstWordAndAfterTheLineEnds() {
        assertNull(PlaybackSync.activeWordIndex(timed, 900))
        assertNull(PlaybackSync.activeWordIndex(timed, 3_001))
    }

    @Test
    fun seekTarget_isTheLineStart() {
        assertEquals(4_000L, PlaybackSync.seekTargetMs(lines[1]))
        assertEquals(0L, PlaybackSync.seekTargetMs(line(-5, 10, "x")))
    }

    @Test
    fun nextSpeed_cyclesAndWraps() {
        assertEquals(1.5f, PlaybackSync.nextSpeed(1f))
        assertEquals(2f, PlaybackSync.nextSpeed(1.5f))
        assertEquals(1f, PlaybackSync.nextSpeed(2f))
        // An unknown speed starts the cycle again at 1x.
        assertEquals(1f, PlaybackSync.nextSpeed(0.75f))
    }
}
