package com.convoscribe.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.convoscribe.R
import com.convoscribe.backup.BackupKind
import com.convoscribe.backup.BackupState
import com.convoscribe.backup.backupResultText
import com.convoscribe.backup.backupResultTitle
import com.convoscribe.ui.components.InlineProgress
import com.convoscribe.ui.components.NoticeText
import com.convoscribe.ui.components.SectionCard
import com.convoscribe.ui.components.SupportingText
import com.convoscribe.ui.theme.AppTheme

/**
 * "Data" section (issue #25): back up every session to one file, or
 * restore one. While either runs it shows the progress and Cancel;
 * afterwards what was saved or restored, or why it failed, until OK.
 */
@Composable
internal fun DataSection(
    state: BackupState,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    SectionCard(
        modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth),
        title = stringResource(R.string.backup_section)
    ) {
        SupportingText(text = stringResource(R.string.backup_section_summary))
        when (state) {
            is BackupState.Running -> {
                val percent = ((state.progress ?: 0f) * 100).toInt()
                val text = when {
                    state.kind == BackupKind.BACKUP -> stringResource(R.string.backup_running, percent)
                    state.progress == null -> stringResource(R.string.restore_running_unknown)
                    else -> stringResource(R.string.restore_running, percent)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)
                ) {
                    InlineProgress()
                    NoticeText(text = text, modifier = Modifier.weight(1f))
                    TextButton(onClick = onCancel) {
                        Text(text = stringResource(R.string.backup_cancel))
                    }
                }
            }
            is BackupState.Done, is BackupState.Failed -> {
                val context = LocalContext.current
                Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
                    Text(
                        text = backupResultTitle(context, state).orEmpty(),
                        style = MaterialTheme.typography.titleSmall
                    )
                    NoticeText(text = backupResultText(context, state).orEmpty(), isError = state is BackupState.Failed)
                    TextButton(onClick = onDismiss) {
                        Text(text = stringResource(R.string.backup_dismiss))
                    }
                }
            }
            BackupState.Idle -> Unit
        }
        val idle = state !is BackupState.Running
        OutlinedButton(onClick = onBackup, enabled = idle) {
            Text(text = stringResource(R.string.backup_action))
        }
        OutlinedButton(onClick = onRestore, enabled = idle) {
            Text(text = stringResource(R.string.restore_action))
        }
    }
}

/**
 * Asks before restoring the picked backup, with "Also restore
 * settings" ticked: the usual reason to restore is a fresh install.
 */
@Composable
internal fun RestoreConfirmDialog(onConfirm: (restoreSettings: Boolean) -> Unit, onDismiss: () -> Unit) {
    var restoreSettings by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.restore_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
                Text(text = stringResource(R.string.restore_confirm_text))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = restoreSettings, role = Role.Checkbox) { restoreSettings = it },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = restoreSettings, onCheckedChange = null)
                    Spacer(modifier = Modifier.width(AppTheme.spacing.s))
                    Text(text = stringResource(R.string.restore_confirm_settings))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(restoreSettings) }) {
                Text(text = stringResource(R.string.restore_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.restore_confirm_cancel))
            }
        }
    )
}
