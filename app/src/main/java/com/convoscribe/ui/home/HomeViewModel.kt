package com.convoscribe.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoscribe.data.SessionEntity
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SessionStatus
import com.convoscribe.data.Tag
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One card in the session list. The screen words it: [createdAt] and
 * [durationMs] through [formatSessionDate] / [formatDuration], [status]
 * as a badge, [speakerCount] (0 until speakers are identified) and
 * [language] through [languageTag], and its [tags] (issue #32).
 */
data class SessionRow(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val durationMs: Long,
    val status: SessionStatus,
    val language: String = "en",
    val speakerCount: Int = 0,
    val tags: List<String> = emptyList()
)

/**
 * The tag filter above the session list (issue #32): the [tags] that
 * are on at least one session, and the [selected] ones. A session
 * shows when it has any selected tag; nothing selected shows all.
 */
data class TagFilter(
    val tags: List<Tag> = emptyList(),
    val selected: Set<Long> = emptySet()
) {
    val active: Boolean get() = selected.isNotEmpty()
}

/**
 * ViewModel for the landing screen. Reads the session list from
 * [repository] (already ordered newest first by the DAO) together with
 * each session's speaker count and tags and maps them to
 * [SessionRow]s, keeping those [tagFilter] lets through; also exposes
 * [delete] for the card's Delete action.
 *
 * The list is a `StateFlow` with `WhileSubscribed(5000)` so the
 * upstream Room observer stops collecting when the screen is off the
 * back stack for more than five seconds — the same convention the
 * other ViewModels follow.
 */
class HomeViewModel(
    private val repository: SessionRepository
) : ViewModel() {

    private val selectedTags = MutableStateFlow<Set<Long>>(emptySet())

    private val usedTags = repository.observeTagUsage()
        .map { usage -> usage.filter { it.sessionCount > 0 }.map { Tag(it.id, it.name) } }

    /** Tags to filter by and the chosen ones; a tag that is gone drops out of the choice. */
    val tagFilter: StateFlow<TagFilter> = combine(usedTags, selectedTags) { tags, selected ->
        TagFilter(tags = tags, selected = selected.filterTo(HashSet()) { id -> tags.any { it.id == id } })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TagFilter())

    val sessions: StateFlow<List<SessionRow>> = combine(
        repository.observeAllSessions(),
        repository.observeSpeakerCounts(),
        repository.observeSessionTags(),
        tagFilter
    ) { rows, counts, tags, filter ->
        rows.filter { row -> !filter.active || tags[row.id].orEmpty().any { it.id in filter.selected } }
            .map { it.toRow(counts[it.id] ?: 0, tags[it.id].orEmpty().map { tag -> tag.name }) }
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /** Add tag [id] to the filter, or take it out. */
    fun toggleTag(id: Long) {
        selectedTags.update { if (id in it) it - id else it + id }
    }

    /** Show every session again. */
    fun clearTagFilter() {
        selectedTags.value = emptySet()
    }

    /**
     * Delete [id] and its WAV. Called from the delete confirmation
     * dialog. Idempotent — [SessionRepository.deleteSession] is a no-op
     * for unknown ids.
     */
    fun delete(id: Long) {
        viewModelScope.launch {
            repository.deleteSession(id)
        }
    }
}

private fun SessionEntity.toRow(speakerCount: Int, tags: List<String>): SessionRow = SessionRow(
    id = id,
    title = title,
    createdAt = createdAt,
    durationMs = durationMs,
    status = status,
    language = language,
    speakerCount = speakerCount,
    tags = tags
)

/**
 * Pinned to [Locale.UK] so the day/month order and the abbreviation
 * are stable regardless of the user's device locale.
 */
private val DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK)

/** `"1 Jan 1970, 00:00"`: when the session was recorded, in [zone]. */
fun formatSessionDate(createdAt: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(createdAt), zone).format(DATE_FORMAT)

/**
 * Format a duration in milliseconds as `mm:ss` under an hour, or
 * `h:mm:ss` past an hour. Returns the empty string for a non-positive
 * duration so the card can skip it for an in-flight recording (the
 * recorder stamps the duration when the recording stops).
 */
fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return ""
    val totalSeconds = durationMs / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

/**
 * Short language code shown on a card (`"FR"`), or `null` for the
 * English default (and a blank value), which is not worth a mention.
 */
fun languageTag(language: String): String? =
    if (language == "en" || language.isEmpty()) null else language.uppercase(Locale.ROOT)
