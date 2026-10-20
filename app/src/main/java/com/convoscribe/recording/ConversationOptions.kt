package com.convoscribe.recording

import com.convoscribe.data.ParticipantsCodec
import com.convoscribe.data.SessionRepository
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
 * @property summaryTemplate summary template key (issue #27).
 * @property cleanAudio clean the audio live while recording (issue
 *   #37); ignored when the cleanup model is not installed.
 */
data class ConversationOptions(
    val language: String,
    val numSpeakers: Int = -1,
    val participants: List<String>? = null,
    val title: String = "",
    val summaryTemplate: String = SummaryTemplates.MEETING_KEY,
    val cleanAudio: Boolean = false
) {
    /**
     * Session title for these options: [title] trimmed and capped at
     * [SessionRepository.SESSION_TITLE_MAX_LEN], or
     * `"Recording yyyy-MM-dd HH:mm"` at [nowMs] when blank.
     */
    fun sessionTitle(nowMs: Long): String {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) {
            return "Recording " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(nowMs))
        }
        return trimmed.take(SessionRepository.SESSION_TITLE_MAX_LEN)
    }

    companion object {
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
