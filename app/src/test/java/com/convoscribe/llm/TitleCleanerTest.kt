package com.convoscribe.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleCleanerTest {

    @Test
    fun stripsQuotesLabelsMarkdownAndTrailingPunctuation() {
        assertEquals("Budget planning for Q3", TitleCleaner.clean("\"Budget planning for Q3.\""))
        assertEquals("Budget planning", TitleCleaner.clean("Title: Budget planning"))
        assertEquals("Planification du budget", TitleCleaner.clean("**Titre : « Planification du budget »**"))
        assertEquals("Hiring a designer", TitleCleaner.clean("# Hiring a designer"))
        assertEquals("Release date", TitleCleaner.clean("\n\n  Release date!  \nSome explanation"))
    }

    @Test
    fun capsWordsAndLength() {
        assertEquals("one two three four five six", TitleCleaner.clean("one two three four five six seven eight"))
        assertEquals(100, TitleCleaner.clean("x".repeat(150))!!.length)
    }

    @Test
    fun rejectsBlankOrPunctuationOnly() {
        assertNull(TitleCleaner.clean(""))
        assertNull(TitleCleaner.clean("   \n  "))
        assertNull(TitleCleaner.clean("\"\"..."))
    }
}
