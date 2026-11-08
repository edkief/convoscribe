package com.convoscribe.llm

import com.convoscribe.data.TranscriptLine
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistAnswerTest {

    private val zone = ZoneId.of("UTC")

    private val excerpts = CrossSessionRetriever.select(
        sources = listOf(
            SessionSource(
                id = 5L,
                title = "Planning, Q4",
                createdAt = LocalDate.of(2026, 10, 1).atStartOfDay(zone).toInstant().toEpochMilli(),
                language = "en",
                lines = listOf(TranscriptLine(72_000L, "Marie", "The launch is on March 3.", id = 9L))
            )
        ),
        question = "launch",
        maxChars = 2_000,
        zone = zone
    )

    private fun parse(reply: String) = AssistAnswer.parse(reply, excerpts)

    @Test
    fun noneInItsVariantsShowsNothing() {
        listOf(
            "NONE",
            "none",
            "NONE.",
            " **NONE** ",
            "`NONE`",
            "NONE — it is not a question",
            "```\nNONE\n```",
            "",
            "  "
        ).forEach { assertNull(it, parse(it)) }
    }

    @Test
    fun anAnswerLosesItsCitationWhichIsMatchedToTheExcerpts() {
        val reply = parse("The launch is on March 3 (Planning, Q4, 2026-10-01, 01:12).")!!
        assertEquals("The launch is on March 3.", reply.text)
        assertEquals(listOf(Citation(5L, "Planning, Q4", "2026-10-01", segmentId = 9L, atMs = 72_000L)), reply.cites)
    }

    @Test
    fun reasoningBlocksFencesAndLineBreaksAreDropped() {
        val reply = parse("<think>The excerpt says March.</think>\n```\nMarch 3.\nMarie said so.\n```")!!
        assertEquals("March 3. Marie said so.", reply.text)
        assertTrue(reply.cites.isEmpty())
        assertEquals("Marie.", parse("half a thought</think>Marie.")!!.text)
        assertNull("still thinking", parse("<think>The excerpt says"))
    }

    @Test
    fun aLongAnswerIsCutOnAWord() {
        val text = parse("word ".repeat(200))!!.text
        assertTrue(text.length <= AssistAnswer.MAX_CHARS + 1)
        assertTrue(text.endsWith("word…"))
    }
}
