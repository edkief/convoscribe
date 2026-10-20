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
import com.convoscribe.llm.ChatClient
import com.convoscribe.llm.PromptBuilder
import com.convoscribe.llm.SummaryTemplate
import com.convoscribe.llm.SummaryTemplates
import com.convoscribe.llm.toUserMessage
import com.convoscribe.settings.SettingsRepository
import com.convoscribe.ui.UiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
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
 */
data class SummaryUiState(
    val saved: String? = null,
    val streaming: String = "",
    val running: Boolean = false,
    val error: UiText? = null,
    val configured: Boolean = true,
    val savedTemplateName: String? = null,
    val templates: List<SummaryTemplate> = SummaryTemplates.BUILT_IN,
    val selectedKey: String = SummaryTemplates.MEETING_KEY
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
 *  - On completion the SUMMARY kind is wiped, the full text is
 *    saved as a fresh ASSISTANT row with the template's name, and
 *    `streaming` / `running` are reset.
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
 */
class SummaryViewModel(
    private val sessionId: Long,
    private val repository: SessionRepository,
    private val chatClient: ChatClient,
    private val settings: SettingsRepository,
    private val templates: SummaryTemplateStore
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
            val transcript = buildTranscript()
            if (transcript.isBlank()) {
                _state.value = _state.value.copy(error = UiText.res(R.string.session_summary_empty_error))
                return@launch
            }
            val maxChars = settings.settings.value.maxTranscriptChars
            val template = SummaryTemplates.resolve(_state.value.selectedKey, templates.getUserTemplates())
            val messages = PromptBuilder.summary(transcript, maxChars, template.body)

            _state.value = _state.value.copy(
                streaming = "",
                running = true,
                error = null
            )

            val accumulated = StringBuilder()
            try {
                chatClient.stream(messages)
                    .onEach { token ->
                        accumulated.append(token)
                        _state.value = _state.value.copy(streaming = accumulated.toString())
                    }
                    .catch { failure ->
                        // Cancellation is handled by the surrounding
                        // coroutine machinery — it does not flip
                        // `error` because the user explicitly asked
                        // to stop. Any other failure is shown, never
                        // rethrown: an exception escaping
                        // viewModelScope crashes the app.
                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                        Log.w(TAG, "summary: LLM request failed", failure)
                        _state.value = _state.value.copy(
                            running = false,
                            streaming = "",
                            error = UiText.Plain(failure.toUserMessage())
                        )
                    }
                    .collect()

                val full = accumulated.toString()
                // Persist the new summary only when the flow
                // completed normally and produced at least one
                // token. A cancelled or empty run leaves the
                // previous saved value untouched.
                if (full.isNotEmpty()) {
                    repository.clearMessages(sessionId, MessageKind.SUMMARY)
                    repository.addMessage(
                        sessionId = sessionId,
                        kind = MessageKind.SUMMARY,
                        role = MessageRole.ASSISTANT,
                        content = full,
                        templateName = template.name
                    )
                }
                _state.value = _state.value.copy(
                    running = false,
                    streaming = ""
                )
            } catch (_: kotlinx.coroutines.CancellationException) {
                // `cancel()` aborted the run — reset the flags and
                // leave the persisted summary alone.
                _state.value = _state.value.copy(
                    running = false,
                    streaming = ""
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

    /**
     * Render the persisted segments + speakers as plain text.
     * Pulls both lists from the repository; runs on the
     * dispatcher [viewModelScope] picks (`Dispatchers.Main.immediate`
     * for tests with `setMain`).
     */
    private suspend fun buildTranscript(): String {
        val segments = repository.getSegments(sessionId)
        val speakers = repository.getSpeakers(sessionId)
        val lines = TranscriptFormatter.lines(segments, speakers)
        return TranscriptFormatter.toText(lines)
    }
}
