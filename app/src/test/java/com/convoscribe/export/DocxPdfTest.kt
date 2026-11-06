package com.convoscribe.export

import com.convoscribe.data.TranscriptLine
import com.convoscribe.export.ExportFixtures.assertGolden
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Word and PDF exports (issue #29). */
class DocxPdfTest {
    private val session = ExportFixtures.session()
    private val blocks = ExportDocument.build(session, ExportOptions(ExportFormat.DOCX))

    @Test
    fun docx_documentXml() = assertGolden("session.document.xml", DocxWriter.documentXml(blocks))

    @Test
    fun docx_isAPackageWithItsParts() {
        val bytes = ExportRenderer.render(session, ExportOptions(ExportFormat.DOCX))
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(
            listOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "word/_rels/document.xml.rels",
                "word/document.xml",
                "word/styles.xml"
            ),
            entries.keys.toList()
        )
        assertEquals(DocxWriter.documentXml(blocks), entries["word/document.xml"])
        assertTrue(entries.getValue("word/styles.xml").contains("w:styleId=\"Heading1\""))
        // The same session gives the same file.
        assertArrayEquals(bytes, ExportRenderer.render(session, ExportOptions(ExportFormat.DOCX)))
    }

    @Test
    fun docx_golden() = assertGolden("session.docx", ExportRenderer.render(session, ExportOptions(ExportFormat.DOCX)))

    @Test
    fun docx_escapesMarkup() {
        assertEquals("a &lt;b&gt; &amp; &quot;c&quot; d", DocxWriter.escape("a <b> & \"c\"\u0001 d"))
    }

    @Test
    fun pdf_golden() = assertGolden("session.pdf", ExportRenderer.render(session, ExportOptions(ExportFormat.PDF)))

    @Test
    fun pdf_crossReferencePointsAtEachObject() {
        val pdf = String(ExportRenderer.render(session, ExportOptions(ExportFormat.PDF)), Charsets.US_ASCII)
        assertTrue(pdf.startsWith("%PDF-1.4\n"))
        assertTrue(pdf.endsWith("%%EOF\n"))
        val startxref = pdf.substringAfterLast("startxref\n").substringBefore('\n').toInt()
        assertTrue(pdf.startsWith("xref\n", startxref))
        val rows = pdf.substring(startxref).lines().drop(3).takeWhile { it.endsWith(" n ") }
        rows.forEachIndexed { i, row ->
            val offset = row.substring(0, 10).toInt()
            assertTrue("object ${i + 1} at $offset", pdf.startsWith("${i + 1} 0 obj\n", offset))
        }
        // Each content stream's length is right.
        Regex("<< /Length (\\d+) >>\nstream\n").findAll(pdf).forEach { match ->
            val start = match.range.last + 1
            assertTrue(pdf.startsWith("endstream", start + match.groupValues[1].toInt()))
        }
    }

    @Test
    fun pdf_longTranscript_flowsOverNumberedPages() {
        val lines = (0 until 200).map { i ->
            TranscriptLine(
                startMs = i * 5_000L,
                endMs = i * 5_000L + 4_000L,
                speaker = "Élodie",
                text = "Phrase numéro $i, assez longue pour remplir la ligne et passer à la suivante."
            )
        }
        val pdf =
            String(
                PdfWriter.write(
                    ExportDocument.build(session.copy(lines = lines), ExportOptions(ExportFormat.PDF)),
                    "t"
                ),
                Charsets.US_ASCII
            )
        val pages = Regex("/Count (\\d+)").find(pdf)!!.groupValues[1].toInt()
        assertTrue("pages: $pages", pages > 5)
        assertTrue(pdf.contains("($pages / $pages) Tj"))
        assertTrue(pdf.contains("(1 / $pages) Tj"))
    }

    @Test
    fun encode_winAnsiAccentsFallbacksAndEmoji() {
        fun bytes(vararg codes: Int) = codes.map { it.toByte() }.toByteArray()
        assertArrayEquals(bytes(0xE9, 0xE8, 0xE0, 0xE7, 0x9C, 0xAB, 0xBB, 0x92, 0x80), PdfWriter.encode("éèàçœ«»’€"))
        // A decomposed accent is composed first.
        assertArrayEquals(bytes(0xE9), PdfWriter.encode("é"))
        assertEquals("-> v >=", String(PdfWriter.encode("→ ✓ ≥"), Charsets.ISO_8859_1))
        assertEquals("ok ", String(PdfWriter.encode("ok 😀️"), Charsets.ISO_8859_1))
        assertEquals("??", String(PdfWriter.encode("Ж中"), Charsets.ISO_8859_1))
    }

    @Test
    fun markdownBlocks_readsHeadingsListsQuotesAndBold() {
        val blocks = MarkdownBlocks.parse(
            "# Résumé\nUne phrase\nsur deux lignes.\n\n## Points\n" +
                "- **Budget** en baisse\n  - détail\n- [x] fait\n1. premier\n> cité",
            topLevel = 2
        )
        assertEquals(
            listOf(
                Block.Heading(2, "Résumé"),
                Block.Paragraph(listOf(Run("Une phrase sur deux lignes."))),
                Block.Heading(3, "Points"),
                Block.Item("•", listOf(Run("Budget", bold = true), Run(" en baisse"))),
                Block.Item("•", listOf(Run("détail")), depth = 1),
                Block.Item("[x]", listOf(Run("fait"))),
                Block.Item("1.", listOf(Run("premier"))),
                Block.Quote(listOf(Run("cité")))
            ),
            blocks
        )
    }
}
