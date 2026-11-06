package com.convoscribe.ui.session

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.convoscribe.R
import com.convoscribe.export.ExportFormat
import com.convoscribe.export.ExportOptions
import com.convoscribe.export.ExportSection
import com.convoscribe.settings.AppSettings
import com.convoscribe.ui.theme.AppTheme

/**
 * What the Export dialog has chosen (issue #29): the [format], the
 * [sections] ticked and whether to save in the export folder rather than
 * share. Sections the format cannot hold stay ticked but do not count.
 */
data class ExportChoices(
    val format: ExportFormat = ExportFormat.MARKDOWN,
    val sections: Set<ExportSection> = ExportSection.entries.toSet(),
    val toFolder: Boolean = false
) {
    val options: ExportOptions get() = ExportOptions(format, sections)

    fun toggle(section: ExportSection): ExportChoices =
        copy(sections = if (section in sections) sections - section else sections + section)

    /** Whether [section] can be ticked for this format. */
    fun allows(section: ExportSection): Boolean = section in format.sections

    /** Something to export, and a folder to save it in when saving. */
    fun canExport(folderName: String?): Boolean = options.valid && (!toFolder || !folderName.isNullOrBlank())

    companion object {
        /** The choices the dialog was last left with. */
        fun from(settings: AppSettings): ExportChoices = ExportChoices(
            format = ExportFormat.of(settings.exportFormat),
            sections = ExportSection.parse(settings.exportSections),
            toFolder = settings.exportToFolder
        )
    }
}

/** The session screen's export hooks. [folderName] is the export folder's name, `null` until one is picked. */
data class ExportUi(
    val initial: ExportChoices = ExportChoices(),
    val folderName: String? = null,
    val onExport: (ExportChoices) -> Unit = {},
    val onPickFolder: () -> Unit = {}
)

@StringRes
private fun ExportFormat.label(): Int = when (this) {
    ExportFormat.TEXT -> R.string.export_format_text
    ExportFormat.MARKDOWN -> R.string.export_format_markdown
    ExportFormat.SRT -> R.string.export_format_srt
    ExportFormat.VTT -> R.string.export_format_vtt
    ExportFormat.JSON -> R.string.export_format_json
    ExportFormat.DOCX -> R.string.export_format_docx
    ExportFormat.PDF -> R.string.export_format_pdf
}

@StringRes
private fun ExportSection.label(): Int = when (this) {
    ExportSection.TRANSCRIPT -> R.string.export_section_transcript
    ExportSection.SUMMARY -> R.string.export_section_summary
    ExportSection.QUESTIONS -> R.string.export_section_questions
}

/**
 * *Export…* (issue #29): the format, what to include and where it goes,
 * the share sheet or the export folder (picked once, then remembered).
 */
@Composable
internal fun ExportDialog(ui: ExportUi, onDismiss: () -> Unit) {
    var formatKey by rememberSaveable { mutableStateOf(ui.initial.format.key) }
    var sectionKeys by rememberSaveable { mutableStateOf(ExportSection.encode(ui.initial.sections)) }
    var toFolder by rememberSaveable { mutableStateOf(ui.initial.toFolder) }
    val choices = ExportChoices(ExportFormat.of(formatKey), ExportSection.parse(sectionKeys), toFolder)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.export_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)
            ) {
                Text(text = stringResource(R.string.export_format), style = MaterialTheme.typography.titleSmall)
                ExportFormat.entries.forEach { format ->
                    ChoiceRow(
                        label = stringResource(format.label()),
                        selected = format == choices.format,
                        role = Role.RadioButton,
                        onClick = { formatKey = format.key }
                    )
                }
                Text(
                    text = stringResource(R.string.export_include),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = AppTheme.spacing.s)
                )
                ExportSection.entries.forEach { section ->
                    ChoiceRow(
                        label = stringResource(section.label()),
                        selected = section in choices.sections && choices.allows(section),
                        role = Role.Checkbox,
                        enabled = choices.allows(section),
                        onClick = { sectionKeys = ExportSection.encode(choices.toggle(section).sections) }
                    )
                }
                if (choices.format.sections.size == 1) {
                    Text(
                        text = stringResource(R.string.export_subtitles_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = stringResource(R.string.export_destination),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = AppTheme.spacing.s)
                )
                ChoiceRow(
                    label = stringResource(R.string.export_destination_share),
                    selected = !toFolder,
                    role = Role.RadioButton,
                    onClick = { toFolder = false }
                )
                ChoiceRow(
                    label = ui.folderName?.let { stringResource(R.string.export_destination_folder_named, it) }
                        ?: stringResource(R.string.export_destination_folder),
                    selected = toFolder,
                    role = Role.RadioButton,
                    onClick = { toFolder = true }
                )
                if (toFolder) {
                    TextButton(onClick = ui.onPickFolder) {
                        val label = when (ui.folderName) {
                            null -> R.string.export_choose_folder
                            else -> R.string.export_change_folder
                        }
                        Text(text = stringResource(label))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { ui.onExport(choices) }, enabled = choices.canExport(ui.folderName)) {
                Text(text = stringResource(R.string.export_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.conversation_cancel))
            }
        }
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, role: Role, onClick: () -> Unit, enabled: Boolean = true) {
    val modifier = if (role == Role.Checkbox) {
        Modifier.toggleable(value = selected, enabled = enabled, role = role, onValueChange = { onClick() })
    } else {
        Modifier.selectable(selected = selected, enabled = enabled, role = role, onClick = onClick)
    }
    Row(modifier = Modifier.fillMaxWidth().then(modifier), verticalAlignment = Alignment.CenterVertically) {
        if (role == Role.Checkbox) {
            Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
        } else {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        }
        Text(
            text = label,
            modifier = Modifier.padding(start = AppTheme.spacing.s),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
