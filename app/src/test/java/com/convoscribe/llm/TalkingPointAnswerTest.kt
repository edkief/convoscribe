package com.convoscribe.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TalkingPointAnswerTest {

    private fun parse(answer: String, count: Int = 3) = TalkingPointAnswer.parse(answer, count)

    @Test
    fun readsPointsAndTimes() {
        assertEquals(
            listOf(CoveredPoint(2, 754_000L), CoveredPoint(3, 3_723_000L)),
            parse("""[{"point": 2, "at": "12:34"}, {"point": 3, "at": "[1:02:03]"}]""")
        )
    }

    @Test
    fun anEmptyArrayIsNothingCovered() {
        assertEquals(emptyList<CoveredPoint>(), parse("[]"))
    }

    @Test
    fun aCodeFenceProseAndAReasoningBlockAreFine() {
        val answer = "<think>Point [1] is about the budget, maybe.</think>\nHere you go:\n" +
            "```json\n[{\"point\": 1, \"at\": \"00:40\"}]\n```\nHope that helps."
        assertEquals(listOf(CoveredPoint(1, 40_000L)), parse(answer))
    }

    @Test
    fun aReasoningBlockWithoutItsStartIsDropped() {
        assertEquals(listOf(CoveredPoint(2)), parse("thinking about [1]...</think>[2]"))
    }

    @Test
    fun bareAndQuotedNumbersAreAccepted() {
        assertEquals(listOf(CoveredPoint(1), CoveredPoint(3)), parse("[1, \"3\"]"))
        assertEquals(listOf(CoveredPoint(2)), parse("""[{"point": "2"}]"""))
    }

    @Test
    fun outOfRangeAndRepeatedNumbersAreDropped() {
        assertEquals(
            listOf(CoveredPoint(2, 5_000L)),
            parse("""[{"point": 0}, {"point": 2, "at": "00:05"}, {"point": 4}, {"point": 2, "at": "00:09"}, 7]""")
        )
    }

    @Test
    fun anAtThatIsNoTimeIsIgnored() {
        assertEquals(listOf(CoveredPoint(1)), parse("""[{"point": 1, "at": "early on"}]"""))
        assertEquals(listOf(CoveredPoint(1)), parse("""[{"point": 1, "at": null}]"""))
    }

    @Test
    fun objectsWithoutAPointAreSkipped() {
        assertEquals(listOf(CoveredPoint(3)), parse("""[{"at": "00:01"}, {"point": "three"}, {"point": 3}]"""))
    }

    @Test
    fun proseWithoutNumbersMeansNothingCovered() {
        assertEquals(emptyList<CoveredPoint>(), parse("None of the points were discussed."))
        assertEquals(emptyList<CoveredPoint>(), parse(""))
    }

    @Test
    fun unreadableAnswersAreNull() {
        assertNull(parse("Points 1 and 2 were covered."))
        assertNull(parse("[{\"point\": 1, "))
        assertNull(parse("""{"point": 1}"""))
        assertNull(parse("<think>Still thinking about [1"))
    }
}
