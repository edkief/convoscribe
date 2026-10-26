package com.convoscribe.ui.session

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoscribe.R
import com.convoscribe.TAG
import com.convoscribe.data.MessageKind
import com.convoscribe.data.MessageRole
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SummaryTemplateStore
import com.convoscribe.data.TranscriptFormatter
import com.convoscribe.data.TranscriptLine
import com.convoscribe.llm.AutoTitler
import com.convoscribe.llm.ChatClient
import com.convoscribe.llm.LongSummariser
import com.convoscribe.llm.MarkNotesProgress
import com.convoscribe.llm.MarkNotesWriter
import com.convoscribe.llm.PromptBuilder
import com.convoscribe.llm.SummaryStep
import com.convoscribe.llm.SummaryTemplate
import com.convoscribe.llm.SummaryTemplates
import com.convoscribe.llm.toUserMessage
import com.convoscribe.settings.SettingsRepository
import com.convoscribe.ui.UiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI snapshot for the session Summary tab.
 *
 * @property saved the most recent ASSISTANT SUMMARY message's
 *   content, or `null` when the session has never been summarised.
 * @property streaming partial answer currently streaming from the
 *   LLM. Empty when nothing is running.
 * @property running `true` while a streaming request is in flight;
 *   the screen greys out "Summarise" and exposes a "Stop" button.
 * @property error human-readable failure message; cleared on the
 *   next successful summarise. The previous `saved` summary stays
 *   visible when an error happens, so the user does not lose
 *   already-generated work.
 * @property configured `false` when either the LLM base URL or the
 *   model is blank — the screen swaps to a "Set up a language
 *   model endpoint in Settings" placeholder in that case.
 * @property savedTemplateName name of the template [saved] was
 *   written with (issue #27); `null` for summaries from before
 *   templates.
 * @property templates built-in and user templates for the picker.
 * @property selectedKey template the next summary uses: the session's
 *   own, or the Settings default when it has none.
 * @property step what a summary of a transcript over the budget is
 *   doing (issue #28); `null` for a single-call summary and when
 *   nothing is running.
 * @property notes how writing the full notes of the session's marks
 *   is going (issue #48), from the Summary tab or after speakers were
 *   identified; `null` when nothing is running and nothing failed.
 */
data class SummaryUiState(
    val saved: String? = null,
    val streaming: String = "",
    val running: Boolean = false,
    val error: UiText? = null,
    val configured: Boolean = true,
    val savedTemplateName: String? = null,
    val templates: List<SummaryTemplate> = SummaryTemplates.BUILT_IN,
    val selectedKey: String = SummaryTemplates.MEETING_KEY,
    val step: SummaryStep? = null,
    val notes: MarkNotesProgress? = null
)

/**
 * ViewModel for the Summary tab. Drives a one-shot LLM call that
 * summarises the session transcript and writes the result as an
 * ASSISTANT SUMMARY [ChatMessageEntity]. The UI observes a single
 * [SummaryUiState] `StateFlow`; the repository's chat-message
 * observer is folded into the same state via the saved-flow `combine`
 * inside [seedSavedFromRepository].
 *
 * Behaviour contract (mirrored by the unit tests):
 *
 *  - [summarise] ignores the request when `running == true`.
 *  - The transcript is built from the persisted segments and
 *    speakers through [TranscriptFormatter]. When the rendered
 *    text is blank, `error` is set to the "transcript is empty" text
 *    and the LLM client is **not** called.
 *  - Otherwise the LLM is called with
 *    `PromptBuilder.summary(text, settings.maxTranscriptChars, body)`,
 *    `body` being the selected template's (Meeting when that
 *    template no longer exists). Every token is appended to
 *    `streaming`.
 *  - A transcript longer than `maxTranscriptChars` (issue #28) is
 *    summarised in parts by [LongSummariser] instead, with `step`
 *    following its progress; only the final summary streams into
 *    `streaming`.
 *  - On completion the SUMMARY kind is wiped, the full text is
 *    saved as a fresh ASSISTANT row with the template's name, and
 *    `streaming` / `running` / `step` are reset. A run that fails
 *    or is cancelled part-way saves nothing.
 *  - [selectTemplate] stores the template on the session, so it
 *    sticks for the next summary too.
 *  - Any failure (an LLM transport or HTTP error, or anything
 *    unexpected) sets `error` to
 *    [com.convoscribe.llm.toUserMessage], logs the stack trace
 *    and keeps the old `saved` value.
 *  - [cancel] stops the in-flight `summarise` job; a subsequent
 *    `summarise` call starts a fresh run.
 *
 * @param sessionId primary key of the session to summarise.
 * @param repository façade over the database; provides segments,
 *   speakers and chat messages.
 * @param chatClient LLM streaming client — fakes are accepted
 *   here so the unit test can drive deterministic token sequences.
 * @param settings persisted settings; the summary budget comes
 *   from `maxTranscriptChars`, `configured` is derived from
 *   `llmBaseUrl` / `llmModel`, and `defaultSummaryTemplate` applies
 *   to sessions that have no template of their own.
 * @param templates the summary templates (issue #27).
 * @param markNotes writes the full notes of marks (issue #48);
 *   [writeNotes] and [cancelNotes] do nothing without it, which the
 *   tests that don't cover notes rely on.
 * @param autoTitler names the session from a new summary (issue #32);
 *   nothing happens without it.
 */
class SummaryViewModel(
    private val sessionId: Long,
    private val repository: SessionRepository,
    private val chatClient: ChatClient,
    private val settings: SettingsRepository,
    private val templates: SummaryTemplateStore,
    private val markNotes: MarkNotesWriter? = null,
    private val autoTitler: AutoTitler? = null
) : ViewModel() {

    private val _state: MutableStateFlow<SummaryUiState> =
        MutableStateFlow(initialState())
    val state: StateFlow<SummaryUiState> = _state.asStateFlow()

    /**
     * In-flight summarise job. Tracked so [cancel] can interrupt
     * the streaming flow mid-answer; `null` when nothing is
     * running. The streaming flow's `catch` block does the same
     * bookkeeping on a failure, so the field is
     * always in sync with the UI.
     */
    private var job: Job? = null

    init {
        // Mirror the persisted SUMMARY row into `state.saved` so
        // the user sees their previous summary immediately when
        // the tab opens. The Room observer re-emits whenever a new
        // SUMMARY is inserted (i.e. after a successful summarise),
        // so we never have to push the value ourselves.
        viewModelScope.launch {
            repository.observeMessages(sessionId, MessageKind.SUMMARY).collect { rows ->
                val assistant = rows.lastOrNull { it.role == MessageRole.ASSISTANT }
                _state.value = _state.value.copy(
                    saved = assistant?.content,
                    savedTemplateName = assistant?.templateName
                )
            }
        }
        markNotes?.let { writer ->
            viewModelScope.launch {
                writer.progress.collect { all -> _state.update { it.copy(notes = all[sessionId]) } }
            }
        }
        viewModelScope.launch {
            templates.observeAll().collect { list -> _state.update { it.copy(templates = list) } }
        }
        // The session's own template, or the default while it has none.
        viewModelScope.launch {
            combine(repository.observeSession(sessionId), settings.settings) { session, s ->
                session?.summaryTemplate ?: s.defaultSummaryTemplate
            }.collect { key -> _state.update { it.copy(selectedKey = key) } }
        }
        // Mirror the configured flag from the repository. Settings
        // is a `StateFlow`, so any future edit (the user types a
        // URL in the Settings screen and pops back) flips the
        // placeholder off without a manual refresh.
        viewModelScope.launch {
            settings.settings.collect { s ->
                _state.value = _state.value.copy(
                    configured = s.llmBaseUrl.isNotBlank() && s.llmModel.isNotBlank()
                )
            }
        }
    }

    private fun initialState(): SummaryUiState {
        val s = settings.settings.value
        return SummaryUiState(
            configured = s.llmBaseUrl.isNotBlank() && s.llmModel.isNotBlank()
        )
    }

    /**
     * Use template [key] for this session's summaries from now on.
     * Does not regenerate; the user taps Summarise or Regenerate.
     */
    fun selectTemplate(key: String) {
        _state.update { it.copy(selectedKey = key) }
        viewModelScope.launch { repository.setSummaryTemplate(sessionId, key) }
    }

    /**
     * Drive the streaming summary call. No-op when a previous run
     * is still in flight (`running == true`); otherwise the
     * transcript is rendered, the streaming flow is collected,
     * and the resulting text is persisted as the new SUMMARY
     * message.
     *
     * Errors surface through [SummaryUiState.error]; the previous
     * `saved` summary is preserved so the user does not lose it
     * when the LLM endpoint hiccups mid-run.
     */
    fun summarise() {
        if (_state.value.running) return
        job = viewModelScope.launch {
            val lines = buildLines()
            val transcript = TranscriptFormatter.toText(lines)
            if (transcript.isBlank()) {
                _state.value = _state.value.copy(error = UiText.res(R.string.session_summary_empty_error))
                return@launch
            }
            val maxChars = settings.settings.value.maxTranscriptChars
            val template = SummaryTemplates.resolve(_state.value.selectedKey, templates.getUserTemplates())
            val marks = TranscriptFormatter.marks(repository.getMarks(sessionId))

            _state.value = _state.value.copy(
                streaming = "",
                running = true,
                error = null,
                step = null
            )

            val accumulated = StringBuilder()
            val onToken: (String) -> Unit = { token ->
                accumulated.append(token)
                _state.value = _state.value.copy(streaming = accumulated.toString())
            }
            try {
                if (transcript.length <= maxChars) {
                    chatClient.stream(PromptBuilder.summary(transcript, maxChars, template.body, marks))
                        .collect { onToken(it) }
                } else {
                    LongSummariser.summarise(
                        client = chatClient,
                        lines = lines,
                        maxChars = maxChars,
                        body = template.body,
                        marks = marks,
                        onStep = { step -> _state.update { it.copy(step = step) } },
                        onToken = onToken
                    )
                }

                val full = accumulated.toString()
                // Persist the new summary only when every call
                // completed normally and the last produced at least
                // one token. An empty run leaves the previous saved
                // value untouched.
                if (full.isNotEmpty()) {
                    repository.clearMessages(sessionId, MessageKind.SUMMARY)
                    repository.addMessage(
                        sessionId = sessionId,
                        kind = MessageKind.SUMMARY,
                        role = MessageRole.ASSISTANT,
                        content = full,
                        templateName = template.name
                    )
                    autoTitler?.onSummarySaved(sessionId, full)
                }
                _state.value = _state.value.copy(running = false, streaming = "", step = null)
            } catch (_: kotlinx.coroutines.CancellationException) {
                // `cancel()` aborted the run — reset the flags and
                // leave the persisted summary alone.
                _state.value = _state.value.copy(running = false, streaming = "", step = null)
            } catch (failure: Exception) {
                // Shown, never rethrown: an exception escaping
                // viewModelScope crashes the app. What streamed before
                // the failure is dropped, not saved as the summary.
                Log.w(TAG, "summary: LLM request failed", failure)
                _state.value = _state.value.copy(
                    running = false,
                    streaming = "",
                    step = null,
                    error = UiText.Plain(failure.toUserMessage())
                )
            }
        }
    }

    /**
     * Cancel the in-flight summary request. The underlying
     * OkHttp call in [OpenAiChatClient.stream] is cancelled
     * through the flow's `invokeOnCompletion` hook, so the
     * server stops producing tokens as soon as this returns. The
     * previous `saved` summary (if any) is left untouched.
     */
    fun cancel() {
        job?.cancel()
        job = null
    }

    /** Write (or rewrite) the full note of every mark (issue #48); it goes on if the screen closes. */
    fun writeNotes() {
        markNotes?.start(sessionId)
    }

    /** Stop writing notes, or dismiss a failure; the notes already written stay. */
    fun cancelNotes() {
        markNotes?.cancel(sessionId)
    }

    /**
     * The persisted segments + speakers as transcript lines.
     * Pulls both lists from the repository; runs on the
     * dispatcher [viewModelScope] picks (`Dispatchers.Main.immediate`
     * for tests with `setMain`).
     */
    private suspend fun buildLines(): List<TranscriptLine> =
        TranscriptFormatter.lines(repository.getSegments(sessionId), repository.getSpeakers(sessionId))
}
