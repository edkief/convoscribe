package com.convoscribe.llm

import com.convoscribe.data.TranscriptLine
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [CitationParser] (issue #49). */
class CitationParserTest {

    private val weeklyLines = listOf(
        TranscriptLine(startMs = 60_000L, speaker = "Alice", text = "Budget is 40k.", id = 11L),
        TranscriptLine(startMs = 75_000L, speaker = "Bob", text = "Agreed.", id = 12L),
        TranscriptLine(startMs = 3_700_000L, speaker = "Bob", text = "Late point.", id = 13L)
    )

    private val excerpts = CrossExcerpts(
        listOf(
            SessionExcerpts(
                sessionId = 1L,
                title = "Weekly, Acme",
                date = "2026-10-01",
                language = "en",
                chunks = listOf(
                    TranscriptChunk("…", 60_000L, 90_000L, 0, 1, 1L),
                    TranscriptChunk("…", 3_700_000L, 3_710_000L, 2, 2, 1L)
                ),
                lines = weeklyLines
            ),
            SessionExcerpts(
                sessionId = 2L,
                title = "Réunion",
                date = "2026-09-12",
                language = "fr",
                chunks = listOf(
                    TranscriptChunk(
                        "Summary: …",
                        0L,
                        0L,
                        CrossSessionRetriever.SUMMARY_LINE,
                        CrossSessionRetriever.SUMMARY_LINE,
                        2L
                    )
                ),
                lines = emptyList()
            )
        )
    )

    @Test
    fun citationsMatchTheSentLineAtOrBeforeTheTime() {
        val answer = "The budget is 40k (Weekly, Acme, 2026-10-01, 01:10). " +
            "Bob agreed (Weekly, Acme, 2026-10-01, [01:15])."
        assertEquals(
            listOf(
                Citation(1L, "Weekly, Acme", "2026-10-01", segmentId = 11L, atMs = 60_000L),
                Citation(1L, "Weekly, Acme", "2026-10-01", segmentId = 12L, atMs = 75_000L)
            ),
            CitationParser.parse(answer, excerpts)
        )
    }

    @Test
    fun hoursAccentsAndRepeats() {
        val answer = "(weekly, acme, 2026-10-01, 1:01:40) and (WEEKLY, ACME, 2026-10-01, 1:01:41) " +
            "and (reunion, 2026-09-12, 00:30)"
        assertEquals(
            listOf(
                Citation(1L, "Weekly, Acme", "2026-10-01", segmentId = 13L, atMs = 3_700_000L),
                Citation(2L, "Réunion", "2026-09-12")
            ),
            CitationParser.parse(answer, excerpts)
        )
    }

    @Test
    fun aTimeBeforeEverySentLineOpensTheFirstOne() {
        val citation = CitationParser.parse("(Weekly, Acme, 2026-10-01, 00:05)", excerpts).single()
        assertEquals(11L, citation.segmentId)
    }

    @Test
    fun unknownSessionsAndOtherBracketsAreIgnored() {
        val answer = "Not cited (Standup, 2026-01-01, 00:10), not a citation (see above), plain [01:00]."
        assertEquals(emptyList<Citation>(), CitationParser.parse(answer, excerpts))
    }

    @Test
    fun theOnlySessionOfTheDayMatchesARewordedTitle() {
        val citation = CitationParser.parse("(The meeting, 2026-09-12, 00:00)", excerpts).single()
        assertEquals(2L, citation.sessionId)
    }
}
