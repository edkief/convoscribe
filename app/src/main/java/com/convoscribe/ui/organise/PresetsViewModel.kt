package com.convoscribe.ui.organise

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoscribe.data.EnrolledSpeakerStore
import com.convoscribe.data.Preset
import com.convoscribe.data.PresetStore
import com.convoscribe.data.SummaryTemplateStore
import com.convoscribe.llm.SummaryTemplate
import com.convoscribe.llm.SummaryTemplates
import com.convoscribe.recording.ConversationOptions
import com.convoscribe.recording.toOptions
import com.convoscribe.recording.toPreset
import com.convoscribe.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A preset being edited: [id] `0` for a new one, its [name] and the
 * [options] it starts a recording with ([ConversationOptions.title] is
 * the title pattern).
 */
data class PresetDraft(
    val id: Long = 0,
    val name: String = "",
    val options: ConversationOptions
) {
    val canSave: Boolean get() = name.isNotBlank()
}

/**
 * Settings › Presets (issue #32): add, edit and delete saved
 * conversation options. The editor offers the same pickers as the
 * Record screen, so it also reads enrolled names, templates and tags.
 */
class PresetsViewModel(
    private val store: PresetStore,
    private val settings: SettingsRepository,
    enrolled: EnrolledSpeakerStore,
    templates: SummaryTemplateStore,
    tagNames: Flow<List<String>>
) : ViewModel() {

    val presets: StateFlow<List<Preset>> = store.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val enrolledNames: StateFlow<List<String>> = enrolled.observeAll()
        .map { rows -> rows.map { it.name }.distinct() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val summaryTemplates: StateFlow<List<SummaryTemplate>> = templates.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SummaryTemplates.BUILT_IN)

    val allTags: StateFlow<List<String>> = tagNames
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** A new preset starts from the Settings defaults. */
    fun newDraft(): PresetDraft = PresetDraft(options = ConversationOptions.fromSettings(settings.settings.value))

    /** [preset] in the editor; a template deleted since falls back to the default. */
    fun draftOf(preset: Preset): PresetDraft = PresetDraft(
        id = preset.id,
        name = preset.name,
        options = preset.toOptions(
            summaryTemplates.value.map { it.key },
            settings.settings.value.defaultSummaryTemplate
        )
    )

    /** Save [draft] as a new preset or over the one it edits; ignored without a name. */
    fun save(draft: PresetDraft) {
        if (!draft.canSave) return
        viewModelScope.launch { store.save(draft.options.toPreset(draft.name, draft.id)) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { store.delete(id) }
    }
}
