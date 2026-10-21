package com.convoscribe.llm

import com.convoscribe.data.TranscriptLine
import com.convoscribe.data.TranscriptMark

/** Notes the model took on the part of a long transcript from [startMs] to [endMs] (issue #28). */
data class PartNotes(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

/** What a multi-call summary is doing, for the Summary tab (issue #28). */
sealed interface SummaryStep {
    /** Taking notes on part [part] of [parts]. */
    data class Part(val part: Int, val parts: Int) : SummaryStep

    /** Combining the notes, which are still too long for one call. */
    data object Combining : SummaryStep

    /** Writing the summary from the notes; its tokens follow. */
    data object Writing : SummaryStep
}

/**
 * Summarises a transcript longer than the character budget in parts
 * (issue #28), so the summary covers all of it instead of the head
 * and tail [TranscriptTruncator] keeps:
 *
 *  1. map — [TranscriptChunker] cuts the transcript into chunks within
 *     the budget, and the model takes notes on each
 *     ([PromptBuilder.summaryPart]);
 *  2. combine — while the notes together are over the budget,
 *     consecutive notes are merged in groups that fit
 *     ([PromptBuilder.mergeNotes]), at most [MAX_MERGE_ROUNDS] times;
 *  3. reduce — the template writes the summary from the notes
 *     ([PromptBuilder.summaryFromParts]).
 *
 * Only the reduce step's tokens reach [onToken]; the notes are
 * intermediate and never shown or saved. Failures and cancellation
 * propagate to the caller.
 */
object LongSummariser {

    /**
     * Rounds of combining before the notes are fitted to the budget
     * as they are. Notes are far shorter than the transcript they
     * cover, so one round is already rare.
     */
    const val MAX_MERGE_ROUNDS: Int = 4

    suspend fun summarise(
        client: ChatClient,
        lines: List<TranscriptLine>,
        maxChars: Int,
        body: String,
        marks: List<TranscriptMark>,
        onStep: (SummaryStep) -> Unit,
        onToken: (String) -> Unit
    ) {
        val chunks = TranscriptChunker.chunk(lines, maxChars)
        var notes = chunks.mapIndexed { index, chunk ->
            onStep(SummaryStep.Part(index + 1, chunks.size))
            val inChunk = marks.filter {
                (index == 0 || it.atMs >= chunk.startMs) && (index == chunks.lastIndex || it.atMs < chunk.endMs)
            }
            PartNotes(
                chunk.startMs,
                chunk.endMs,
                collect(client, PromptBuilder.summaryPart(chunk, index + 1, chunks.size, inChunk))
            )
        }

        var rounds = 0
        while (rounds < MAX_MERGE_ROUNDS && PromptBuilder.notesText(notes).length > maxChars) {
            val groups = group(notes, maxChars)
            if (groups.size == notes.size) break
            onStep(SummaryStep.Combining)
            val merged = groups.map { group ->
                if (group.size == 1) {
                    group.single()
                } else {
                    PartNotes(
                        group.first().startMs,
                        group.last().endMs,
                        collect(client, PromptBuilder.mergeNotes(group))
                    )
                }
            }
            if (PromptBuilder.notesText(merged).length >= PromptBuilder.notesText(notes).length) break
            notes = merged
            rounds++
        }

        onStep(SummaryStep.Writing)
        client.stream(PromptBuilder.summaryFromParts(notes, maxChars, body, marks)).collect { onToken(it) }
    }

    /** Consecutive runs of [notes] whose [PromptBuilder.notesText] fits [maxChars]; a note too long alone is a run of one. */
    private fun group(notes: List<PartNotes>, maxChars: Int): List<List<PartNotes>> {
        val groups = ArrayList<List<PartNotes>>()
        var current = ArrayList<PartNotes>()
        for (note in notes) {
            if (current.isNotEmpty() && PromptBuilder.notesText(current + note).length > maxChars) {
                groups += current
                current = ArrayList()
            }
            current += note
        }
        if (current.isNotEmpty()) groups += current
        return groups
    }

    private suspend fun collect(client: ChatClient, messages: List<LlmMessage>): String {
        val text = StringBuilder()
        client.stream(messages).collect { text.append(it) }
        return text.toString()
    }
}
