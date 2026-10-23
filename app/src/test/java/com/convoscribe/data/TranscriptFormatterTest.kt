package com.convoscribe.data

import com.convoscribe.asr.TimedToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [TranscriptFormatter]. The formatter is a pure
 * object so it does not need Robolectric; it lives in
 * `src/test/` and runs against the regular JVM. Only the
 * acceptance tests called out by TASK-21 are covered here — the
 * empty-speaker lookup is an extra edge case worth pinning so a
 * future refactor does not silently flip the behaviour.
 */
class TranscriptFormatterTest {

    @Test
    fun timestamp_minutesSeconds_underAnHour() {
        assertEquals("00:00", TranscriptFormatter.timestamp(0L))
        assertEquals("00:01", TranscriptFormatter.timestamp(1_000L))
        assertEquals("01:05", TranscriptFormatter.timestamp(65_000L))
        assertEquals("12:34", TranscriptFormatter.timestamp(754_000L))
    }

    @Test
    fun timestamp_hoursMinutesSeconds_pastOneHour() {
        assertEquals("1:00:00", TranscriptFormatter.timestamp(3_600_000L))
        assertEquals("1:02:05", TranscriptFormatter.timestamp(3_725_000L))
        assertEquals("10:00:00", TranscriptFormatter.timestamp(36_000_000L))
    }

    @Test
    fun timestamp_negativeInputIsClampedToZero() {
        // Negative offsets only show up with a misbehaving clock;
        // the formatter must still produce a parseable string.
        assertEquals("00:00", TranscriptFormatter.timestamp(-1L))
        assertEquals("00:00", TranscriptFormatter.timestamp(Long.MIN_VALUE))
    }

    @Test
    fun lines_resolvesSpeakerLabelFromSpeakerIndex() {
        val segments = listOf(
            SegmentEntity(
                id = 1,
                sessionId = 42,
                startMs = 0,
                endMs = 1_000,
                text = "Hello",
                speakerIndex = 0
            ),
            SegmentEntity(
                id = 2,
                sessionId = 42,
                startMs = 3_000,
                endMs = 4_000,
                text = "Hi",
                speakerIndex = 1
            )
        )
        val speakers = listOf(
            SpeakerEntity(sessionId = 42, speakerIndex = 0, label = "Alice"),
            SpeakerEntity(sessionId = 42, speakerIndex = 1, label = "Bob")
        )

        val lines = TranscriptFormatter.lines(segments, speakers)
        assertEquals(2, lines.size)
        assertEquals(0L, lines[0].startMs)
        assertEquals("Alice", lines[0].speaker)
        assertEquals("Hello", lines[0].text)
        assertEquals(3_000L, lines[1].startMs)
        assertEquals("Bob", lines[1].speaker)
        assertEquals("Hi", lines[1].text)
        assertEquals(0, lines[0].speakerIndex)
        assertEquals(1, lines[1].speakerIndex)
    }

    /**
     * The speaker index (which keys the colour) is the same before and
     * after a rename, so a chip and its lines keep matching (issue #4).
     */
    @Test
    fun lines_speakerIndexSurvivesARename() {
        val segments =
            listOf(SegmentEntity(id = 1, sessionId = 42, startMs = 0, endMs = 1_000, text = "Hello", speakerIndex = 2))
        val before = TranscriptFormatter.lines(segments, listOf(SpeakerEntity(42, 2, "Speaker 3")))
        val after = TranscriptFormatter.lines(segments, listOf(SpeakerEntity(42, 2, "Carol")))
        assertEquals("Carol", after[0].speaker)
        assertEquals(before[0].speakerIndex, after[0].speakerIndex)
        assertEquals(2, after[0].speakerIndex)
    }

    @Test
    fun lines_indexWithoutSpeakerRowIsUnlabelled() {
        val segments =
            listOf(SegmentEntity(id = 1, sessionId = 42, startMs = 0, endMs = 1_000, text = "Hello", speakerIndex = 5))
        val line = TranscriptFormatter.lines(segments, emptyList()).single()
        assertNull(line.speaker)
        assertNull(line.speakerIndex)
    }

    @Test
    fun lines_carryIdEndTimeAndTokens() {
        val tokens = listOf(TimedToken(" Hello", 100), TimedToken(" there", 600))
        val segments = listOf(
            SegmentEntity(
                id = 7,
                sessionId = 42,
                startMs = 100,
                endMs = 1_000,
                text = "Hello there",
                tokens = TokenTimingCodec.encode(tokens)
            ),
            SegmentEntity(id = 8, sessionId = 42, startMs = 2_000, endMs = 2_500, text = "Old line")
        )

        val lines = TranscriptFormatter.lines(segments, emptyList())
        assertEquals(7L, lines[0].id)
        assertEquals(1_000L, lines[0].endMs)
        assertEquals(tokens, lines[0].tokens)
        assertEquals(emptyList<TimedToken>(), lines[1].tokens)
    }

    @Test
    fun lines_unlabelledSegmentHasNullSpeaker() {
        val segments = listOf(
            SegmentEntity(
                id = 1,
                sessionId = 42,
                startMs = 0,
                endMs = 1_000,
                text = "Hello",
                speakerIndex = null
            )
        )

        val lines = TranscriptFormatter.lines(segments, emptyList())
        assertEquals(1, lines.size)
        assertNull(lines[0].speaker)
        assertEquals("Hello", lines[0].text)
    }

    @Test
    fun lines_dropsSpeakerLabelWhenIndexDoesNotResolve() {
        // speakerIndex points at a row that no longer exists (the
        // diarizer rewrote the table between two emissions). The
        // segment should fall back to an unlabelled line rather
        // than crash or display "Speaker 3" with no matching row.
        val segments = listOf(
            SegmentEntity(
                id = 1,
                sessionId = 42,
                startMs = 0,
                endMs = 1_000,
                text = "Hello",
                speakerIndex = 2
            )
        )

        val lines = TranscriptFormatter.lines(
            segments,
            listOf(
                SpeakerEntity(sessionId = 42, speakerIndex = 0, label = "Alice"),
                SpeakerEntity(sessionId = 42, speakerIndex = 1, label = "Bob")
            )
        )
        assertEquals(1, lines.size)
        assertNull(lines[0].speaker)
    }

    @Test
    fun toText_formatsLabelledLinesWithSpeakerPrefix() {
        val lines = listOf(
            TranscriptLine(startMs = 0, speaker = "Speaker 1", text = "Hello"),
            TranscriptLine(startMs = 3_000, speaker = "Speaker 2", text = "Hi")
        )
        assertEquals(
            "[00:00] Speaker 1: Hello\n[00:03] Speaker 2: Hi",
            TranscriptFormatter.toText(lines)
        )
    }

    @Test
    fun toText_unlabelledLineOmitsTheSpeaker() {
        val lines = listOf(
            TranscriptLine(startMs = 0, speaker = null, text = "Hello")
        )
        assertEquals(
            "[00:00] Hello",
            TranscriptFormatter.toText(lines)
        )
    }

    @Test
    fun toText_doesNotMergeConsecutiveSameSpeakerSegments() {
        // Two back-to-back "Speaker 1" lines stay as two entries;
        // the share-text reader should see the same per-segment
        // granularity the on-screen list shows.
        val lines = listOf(
            TranscriptLine(startMs = 0, speaker = "Speaker 1", text = "Hello"),
            TranscriptLine(startMs = 2_000, speaker = "Speaker 1", text = "again")
        )
        assertEquals(
            "[00:00] Speaker 1: Hello\n[00:02] Speaker 1: again",
            TranscriptFormatter.toText(lines)
        )
    }

    @Test
    fun toText_emptyInputProducesEmptyString() {
        assertEquals("", TranscriptFormatter.toText(emptyList()))
    }

    @Test
    fun toText_roundTripsViaLinesAcceptanceExample() {
        // Cover both acceptance criteria in a single round-trip:
        //  1. two labelled segments -> "[00:00] Speaker 1: Hello\n[00:03] Speaker 2: Hi"
        //  2. unlabelled segment      -> "[00:00] Hello"
        val labelledSegments = listOf(
            SegmentEntity(
                id = 1,
                sessionId = 7,
                startMs = 0,
                endMs = 1_000,
                text = "Hello",
                speakerIndex = 0
            ),
            SegmentEntity(
                id = 2,
                sessionId = 7,
                startMs = 3_000,
                endMs = 4_000,
                text = "Hi",
                speakerIndex = 1
            )
        )
        val labelledSpeakers = listOf(
            SpeakerEntity(sessionId = 7, speakerIndex = 0, label = "Speaker 1"),
            SpeakerEntity(sessionId = 7, speakerIndex = 1, label = "Speaker 2")
        )
        assertEquals(
            "[00:00] Speaker 1: Hello\n[00:03] Speaker 2: Hi",
            TranscriptFormatter.toText(
                TranscriptFormatter.lines(labelledSegments, labelledSpeakers)
            )
        )

        val unlabelledSegments = listOf(
            SegmentEntity(
                id = 1,
                sessionId = 7,
                startMs = 0,
                endMs = 1_000,
                text = "Hello",
                speakerIndex = null
            )
        )
        assertEquals(
            "[00:00] Hello",
            TranscriptFormatter.toText(
                TranscriptFormatter.lines(unlabelledSegments, emptyList())
            )
        )
    }

    @Test
    fun markLineIndex_isTheLineBeingSpokenOrTheFirst() {
        val lines = listOf(
            TranscriptLine(startMs = 1_000L, speaker = null, text = "a"),
            TranscriptLine(startMs = 5_000L, speaker = null, text = "b")
        )
        assertEquals(0, TranscriptFormatter.markLineIndex(lines, 0L))
        assertEquals(0, TranscriptFormatter.markLineIndex(lines, 4_999L))
        assertEquals(1, TranscriptFormatter.markLineIndex(lines, 5_000L))
        assertEquals(1, TranscriptFormatter.markLineIndex(lines, 99_000L))
        assertEquals(null, TranscriptFormatter.markLineIndex(emptyList(), 1_000L))
    }

    @Test
    fun toText_putsEachMarkAboveItsLine() {
        val lines = listOf(
            TranscriptLine(startMs = 1_000L, speaker = "Alice", text = "Hello."),
            TranscriptLine(startMs = 65_000L, speaker = null, text = "We agree.")
        )
        val marks = listOf(
            TranscriptMark(id = 2, atMs = 70_000L, note = "decision"),
            TranscriptMark(id = 1, atMs = 2_000L)
        )
        assertEquals(
            "[00:02] \u2691 Mark\n" +
                "[00:01] Alice: Hello.\n" +
                "[01:10] \u2691 Mark: decision\n" +
                "[01:05] We agree.",
            TranscriptFormatter.toText(lines, marks)
        )
    }

    @Test
    fun toText_listsMarksAloneWithoutLines() {
        assertEquals(
            "[00:03] \u2691 Mark: later",
            TranscriptFormatter.toText(emptyList(), listOf(TranscriptMark(id = 1, atMs = 3_000L, note = "later")))
        )
    }

    @Test
    fun markText_putsTheFullNoteOnIndentedLines() {
        val mark = TranscriptMark(
            id = 1,
            atMs = 65_000L,
            note = "pricing",
            expandedNote = " Dana proposes €12.\n\n- Bob agrees. "
        )
        assertEquals(
            "[01:05] \u2691 Mark: pricing\n    Dana proposes €12.\n    - Bob agrees.",
            TranscriptFormatter.markText(mark)
        )
    }

    @Test
    fun marks_carryTheFullNoteAndWhetherItIsOutdated() {
        val row = MarkEntity(
            id = 3,
            sessionId = 1,
            atMs = 1_000L,
            note = "n",
            createdAt = 0,
            expandedNote = "full",
            expandedStale = true
        )
        assertEquals(
            listOf(TranscriptMark(3, 1_000L, "n", "full", expandedStale = true)),
            TranscriptFormatter.marks(listOf(row))
        )
    }
}
