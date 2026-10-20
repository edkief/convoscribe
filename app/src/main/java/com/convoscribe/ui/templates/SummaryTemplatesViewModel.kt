package com.convoscribe.ui.templates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoscribe.data.SummaryTemplateStore
import com.convoscribe.llm.SummaryTemplate
import com.convoscribe.llm.SummaryTemplates
import com.convoscribe.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A template being written in the editor: [key] is `null` for a new
 * one, else the user template being edited.
 */
data class TemplateDraft(
    val key: String? = null,
    val name: String = "",
    val body: String = ""
) {
    /** Both fields are needed; the editor's Save is disabled until then. */
    val canSave: Boolean get() = name.isNotBlank() && body.isNotBlank()
}

/**
 * Settings › Summaries › Templates (issue #27): add, edit, duplicate
 * and delete user templates. Built-ins can only be duplicated; the
 * store ignores edits and deletes of a built-in key.
 */
class SummaryTemplatesViewModel(
    private val store: SummaryTemplateStore,
    private val settings: SettingsRepository
) : ViewModel() {

    /** Built-ins first, then the user's templates. */
    val templates: StateFlow<List<SummaryTemplate>> = store.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SummaryTemplates.BUILT_IN)

    /** Add [draft] as a new template, or save it over the one it edits. Ignored unless [TemplateDraft.canSave]. */
    fun save(draft: TemplateDraft) {
        if (!draft.canSave) return
        viewModelScope.launch {
            val key = draft.key
            if (key == null) store.add(draft.name, draft.body) else store.update(key, draft.name, draft.body)
        }
    }

    fun duplicate(template: SummaryTemplate) {
        viewModelScope.launch { store.duplicate(template) }
    }

    /**
     * Delete user template [key]. When it was the default for new
     * conversations, the default goes back to Meeting.
     */
    fun delete(key: String) {
        viewModelScope.launch {
            store.delete(key)
            if (settings.settings.value.defaultSummaryTemplate == key) {
                settings.update { it.copy(defaultSummaryTemplate = SummaryTemplates.MEETING_KEY) }
            }
        }
    }
}
