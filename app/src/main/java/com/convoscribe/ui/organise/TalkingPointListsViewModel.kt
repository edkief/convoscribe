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
 * A talking-point list being edited (issue #111): [id] `0` for a new
 * one, its [name] and its [points] as shown, one row each. There is
 * always at least one row, blank when the list has no points yet.
 */
data class TalkingPointListDraft(val id: Long = 0, val name: String = "", val points: List<String> = listOf("")) {
    val canSave: Boolean get() = name.isNotBlank()

    /** The points as saved: in the order shown, cleaned, without blanks. */
    val savedPoints: List<String> get() = TalkingPoints.cleanAll(points)

    /** Point [index] set to [text], kept on one line and capped at [TalkingPoints.MAX_LEN]. */
    fun withPoint(index: Int, text: String): TalkingPointListDraft {
        if (index !in points.indices) return this
        val line = text.replace('\n', ' ').take(TalkingPoints.MAX_LEN)
        return copy(points = points.toMutableList().also { it[index] = line })
    }

    /** A blank point after [after], at the end by default. */
    fun addPoint(after: Int = points.lastIndex): TalkingPointListDraft {
        val at = (after + 1).coerceIn(0, points.size)
        return copy(points = points.toMutableList().also { it.add(at, "") })
    }

    /** Point [index] moved [by] rows (`-1` up, `1` down); unchanged when that would leave the list. */
    fun movePoint(index: Int, by: Int): TalkingPointListDraft {
        val to = index + by
        if (index !in points.indices || to !in points.indices || to == index) return this
        return copy(points = points.toMutableList().also { it.add(to, it.removeAt(index)) })
    }

    /** Point [index] removed; removing the only one leaves a blank row. */
    fun removePoint(index: Int): TalkingPointListDraft {
        if (index !in points.indices) return this
        return copy(points = points.filterIndexed { i, _ -> i != index }.ifEmpty { listOf("") })
    }

    companion object {
        fun of(list: TalkingPointList) = TalkingPointListDraft(list.id, list.name, list.points.ifEmpty { listOf("") })
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
        val points = draft.savedPoints
        viewModelScope.launch {
            if (draft.id == 0L) store.add(draft.name, points) else store.update(draft.id, draft.name, points)
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { store.delete(id) }
    }
}
