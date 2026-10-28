package com.convoscribe.ui.session

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [StreamPacer] (issue #49). */
class StreamPacerTest {

    @Test
    fun zeroIntervalShowsEveryToken() {
        val pacer = StreamPacer(0L) { 0L }
        assertEquals(listOf(true, true, true), List(3) { pacer.due() })
    }

    @Test
    fun showsTheFirstTokenThenAtMostOncePerInterval() {
        var now = 0L
        val pacer = StreamPacer(1_000L) { now }
        val shown = listOf(0L, 300L, 999L, 1_000L, 1_500L, 2_100L).map { at ->
            now = at
            pacer.due()
        }
        assertEquals(listOf(true, false, false, true, false, true), shown)
    }
}
