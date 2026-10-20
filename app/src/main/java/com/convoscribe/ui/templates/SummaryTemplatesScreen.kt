package com.convoscribe.ui.templates

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.convoscribe.llm.PromptBuilder
import com.convoscribe.llm.SummaryTemplate
import com.convoscribe.ui.components.AppCard
import com.convoscribe.ui.components.ConfirmDialog
import com.convoscribe.ui.components.ScreenScaffold
import com.convoscribe.ui.components.SectionHeader
import com.convoscribe.ui.components.SupportingText
import com.convoscribe.ui.theme.AppTheme

/** Settings › Summaries › Templates (issue #27). */
@Composable
fun SummaryTemplatesScreen(navController: NavHostController) {
    val container = (LocalContext.current.applicationContext as ConvoApp).container
    val vm: SummaryTemplatesViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SummaryTemplatesViewModel(store = container.summaryTemplates, settings = container.settingsRepository)
            }
        }
    )
    val templates by vm.templates.collectAsStateWithLifecycle()
    SummaryTemplatesBody(
        templates = templates,
        onBack = { navController.popBackStack() },
        onSave = vm::save,
        onDuplicate = vm::duplicate,
        onDelete = vm::delete
    )
}

/**
 * The template list: built-ins (Duplicate only) then the user's own
 * (Edit, Duplicate, Delete), with New template on top. Edit and New
 * open [TemplateEditorDialog]; Delete asks first.
 *
 * @param initialDraft editor to show on first composition (screenshots).
 */
@Composable
internal fun SummaryTemplatesBody(
    templates: List<SummaryTemplate>,
    onBack: () -> Unit,
    onSave: (TemplateDraft) -> Unit,
    onDuplicate: (SummaryTemplate) -> Unit,
    onDelete: (String) -> Unit,
    initialDraft: TemplateDraft? = null
) {
    val spacing = AppTheme.spacing
    var draft by remember { mutableStateOf(initialDraft) }
    var deleting by remember { mutableStateOf<SummaryTemplate?>(null) }
    val (builtIn, mine) = templates.partition { it.builtIn }
    ScreenScaffold(title = stringResource(R.string.templates_title), onBack = onBack) { innerPadding: PaddingValues ->
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
                    SupportingText(text = stringResource(R.string.templates_explanation))
                    Button(onClick = { draft = TemplateDraft() }) {
                        Text(text = stringResource(R.string.templates_new))
                    }
                }
            }
            item(key = "mine-header") { Header(stringResource(R.string.templates_section_mine)) }
            if (mine.isEmpty()) {
                item(key = "mine-empty") {
                    SupportingText(
                        text = stringResource(R.string.templates_mine_empty),
                        modifier = Modifier
                            .widthIn(max = AppTheme.sizes.contentMaxWidth)
                            .fillMaxWidth()
                    )
                }
            }
            items(mine, key = { it.key }) { template ->
                TemplateRow(
                    template = template,
                    onEdit = { draft = TemplateDraft(template.key, template.name, template.body) },
                    onDuplicate = { onDuplicate(template) },
                    onDelete = { deleting = template }
                )
            }
            item(key = "builtin-header") { Header(stringResource(R.string.templates_section_builtin)) }
            items(builtIn, key = { it.key }) { template ->
                TemplateRow(
                    template = template,
                    onEdit = null,
                    onDuplicate = { onDuplicate(template) },
                    onDelete = null
                )
            }
        }
    }

    draft?.let { current ->
        TemplateEditorDialog(
            draft = current,
            onSave = {
                onSave(it)
                draft = null
            },
            onDismiss = { draft = null }
        )
    }
    deleting?.let { template ->
        ConfirmDialog(
            title = stringResource(R.string.templates_delete_title),
            message = stringResource(R.string.templates_delete_message, template.name),
            confirmLabel = stringResource(R.string.templates_delete),
            dismissLabel = stringResource(R.string.templates_cancel),
            onConfirm = {
                onDelete(template.key)
                deleting = null
            },
            onDismiss = { deleting = null },
            destructive = true
        )
    }
}

@Composable
private fun Header(title: String) {
    SectionHeader(
        title = title,
        modifier = Modifier
            .widthIn(max = AppTheme.sizes.contentMaxWidth)
            .fillMaxWidth()
            .padding(top = AppTheme.spacing.s)
    )
}

/** Name, the start of the body, and the actions; `null` hides Edit / Delete. */
@Composable
private fun TemplateRow(
    template: SummaryTemplate,
    onEdit: (() -> Unit)?,
    onDuplicate: () -> Unit,
    onDelete: (() -> Unit)?
) {
    val spacing = AppTheme.spacing
    AppCard(modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth).fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(start = spacing.l, end = spacing.l, top = spacing.m),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            Text(text = template.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = template.body.trim(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(modifier = Modifier.padding(horizontal = spacing.s)) {
            if (onEdit != null) {
                TextButton(onClick = onEdit) { Text(text = stringResource(R.string.templates_edit)) }
            }
            TextButton(onClick = onDuplicate) { Text(text = stringResource(R.string.templates_duplicate)) }
            if (onDelete != null) {
                TextButton(onClick = onDelete) { Text(text = stringResource(R.string.templates_delete)) }
            }
        }
    }
}

/**
 * Name and instructions of a template. The fixed opening and
 * guardrails every summary prompt gets are shown read-only around the
 * instructions, so the user sees they are kept.
 */
@Composable
internal fun TemplateEditorDialog(draft: TemplateDraft, onSave: (TemplateDraft) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(draft.name) }
    var body by rememberSaveable { mutableStateOf(draft.body) }
    val edited = draft.copy(name = name, body = body)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(if (draft.key == null) R.string.templates_new else R.string.templates_edit_title)
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(NAME_MAX_LEN) },
                    label = { Text(text = stringResource(R.string.templates_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                FixedPromptText(text = PromptBuilder.SUMMARY_PREAMBLE.trim())
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text(text = stringResource(R.string.templates_body_label)) },
                    placeholder = { Text(text = stringResource(R.string.templates_body_placeholder)) },
                    minLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                FixedPromptText(text = PromptBuilder.SUMMARY_GUARDRAILS)
                SupportingText(text = stringResource(R.string.templates_fixed_help))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(edited) }, enabled = edited.canSave) {
                Text(text = stringResource(R.string.templates_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.templates_cancel))
            }
        }
    )
}

@Composable
private fun FixedPromptText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private const val NAME_MAX_LEN = 60
