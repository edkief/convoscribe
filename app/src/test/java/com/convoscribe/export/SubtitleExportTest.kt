package com.convoscribe.export

import com.convoscribe.data.TranscriptLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SRT and WebVTT timings and line lengths (issue #29). */
class SubtitleExportTest {

    private val srtTime = Regex("^(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3}) --> (\\d{2}):(\\d{2}):(\\d{2}),(\\d{3})$")
    private val vttTime = Regex("^(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3}) --> (\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})$")

    private fun ms(match: MatchResult, from: Int): Long {
        val g = match.groupValues
        return g[from].toLong() * 3_600_000 + g[from + 1].toLong() * 60_000 +
            g[from + 2].toLong() * 1_000 + g[from + 3].toLong()
    }

    /** Every cue's (start, end) and text lines from a SubRip file. */
    private fun parseSrt(srt: String): List<Triple<Long, Long, List<String>>> =
        srt.trimEnd().split("\n\n").mapIndexed { i, block ->
            val rows = block.lines()
            assertEquals("cue number", (i + 1).toString(), rows[0])
            val match = srtTime.matchEntire(rows[1]) ?: throw AssertionError("bad timing line: ${rows[1]}")
            Triple(ms(match, 1), ms(match, 5), rows.drop(2))
        }

    private fun assertValid(cues: List<Triple<Long, Long, List<String>>>) {
        cues.forEachIndexed { i, (start, end, text) ->
            assertTrue("cue $i ends after it starts", end > start)
            assertTrue("cue $i has text", text.isNotEmpty() && text.all { it.isNotBlank() })
            assertTrue("cue $i at most two lines: $text", text.size <= SubtitleExport.LINES_PER_CUE)
            assertTrue("cue $i lines fit: $text", text.all { it.length <= SubtitleExport.MAX_LINE })
            val next = cues.getOrNull(i + 1) ?: return@forEachIndexed
            assertTrue("cues in order", next.first >= start)
            if (next.first > start) assertTrue("cue $i ends before the next starts", end <= next.first)
        }
    }

    @Test
    fun fixture_isValidAndSplitsTheLongLine() {
        val session = ExportFixtures.session()
        val cues = parseSrt(SubtitleExport.srt(session))
        assertValid(cues)
        // Three short lines and one long one split over several cues.
        assertTrue(cues.size > 4)
        val split = cues.filter { it.third.first().startsWith("Speaker 2: ") }
        assertTrue(split.size >= 2)
        // Split cues are timed from the words and cover the line.
        assertEquals(4_200L, split.first().first)
        assertEquals(16_800L, split.last().second)
    }

    @Test
    fun vtt_hasHeaderVoiceAndValidTimings() {
        val vtt = SubtitleExport.vtt(ExportFixtures.session())
        assertTrue(vtt.startsWith("WEBVTT\n\n"))
        val timings = vtt.lines().mapNotNull { vttTime.matchEntire(it) }.map { ms(it, 1) to ms(it, 5) }
        assertEquals(SubtitleExport.cues(ExportFixtures.lines).size, timings.size)
        timings.zipWithNext().forEach { (a, b) -> assertTrue(b.first >= a.first && a.second <= b.first) }
        assertTrue(vtt.contains("<v Élodie>Élodie: Bonjour"))
        assertTrue(vtt.contains("&lt;vendredi&gt; &amp;"))
    }

    @Test
    fun cues_sameStartAndNoEndAndBlankLines_stayValid() {
        val lines = listOf(
            TranscriptLine(startMs = 1_000L, endMs = 1_000L, speaker = "A", text = "one"),
            TranscriptLine(startMs = 1_000L, endMs = 3_000L, speaker = "B", text = "two"),
            TranscriptLine(startMs = 2_000L, endMs = 2_500L, speaker = null, text = "   "),
            TranscriptLine(startMs = 5_000L, endMs = 4_000L, speaker = null, text = "last")
        )
        val cues = SubtitleExport.cues(lines)
        assertEquals(3, cues.size)
        cues.forEach { assertTrue(it.endMs > it.startMs) }
        // No end and the next line starts at the same time: a second, overlapping it.
        assertEquals(1_000L to 2_000L, cues[0].startMs to cues[0].endMs)
        assertEquals(5_000L to 6_000L, cues[2].startMs to cues[2].endMs)
        assertValid(parseSrt(SubtitleExport.srt(ExportFixtures.session().copy(lines = lines))))
    }

    @Test
    fun cues_untimedLongLine_isSplitInProportion() {
        val text = (1..40).joinToString(" ") { "mot$it" }
        val cues = SubtitleExport.cues(
            listOf(TranscriptLine(startMs = 0L, endMs = 40_000L, speaker = null, text = text))
        )
        assertTrue(cues.size >= 2)
        assertEquals(0L, cues.first().startMs)
        assertEquals(40_000L, cues.last().endMs)
        cues.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs) }
        assertEquals(text, cues.joinToString(" ") { it.lines.joinToString(" ") })
    }

    @Test
    fun wrap_balancesTwoLinesAndCutsLongWords() {
        val two = SubtitleExport.wrap("Élodie: on envoie le compte rendu vendredi à toute l'équipe")
        assertEquals(2, two.size)
        assertTrue(two.all { it.length <= SubtitleExport.MAX_LINE })
        val long = "x".repeat(100)
        assertEquals(listOf("x".repeat(42), "x".repeat(42), "x".repeat(16)), SubtitleExport.wrap(long))
    }

    @Test
    fun clock_formatsBothSeparators() {
        assertEquals("01:02:03,004", SubtitleExport.clock(3_723_004L, ','))
        assertEquals("00:00:00.000", SubtitleExport.clock(-5L, '.'))
    }
}
