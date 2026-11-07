package com.convoscribe.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** How a talking-point check asks the user's own model for little reasoning (issue #136). */
class TalkingPointReasoningTest {

    @Test
    fun parseFields_blankIsNone_aJsonObjectIsKept_anythingElseIsRefused() {
        assertEquals("{}", TalkingPointReasoning.parseFields(" ").toString())
        assertEquals("""{"a":1}""", TalkingPointReasoning.parseFields("""{"a": 1}""").toString())
        for (bad in listOf("{a: 1}", "[1]", "\"low\"", "null", "1", "{")) {
            assertNull(bad, TalkingPointReasoning.parseFields(bad))
        }
    }

    @Test
    fun ownEndpointFields_isLowReasoningEffort_withTheExtraFieldsOnTop() {
        assertEquals("""{"reasoning_effort":"low"}""", TalkingPointReasoning.ownEndpointFields("").toString())
        assertEquals(
            """{"reasoning_effort":"minimal","chat_template_kwargs":{"reasoning_strength":"none"}}""",
            TalkingPointReasoning.ownEndpointFields(
                """{"reasoning_effort": "minimal", "chat_template_kwargs": {"reasoning_strength": "none"}}"""
            ).toString()
        )
    }

    @Test
    fun ownEndpointFields_aNullRemovesAField_andInvalidExtraAddsNothing() {
        assertEquals(
            """{"chat_template_kwargs":{"enable_thinking":false}}""",
            TalkingPointReasoning.ownEndpointFields(
                """{"reasoning_effort": null, "chat_template_kwargs": {"enable_thinking": false}}"""
            ).toString()
        )
        assertEquals("""{"reasoning_effort":"low"}""", TalkingPointReasoning.ownEndpointFields("{oops").toString())
    }
}
