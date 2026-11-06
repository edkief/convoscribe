package com.convoscribe.export

import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes [Block]s as a Word document (issue #29): the smallest valid
 * Office Open XML package, with a style for each kind of block so the
 * document can be restyled in Word, Google Docs or LibreOffice. Lists are
 * indented paragraphs led by their marker, not Word numbering, so the
 * output stays one simple part.
 */
object DocxWriter {
    /**
     * Every entry gets this time, the earliest a zip can hold, set as a
     * local time so the same session gives the same bytes in any time zone.
     */
    private val ENTRY_TIME: LocalDateTime = LocalDateTime.of(1980, 1, 1, 0, 0)

    fun write(blocks: List<Block>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name).apply { timeLocal = ENTRY_TIME })
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", CONTENT_TYPES)
            put("_rels/.rels", RELS)
            put("word/_rels/document.xml.rels", DOCUMENT_RELS)
            put("word/document.xml", documentXml(blocks))
            put("word/styles.xml", STYLES)
        }
        return bytes.toByteArray()
    }

    /** The body part, `word/document.xml`. */
    fun documentXml(blocks: List<Block>): String {
        val body = StringBuilder()
        for (block in blocks) {
            when (block) {
                is Block.Title -> body.paragraph("Title", listOf(Run(block.text)))
                is Block.Meta -> body.paragraph("Meta", listOf(Run(block.text)))
                is Block.Heading -> body.paragraph("Heading${block.level}", listOf(Run(block.text)))
                is Block.Paragraph -> body.paragraph(null, block.runs)
                is Block.Quote -> body.paragraph("Quote", block.runs)
                is Block.Item -> {
                    val left = 360 * (block.depth + 1)
                    body.paragraph(
                        "ListParagraph",
                        listOf(Run(block.marker + "\t")) + block.runs,
                        indent = "<w:ind w:left=\"$left\" w:hanging=\"360\"/>"
                    )
                }
            }
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" + "\n" +
            "<w:document xmlns:w=\"$W\"><w:body>\n" + body +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
            "<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\"" +
            " w:header=\"567\" w:footer=\"567\" w:gutter=\"0\"/>" +
            "</w:sectPr>\n</w:body></w:document>\n"
    }

    private fun StringBuilder.paragraph(style: String?, runs: List<Run>, indent: String = "") {
        append("<w:p>")
        if (style != null || indent.isNotEmpty()) {
            append("<w:pPr>")
            if (style != null) append("<w:pStyle w:val=\"").append(style).append("\"/>")
            append(indent)
            append("</w:pPr>")
        }
        for (run in runs) {
            append("<w:r>")
            if (run.bold) append("<w:rPr><w:b/></w:rPr>")
            // A tab in the text is a real tab, between the marker and the item.
            run.text.split('\t').forEachIndexed { i, part ->
                if (i > 0) append("<w:tab/>")
                if (part.isNotEmpty()) append("<w:t xml:space=\"preserve\">").append(escape(part)).append("</w:t>")
            }
            append("</w:r>")
        }
        append("</w:p>\n")
    }

    /** [text] safe in XML: markup characters escaped, characters XML cannot hold dropped. */
    internal fun escape(text: String): String = buildString {
        for (c in text) {
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c < ' ' && c != '\n' -> Unit
                c == '\n' -> append(' ')
                c == '\uFFFE' || c == '\uFFFF' -> Unit
                else -> append(c)
            }
        }
    }

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private val CONTENT_TYPES = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
        <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
        <Default Extension="xml" ContentType="application/xml"/>
        <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
        <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
        </Types>
    """.trimIndent() + "\n"

    private val RELS = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
        </Relationships>
    """.trimIndent() + "\n"

    private val DOCUMENT_RELS = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
        </Relationships>
    """.trimIndent() + "\n"

    private val STYLES = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:styles xmlns:w="$W">
        <w:docDefaults>
        <w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Calibri" w:cs="Calibri"/><w:sz w:val="22"/><w:szCs w:val="22"/><w:lang w:val="en-GB"/></w:rPr></w:rPrDefault>
        <w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="264" w:lineRule="auto"/></w:pPr></w:pPrDefault>
        </w:docDefaults>
        <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>
        <w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:spacing w:after="80"/></w:pPr><w:rPr><w:b/><w:sz w:val="40"/><w:szCs w:val="40"/></w:rPr></w:style>
        <w:style w:type="paragraph" w:styleId="Meta"><w:name w:val="Meta"/><w:basedOn w:val="Normal"/><w:qFormat/><w:pPr><w:spacing w:after="40"/></w:pPr><w:rPr><w:color w:val="666666"/><w:sz w:val="19"/><w:szCs w:val="19"/></w:rPr></w:style>
        <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="360" w:after="120"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="30"/><w:szCs w:val="30"/></w:rPr></w:style>
        <w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="240" w:after="80"/><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b/><w:sz w:val="26"/><w:szCs w:val="26"/></w:rPr></w:style>
        <w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="200" w:after="60"/><w:outlineLvl w:val="2"/></w:pPr><w:rPr><w:b/><w:sz w:val="23"/><w:szCs w:val="23"/></w:rPr></w:style>
        <w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/><w:basedOn w:val="Normal"/><w:qFormat/><w:pPr><w:spacing w:after="60"/></w:pPr></w:style>
        <w:style w:type="paragraph" w:styleId="Quote"><w:name w:val="Quote"/><w:basedOn w:val="Normal"/><w:qFormat/><w:pPr><w:pBdr><w:left w:val="single" w:sz="12" w:space="8" w:color="BBBBBB"/></w:pBdr><w:spacing w:after="60"/><w:ind w:left="284"/></w:pPr><w:rPr><w:color w:val="555555"/></w:rPr></w:style>
        </w:styles>
    """.trimIndent() + "\n"
}
