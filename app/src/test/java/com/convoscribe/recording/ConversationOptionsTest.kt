package com.convoscribe.recording

import com.convoscribe.data.Preset
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.TitleSource
import com.convoscribe.llm.SummaryTemplates
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

/** Session titles from [ConversationOptions], and presets mapped to and from options (issue #32). */
class ConversationOptionsTest {

    private val now: Long = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.MARCH, 4, 9, 5)
    }.timeInMillis

    @Test
    fun blankTitle_isTheDateDefault() {
        val options = ConversationOptions(language = "en", title = "  ")
        assertEquals("Recording 2026-03-04 09:05", options.sessionTitle(now))
        assertEquals(TitleSource.DEFAULT, options.titleSource)
    }

    @Test
    fun pattern_fillsInDateAndTime() {
        val options = ConversationOptions(language = "en", title = " Weekly sync {date} at {time} ")
        assertEquals("Weekly sync 2026-03-04 at 09:05", options.sessionTitle(now))
        assertEquals(TitleSource.USER, options.titleSource)
    }

    @Test
    fun title_isCappedAfterExpansion() {
        val options = ConversationOptions(language = "en", title = "x".repeat(95) + " {date}")
        val title = options.sessionTitle(now)
        assertEquals(SessionRepository.SESSION_TITLE_MAX_LEN, title.length)
        assertEquals(" 2026", title.takeLast(5))
    }

    @Test
    fun withTags_cleansNames() {
        val options = ConversationOptions(language = "en").withTags(listOf(" Acme ", "acme", "", "Weekly"))
        assertEquals(listOf("Acme", "Weekly"), options.tags)
    }

    @Test
    fun preset_roundTripsThroughOptions() {
        val options = ConversationOptions(
            language = "fr",
            numSpeakers = 3,
            participants = listOf("Alice"),
            title = "Standup {date}",
            summaryTemplate = SummaryTemplates.INTERVIEW_KEY,
            cleanAudio = true,
            tags = listOf("Acme")
        )
        val preset = options.toPreset("Standup", id = 4)
        assertEquals(4L, preset.id)
        assertEquals("Standup {date}", preset.titlePattern)
        assertEquals(options, preset.toOptions(SummaryTemplates.BUILT_IN.map { it.key }, SummaryTemplates.MEETING_KEY))
    }

    @Test
    fun preset_withDeletedTemplate_fallsBackToTheDefault() {
        val preset = Preset(name = "P", language = "en", summaryTemplate = "user:99")
        val options = preset.toOptions(SummaryTemplates.BUILT_IN.map { it.key }, SummaryTemplates.INTERVIEW_KEY)
        assertEquals(SummaryTemplates.INTERVIEW_KEY, options.summaryTemplate)
    }
}
