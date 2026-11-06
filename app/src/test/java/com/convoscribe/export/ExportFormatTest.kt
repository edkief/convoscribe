package com.convoscribe.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportFormatTest {
    private val session = ExportFixtures.session()

    @Test
    fun fileName_startsWithTheDayUnlessTheTitleHasIt() {
        assertEquals("2026-11-06 Point d'équipe — budget.md", ExportNames.fileName(session, ExportFormat.MARKDOWN))
        assertEquals(
            "Recording 2026-11-06 14_30.srt",
            ExportNames.fileName(session.copy(title = "Recording 2026-11-06 14:30"), ExportFormat.SRT)
        )
        assertEquals("2026-11-06 a_b_c.pdf", ExportNames.fileName(session.copy(title = " a/b:c "), ExportFormat.PDF))
        assertEquals(
            "2026-11-06 Conversation.json",
            ExportNames.fileName(session.copy(title = "  "), ExportFormat.JSON)
        )
    }

    @Test
    fun options_subtitlesHoldTheTranscriptOnly() {
        val summaryOnly = ExportOptions(ExportFormat.SRT, setOf(ExportSection.SUMMARY))
        assertFalse(summaryOnly.valid)
        assertEquals(setOf(ExportSection.TRANSCRIPT), ExportOptions(ExportFormat.VTT).effective)
        assertTrue(ExportOptions(ExportFormat.DOCX, setOf(ExportSection.QUESTIONS)).valid)
    }

    @Test
    fun sections_roundTripThroughSettings() {
        val sections = setOf(ExportSection.QUESTIONS, ExportSection.TRANSCRIPT)
        assertEquals("transcript,questions", ExportSection.encode(sections))
        assertEquals(sections, ExportSection.parse("questions, transcript,unknown"))
        assertEquals(emptySet<ExportSection>(), ExportSection.parse(null))
    }

    @Test
    fun format_keysAndDocumentTypes() {
        assertEquals(ExportFormat.MARKDOWN, ExportFormat.of("nope"))
        ExportFormat.entries.forEach { assertEquals(it, ExportFormat.of(it.key)) }
        assertEquals("application/octet-stream", ExportFormat.MARKDOWN.documentMime)
        assertEquals("application/pdf", ExportFormat.PDF.documentMime)
    }
}
