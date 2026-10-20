package com.convoscribe.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [recordingNotificationText]. No Android
 * dependencies — the helper is a pure function over a millisecond
 * counter, so the JVM test runner is enough.
 */
class RecordingNotificationTextTest {

    @Test
    fun formatUnderOneMinuteShowsZeroPadded() {
        // 5 s → "Recording – 00:05"
        assertEquals("Recording – 00:05", recordingNotificationText(5_000))
    }

    @Test
    fun formatExactlyOneMinuteFiveSeconds() {
        // Acceptance criterion: 65 000 ms == 01:05
        assertEquals("Recording – 01:05", recordingNotificationText(65_000))
    }

    @Test
    fun formatJustUnderAnHourStaysMmSs() {
        // 59:59 → 3 599 000 ms
        assertEquals("Recording – 59:59", recordingNotificationText(3_599_000))
    }

    @Test
    fun formatAtOneHourSwitchesToHhMmSs() {
        // Exactly 1 h = 3 600 000 ms, must render as "1:00:00"
        assertEquals("Recording – 1:00:00", recordingNotificationText(3_600_000))
    }

    @Test
    fun formatOverOneHourShowsLeadingOneHourDigit() {
        // Acceptance criterion: 3 725 000 ms == 1 h 02 m 05 s
        assertEquals("Recording – 1:02:05", recordingNotificationText(3_725_000))
    }

    @Test
    fun formatTwoHourAndBeyondKeepsTwoDigitsForMinutesAndSeconds() {
        // 2 h 03 m 04 s = 7 384 000 ms — minutes/seconds remain zero-padded
        assertEquals("Recording – 2:03:04", recordingNotificationText(7_384_000))
    }

    @Test
    fun formatZeroStaysZero() {
        // Edge case: brand-new session, before the timer ticks.
        assertEquals("Recording – 00:00", recordingNotificationText(0))
    }

    @Test
    fun formatNegativeFallsBackToZero() {
        // Defensive: a negative input (shouldn't happen) should not
        // throw and should render as the zero timestamp.
        assertEquals("Recording – 00:00", recordingNotificationText(-1))
    }

    @Test
    fun pausedReadsPausedWithTheFrozenTime() {
        assertEquals("Paused – 01:05", recordingNotificationText(65_000, paused = true))
    }

    @Test
    fun marksAreCountedAfterTheTime() {
        assertEquals("Recording – 01:05", recordingNotificationText(65_000, marks = 0))
        assertEquals("Recording – 01:05 · 1 mark", recordingNotificationText(65_000, marks = 1))
        assertEquals("Paused – 01:05 · 3 marks", recordingNotificationText(65_000, paused = true, marks = 3))
    }
}
