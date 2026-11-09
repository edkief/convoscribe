package com.convoscribe.ui.record

import com.convoscribe.R
import com.convoscribe.llm.AssistMiss
import com.convoscribe.llm.AssistMissReason
import com.convoscribe.llm.AssistStatus
import com.convoscribe.llm.Citation
import com.convoscribe.llm.LiveAssistState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAssistUiTest {

    @Test
    fun oneStatusLineAtATime() {
        val on = LiveAssistState(sessionId = 1L, enabled = true)
        assertNull(assistStatusLine(LiveAssistState(sessionId = 1L)))
        assertEquals(AssistStatusLine(R.string.record_assist_listening), assistStatusLine(on))
        assertEquals(
            AssistStatusLine(R.string.record_assist_checking),
            assistStatusLine(on.copy(status = AssistStatus.CHECKING))
        )
        assertEquals(
            AssistStatusLine(R.string.record_assist_unreachable),
            assistStatusLine(on.copy(enabled = false, status = AssistStatus.UNREACHABLE))
        )
        val missed = AssistMiss("Who signs off on the budget", AssistMissReason.NO_ANSWER)
        assertEquals(
            "paused says more than the question",
            AssistStatusLine(R.string.record_assist_paused),
            assistStatusLine(on.copy(status = AssistStatus.PAUSED, answering = "When is the launch", missed = missed))
        )
    }

    @Test
    fun theQuestionBeingAnsweredComesBeforeTheLastMiss() {
        val on = LiveAssistState(sessionId = 1L, enabled = true)
        val missed = AssistMiss("Who signs off on the budget", AssistMissReason.NO_ANSWER)
        val answering = assistStatusLine(on.copy(answering = "When is the launch", missed = missed))
        assertEquals(AssistStatusLine(R.string.record_assist_answering, "When is the launch"), answering)
        assertTrue(answering!!.answering)
        assertEquals(
            AssistStatusLine(R.string.record_assist_no_answer, "Who signs off on the budget"),
            assistStatusLine(on.copy(missed = missed))
        )
        assertEquals(
            AssistStatusLine(R.string.record_assist_failed, "Who signs off on the budget"),
            assistStatusLine(on.copy(missed = missed.copy(reason = AssistMissReason.FAILED)))
        )
        assertFalse(assistStatusLine(on.copy(missed = missed))!!.answering)
    }

    @Test
    fun aLongQuestionIsCutOnAWord() {
        val short = "When is the launch date again"
        assertEquals(short, clipQuestion(short))
        val long = "So we remind me? Can you remind me which cloud platform we were going to use for IESB and why"
        val clipped = clipQuestion(long)
        assertTrue(clipped.length <= STATUS_QUESTION_CHARS + 1)
        assertEquals("So we remind me? Can you remind me which cloud platform we were going to use…", clipped)
    }

    @Test
    fun aCitationShowsItsSessionDayAndTime() {
        assertEquals(
            "Planning — 2026-10-01 · 01:12",
            citeText(Citation(5L, "Planning", "2026-10-01", segmentId = 9L, atMs = 72_000L))
        )
        assertEquals("Planning — 2026-10-01", citeText(Citation(5L, "Planning", "2026-10-01")))
    }
}
