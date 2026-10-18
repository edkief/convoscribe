package com.convoscribe.ui.home

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JUnit tests for the session card's formatters. The status is a
 * badge and the speaker count a plural resource, so neither is part of
 * these strings.
 */
class SessionFormatTest {

    @Test
    fun date_isDayMonthYearAndTime_inTheGivenZone() {
        assertEquals("1 Jan 1970, 00:00", formatSessionDate(0L, ZoneOffset.UTC))
        assertEquals("14 Mar 2025, 09:30", formatSessionDate(1_741_944_600_000L, ZoneOffset.UTC))
    }

    @Test
    fun date_isPinnedToEnglishMonthNames() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.FRANCE)
            assertEquals("1 Jan 1970, 00:00", formatSessionDate(0L, ZoneOffset.UTC))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun duration_underAnHourIsMinutesSeconds() {
        assertEquals("12:34", formatDuration(754_000L))
        assertEquals("00:05", formatDuration(5_999L))
    }

    @Test
    fun duration_pastAnHourHasHours() {
        assertEquals("1:02:05", formatDuration(3_725_000L))
    }

    @Test
    fun duration_zeroOrNegativeIsEmpty() {
        assertEquals("", formatDuration(0L))
        assertEquals("", formatDuration(-1L))
    }

    @Test
    fun languageTag_onlyForNonEnglish() {
        assertEquals("FR", languageTag("fr"))
        assertNull(languageTag("en"))
        assertNull(languageTag(""))
    }
}
