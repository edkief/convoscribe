package com.convoscribe.ui.record

import com.convoscribe.R
import com.convoscribe.recording.RetranscribeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the Record screen says above Start about an earlier session's work (issue #123). */
class StartNoticeTest {

    @Test
    fun aReTranscriptionOnThePhone_isStoppedByRecording() {
        assertEquals(
            R.string.record_notice_stops_retranscription,
            startNoticeRes(RetranscribeState.Running(1L, 0.4f))
        )
        // Cleaning the audio runs on the phone too, even when the server transcribes next.
        assertEquals(
            R.string.record_notice_stops_retranscription,
            startNoticeRes(RetranscribeState.Running(1L, 0.1f, cleaning = true, onServer = true))
        )
    }

    @Test
    fun theServersTranscript_carriesOnWithoutANotice() {
        assertNull(startNoticeRes(RetranscribeState.Running(1L, 0.4f, onServer = true)))
    }

    @Test
    fun nothingRunning_noNotice() {
        assertNull(startNoticeRes(RetranscribeState.Idle))
        assertNull(startNoticeRes(RetranscribeState.Failed(1L, "down")))
    }
}
