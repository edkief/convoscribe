package com.convoscribe.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextTest {

    @Test
    fun fold_dropsAccentsAndCase() {
        assertEquals("reunion a l'ecole", SearchText.fold("Réunion à l'École"))
        assertEquals("ca marche, eleve", SearchText.fold("Ça marche, ÉLÈVE"))
    }

    @Test
    fun terms_splitOnPunctuationLikeTheTokenizer() {
        assertEquals(listOf("l", "equipe", "budget"), SearchText.terms("  L'équipe, BUDGET! budget "))
        assertEquals(emptyList<String>(), SearchText.terms(" - , ! "))
    }

    @Test
    fun ftsQuery_isPrefixTermsWithNoOperatorsOrQuotes() {
        val terms = SearchText.terms("\"budget\" OR NOT reu* NEAR (x) -y")
        assertEquals("budget* or* not* reu* near* x* y*", SearchText.ftsQuery(terms))
    }

    @Test
    fun matchRanges_highlightsWholeWordsByPrefix() {
        val text = "La Réunion sur le budget"
        assertEquals(listOf(3..9), SearchText.matchRanges(text, SearchText.terms("reun")))
        assertEquals(listOf(3..9, 18..23), SearchText.matchRanges(text, SearchText.terms("BUDGET réunion")))
    }

    @Test
    fun matchRanges_requiresEveryTerm_andWordStarts() {
        assertEquals(emptyList<IntRange>(), SearchText.matchRanges("budget meeting", SearchText.terms("budget alice")))
        assertEquals(emptyList<IntRange>(), SearchText.matchRanges("the budget", SearchText.terms("udget")))
    }

    @Test
    fun excerpt_cutsAroundTheFirstMatch() {
        val long = "word ".repeat(40) + "the Budget was approved " + "tail ".repeat(60)
        val excerpt = SearchText.excerpt(long, SearchText.terms("budget"))!!
        assertTrue(excerpt.text.startsWith("…"))
        assertTrue(excerpt.text.endsWith("…"))
        val range = excerpt.ranges.single()
        assertEquals("Budget", excerpt.text.substring(range))
        assertNull(SearchText.excerpt(long, SearchText.terms("missing")))
    }

    @Test
    fun excerpt_keepsShortTextWhole() {
        val excerpt = SearchText.excerpt("Short budget note", SearchText.terms("budget"))!!
        assertEquals("Short budget note", excerpt.text)
        assertEquals(listOf(6..11), excerpt.ranges)
    }

    @Test
    fun parseMarked_turnsMarkersIntoRanges() {
        val o = SearchText.MARK_OPEN
        val c = SearchText.MARK_CLOSE
        val parsed = SearchText.parseMarked("…la ${o}réunion$c du ${o}budget$c")
        assertEquals("…la réunion du budget", parsed.text)
        assertEquals(listOf("réunion", "budget"), parsed.ranges.map { parsed.text.substring(it) })
    }
}
