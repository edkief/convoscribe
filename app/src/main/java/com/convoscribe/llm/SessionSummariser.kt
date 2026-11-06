package com.convoscribe.llm

import com.convoscribe.data.MessageKind
import com.convoscribe.data.MessageRole
import com.convoscribe.data.ParticipantsCodec
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SummaryTemplateStore
import com.convoscribe.data.TranscriptFormatter
import com.convoscribe.settings.SettingsRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Writes a session's summary: the transcript goes to the LLM with the
 * session's template ([PromptBuilder.summary], or [LongSummariser] for
 * a transcript over the budget), the action items (issue #31) are split
 * off the answer, and both are saved. A new summary may then title the
 * session ([AutoTitler], issue #32).
 *
 * [summarise] is the Summary tab's *Summarise* / *Regenerate*, which
 * streams into the screen.
 */
class SessionSummariser(
    private val repository: SessionRepository,
    private val chatClient: ChatClient,
    private val settings: SettingsRepository,
    private val templates: SummaryTemplateStore,
    private val autoTitler: AutoTitler? = null
) {
    /** How a [summarise] run ended without an error. */
    enum class Outcome {
        /** The summary and its action items were saved. */
        SAVED,

        /** The session has no transcript to summarise; nothing was sent. */
        EMPTY_TRANSCRIPT,

        /** The LLM answered nothing; the previous summary stays. */
        NO_ANSWER
    }

    /**
     * Summarise [sessionId] with the template [templateKey] (a built-in
     * key or `user:<name>`; one that no longer exists resolves to
     * Meeting). [onStarted] is called once the request is about to go
     * out, [onToken] with each streamed piece, [onStep] with the parts
     * of a long transcript. The summary is saved only when the whole run
     * completed and produced text.
     *
     * Failures and cancellation propagate to the caller.
     */
    suspend fun summarise(
        sessionId: Long,
        templateKey: String,
        onStarted: () -> Unit = {},
        onToken: (String) -> Unit = {},
        onStep: (SummaryStep) -> Unit = {}
    ): Outcome {
        val lines = TranscriptFormatter.lines(repository.getSegments(sessionId), repository.getSpeakers(sessionId))
        val transcript = TranscriptFormatter.toText(lines)
        if (transcript.isBlank()) return Outcome.EMPTY_TRANSCRIPT
        val maxChars = settings.settings.value.maxTranscriptChars
        val template = SummaryTemplates.resolve(templateKey, templates.getUserTemplates())
        val marks = TranscriptFormatter.marks(repository.getMarks(sessionId))
        val session = repository.getSession(sessionId)
        val recordedOn = session?.createdAt?.let(::recordingDate)
        onStarted()

        val accumulated = StringBuilder()
        val collect: (String) -> Unit = { token ->
            accumulated.append(token)
            onToken(token)
        }
        if (transcript.length <= maxChars) {
            chatClient.stream(PromptBuilder.summary(transcript, maxChars, template.body, marks, recordedOn))
                .collect { collect(it) }
        } else {
            LongSummariser.summarise(
                client = chatClient,
                lines = lines,
                maxChars = maxChars,
                body = template.body,
                marks = marks,
                onStep = onStep,
                onToken = collect,
                recordedOn = recordedOn
            )
        }

        val parsed = ActionItemsParser.split(accumulated.toString(), knownNames(sessionId))
        val full = parsed.prose
        if (full.isEmpty()) return Outcome.NO_ANSWER
        repository.replaceExtractedItems(sessionId, parsed.items)
        repository.clearMessages(sessionId, MessageKind.SUMMARY)
        repository.addMessage(
            sessionId = sessionId,
            kind = MessageKind.SUMMARY,
            role = MessageRole.ASSISTANT,
            content = full,
            templateName = template.name
        )
        autoTitler?.onSummarySaved(sessionId, full)
        return Outcome.SAVED
    }

    /** The session's speaker names and participants, that action item owners are matched to. */
    private suspend fun knownNames(sessionId: Long): List<String> = (
        repository.getSpeakers(sessionId).map { it.label } +
            ParticipantsCodec.decode(repository.getSession(sessionId)?.participants).orEmpty()
        ).filter { it.isNotBlank() }.distinct()

    private companion object {
        /** The local day a session recorded at [createdAt] (epoch ms) happened on. */
        fun recordingDate(createdAt: Long): LocalDate =
            Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
    }
}
