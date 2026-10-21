package com.convoscribe.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.llm.SummaryTemplates
import com.convoscribe.recording.ConversationOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Record screen's options survive the trip through [RecordingService.startIntent]. */
@RunWith(RobolectricTestRunner::class)
class RecordingServiceIntentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun startIntent_carriesEveryOption() {
        val options = ConversationOptions(
            language = "fr",
            numSpeakers = 3,
            participants = listOf("Alice"),
            title = "Cafe",
            summaryTemplate = SummaryTemplates.INTERVIEW_KEY,
            cleanAudio = true
        )
        val read = RecordingService.optionsFrom(RecordingService.startIntent(context, options = options))!!
        assertEquals(options, read)
        assertEquals(
            false,
            RecordingService.optionsFrom(
                RecordingService.startIntent(context, options = options.copy(cleanAudio = false))
            )!!.cleanAudio
        )
    }

    @Test
    fun startIntent_withoutOptions_meansTheSettingsDefaults() {
        assertNull(RecordingService.optionsFrom(RecordingService.startIntent(context)))
    }
}
