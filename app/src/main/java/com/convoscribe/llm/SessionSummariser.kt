package com.convoscribe.llm

import android.util.Log
import com.convoscribe.TAG
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Writes a session's summary: the transcript goes to the LLM with the
 * session's template ([PromptBuilder.summary], or [LongSummariser] for
 * a transcript over the budget), the action items (issue #31) are split
 * off the answer, and both are saved. A new summary may then title the
 * session ([AutoTitler], issue #32).
 *
 * [summarise] is the Summary tab's *Summarise* / *Regenerate*, which
 * streams into the screen. [onFinished] is the automatic path (issue
 * #29): once a session is finished, it is summarised in the app's
 * [scope] if *Summarise automatically* is on, an endpoint is set up and
 * it has no summary yet. The automatic run takes [calls], the lock that
 * keeps one background LLM call at a time ([MarkNotesWriter],
 * [AutoTitler]); the tab's run does not wait for it, as before.
 */
class SessionSummariser(
    private val repository: SessionRepository,
    private val chatClient: ChatClient,
    private val settings: SettingsRepository,
    private val templates: SummaryTemplateStore,
    private val autoTitler: AutoTitler? = null,
    private val scope: CoroutineScope? = null,
    private val calls: Mutex = Mutex()
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

    private val jobs = mutableMapOf<Long, Job>()

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

    /**
     * [sessionId] is finished (its speakers identified, or left for the
     * user to identify): summarise it if *Summarise automatically* is
     * on, an endpoint is set up and it has no summary yet. Returns the
     * run, or `null` when there is none to start. A failure is logged
     * and otherwise ignored; the user can still summarise by hand.
     */
    fun onFinished(sessionId: Long): Job? {
        val current = settings.settings.value
        if (!current.autoSummary || !current.llmConfigured) return null
        val scope = scope ?: return null
        synchronized(jobs) {
            if (jobs[sessionId]?.isActive == true) return null
            val job = scope.launch { summariseIfMissing(sessionId) }
            jobs[sessionId] = job
            job.invokeOnCompletion { synchronized(jobs) { if (jobs[sessionId] === job) jobs.remove(sessionId) } }
            return job
        }
    }

    private suspend fun summariseIfMissing(sessionId: Long) {
        try {
            val outcome = calls.withLock {
                val hasSummary = repository.getMessages(sessionId, MessageKind.SUMMARY)
                    .any { it.role == MessageRole.ASSISTANT }
                val session = repository.getSession(sessionId)
                if (hasSummary || session == null) return
                val key = session.summaryTemplate ?: settings.settings.value.defaultSummaryTemplate
                summarise(sessionId, key)
            }
            Log.i(TAG, "auto summary session=$sessionId outcome=$outcome")
        } catch (ce: CancellationException) {
            throw ce
        } catch (failure: Exception) {
            Log.w(TAG, "auto summary: LLM request failed session=$sessionId", failure)
        }
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
