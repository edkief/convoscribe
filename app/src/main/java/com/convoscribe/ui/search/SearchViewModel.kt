package com.convoscribe.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoscribe.data.SearchFilters
import com.convoscribe.data.SearchOptions
import com.convoscribe.data.SearchResult
import com.convoscribe.data.SearchText
import com.convoscribe.data.SessionSearch
import com.convoscribe.llm.embedding.SemanticSearch
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update

/** The date filter's presets. */
enum class DateRange {
    ANY,
    WEEK,
    MONTH,
    YEAR;

    /** Earliest `createdAt` this range lets through, at [now] in [zone]. */
    fun since(now: Long, zone: ZoneId): Long = when (this) {
        ANY -> 0L
        WEEK -> now - 7 * DAY_MS
        MONTH -> now - 30 * DAY_MS
        YEAR -> ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
            .withDayOfYear(1).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

/** What the filter chips hold: `null` language, speaker or tag means any. */
data class FilterState(
    val language: String? = null,
    val dateRange: DateRange = DateRange.ANY,
    val speaker: String? = null,
    val tagId: Long? = null
) {
    val isActive: Boolean
        get() = language != null || dateRange != DateRange.ANY || speaker != null || tagId != null
}

/** The results of searching [query]; an empty [query] is "nothing searched yet". */
data class SearchResults(val query: String = "", val items: List<SearchResult> = emptyList())

/**
 * ViewModel for the search screen (issue #24). The query searches as
 * the user types, once they pause for [debounceMs] (the screen passes
 * a longer pause on e-ink so a typing burst redraws once); [submit]
 * (the keyboard's Search key) searches straight away, and a filter
 * change applies at once. [results] keeps the previous results until
 * the new ones are ready, and does not re-emit identical ones, so the
 * list does not flash in between.
 *
 * With the semantic search model ([semantic], issue #63) the word
 * results come first, then the same results with what was found by
 * meaning once the query is embedded, so exact matches never wait for
 * the model to load.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val search: SessionSearch,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val semantic: SemanticSearch? = null
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filters = MutableStateFlow(FilterState())
    val filters: StateFlow<FilterState> = _filters.asStateFlow()

    private val submits = MutableSharedFlow<String>(extraBufferCapacity = 1)

    val results: StateFlow<SearchResults> = combine(
        merge(_query.debounce(debounceMs), submits),
        _filters
    ) { query, filters -> query.trim() to filters }
        .distinctUntilChanged()
        .transformLatest { (query, filters) ->
            if (query.isEmpty()) {
                emit(SearchResults(query))
                return@transformLatest
            }
            val searchFilters = filters.toSearchFilters()
            emit(SearchResults(query, search.search(query, searchFilters)))
            if (SearchText.terms(query).isEmpty()) return@transformLatest
            val matches = semantic?.match(query)
            if (matches != null && matches.sessions.isNotEmpty()) {
                emit(SearchResults(query, search.search(query, searchFilters, matches)))
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SearchResults())

    /** Languages and people the filters offer, read once when the screen opens. */
    val options: StateFlow<SearchOptions> = flow { emit(search.options()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SearchOptions())

    fun onQueryChange(query: String) {
        _query.value = query
    }

    /** The keyboard's Search key: search now instead of after the pause. */
    fun submit() {
        submits.tryEmit(_query.value)
    }

    fun setLanguage(language: String?) = _filters.update { it.copy(language = language) }

    fun setDateRange(range: DateRange) = _filters.update { it.copy(dateRange = range) }

    fun setSpeaker(speaker: String?) = _filters.update { it.copy(speaker = speaker) }

    /** Only sessions with tag [tagId] (issue #32); `null` any. */
    fun setTag(tagId: Long?) = _filters.update { it.copy(tagId = tagId) }

    override fun onCleared() {
        semantic?.close()
    }

    private fun FilterState.toSearchFilters() = SearchFilters(
        language = language,
        since = dateRange.since(clock(), zone),
        speaker = speaker,
        tagIds = setOfNotNull(tagId)
    )

    companion object {
        /** Pause after the last keystroke before searching. */
        const val DEBOUNCE_MS: Long = 300L

        /** The same on e-ink, long enough that a typing burst refreshes the panel once. */
        const val EINK_DEBOUNCE_MS: Long = 800L
    }
}
