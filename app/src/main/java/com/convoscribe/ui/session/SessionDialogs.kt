package com.convoscribe.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.convoscribe.R
import com.convoscribe.ui.theme.AppTheme

/**
 * "Re-identify speakers" dialog: pick how many people speak in the
 * recording (Automatic, 2, 3 or 4), preselected with [current] (the
 * session's count, else the Settings default; a Settings value
 * outside the list is offered too). [onConfirm] receives `-1` for
 * Automatic.
 */
@Composable
internal fun ReidentifySpeakersDialog(current: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val options = (listOf(-1, 2, 3, 4) + current).distinct().sortedBy { if (it == -1) 0 else it }
    var selected by remember { mutableIntStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.session_reidentify_title)) },
        text = {
            Column {
                Text(text = stringResource(R.string.session_reidentify_message))
                for (option in options) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = option == selected, role = Role.RadioButton) { selected = option },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text(
                            text = if (option == -1) {
                                stringResource(R.string.session_reidentify_auto)
                            } else {
                                pluralStringResource(R.plurals.session_reidentify_count, option, option)
                            },
                            modifier = Modifier.padding(start = AppTheme.spacing.s)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) {
                Text(text = stringResource(R.string.session_reidentify_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.session_ask_clear_cancel))
            }
        }
    )
}

/**
 * Reusable rename dialog used for both the speaker and session
 * rename flows. Pre-fills with [initialValue], commits through
 * [onConfirm] on Save, and discards on Cancel. Empty inputs
 * disable the Save button so the user cannot accidentally blank
 * out a label (the repository also defends against this, but the
 * UI gate gives immediate feedback).
 *
 * When [checkboxLabelRes] is non-null the dialog also renders a
 * checkbox row beneath the text field. The checkbox starts
 * unchecked and the screen reads its value through
 * [onConfirm]'s second parameter. The session-rename flow passes
 * `null` so the checkbox row is hidden; the speaker-rename flow
 * passes the "Remember this voice" label / help string so the
 * user can promote a session speaker to a known voice in one
 * tap (TASK-34).
 */
@Composable
internal fun RenameDialog(
    initialValue: String,
    titleRes: Int,
    onConfirm: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
    checkboxLabelRes: Int? = null,
    checkboxHelpRes: Int? = null
) {
    var text by rememberSaveable(initialValue) { mutableStateOf(initialValue) }
    var rememberVoice by rememberSaveable(initialValue) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(titleRes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.session_rename_label)) }
                )
                if (checkboxLabelRes != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = rememberVoice,
                            onCheckedChange = { rememberVoice = it }
                        )
                        Spacer(modifier = Modifier.width(AppTheme.spacing.s))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(checkboxLabelRes),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (checkboxHelpRes != null) {
                                Text(
                                    text = stringResource(checkboxHelpRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text, rememberVoice) }
            ) {
                Text(text = stringResource(R.string.session_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.session_rename_cancel))
            }
        }
    )
}
