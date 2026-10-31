package com.convoscribe.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.convoscribe.R
import com.convoscribe.data.ActionItemEntity
import com.convoscribe.ui.actions.ActionItemIntents
import com.convoscribe.ui.theme.AppTheme
import java.time.Instant
import java.time.ZoneOffset

/**
 * Editing an action item (issue #31): its text, its owner and its due
 * date, picked on a calendar and clearable. Save stays off while the
 * text is blank; [onConfirm] gets the text, the owner (blank for none)
 * and the date as `yyyy-MM-dd`, or `null`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionItemDialog(
    item: ActionItemEntity,
    onConfirm: (text: String, owner: String, dueDate: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var text by rememberSaveable(item.id) { mutableStateOf(item.text) }
    var owner by rememberSaveable(item.id) { mutableStateOf(item.owner.orEmpty()) }
    var due by rememberSaveable(item.id) { mutableStateOf(item.dueDate) }
    var picking by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.action_item_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(TEXT_MAX_CHARS) },
                    label = { Text(text = stringResource(R.string.action_item_text_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = owner,
                    onValueChange = { owner = it.take(OWNER_MAX_CHARS) },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.action_item_owner_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { picking = true }) {
                        val label = ActionItemIntents.dueLabel(due)
                        Text(
                            text = label?.let { stringResource(R.string.action_item_due, it) }
                                ?: stringResource(R.string.action_item_due_set)
                        )
                    }
                    if (due != null) {
                        IconButton(onClick = { due = null }) {
                            Icon(
                                imageVector = Icons.Filled.Clear,
                                contentDescription = stringResource(R.string.action_item_due_clear)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text, owner, due) }, enabled = text.isNotBlank()) {
                Text(text = stringResource(R.string.session_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.session_rename_cancel))
            }
        }
    )
    if (picking) {
        // The picker works in UTC midnights.
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = ActionItemIntents.dueDate(due)
                ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        picker.selectedDateMillis?.let { ms ->
                            due = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()
                        }
                        picking = false
                    }
                ) {
                    Text(text = stringResource(R.string.session_rename_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { picking = false }) {
                    Text(text = stringResource(R.string.session_rename_cancel))
                }
            }
        ) {
            DatePicker(state = picker)
        }
    }
}

private const val TEXT_MAX_CHARS = 300
private const val OWNER_MAX_CHARS = 60
