package com.convoscribe.data

import java.text.Normalizer
import java.util.Locale

/**
 * Text with the parts to highlight: [ranges] index into [text], each
 * a whole matched word.
 */
data class Highlighted(val text: String, val ranges: List<IntRange> = emptyList())

/**
 * Pure text helpers for cross-session search (issue #24), shared by
 * the FTS query on transcript lines and the Kotlin matching of
 * titles, speaker names and summaries so both behave the same way:
 *
 *  - words are runs of letters and digits, as the FTS4 `unicode61`
 *    tokenizer splits them ("l'équipe" is "l" and "équipe");
 *  - matching ignores case and accents ([fold]);
 *  - each typed word matches as a word prefix ("reun" finds
 *    "Réunion"), and every typed word must match.
 */
object SearchText {
    /** Snippet markers around a matched word; control characters never appear in a transcript. */
    const val MARK_OPEN: String = "\u0001"
    const val MARK_CLOSE: String = "\u0002"

    private val WORD = Regex("[\\p{L}\\p{N}]+")
    private val COMBINING = Regex("\\p{Mn}+")

    /** [text] lowercased with its diacritics removed: `"Réunion"` → `"reunion"`. */
    fun fold(text: String): String =
        COMBINING.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

    /** The folded words of a query, in order and without repeats. */
    fun terms(query: String): List<String> = WORD.findAll(fold(query)).map { it.value }.distinct().toList()

    /**
     * FTS4 `MATCH` expression for [terms]: each one as a prefix term,
     * implicitly ANDed. Terms hold only letters and digits and are
     * lowercase, so no FTS operator or quote can get through.
     */
    fun ftsQuery(terms: List<String>): String = terms.joinToString(" ") { "$it*" }

    /**
     * The words of [text] that one of [terms] is a prefix of, as
     * ranges into [text]; empty unless every term matches some word.
     */
    fun matchRanges(text: String, terms: List<String>): List<IntRange> {
        if (terms.isEmpty()) return emptyList()
        val matched = mutableSetOf<String>()
        val ranges = WORD.findAll(text).mapNotNull { word ->
            val folded = fold(word.value)
            val hits = terms.filter { folded.startsWith(it) }
            matched += hits
            word.range.takeIf { hits.isNotEmpty() }
        }.toList()
        return if (matched.size == terms.size) ranges else emptyList()
    }

    /** [text] highlighted for [terms], or `null` when it does not match. */
    fun match(text: String, terms: List<String>): Highlighted? =
        matchRanges(text, terms).takeIf { it.isNotEmpty() }?.let { Highlighted(text, it) }

    /**
     * A short excerpt of a long [text] (a summary) around its first
     * match: about [before] characters ahead of it and [after] from
     * it, cut at word boundaries and marked with `…`; `null` when
     * [text] does not match.
     */
    fun excerpt(text: String, terms: List<String>, before: Int = 60, after: Int = 160): Highlighted? {
        val ranges = matchRanges(text, terms)
        val first = ranges.firstOrNull() ?: return null
        var start = (first.first - before).coerceAtLeast(0)
        var end = (first.first + after).coerceAtMost(text.length)
        if (start > 0) start = text.indexOf(' ', start).let { if (it in start until first.first) it + 1 else start }
        if (end < text.length) end = text.lastIndexOf(' ', end).let { if (it > first.last) it else end }
        val lead = if (start > 0) "…" else ""
        val tail = if (end < text.length) "…" else ""
        val body = text.substring(start, end).replace('\n', ' ')
        val shift = lead.length - start
        val kept = ranges.filter { it.first >= start && it.last < end }.map { (it.first + shift)..(it.last + shift) }
        return Highlighted(lead + body + tail, kept)
    }

    /** Turn an FTS snippet marked with [MARK_OPEN] / [MARK_CLOSE] into [Highlighted]. */
    fun parseMarked(snippet: String): Highlighted {
        val out = StringBuilder()
        val ranges = mutableListOf<IntRange>()
        var open = -1
        for (c in snippet) {
            when (c.toString()) {
                MARK_OPEN -> open = out.length
                MARK_CLOSE -> if (open >= 0) {
                    if (out.length > open) ranges += open until out.length
                    open = -1
                }
                else -> out.append(c)
            }
        }
        return Highlighted(out.toString(), ranges)
    }
}
