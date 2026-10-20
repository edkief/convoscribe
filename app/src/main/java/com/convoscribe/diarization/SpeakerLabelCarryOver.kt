package com.convoscribe.diarization

import com.convoscribe.data.defaultSpeakerLabel

/** A stretch of transcript and the speaker it is labelled with (`null` = none). */
data class LabelledSpan(val startMs: Long, val endMs: Long, val speaker: Int?)

/**
 * Keeps the speaker names a user typed when a session is diarized
 * again (after continuing a recording, or "Re-identify speakers").
 *
 * A re-run numbers speakers from scratch, so old index 0 may now be
 * index 1, and lines may have been split into new rows. Each old
 * speaker with a custom name (anything other than the default
 * `"Speaker N"`) hands that name to the new speaker it shares the
 * most transcript time with. Each new speaker gets at most one name
 * and each name goes to at most one speaker; the strongest overlaps
 * are matched first.
 */
object SpeakerLabelCarryOver {

    /**
     * @param previous the transcript as last diarized (old speaker
     *   indices; `null` for lines recorded since).
     * @param previousLabels old speaker index → label, as stored.
     * @param current the transcript as just diarized (new indices).
     * @return new speaker index → carried-over custom label.
     */
    fun carry(
        previous: List<LabelledSpan>,
        previousLabels: Map<Int, String>,
        current: List<LabelledSpan>
    ): Map<Int, String> {
        val custom = previousLabels.filter { (index, label) -> label != defaultSpeakerLabel(index) }
        if (custom.isEmpty()) return emptyMap()
        return pair(previous.filter { it.speaker in custom }, current)
            .entries.associate { (old, new) -> new to custom.getValue(old) }
    }

    /**
     * Old speaker index → the new index of the speaker it shares the
     * most transcript time with, for every old speaker that overlaps
     * one (custom name or not), matched one-to-one as in [carry]. Used
     * to keep a speaker the user picked for a line (issue #26).
     */
    fun match(previous: List<LabelledSpan>, current: List<LabelledSpan>): Map<Int, Int> = pair(previous, current)

    /** Greedy one-to-one pairing of old and new speakers by overlap, strongest first: old → new. */
    private fun pair(previous: List<LabelledSpan>, current: List<LabelledSpan>): Map<Int, Int> {
        // (newIndex, oldIndex) -> milliseconds of transcript in common.
        val overlap = mutableMapOf<Pair<Int, Int>, Long>()
        val now = current.filter { it.speaker != null }.sortedBy { it.startMs }
        var first = 0
        for (old in previous.filter { it.speaker != null }.sortedBy { it.startMs }) {
            // Both lists are transcript lines in time order, so their
            // ends are ordered too: skip what ended before this span.
            while (first < now.size && now[first].endMs <= old.startMs) first++
            var i = first
            while (i < now.size && now[i].startMs < old.endMs) {
                val ms = minOf(old.endMs, now[i].endMs) - maxOf(old.startMs, now[i].startMs)
                if (ms > 0) overlap.merge(now[i].speaker!! to old.speaker!!, ms, Long::plus)
                i++
            }
        }

        val result = mutableMapOf<Int, Int>()
        val usedNew = mutableSetOf<Int>()
        for ((pair, _) in overlap.entries.sortedByDescending { it.value }) {
            val (new, old) = pair
            if (new in usedNew || old in result) continue
            result[old] = new
            usedNew += new
        }
        return result
    }
}
