package com.convoscribe.export

import java.text.Normalizer
import java.util.Locale

/**
 * Writes [Block]s as a PDF (issue #29), with no library: A4 pages of the
 * two standard Helvetica fonts every PDF reader has, so nothing is
 * embedded. Text is wrapped with the fonts' metrics and paginated, with a
 * page number at the foot of each page.
 *
 * The standard fonts use WinAnsi encoding, which holds the Western
 * European alphabets (all of English and French: é è à ç œ « » ’ € …).
 * Common symbols outside it get a plain stand-in (→ `->`, ✓ `v`), emoji
 * are dropped and anything else is written as `?` ([encode]). The output
 * holds no dates, so a session always gives the same bytes.
 */
object PdfWriter {
    private const val PAGE_W = 595f
    private const val PAGE_H = 842f
    private const val MARGIN = 56f
    private const val FOOTER_Y = 30f
    private const val BOTTOM = MARGIN + 10f
    private const val TEXT_W = PAGE_W - 2 * MARGIN

    /** A run of text placed on a line, in one font. */
    private class Piece(val x: Float, val bold: Boolean, val bytes: ByteArray)

    /** A laid-out line: its baseline [y], font [size], grey level, pieces and quote bar. */
    private class Line(
        val y: Float,
        val size: Float,
        val grey: Float,
        val pieces: List<Piece>,
        val bar: Pair<Float, Float>?
    )

    private data class Style(
        val size: Float,
        val leading: Float,
        val before: Float,
        val after: Float,
        val bold: Boolean = false,
        val grey: Float = 0f
    )

    private val TITLE = Style(20f, 25f, 0f, 6f, bold = true)
    private val META = Style(9.5f, 13f, 0f, 1f, grey = 0.4f)
    private val H1 = Style(14f, 18f, 16f, 5f, bold = true)
    private val H2 = Style(12f, 16f, 11f, 4f, bold = true)
    private val H3 = Style(11f, 14.5f, 9f, 3f, bold = true)
    private val BODY = Style(10.5f, 14.5f, 0f, 5f)
    private val ITEM = Style(10.5f, 14.5f, 0f, 3f)
    private val QUOTE = Style(10f, 13.5f, 0f, 3f, grey = 0.33f)

    fun write(blocks: List<Block>, title: String): ByteArray {
        val pages = layout(blocks)
        return serialise(pages, title)
    }

    // --- Layout ---------------------------------------------------------

    private fun layout(blocks: List<Block>): List<List<Line>> {
        val pages = mutableListOf<MutableList<Line>>(mutableListOf())
        var y = PAGE_H - MARGIN
        var previousAfter = 0f

        fun newPage() {
            pages += mutableListOf<Line>()
            y = PAGE_H - MARGIN
            previousAfter = 0f
        }

        fun place(style: Style, rows: List<List<Piece>>, keepWithNext: Float = 0f, bar: Float? = null) {
            val before = if (pages.last().isEmpty()) 0f else maxOf(style.before, previousAfter)
            val needed = before + style.leading + keepWithNext
            if (y - needed < BOTTOM && pages.last().isNotEmpty()) newPage() else y -= before
            for (row in rows) {
                if (y - style.leading < BOTTOM) newPage()
                y -= style.leading
                val barSpan = bar?.let { it to style.leading }
                pages.last() += Line(y + style.leading * 0.25f, style.size, style.grey, row, barSpan)
            }
            previousAfter = style.after
        }

        for (block in blocks) {
            when (block) {
                is Block.Title -> place(TITLE, wrap(listOf(Run(block.text, true)), TITLE, MARGIN, TEXT_W))
                is Block.Meta -> place(META, wrap(listOf(Run(block.text)), META, MARGIN, TEXT_W))
                is Block.Heading -> {
                    val style = when (block.level) {
                        1 -> H1
                        2 -> H2
                        else -> H3
                    }
                    place(
                        style,
                        wrap(listOf(Run(block.text, true)), style, MARGIN, TEXT_W),
                        keepWithNext = 2 * BODY.leading
                    )
                }
                is Block.Paragraph -> place(BODY, wrap(block.runs, BODY, MARGIN, TEXT_W))
                is Block.Quote -> place(QUOTE, wrap(block.runs, QUOTE, MARGIN + 12f, TEXT_W - 12f), bar = MARGIN + 3f)
                is Block.Item -> {
                    val left = MARGIN + 14f * block.depth
                    val textX = left + 18f
                    val rows = wrap(block.runs, ITEM, textX, MARGIN + TEXT_W - textX).toMutableList()
                    if (rows.isEmpty()) rows += emptyList<Piece>()
                    rows[0] = listOf(Piece(left, false, encode(block.marker))) + rows[0]
                    place(ITEM, rows)
                }
            }
        }
        return pages.filter { it.isNotEmpty() }.ifEmpty { listOf(mutableListOf()) }
    }

    /** One word of a paragraph and the font it is set in. */
    private class Word(val bytes: ByteArray, val bold: Boolean)

    /** [runs] broken into lines no wider than [width], starting at [x]. */
    private fun wrap(runs: List<Run>, style: Style, x: Float, width: Float): List<List<Piece>> {
        val words = mutableListOf<Word?>() // null marks a space between words
        for (run in runs) {
            val bold = run.bold || style.bold
            run.text.split(' ').forEachIndexed { i, part ->
                if (i > 0) words += null
                if (part.isNotEmpty()) words += Word(encode(part), bold)
            }
        }
        val rows = mutableListOf<List<Piece>>()
        var row = mutableListOf<Piece>()
        var cursor = x
        var pendingSpace = false
        fun spaceWidth(bold: Boolean) = width(byteArrayOf(' '.code.toByte()), bold, style.size)
        fun breakRow() {
            rows += row
            row = mutableListOf()
            cursor = x
            pendingSpace = false
        }
        for (word in words) {
            if (word == null) {
                pendingSpace = row.isNotEmpty()
                continue
            }
            var bytes = word.bytes
            while (true) {
                val space = if (pendingSpace) spaceWidth(word.bold) else 0f
                val w = width(bytes, word.bold, style.size)
                if (cursor + space + w <= x + width + 0.01f) {
                    cursor += space
                    row += Piece(cursor, word.bold, bytes)
                    cursor += w
                    pendingSpace = false
                    break
                }
                if (row.isNotEmpty()) {
                    breakRow()
                    continue
                }
                // A word wider than the line: cut it where it overflows.
                var fit = 1
                while (fit < bytes.size && width(bytes.copyOfRange(0, fit + 1), word.bold, style.size) <= width) fit++
                row += Piece(cursor, word.bold, bytes.copyOfRange(0, fit))
                breakRow()
                bytes = bytes.copyOfRange(fit, bytes.size)
                if (bytes.isEmpty()) break
            }
        }
        if (row.isNotEmpty()) rows += row
        return rows
    }

    /** Width in points of WinAnsi [bytes] in Helvetica (or bold) at [size]. */
    private fun width(bytes: ByteArray, bold: Boolean, size: Float): Float {
        val table = if (bold) HelveticaMetrics.BOLD else HelveticaMetrics.REGULAR
        var units = 0
        for (b in bytes) {
            val code = b.toInt() and 0xFF
            units += if (code >= 32) table[code - 32] else 0
        }
        return units * size / 1000f
    }

    // --- Encoding -------------------------------------------------------

    /** WinAnsi codes 0x80 to 0x9F, where it differs from Latin-1. */
    private val WIN_ANSI_HIGH: Map<Char, Int> = mapOf(
        '\u20AC' to 0x80, '\u201A' to 0x82, '\u0192' to 0x83, '\u201E' to 0x84, '\u2026' to 0x85, '\u2020' to 0x86,
        '\u2021' to 0x87, '\u02C6' to 0x88, '\u2030' to 0x89, '\u0160' to 0x8A, '\u2039' to 0x8B, '\u0152' to 0x8C,
        '\u017D' to 0x8E, '\u2018' to 0x91, '\u2019' to 0x92, '\u201C' to 0x93, '\u201D' to 0x94, '\u2022' to 0x95,
        '\u2013' to 0x96, '\u2014' to 0x97, '\u02DC' to 0x98, '\u2122' to 0x99, '\u0161' to 0x9A, '\u203A' to 0x9B,
        '\u0153' to 0x9C, '\u017E' to 0x9E, '\u0178' to 0x9F
    )

    /** Stand-ins for common characters WinAnsi lacks. */
    private val FALLBACKS: Map<Char, String> = mapOf(
        '\u2192' to "->", '\u2190' to "<-", '\u2194' to "<->", '\u21D2' to "=>", '\u21D0' to "<=",
        '\u2713' to "v", '\u2714' to "v", '\u2705' to "v", '\u2717' to "x", '\u2718' to "x",
        '\u274C' to "x", '\u2265' to ">=", '\u2264' to "<=", '\u2260' to "!=", '\u2248' to "~",
        '\u2212' to "-", '\u2010' to "-", '\u2011' to "-", '\u2012' to "-", '\u2015' to "\u2014",
        '\u2032' to "'", '\u2033' to "\"", '\u2691' to "\u00BB", '\u2002' to " ", '\u2003' to " ",
        '\u2007' to " ", '\u2009' to " ", '\u200A' to " ", '\u202F' to "\u00A0", '\u200B' to "",
        '\u200C' to "", '\u200D' to "", '\uFEFF' to "", '\uFE0E' to "", '\uFE0F' to "",
        '\u00AD' to "", '\u25CF' to "\u2022", '\u2023' to "\u2022", '\u25E6' to "-", '\u2043' to "-",
        '\u2610' to "[ ]", '\u2611' to "[x]", '\u2612' to "[x]"
    )

    /**
     * [text] in WinAnsi: composed first (so a letter and its accent become
     * one character), then mapped byte by byte; see the class comment for
     * characters it cannot hold.
     */
    internal fun encode(text: String): ByteArray {
        val composed = Normalizer.normalize(text, Normalizer.Form.NFC)
        val out = java.io.ByteArrayOutputStream(composed.length)
        var i = 0
        while (i < composed.length) {
            val cp = composed.codePointAt(i)
            i += Character.charCount(cp)
            if (cp > 0xFFFF) continue // emoji and other supplementary characters
            val c = cp.toChar()
            val fallback = FALLBACKS[c]
            val mapped = if (fallback != null && fallback.length != 1) fallback else (fallback ?: c.toString())
            for (m in mapped) out.write(byteOf(m))
        }
        return out.toByteArray()
    }

    private fun byteOf(c: Char): Int = when {
        c == '\t' || c == '\n' || c == '\r' -> ' '.code
        c.code in 32..126 -> c.code
        c.code in 0xA0..0xFF -> c.code
        else -> WIN_ANSI_HIGH[c] ?: '?'.code
    }

    /** [bytes] as a PDF literal string, with everything outside printable ASCII escaped. */
    private fun literal(bytes: ByteArray): String = buildString {
        append('(')
        for (b in bytes) {
            val code = b.toInt() and 0xFF
            when {
                code == '('.code || code == ')'.code || code == '\\'.code -> append('\\').append(code.toChar())
                code in 32..126 -> append(code.toChar())
                else -> append('\\').append(String.format(Locale.US, "%03o", code))
            }
        }
        append(')')
    }

    /** [text] as a PDF text string in UTF-16BE, for the document information. */
    private fun textString(text: String): String = buildString {
        append("<FEFF")
        for (c in text) append(String.format(Locale.US, "%04X", c.code))
        append('>')
    }

    // --- Serialisation --------------------------------------------------

    private fun num(value: Float): String {
        val rounded = Math.round(value * 100) / 100.0
        return if (rounded == Math.floor(rounded)) {
            rounded.toLong().toString()
        } else {
            String.format(
                Locale.US,
                "%.2f",
                rounded
            ).trimEnd('0')
        }
    }

    private fun content(lines: List<Line>, page: Int, pages: Int): String = buildString {
        for (line in lines) {
            line.bar?.let { (x, leading) ->
                append("0.75 G 1.5 w ${num(x)} ${num(line.y - leading * 0.25f)} m ")
                append("${num(x)} ${num(line.y - leading * 0.25f + leading)} l S\n")
            }
            if (line.pieces.isEmpty()) continue
            append("BT ${num(line.grey)} g\n")
            var font: Boolean? = null
            var lastX = 0f
            line.pieces.forEachIndexed { i, piece ->
                if (piece.bold != font) {
                    append(if (piece.bold) "/F2 " else "/F1 ").append(num(line.size)).append(" Tf\n")
                    font = piece.bold
                }
                val dx = if (i == 0) piece.x else piece.x - lastX
                val dy = if (i == 0) line.y else 0f
                append("${num(dx)} ${num(dy)} Td ").append(literal(piece.bytes)).append(" Tj\n")
                lastX = piece.x
            }
            append("ET\n")
        }
        val footer = encode("$page / $pages")
        val x = (PAGE_W - width(footer, false, 8f)) / 2
        append("BT 0.5 g /F1 8 Tf ${num(x)} ${num(FOOTER_Y)} Td ").append(literal(footer)).append(" Tj ET\n")
    }

    private fun serialise(pages: List<List<Line>>, title: String): ByteArray {
        val objects = mutableListOf<String>()
        // 1 catalog, 2 page tree, 3 and 4 fonts, 5 information, then a page and its content per page.
        val firstPage = 6
        val kids = pages.indices.joinToString(" ") { "${firstPage + 2 * it} 0 R" }
        objects += "<< /Type /Catalog /Pages 2 0 R >>"
        objects += "<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>"
        objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"
        objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>"
        objects += "<< /Title ${textString(title)} /Producer (ConvoScribe) >>"
        pages.forEachIndexed { i, lines ->
            val contentId = firstPage + 2 * i + 1
            objects += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${num(PAGE_W)} ${num(PAGE_H)}] " +
                "/Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents $contentId 0 R >>"
            val stream = content(lines, i + 1, pages.size)
            objects += "<< /Length ${stream.length} >>\nstream\n${stream}endstream"
        }
        val out = StringBuilder("%PDF-1.4\n")
        val offsets = mutableListOf<Int>()
        objects.forEachIndexed { i, body ->
            offsets += out.length
            out.append("${i + 1} 0 obj\n").append(body).append("\nendobj\n")
        }
        val xref = out.length
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { out.append(String.format(Locale.US, "%010d 00000 n \n", it)) }
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R /Info 5 0 R >>\nstartxref\n$xref\n%%EOF\n")
        // Every byte above is ASCII: text outside it is escaped.
        return out.toString().toByteArray(Charsets.US_ASCII)
    }
}
