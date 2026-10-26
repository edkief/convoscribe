package com.convoscribe.ui.organise

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.convoscribe.ConvoApp
import com.convoscribe.R
import com.convoscribe.data.ParticipantsCodec
import com.convoscribe.data.Preset
import com.convoscribe.data.PresetStore
import com.convoscribe.llm.SummaryTemplate
import com.convoscribe.ui.components.AppCard
import com.convoscribe.ui.components.ConfirmDialog
import com.convoscribe.ui.components.ScreenScaffold
import com.convoscribe.ui.components.SectionCard
import com.convoscribe.ui.components.SupportingText
import com.convoscribe.ui.record.CleanupOption
import com.convoscribe.ui.record.ConversationOptionsForm
import com.convoscribe.ui.theme.AppTheme
import kotlinx.coroutines.flow.map

/** Settings › Presets (issue #32), the `presets` route. */
@Composable
fun PresetsScreen(navController: NavHostController) {
    val container = (LocalContext.current.applicationContext as ConvoApp).container
    val vm: PresetsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                PresetsViewModel(
                    store = container.presets,
                    settings = container.settingsRepository,
                    enrolled = container.enrolledSpeakers,
                    templates = container.summaryTemplates,
                    tagNames = container.sessionRepository.observeTags().map { tags -> tags.map { it.name } }
                )
            }
        }
    )
    val presets by vm.presets.collectAsStateWithLifecycle()
    val enrolledNames by vm.enrolledNames.collectAsStateWithLifecycle()
    val templates by vm.summaryTemplates.collectAsStateWithLifecycle()
    val allTags by vm.allTags.collectAsStateWithLifecycle()
    PresetsBody(
        presets = presets,
        enrolledNames = enrolledNames,
        summaryTemplates = templates,
        allTags = allTags,
        cleanup = remember { container.audioCleanerFactory.isAvailable() }.takeIf { it }?.let { CleanupOption() },
        onBack = { navController.popBackStack() },
        newDraft = vm::newDraft,
        draftOf = vm::draftOf,
        onSave = vm::save,
        onDelete = vm::delete
    )
}

/**
 * The preset list with New preset, or the editor for one: its name,
 * then the same options as the Record screen, the title field holding
 * a pattern. Delete asks first.
 *
 * @param initialDraft editor to show on first composition (screenshots).
 */
@Composable
internal fun PresetsBody(
    presets: List<Preset>,
    enrolledNames: List<String>,
    summaryTemplates: List<SummaryTemplate>,
    allTags: List<String>,
    cleanup: CleanupOption?,
    onBack: () -> Unit,
    newDraft: () -> PresetDraft,
    draftOf: (Preset) -> PresetDraft,
    onSave: (PresetDraft) -> Unit,
    onDelete: (Long) -> Unit,
    initialDraft: PresetDraft? = null
) {
    var draft by remember { mutableStateOf(initialDraft) }
    var deleting by remember { mutableStateOf<Preset?>(null) }
    val editing = draft
    if (editing != null) {
        BackHandler { draft = null }
        PresetEditor(
            draft = editing,
            enrolledNames = enrolledNames,
            summaryTemplates = summaryTemplates,
            allTags = allTags,
            cleanup = cleanup,
            onChange = { draft = it },
            onSave = {
                onSave(editing)
                draft = null
            },
            onCancel = { draft = null }
        )
    } else {
        PresetList(
            presets = presets,
            summaryTemplates = summaryTemplates,
            onBack = onBack,
            onNew = { draft = newDraft() },
            onEdit = { draft = draftOf(it) },
            onDelete = { deleting = it }
        )
    }
    deleting?.let { preset ->
        ConfirmDialog(
            title = stringResource(R.string.presets_delete_title, preset.name),
            message = stringResource(R.string.presets_delete_message),
            confirmLabel = stringResource(R.string.tags_delete),
            dismissLabel = stringResource(R.string.conversation_cancel),
            onConfirm = {
                onDelete(preset.id)
                deleting = null
            },
            onDismiss = { deleting = null },
            destructive = true
        )
    }
}

@Composable
private fun PresetList(
    presets: List<Preset>,
    summaryTemplates: List<SummaryTemplate>,
    onBack: () -> Unit,
    onNew: () -> Unit,
    onEdit: (Preset) -> Unit,
    onDelete: (Preset) -> Unit
) {
    val spacing = AppTheme.spacing
    ScreenScaffold(title = stringResource(R.string.presets_title), onBack = onBack) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = spacing.screen, vertical = spacing.s),
            verticalArrangement = Arrangement.spacedBy(spacing.m),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item(key = "intro") {
                Column(
                    modifier = Modifier
                        .widthIn(max = AppTheme.sizes.contentMaxWidth)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(spacing.s)
                ) {
                    SupportingText(text = stringResource(R.string.presets_explanation))
                    Button(onClick = onNew) { Text(text = stringResource(R.string.presets_new)) }
                    if (presets.isEmpty()) SupportingText(text = stringResource(R.string.presets_empty))
                }
            }
            items(presets, key = { it.id }) { preset ->
                PresetRow(
                    preset = preset,
                    templateName = summaryTemplates.firstOrNull { it.key == preset.summaryTemplate }?.name,
                    onEdit = { onEdit(preset) },
                    onDelete = { onDelete(preset) }
                )
            }
        }
    }
}

/** Name, title pattern, then language · participants · tags · template, with Edit and Delete. */
@Composable
private fun PresetRow(preset: Preset, templateName: String?, onEdit: () -> Unit, onDelete: () -> Unit) {
    val spacing = AppTheme.spacing
    val details = listOfNotNull(
        stringResource(
            if (preset.language == "fr") R.string.conversation_language_fr else R.string.conversation_language_en
        ),
        preset.participants?.joinToString(", "),
        preset.tags.takeIf { it.isNotEmpty() }?.joinToString(", "),
        templateName
    ).joinToString(" · ")
    AppCard(modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth).fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(start = spacing.l, end = spacing.l, top = spacing.m),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            Text(text = preset.name, style = MaterialTheme.typography.titleMedium)
            if (preset.titlePattern.isNotBlank()) {
                Text(
                    text = stringResource(R.string.presets_title_pattern, preset.titlePattern),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.s),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onEdit) { Text(text = stringResource(R.string.presets_edit)) }
            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text(text = stringResource(R.string.tags_delete))
            }
        }
    }
}

@Composable
private fun PresetEditor(
    draft: PresetDraft,
    enrolledNames: List<String>,
    summaryTemplates: List<SummaryTemplate>,
    allTags: List<String>,
    cleanup: CleanupOption?,
    onChange: (PresetDraft) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val spacing = AppTheme.spacing
    val options = draft.options
    ScreenScaffold(
        title = stringResource(if (draft.id == 0L) R.string.presets_new_title else R.string.presets_edit_title),
        onBack = onCancel,
        actions = {
            TextButton(onClick = onSave, enabled = draft.canSave) {
                Text(text = stringResource(R.string.session_rename_save))
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screen, vertical = spacing.s),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SectionCard(
                title = stringResource(R.string.presets_section_preset),
                modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth)
            ) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { onChange(draft.copy(name = it.take(PresetStore.NAME_MAX_LEN))) },
                    label = { Text(text = stringResource(R.string.presets_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                ConversationOptionsForm(
                    options = options,
                    enrolledNames = enrolledNames,
                    summaryTemplates = summaryTemplates,
                    onLanguageChange = { onChange(draft.copy(options = options.copy(language = it))) },
                    onNumSpeakersChange = { onChange(draft.copy(options = options.copy(numSpeakers = it))) },
                    onParticipantsChange = {
                        onChange(draft.copy(options = options.copy(participants = ParticipantsCodec.normalise(it))))
                    },
                    onTitleChange = { onChange(draft.copy(options = options.copy(title = it))) },
                    onSummaryTemplateChange = { onChange(draft.copy(options = options.copy(summaryTemplate = it))) },
                    onSaveAsDefault = null,
                    cleanup = cleanup,
                    onCleanAudioChange = { onChange(draft.copy(options = options.copy(cleanAudio = it))) },
                    allTags = allTags,
                    onTagsChange = { onChange(draft.copy(options = options.withTags(it))) }
                )
            }
        }
    }
}
