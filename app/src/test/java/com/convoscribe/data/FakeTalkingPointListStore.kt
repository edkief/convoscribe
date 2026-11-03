package com.convoscribe.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** In-memory [TalkingPointListStore] for ViewModel tests (issue #102), cleaning like the real one. */
class FakeTalkingPointListStore(initial: List<TalkingPointList> = emptyList()) : TalkingPointListStore {
    val lists = MutableStateFlow(initial)
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0) + 1

    override fun observeAll(): Flow<List<TalkingPointList>> = lists

    override suspend fun add(name: String, points: List<String>): Long {
        val id = nextId++
        lists.update { it + TalkingPointList(id, TalkingPoints.clean(name), TalkingPoints.cleanAll(points)) }
        return id
    }

    override suspend fun update(id: Long, name: String, points: List<String>) = lists.update { all ->
        all.map {
            if (it.id == id) {
                TalkingPointList(
                    id,
                    TalkingPoints.clean(name),
                    TalkingPoints.cleanAll(points)
                )
            } else {
                it
            }
        }
    }

    override suspend fun delete(id: Long) = lists.update { all -> all.filterNot { it.id == id } }
}
