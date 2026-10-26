package com.convoscribe.recording

import com.convoscribe.data.ParticipantsCodec
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.TagNames
import com.convoscribe.data.TitleSource
import com.convoscribe.llm.SummaryTemplates
import com.convoscribe.settings.AppSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What a new conversation is set up with before Start: chosen on the
 * Record screen, pre-filled from the Settings defaults, and stored on
 * the session so continuing or re-identifying it never reads the
 * current Settings again.
 *
 * Built to be shared by every way a session is created (recording
 * now, file import in #3).
 *
 * @property language ASR language, `"en"` or `"fr"`.
 * @property numSpeakers `-1` automatic, `1..10` fixed.
 * @property participants enrolled names expected in the conversation,
 *   or `null` for anyone.
 * @property title optional; blank keeps the date-based default.
 *   `{date}` and `{time}` stand for the day and time recording starts
 *   (issue #32), so a preset can carry a pattern.
 * @property summaryTemplate summary template key (issue #27).
 * @property cleanAudio clean the audio live while recording (issue
 *   #37); ignored when the cleanup model is not installed.
 * @property tags tag names put on the session (issue #32), created
 *   when missing.
 */
data class ConversationOptions(
    val language: String,
    val numSpeakers: Int = -1,
    val participants: List<String>? = null,
    val title: String = "",
    val summaryTemplate: String = SummaryTemplates.MEETING_KEY,
    val cleanAudio: Boolean = false,
    val tags: List<String> = emptyList()
) {
    /**
     * Session title for these options: [title] with `{date}`
     * (`yyyy-MM-dd`) and `{time}` (`HH:mm`) filled in for [nowMs],
     * trimmed and capped at [SessionRepository.SESSION_TITLE_MAX_LEN],
     * or `"Recording yyyy-MM-dd HH:mm"` when blank.
     */
    fun sessionTitle(nowMs: Long): String {
        val trimmed = title.trim()
        val date = Date(nowMs)
        if (trimmed.isEmpty()) {
            return "Recording " + format(DATE_TIME, date)
        }
        return trimmed
            .replace(DATE_TOKEN, format(DATE, date))
            .replace(TIME_TOKEN, format(TIME, date))
            .take(SessionRepository.SESSION_TITLE_MAX_LEN)
            .trim()
    }

    /** Where [sessionTitle] comes from: the date-based default when [title] is blank, else the user. */
    val titleSource: String
        get() = if (title.isBlank()) TitleSource.DEFAULT else TitleSource.USER

    /** These options with [names] as the tags, cleaned like the stored ones. */
    fun withTags(names: List<String>): ConversationOptions = copy(tags = TagNames.clean(names))

    companion object {
        const val DATE_TOKEN: String = "{date}"
        const val TIME_TOKEN: String = "{time}"
        private const val DATE = "yyyy-MM-dd"
        private const val TIME = "HH:mm"
        private const val DATE_TIME = "$DATE $TIME"

        private fun format(pattern: String, date: Date): String = SimpleDateFormat(pattern, Locale.US).format(date)

        /** The defaults a new conversation starts from. */
        fun fromSettings(settings: AppSettings): ConversationOptions = ConversationOptions(
            language = settings.asrLanguage,
            numSpeakers = settings.numSpeakers,
            participants = ParticipantsCodec.normalise(settings.defaultParticipants),
            summaryTemplate = settings.defaultSummaryTemplate,
            cleanAudio = settings.cleanAudioDefault
        )
    }
}
