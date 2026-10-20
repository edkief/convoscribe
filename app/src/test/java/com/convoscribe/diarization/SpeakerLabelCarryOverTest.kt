package com.convoscribe.diarization

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-JVM tests for [SpeakerLabelCarryOver]. */
class SpeakerLabelCarryOverTest {

    private fun span(startMs: Long, endMs: Long, speaker: Int?) = LabelledSpan(startMs, endMs, speaker)

    @Test
    fun customNameFollowsItsSpeakerWhenIndicesSwap() {
        val previous = listOf(span(0, 1_000, 0), span(1_000, 3_000, 1))
        val labels = mapOf(0 to "Alice", 1 to "Bob")
        // The re-run numbers them the other way round.
        val current = listOf(span(0, 1_000, 1), span(1_000, 3_000, 0))
        assertEquals(mapOf(1 to "Alice", 0 to "Bob"), SpeakerLabelCarryOver.carry(previous, labels, current))
    }

    @Test
    fun defaultLabelsAreNotCarried() {
        val previous = listOf(span(0, 1_000, 0), span(1_000, 2_000, 1))
        val labels = mapOf(0 to "Speaker 1", 1 to "Bob")
        val current = listOf(span(0, 1_000, 0), span(1_000, 2_000, 1))
        assertEquals(mapOf(1 to "Bob"), SpeakerLabelCarryOver.carry(previous, labels, current))
    }

    @Test
    fun newlyRecordedLinesDoNotAffectTheMapping() {
        // The line at 5..60 s was recorded after the last diarization: no old label.
        val previous = listOf(span(0, 1_000, 0), span(5_000, 60_000, null))
        val current = listOf(span(0, 1_000, 0), span(5_000, 60_000, 1))
        assertEquals(mapOf(0 to "Alice"), SpeakerLabelCarryOver.carry(previous, mapOf(0 to "Alice"), current))
    }

    @Test
    fun aSplitLineStillCarriesItsName() {
        // One old line by Bob is now two pieces: mostly Bob (new 1), a little Alice (new 0).
        val previous = listOf(span(0, 4_000, 0))
        val current = listOf(span(0, 600, 0), span(600, 4_000, 1))
        assertEquals(mapOf(1 to "Bob"), SpeakerLabelCarryOver.carry(previous, mapOf(0 to "Bob"), current))
    }

    @Test
    fun strongestOverlapWinsAndEachNameIsUsedOnce() {
        // Old speaker 0 ("Alice") is split across new speakers 0 and 1;
        // most of her speech lands on new speaker 1.
        val previous = listOf(span(0, 1_000, 0), span(1_000, 5_000, 0))
        val current = listOf(span(0, 1_000, 0), span(1_000, 5_000, 1))
        assertEquals(mapOf(1 to "Alice"), SpeakerLabelCarryOver.carry(previous, mapOf(0 to "Alice"), current))
    }

    @Test
    fun twoOldSpeakersMergedIntoOneKeepTheStrongerName() {
        val previous = listOf(span(0, 4_000, 0), span(4_000, 5_000, 1))
        val labels = mapOf(0 to "Alice", 1 to "Bob")
        val current = listOf(span(0, 4_000, 0), span(4_000, 5_000, 0))
        assertEquals(mapOf(0 to "Alice"), SpeakerLabelCarryOver.carry(previous, labels, current))
    }

    @Test
    fun match_pairsEveryOldSpeaker_defaultNamesToo_oneToOne() {
        val previous = listOf(span(0, 1_000, 0), span(1_000, 3_000, 1), span(3_000, 3_500, 2))
        // Old 0 and old 2 both overlap new 1 most; old 1 overlaps new 0 most.
        val current = listOf(span(0, 1_000, 1), span(1_000, 3_000, 0), span(3_000, 3_500, 1))
        assertEquals(mapOf(1 to 0, 0 to 1), SpeakerLabelCarryOver.match(previous, current))
    }
}
