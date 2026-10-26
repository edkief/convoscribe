package com.convoscribe.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [PresetStore] for view-model tests; saves names and tags as given. */
class FakePresetStore(initial: List<Preset> = emptyList()) : PresetStore {
    private var nextId = 100L
    val presets = MutableStateFlow(initial)

    override fun observeAll(): Flow<List<Preset>> = presets

    override suspend fun get(id: Long): Preset? = presets.value.firstOrNull { it.id == id }

    override suspend fun save(preset: Preset): Long {
        if (preset.id == 0L) {
            val id = nextId++
            presets.value = presets.value + preset.copy(id = id)
            return id
        }
        presets.value = presets.value.map { if (it.id == preset.id) preset else it }
        return preset.id
    }

    override suspend fun delete(id: Long) {
        presets.value = presets.value.filterNot { it.id == id }
    }
}
