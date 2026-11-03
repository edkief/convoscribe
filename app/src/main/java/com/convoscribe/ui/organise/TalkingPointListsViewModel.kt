package com.convoscribe.ui.organise

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoscribe.data.TalkingPointList
import com.convoscribe.data.TalkingPointListStore
import com.convoscribe.data.TalkingPoints
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

/** Settings › Talking points (issue #102): add, edit and delete talking-point lists. */
class TalkingPointListsViewModel(private val store: TalkingPointListStore) : ViewModel() {

    val lists: StateFlow<List<TalkingPointList>> = store.observeAll()
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
