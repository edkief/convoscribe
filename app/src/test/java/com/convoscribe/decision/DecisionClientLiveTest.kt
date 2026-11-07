package com.convoscribe.decision

import com.convoscribe.data.TranscriptLine
import com.convoscribe.llm.CoveredPoint
import com.convoscribe.llm.DecisionTalkingPoints
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Talking points checked by a real laya decision service (issue #101).
 * Skipped unless `CONVOSCRIBE_DECISION_SERVER` is its base URL, e.g.
 * `http://laya.llama:8080` (and `CONVOSCRIBE_DECISION_KEY` its key, if
 * it has one).
 */
class DecisionClientLiveTest {

    @Test
    fun aShortMeeting_ticksThePointsDiscussed_atTheirLines() = runBlocking {
        val url = System.getenv("CONVOSCRIBE_DECISION_SERVER").orEmpty()
        assumeTrue(url.isNotBlank())
        val http = OkHttpClient.Builder().readTimeout(1, TimeUnit.MINUTES).build()
        val client = HttpDecisionClient(http) {
            DecisionConfig(url, System.getenv("CONVOSCRIBE_DECISION_KEY").orEmpty(), model = "")
        }
        val lines = listOf(
            TranscriptLine(5_000L, "Alice", "Okay, let's get started. Thanks everyone for joining."),
            TranscriptLine(18_000L, "Alice", "First up, where are we with the mobile release?"),
            TranscriptLine(31_000L, "Bob", "The build is green, QA signed off, and we push it to the store on Friday."),
            TranscriptLine(80_000L, "Alice", "Next, hiring. We still have the backend role open."),
            TranscriptLine(88_000L, "Carol", "I did three interviews, one strong candidate I'd like to bring onsite."),
            TranscriptLine(115_000L, "Alice", "By the way, the coffee machine on the third floor is broken again."),
            TranscriptLine(185_000L, "Alice", "Anything else? Okay, thanks all.")
        )
        val points = listOf(
            "Mobile release date",
            "Backend hiring: next steps",
            "Office coffee machine",
            "Security audit findings",
            "Q3 roadmap priorities",
            "Team offsite location"
        )
        val states = DecisionTalkingPoints.states(lines)

        val started = System.nanoTime()
        val answers = client.decide(states.map { it.text }, DecisionTalkingPoints.questions(points))
        val covered = DecisionTalkingPoints.covered(states, answers, points.size)

        println("laya ${client.ping()}: ${(System.nanoTime() - started) / 1_000_000} ms, covered $covered")
        assertEquals(listOf(1, 2, 3), covered.map(CoveredPoint::number))
    }
}
