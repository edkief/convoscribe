package com.convoscribe.data

import com.convoscribe.llm.SummaryTemplate
import com.convoscribe.llm.SummaryTemplates
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Summary templates as the app sees them (issue #27): the built-ins
 * followed by the user's own, each as a [SummaryTemplate]. Built-ins
 * can be duplicated but not edited or deleted; those calls ignore a
 * built-in key.
 */
interface SummaryTemplateStore {

    /** Built-ins first, then user templates in the order they were added. */
    fun observeAll(): Flow<List<SummaryTemplate>>

    /** Snapshot of the user templates only. */
    suspend fun getUserTemplates(): List<SummaryTemplate>

    /** Add a user template; returns its key. Name and body are trimmed. */
    suspend fun add(name: String, body: String): String

    /** Change a user template's name and body; a built-in [key] is ignored. */
    suspend fun update(key: String, name: String, body: String)

    /** Remove a user template; a built-in [key] is ignored. */
    suspend fun delete(key: String)

    /** Copy [template] as a new user template named "<name> (copy)"; returns its key. */
    suspend fun duplicate(template: SummaryTemplate): String =
        add(name = template.name + COPY_SUFFIX, body = template.body)

    companion object {
        const val COPY_SUFFIX: String = " (copy)"
    }
}

/** Room-backed [SummaryTemplateStore]. */
class SummaryTemplateRepository(
    private val dao: SummaryTemplateDao,
    private val clock: () -> Long = System::currentTimeMillis
) : SummaryTemplateStore {

    override fun observeAll(): Flow<List<SummaryTemplate>> =
        dao.observeAll().map { rows -> SummaryTemplates.BUILT_IN + rows.map { it.toTemplate() } }

    override suspend fun getUserTemplates(): List<SummaryTemplate> = dao.getAll().map { it.toTemplate() }

    override suspend fun add(name: String, body: String): String {
        val id = dao.insert(SummaryTemplateEntity(name = name.trim(), body = body.trim(), createdAt = clock()))
        return SummaryTemplates.userKey(id)
    }

    override suspend fun update(key: String, name: String, body: String) {
        val id = SummaryTemplates.userId(key) ?: return
        dao.update(id, name.trim(), body.trim())
    }

    override suspend fun delete(key: String) {
        val id = SummaryTemplates.userId(key) ?: return
        dao.delete(id)
    }

    private fun SummaryTemplateEntity.toTemplate(): SummaryTemplate =
        SummaryTemplate(key = SummaryTemplates.userKey(id), name = name, body = body, builtIn = false)
}
