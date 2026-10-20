package com.convoscribe.data

import com.convoscribe.asr.TimedToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for [TranscriptEditor]. */
class TranscriptEditorTest {

    private fun line(
        id: Long,
        startMs: Long,
        endMs: Long,
        text: String,
        tokens: List<TimedToken> = emptyList(),
        speaker: Int? = 0
    ) = SegmentEntity(
        id = id,
        sessionId = 1,
        startMs = startMs,
        endMs = endMs,
        text = text,
        speakerIndex = speaker,
        tokens = TokenTimingCodec.encode(tokens)
    )

    private val timed = line(
        id = 5,
        startMs = 0,
        endMs = 2_000,
        text = "Hello there general kenobi",
        tokens = listOf(
            TimedToken(" hello", 0),
            TimedToken(" there", 400),
            TimedToken(" gen", 900),
            TimedToken("eral", 1_100),
            TimedToken(" kenobi", 1_400)
        )
    )

    @Test
    fun editText_cleansWhitespace_reTimes_andFlags() {
        val edited = TranscriptEditor.editText(timed, "  Hello there\n General  Kenobi! ")!!
        assertEquals("Hello there General Kenobi!", edited.text)
        assertEquals(listOf(0L, 400L, 900L, 1_400L), TokenTimingCodec.decode(edited.tokens).map { it.ms })
        assertTrue(edited.textEdited)
        assertFalse(edited.speakerEdited)
    }

    @Test
    fun editText_blankOrUnchanged_isNull() {
        assertNull(TranscriptEditor.editText(timed, "   "))
        assertNull(TranscriptEditor.editText(timed, " Hello there general kenobi "))
    }

    @Test
    fun merge_joinsTextAndTimes_keepsFirstSpeaker() {
        val next = line(6, 2_500, 3_000, "Nice", listOf(TimedToken(" nice", 2_500)), speaker = 1)
        val merged = TranscriptEditor.merge(timed, next)
        assertEquals(5L, merged.id)
        assertEquals("Hello there general kenobi Nice", merged.text)
        assertEquals(0L to 3_000L, merged.startMs to merged.endMs)
        assertEquals(0, merged.speakerIndex)
        assertEquals(listOf(0L, 400L, 900L, 1_400L, 2_500L), TokenTimingCodec.decode(merged.tokens).map { it.ms })
        assertTrue(merged.textEdited)
        assertTrue("different speakers make the choice manual", merged.speakerEdited)
    }

    @Test
    fun merge_sameSpeaker_isNotASpeakerEdit() {
        val next = line(6, 2_500, 3_000, "Nice", speaker = 0)
        assertFalse(TranscriptEditor.merge(timed, next).speakerEdited)
    }

    @Test
    fun split_atWordBoundaryNearestTheCursor_usesWordTimes() {
        // Cursor inside "general" (offset 14), nearer its start.
        val (first, second) = TranscriptEditor.split(timed, 14)!!
        assertEquals("Hello there", first.text)
        assertEquals("general kenobi", second.text)
        assertEquals(5L, first.id)
        assertEquals(0L, second.id)
        assertEquals(900L, first.endMs)
        assertEquals(900L to 2_000L, second.startMs to second.endMs)
        assertEquals(listOf(0L, 400L), TokenTimingCodec.decode(first.tokens).map { it.ms })
        assertEquals(listOf(900L, 1_100L, 1_400L), TokenTimingCodec.decode(second.tokens).map { it.ms })
        assertTrue(first.textEdited && second.textEdited)
        assertEquals(0, second.speakerIndex)
    }

    @Test
    fun split_withoutTimings_isProportional() {
        val plain = line(5, 0, 1_000, "aaaa bbbb")
        val (first, second) = TranscriptEditor.split(plain, 0)!!
        assertEquals("aaaa", first.text)
        assertEquals("bbbb", second.text)
        // "bbbb" starts at character 5 of 9.
        assertEquals(555L, second.startMs)
        assertNull(second.tokens)
    }

    @Test
    fun split_oneWord_isNull() {
        assertNull(TranscriptEditor.split(line(5, 0, 1_000, "Hello"), 2))
    }

    @Test
    fun setSpeaker_flagsTheLine() {
        val changed = TranscriptEditor.setSpeaker(timed, 2)
        assertEquals(2, changed.speakerIndex)
        assertTrue(changed.speakerEdited)
    }
}
