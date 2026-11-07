package com.convoscribe.llm

import com.convoscribe.data.TranscriptLine
import com.convoscribe.decision.DecisionAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionTalkingPointsTest {

    private fun answer(choice: String, p: Double) = DecisionAnswer(choice, mapOf(choice to p))

    @Test
    fun eachLineIsAStateOfItsOwn_withItsTimeAndSpeaker() {
        val states = DecisionTalkingPoints.states(
            listOf(TranscriptLine(5_000L, "Alice", "Budget first."), TranscriptLine(12_000L, null, "Then hiring."))
        )
        assertEquals(
            listOf(
                DecisionTalkingPoints.State("[00:05] Alice: Budget first.", 5_000L),
                DecisionTalkingPoints.State("[00:12] Then hiring.", 12_000L)
            ),
            states
        )
    }

    @Test
    fun aLongLineGoesInParts_eachWithTheLinesTime_andNothingIsLost() {
        val sentence = "We went through the whole budget line by line and agreed on the numbers. "
        val text = sentence.repeat(40).trim()
        val states = DecisionTalkingPoints.states(listOf(TranscriptLine(65_000L, "Bob", text)))
        assertTrue(states.size > 1)
        for (state in states) {
            assertTrue(state.text.length <= DecisionTalkingPoints.MAX_STATE_CHARS)
            assertTrue(state.text.startsWith("[01:05] Bob: "))
            assertTrue(state.text.endsWith("."))
            assertEquals(65_000L, state.atMs)
        }
        val rejoined = states.joinToString(" ") { it.text.removePrefix("[01:05] Bob: ") }
        assertEquals(text, rejoined)
    }

    @Test
    fun splitBreaksAtASpaceWhenThereIsNoSentenceEnd() {
        val parts = DecisionTalkingPoints.split("word ".repeat(300).trim(), 100)
        assertTrue(parts.all { it.length <= 100 && !it.startsWith(" ") && !it.endsWith(" ") })
        assertEquals(300, parts.sumOf { it.split(' ').size })
    }

    @Test
    fun openPointsAreSpreadEvenly_numberedFromOne_eachQuestionEndingWithNone() {
        val questions = DecisionTalkingPoints.questions((1..12).map { "Point $it" })
        assertEquals(listOf("points1", "points2", "points3"), questions.keys.toList())
        assertEquals(listOf("1", "4", "7", "10", "none"), questions["points1"]!!.options.keys.toList())
        assertEquals(listOf("3", "6", "9", "12", "none"), questions["points3"]!!.options.keys.toList())
        assertEquals("Point 7", questions["points1"]!!.options["7"])
        assertEquals(DecisionTalkingPoints.NONE_TEXT, questions["points3"]!!.options["none"])
        assertEquals(DecisionTalkingPoints.INSTRUCTIONS, questions["points1"]!!.instructions)
        // Six open points: three and three, never five and one.
        assertEquals(
            listOf(4, 4),
            DecisionTalkingPoints.questions((1..6).map { "P$it" }).values.map { it.options.size }
        )
    }

    @Test
    fun theSessionsOtherPointsFillEachQuestionUpToFive_numberedAfterTheOpenOnes() {
        val questions = DecisionTalkingPoints.questions(
            listOf("Budget"),
            others = listOf("Hiring", "Office", "Roadmap")
        )
        val options = questions.values.single().options
        assertEquals(listOf("1", "2", "3", "4", "none"), options.keys.toList())
        assertEquals(listOf("Budget", "Hiring", "Office", "Roadmap"), options.values.take(4))

        val many = DecisionTalkingPoints.questions(
            (1..6).map { "Open $it" },
            others = (1..10).map { "Done $it" }
        )
        assertTrue(many.values.all { it.options.size == DecisionTalkingPoints.GROUP_SIZE + 1 })
        // Each filler once, until they run out.
        assertEquals(4, many.values.flatMap { it.options.values }.count { it.startsWith("Done") })
    }

    @Test
    fun noOpenPoints_noQuestions() {
        assertEquals(emptyMap<String, Any>(), DecisionTalkingPoints.questions(emptyList(), listOf("Done")))
    }

    @Test
    fun aPointIsCoveredAtTheFirstLineWhereItIsTheSureAnswer() {
        val states = listOf(
            DecisionTalkingPoints.State("a", 1_000L),
            DecisionTalkingPoints.State("b", 2_000L),
            DecisionTalkingPoints.State("c", 3_000L)
        )
        val answers = listOf(
            mapOf("points1" to answer("2", 0.4), "points2" to answer("none", 0.9)),
            mapOf("points1" to answer("2", 0.8), "points2" to answer("6", 0.5)),
            mapOf("points1" to answer("2", 0.99), "points2" to answer("none", 0.9))
        )
        assertEquals(
            listOf(CoveredPoint(2, 2_000L), CoveredPoint(6, 2_000L)),
            DecisionTalkingPoints.covered(states, answers, count = 6)
        )
    }

    @Test
    fun noneAndUnknownOptionsCoverNothing() {
        val states = listOf(DecisionTalkingPoints.State("a", 1_000L))
        val answers = listOf(mapOf("points1" to answer("none", 0.99), "points2" to answer("9", 0.99)))
        assertEquals(emptyList<CoveredPoint>(), DecisionTalkingPoints.covered(states, answers, count = 3))
    }
}
