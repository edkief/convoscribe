package com.convoscribe.export

import com.convoscribe.export.ExportFixtures.assertGolden
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Golden files for each text format (issue #29). */
class ExportFormattersTest {
    private val session = ExportFixtures.session()
    private fun all(format: ExportFormat) = ExportOptions(format)

    @Test
    fun text() = assertGolden("session.txt", TextExport.render(session, all(ExportFormat.TEXT)))

    @Test
    fun markdown() = assertGolden("session.md", MarkdownExport.render(session, all(ExportFormat.MARKDOWN)))

    @Test
    fun markdown_transcriptOnly() = assertGolden(
        "session-transcript.md",
        MarkdownExport.render(session, ExportOptions(ExportFormat.MARKDOWN, setOf(ExportSection.TRANSCRIPT)))
    )

    @Test
    fun srt() = assertGolden("session.srt", SubtitleExport.srt(session))

    @Test
    fun vtt() = assertGolden("session.vtt", SubtitleExport.vtt(session))

    @Test
    fun json() = assertGolden("session.json", JsonExport.render(session, all(ExportFormat.JSON)))

    @Test
    fun json_leavesOutUnticketSections() {
        val doc = Json.parseToJsonElement(
            JsonExport.render(session, ExportOptions(ExportFormat.JSON, setOf(ExportSection.SUMMARY)))
        ).jsonObject
        assertEquals(JsonExport.FORMAT, doc["format"]!!.jsonPrimitive.content)
        assertEquals(JsonExport.VERSION, doc["version"]!!.jsonPrimitive.int)
        assertFalse("segments" in doc)
        assertFalse("marks" in doc)
        assertFalse("questions" in doc)
        assertEquals(2, doc["actionItems"]!!.jsonArray.size)
        assertTrue("summary" in doc)
    }

    @Test
    fun text_withoutSummaryOrQuestions_isHeaderAndTranscript() {
        val text = TextExport.render(session, ExportOptions(ExportFormat.TEXT, setOf(ExportSection.TRANSCRIPT)))
        assertFalse(text.contains("Summary"))
        assertFalse(text.contains("Questions"))
        assertTrue(text.contains("[00:00] Élodie: Bonjour à tous"))
    }

    @Test
    fun demoteHeadings_keepsRelativeLevelsAndCodeBlocks() {
        val md = "# Top\n\ntext\n\n### Deep\n\n```\n# not a heading\n```"
        assertEquals(
            "### Top\n\ntext\n\n##### Deep\n\n```\n# not a heading\n```",
            MarkdownExport.demoteHeadings(md, minLevel = 3)
        )
        assertEquals("### Already deep", MarkdownExport.demoteHeadings("### Already deep", minLevel = 3))
    }

    @Test
    fun yaml_escapesQuotesAndBackslashes() {
        assertEquals("\"a \\\"b\\\" \\\\ c\"", MarkdownExport.yaml("a \"b\" \\ c"))
    }
}
