package com.convoscribe.ui.organise

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoscribe.data.TalkingPointList
import com.convoscribe.data.TalkingPointListStore
import com.convoscribe.data.TalkingPoints
import com.convoscribe.data.UncoveredPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A talking-point list being edited: [id] `0` for a new one, its
 * [name] and its [points] as typed, one per line.
 */
data class TalkingPointListDraft(val id: Long = 0, val name: String = "", val points: String = "") {
    val canSave: Boolean get() = name.isNotBlank()

    companion object {
        fun of(list: TalkingPointList) = TalkingPointListDraft(list.id, list.name, list.points.joinToString("\n"))
    }
}

/**
 * Home's Talking points section (issues #102, #108): add, edit and
 * delete talking-point lists, and the points recent conversations did
 * not cover, from [uncovered].
 */
class TalkingPointListsViewModel(
    private val store: TalkingPointListStore,
    uncovered: Flow<List<UncoveredPoint>> = flowOf(emptyList())
) : ViewModel() {

    val lists: StateFlow<List<TalkingPointList>> = store.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** *Not covered last time* (issue #108): newest conversation first. */
    val notCovered: StateFlow<List<UncoveredPoint>> = uncovered
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Save [draft] as a new list or over the one it edits; ignored without a name. */
    fun save(draft: TalkingPointListDraft) {
        if (!draft.canSave) return
        val points = TalkingPoints.fromLines(draft.points)
        viewModelScope.launch {
            if (draft.id == 0L) store.add(draft.name, points) else store.update(draft.id, draft.name, points)
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { store.delete(id) }
    }
}
