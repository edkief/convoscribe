package com.convoscribe.ui.ask

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoscribe.TAG
import com.convoscribe.data.GlobalChatCodec
import com.convoscribe.data.GlobalChatMessageEntity
import com.convoscribe.data.MessageRole
import com.convoscribe.data.SearchFilters
import com.convoscribe.data.SearchOptions
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SessionSearch
import com.convoscribe.data.TranscriptFormatter
import com.convoscribe.llm.ChatClient
import com.convoscribe.llm.Citation
import com.convoscribe.llm.CitationParser
import com.convoscribe.llm.CrossSessionRetriever
import com.convoscribe.llm.LlmMessage
import com.convoscribe.llm.PromptBuilder
import com.convoscribe.llm.SessionSource
import com.convoscribe.llm.toUserMessage
import com.convoscribe.settings.SettingsRepository
import com.convoscribe.ui.search.DateRange
import com.convoscribe.ui.search.FilterState
import com.convoscribe.ui.session.StreamPacer
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A question or answer in *Ask across sessions*, as the screen shows
 * it: an answer's [citations], and how many excerpts from how many
 * sessions it was based on.
 */
data class AskAcrossMessage(
    val id: Long,
    val role: MessageRole,
    val content: String,
    val citations: List<Citation> = emptyList(),
    val excerptCount: Int? = null,
    val sessionCount: Int? = null
)

/**
 * UI snapshot for *Ask across sessions* (issue #49). As the Ask tab's
 * [com.convoscribe.ui.session.AskUiState], plus the filter chips'
 * [filters] and [options], and the ids of the sessions that still
 * exist ([sessionIds], `null` until known), so a citation of a deleted
 * session shows as such.
 */
data class AskAcrossUiState(
    val messages: List<AskAcrossMessage> = emptyList(),
    val streaming: String = "",
    val running: Boolean = false,
    val error: String? = null,
    val configured: Boolean = true,
    val filters: FilterState = FilterState(),
    val options: SearchOptions = SearchOptions(),
    val sessionIds: Set<Long>? = null
)

/**
 * ViewModel for *Ask across sessions* (issue #49): a chat with the
 * LLM about every past conversation, narrowed by the Search screen's
 * filters. Each question goes through [SessionSearch.candidates] (the
 * sessions whose lines share the most words with it),
 * [CrossSessionRetriever] (the excerpts of those that fit the budget)
 * and [PromptBuilder.questionAcrossSessions]; the answer's citations are
 * matched against the excerpts sent with [CitationParser] and saved
 * with it.
 *
 * The rest follows [com.convoscribe.ui.session.AskViewModel]: the
 * question is saved first (with the filters it was asked with), a
 * failure keeps it and shows the error, [retryLast] resends it with
 * its filters, [cancel] stops the answer, and [clearHistory] drops the
 * whole history.
 */
class AskAcrossViewModel(
    private val repository: SessionRepository,
    private val search: SessionSearch,
    private val chatClient: ChatClient,
    private val settings: SettingsRepository,
    private val streamIntervalMs: Long = 0L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault()
) : ViewModel() {

    private val _state = MutableStateFlow(AskAcrossUiState(configured = configured()))
    val state: StateFlow<AskAcrossUiState> = _state.asStateFlow()

    /** Raw history rows, for the prompt's history and [retryLast]'s filters. */
    private var rows: List<GlobalChatMessageEntity> = emptyList()

    private var job: Job? = null

    init {
        viewModelScope.launch {
            repository.observeGlobalMessages().collect { list ->
                rows = list
                _state.update { it.copy(messages = list.map { row -> row.toMessage() }) }
            }
        }
        viewModelScope.launch {
            repository.observeAllSessions().collect { sessions ->
                _state.update { it.copy(sessionIds = sessions.mapTo(HashSet()) { s -> s.id }) }
            }
        }
        viewModelScope.launch {
            settings.settings.collect { _state.update { it.copy(configured = configured()) } }
        }
        viewModelScope.launch {
            val options = search.options()
            _state.update { it.copy(options = options) }
        }
    }

    private fun configured(): Boolean {
        val s = settings.settings.value
        return s.llmBaseUrl.isNotBlank() && s.llmModel.isNotBlank()
    }

    fun setLanguage(language: String?) = _state.update { it.copy(filters = it.filters.copy(language = language)) }

    fun setDateRange(range: DateRange) = _state.update { it.copy(filters = it.filters.copy(dateRange = range)) }

    fun setSpeaker(speaker: String?) = _state.update { it.copy(filters = it.filters.copy(speaker = speaker)) }

    fun setTag(tagId: Long?) = _state.update { it.copy(filters = it.filters.copy(tagId = tagId)) }

    /**
     * Ask [question] with the current filters. No-op when it is blank or
     * an answer is still streaming.
     */
    fun ask(question: String) {
        val trimmed = question.trim()
        if (trimmed.isBlank() || _state.value.running) return
        val filters = _state.value.filters.toSearchFilters()
        job = viewModelScope.launch {
            val history = history()
            repository.addGlobalMessage(role = MessageRole.USER, content = trimmed, filters = filters)
            answer(trimmed, history, filters)
        }
    }

    /** Resend the last question, with the filters it was asked with, without saving it again. */
    fun retryLast() {
        if (_state.value.running) return
        val lastUser = rows.lastOrNull { it.role == MessageRole.USER } ?: return
        val filters = GlobalChatCodec.decodeFilters(lastUser.filters) ?: _state.value.filters.toSearchFilters()
        _state.update { it.copy(error = null) }
        job = viewModelScope.launch { answer(lastUser.content, history(), filters) }
    }

    /** Stop the answer being streamed; the question stays so it can be retried. */
    fun cancel() {
        job?.cancel()
        job = null
    }

    fun clearHistory() {
        viewModelScope.launch { repository.clearGlobalMessages() }
    }

    private fun history(): List<LlmMessage> = rows.map { row ->
        when (row.role) {
            MessageRole.USER -> LlmMessage(LlmMessage.ROLE_USER, row.content)
            MessageRole.ASSISTANT -> LlmMessage(LlmMessage.ROLE_ASSISTANT, row.content)
        }
    }

    /**
     * Pick the excerpts for [question] (and the question before it, for
     * follow-ups) within [filters], stream the answer and save it with
     * its citations.
     */
    private suspend fun answer(question: String, history: List<LlmMessage>, filters: SearchFilters) {
        _state.update { it.copy(streaming = "", running = true, error = null) }
        val accumulated = StringBuilder()
        val pacer = StreamPacer(streamIntervalMs)
        try {
            val previous = history.lastOrNull { it.role == LlmMessage.ROLE_USER && it.content != question }?.content
            val lookup = listOfNotNull(question, previous).joinToString(" ")
            val sources = search.candidates(lookup, filters).map { candidate ->
                val session = candidate.session
                SessionSource(
                    id = session.id,
                    title = session.title,
                    createdAt = session.createdAt,
                    language = session.language,
                    lines = TranscriptFormatter.lines(repository.getSegments(session.id), candidate.speakers),
                    summary = candidate.summary
                )
            }
            val excerpts = CrossSessionRetriever.select(
                sources = sources,
                question = question,
                maxChars = settings.settings.value.maxTranscriptChars,
                previous = previous,
                speaker = filters.speaker,
                zone = zone
            )
            var failed = false
            chatClient.stream(PromptBuilder.questionAcrossSessions(excerpts, history, question))
                .onEach { token ->
                    accumulated.append(token)
                    if (pacer.due()) _state.update { it.copy(streaming = accumulated.toString()) }
                }
                .catch { failure ->
                    // Cancellation ("Stop") is not an error; anything
                    // else is shown, never rethrown, so the app survives.
                    if (failure is CancellationException) throw failure
                    Log.w(TAG, "askAcross: LLM request failed", failure)
                    failed = true
                    _state.update { it.copy(running = false, streaming = "", error = failure.toUserMessage()) }
                }
                .collect()

            val full = accumulated.toString()
            if (!failed && full.isNotEmpty()) {
                repository.addGlobalMessage(
                    role = MessageRole.ASSISTANT,
                    content = full,
                    citations = CitationParser.parse(full, excerpts),
                    excerptCount = excerpts.excerptCount,
                    sessionCount = excerpts.sessionCount
                )
            }
            _state.update { it.copy(running = false, streaming = "") }
        } catch (_: CancellationException) {
            _state.update { it.copy(running = false, streaming = "") }
        }
    }

    private fun FilterState.toSearchFilters() = SearchFilters(
        language = language,
        since = dateRange.since(clock(), zone),
        speaker = speaker,
        tagIds = setOfNotNull(tagId)
    )

    private fun GlobalChatMessageEntity.toMessage() = AskAcrossMessage(
        id = id,
        role = role,
        content = content,
        citations = GlobalChatCodec.decodeCitations(citations),
        excerptCount = excerptCount,
        sessionCount = sessionCount
    )
}
