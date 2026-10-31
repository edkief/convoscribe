package com.convoscribe.ui.actions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoscribe.data.OpenActionItem
import com.convoscribe.data.SearchText
import com.convoscribe.data.SessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How *Open actions* lists the items (issue #31). */
enum class ActionsGrouping { SESSION, OWNER }

/**
 * One heading of *Open actions* and its items: a session (with
 * [sessionId] and [createdAt]) or an owner; [title] is `null` for the
 * items without an owner.
 */
data class ActionGroup(
    val title: String?,
    val items: List<OpenActionItem>,
    val sessionId: Long? = null,
    val createdAt: Long? = null
)

/**
 * @property loaded `false` until the items were read once, so the empty
 *   state does not flash.
 */
data class ActionsUiState(
    val grouping: ActionsGrouping = ActionsGrouping.SESSION,
    val groups: List<ActionGroup> = emptyList(),
    val loaded: Boolean = false
)

/**
 * *Open actions* (issue #31): the action items neither done nor
 * deleted across every session, by session (newest first) or by owner
 * (alphabetical, the items without one last). Ticking one off takes it
 * off the list.
 */
class ActionsViewModel(private val repository: SessionRepository) : ViewModel() {

    private val grouping = MutableStateFlow(ActionsGrouping.SESSION)

    val state: StateFlow<ActionsUiState> =
        combine(repository.observeOpenActionItems(), grouping) { items, by ->
            ActionsUiState(grouping = by, groups = group(items, by), loaded = true)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ActionsUiState())

    fun setGrouping(by: ActionsGrouping) {
        grouping.value = by
    }

    fun setDone(itemId: Long, done: Boolean) {
        viewModelScope.launch { repository.setActionItemDone(itemId, done) }
    }

    fun edit(itemId: Long, text: String, owner: String, dueDate: String?) {
        viewModelScope.launch { repository.editActionItem(itemId, text, owner, dueDate) }
    }

    fun delete(itemId: Long) {
        viewModelScope.launch { repository.deleteActionItem(itemId) }
    }

    companion object {
        /**
         * [items] (newest session first, then in each summary's order)
         * under their headings. Owners are told apart ignoring case and
         * accents, and shown as first spelled.
         */
        fun group(items: List<OpenActionItem>, by: ActionsGrouping): List<ActionGroup> = when (by) {
            ActionsGrouping.SESSION -> items.groupBy { it.item.sessionId }.map { (sessionId, inSession) ->
                val first = inSession.first()
                ActionGroup(first.sessionTitle, inSession, sessionId = sessionId, createdAt = first.sessionCreatedAt)
            }
            ActionsGrouping.OWNER -> {
                val byOwner = items.groupBy { it.item.owner?.let { owner -> SearchText.fold(owner.trim()) } }
                val named = byOwner.filterKeys { it != null }.entries
                    .map { (_, owned) -> ActionGroup(owned.first().item.owner!!.trim(), owned) }
                    .sortedBy { SearchText.fold(it.title!!) }
                named + listOfNotNull(byOwner[null]?.let { ActionGroup(null, it) })
            }
        }
    }
}
