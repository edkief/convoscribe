package com.convoscribe.llm

import com.convoscribe.data.TranscriptLine
import com.convoscribe.data.TranscriptMark
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [LongSummariser] (issue #28) with a fake model. */
class LongSummariserTest {

    /** Answers each call with [reply] for its messages and records the calls. */
    private class FakeClient(private val reply: (List<LlmMessage>) -> String) : ChatClient {
        val calls = ArrayList<List<LlmMessage>>()

        override fun stream(messages: List<LlmMessage>): Flow<String> {
            calls += messages
            val text = reply(messages)
            return flow { text.chunked(7).forEach { emit(it) } }
        }

        override suspend fun listModels(): List<String> = emptyList()
    }

    /** [count] lines, one a minute; line [i] mentions the fact `fact<i>`. */
    private fun lines(count: Int): List<TranscriptLine> = (0 until count).map { i ->
        TranscriptLine(
            startMs = i * 60_000L,
            speaker = "Alice",
            text = "we agreed on fact$i and then talked for a while about it.",
            endMs = i * 60_000L + 50_000L
        )
    }

    /** Notes listing the facts the part mentions, as a model would. */
    private fun notesFor(messages: List<LlmMessage>): String =
        Regex("fact\\d+").findAll(messages.last().content).map { it.value }.distinct().joinToString(" ")

    @Test
    fun summarise_takesNotesOnEveryPartThenWritesFromAllOfThem() = runTest {
        val client = FakeClient { m ->
            if (m.first().content == PromptBuilder.PART_NOTES_SYSTEM) notesFor(m) else "final summary"
        }
        val steps = ArrayList<SummaryStep>()
        val out = StringBuilder()
        LongSummariser.summarise(client, lines(90), 1_000, "Body.", emptyList(), { steps += it }, { out.append(it) })

        val parts = client.calls.count { it.first().content == PromptBuilder.PART_NOTES_SYSTEM }
        assertTrue(parts > 3)
        assertEquals(parts + 1, client.calls.size)
        assertEquals((1..parts).map { SummaryStep.Part(it, parts) } + SummaryStep.Writing, steps)
        val reduce = client.calls.last()
        assertEquals(PromptBuilder.summarySystem("Body."), reduce.first().content)
        (0 until 90).forEach {
            assertTrue(
                "fact$it missing",
                Regex("\\bfact$it\\b").containsMatchIn(reduce.last().content)
            )
        }
        assertEquals("final summary", out.toString())
    }

    @Test
    fun summarise_combinesNotesThatAreStillTooLong() = runTest {
        // Every part's notes are long, so they are merged before the summary.
        val client = FakeClient { m ->
            when (m.first().content) {
                PromptBuilder.PART_NOTES_SYSTEM -> notesFor(m) + " " + "x".repeat(300)
                PromptBuilder.MERGE_NOTES_SYSTEM -> notesFor(m)
                else -> "final"
            }
        }
        val steps = ArrayList<SummaryStep>()
        LongSummariser.summarise(client, lines(90), 1_000, "Body.", emptyList(), { steps += it }, {})

        assertTrue(SummaryStep.Combining in steps)
        assertTrue(client.calls.any { it.first().content == PromptBuilder.MERGE_NOTES_SYSTEM })
        val reduce = client.calls.last().last().content
        assertTrue(reduce.length < 1_500)
        (0 until 90).forEach { assertTrue("fact$it missing", Regex("\\bfact$it\\b").containsMatchIn(reduce)) }
    }

    @Test
    fun summarise_notesThatDoNotShrinkAreFittedRatherThanLoopingForever() = runTest {
        val client = FakeClient { "y".repeat(800) }
        LongSummariser.summarise(client, lines(90), 1_000, "Body.", emptyList(), {}, {})
        assertTrue(client.calls.size < 100)
        assertTrue(client.calls.last().last().content.contains("characters omitted"))
    }

    @Test
    fun summarise_eachPartGetsItsOwnMarksAndTheSummaryAllOfThem() = runTest {
        val client = FakeClient { "notes" }
        val early = TranscriptMark(1, 30_000L, "early")
        val late = TranscriptMark(2, 85 * 60_000L, "late")
        LongSummariser.summarise(client, lines(90), 1_000, "Body.", listOf(early, late), {}, {})

        val parts = client.calls.dropLast(1)
        assertTrue(parts.first().last().content.contains("early"))
        assertTrue(!parts.first().last().content.contains("late"))
        assertTrue(parts.last().last().content.contains("late"))
        assertEquals(1, parts.count { it.last().content.contains("early") })
        val reduce = client.calls.last().last().content
        assertTrue(reduce.contains("early") && reduce.contains("late"))
    }
}
