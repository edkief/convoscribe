package com.convoscribe.ui.record

import com.convoscribe.R
import com.convoscribe.llm.AssistStatus
import com.convoscribe.llm.Citation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveAssistUiTest {

    @Test
    fun oneStatusLineAtATime() {
        assertNull(assistStatusText(AssistStatus.IDLE, enabled = false))
        assertEquals(R.string.record_assist_listening, assistStatusText(AssistStatus.IDLE, enabled = true))
        assertEquals(R.string.record_assist_checking, assistStatusText(AssistStatus.CHECKING, enabled = true))
        assertEquals(R.string.record_assist_unreachable, assistStatusText(AssistStatus.UNREACHABLE, enabled = false))
        assertEquals(R.string.record_assist_paused, assistStatusText(AssistStatus.PAUSED, enabled = true))
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
