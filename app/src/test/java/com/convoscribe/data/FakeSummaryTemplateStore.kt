package com.convoscribe.data

import com.convoscribe.llm.SummaryTemplate
import com.convoscribe.llm.SummaryTemplates
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [SummaryTemplateStore] for view-model tests. */
class FakeSummaryTemplateStore(initial: List<SummaryTemplate> = emptyList()) : SummaryTemplateStore {
    private var nextId = 100L
    val user = MutableStateFlow(initial)

    override fun observeAll(): Flow<List<SummaryTemplate>> = user.map { SummaryTemplates.BUILT_IN + it }

    override suspend fun getUserTemplates(): List<SummaryTemplate> = user.value

    override suspend fun add(name: String, body: String): String {
        val key = SummaryTemplates.userKey(nextId++)
        user.value = user.value + SummaryTemplate(key, name.trim(), body.trim(), builtIn = false)
        return key
    }

    override suspend fun update(key: String, name: String, body: String) {
        user.value = user.value.map { if (it.key == key) it.copy(name = name.trim(), body = body.trim()) else it }
    }

    override suspend fun delete(key: String) {
        user.value = user.value.filterNot { it.key == key }
    }
}
