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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.convoscribe.R
import com.convoscribe.data.SpeakerEntity
import com.convoscribe.data.TokenAligner
import com.convoscribe.data.TranscriptEditor
import com.convoscribe.data.defaultSpeakerLabel
import com.convoscribe.ui.components.SpeakerDot
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

/**
 * *Edit text* for one transcript line (issue #26): the line's text in a
 * multi-line field. Save is disabled while it is blank; an unchanged
 * text saves nothing.
 */
@Composable
internal fun EditLineDialog(initialText: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable(initialText) { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.session_line_edit_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 3,
                label = { Text(text = stringResource(R.string.session_line_edit_label)) },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text) }) {
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

/**
 * *Split here*: the line's text, read-only, with a cursor the user
 * places where the second line should start (preset to the middle
 * word). The preview shows both parts as [TranscriptEditor.split]
 * will cut them, snapped to the nearest word boundary.
 */
@Composable
internal fun SplitLineDialog(text: String, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var value by remember(text) {
        val starts = TokenAligner.wordStarts(text)
        mutableStateOf(TextFieldValue(text, TextRange(starts.getOrElse(starts.size / 2) { 0 })))
    }
    val parts = TranscriptEditor.splitTexts(text, value.selection.start)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.session_line_split_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
                Text(text = stringResource(R.string.session_line_split_message))
                OutlinedTextField(
                    value = value,
                    // Read-only: only the cursor moves.
                    onValueChange = { value = it.copy(text = text) },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (parts != null) {
                    Text(
                        text = stringResource(R.string.session_line_split_preview, parts.first, parts.second),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = parts != null, onClick = { onConfirm(value.selection.start) }) {
                Text(text = stringResource(R.string.session_line_split_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.session_rename_cancel))
            }
        }
    )
}

/**
 * *Change speaker*: the session's speakers (preselected with the
 * line's [current] one) and *New speaker*, which shows a name field
 * prefilled with the next default name. [onPick] gets an existing
 * speaker's index, [onNew] the new speaker's name.
 */
@Composable
internal fun ChangeSpeakerDialog(
    speakers: List<SpeakerEntity>,
    current: Int?,
    onPick: (Int) -> Unit,
    onNew: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val nextIndex = (speakers.maxOfOrNull { it.speakerIndex } ?: -1) + 1
    var selected by rememberSaveable { mutableStateOf(current ?: NEW_SPEAKER) }
    var name by rememberSaveable { mutableStateOf(defaultSpeakerLabel(nextIndex)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.session_line_speaker_title)) },
        text = {
            Column {
                for (speaker in speakers) {
                    SpeakerOption(
                        label = speaker.label,
                        selected = selected == speaker.speakerIndex,
                        speakerIndex = speaker.speakerIndex,
                        onSelect = { selected = speaker.speakerIndex }
                    )
                }
                SpeakerOption(
                    label = stringResource(R.string.session_line_speaker_new),
                    selected = selected == NEW_SPEAKER,
                    speakerIndex = null,
                    onSelect = { selected = NEW_SPEAKER }
                )
                if (selected == NEW_SPEAKER) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text(text = stringResource(R.string.session_rename_label)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != NEW_SPEAKER || name.isNotBlank(),
                onClick = { if (selected == NEW_SPEAKER) onNew(name) else onPick(selected) }
            ) {
                Text(text = stringResource(R.string.session_line_speaker_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.session_rename_cancel))
            }
        }
    )
}

/** One radio row of [ChangeSpeakerDialog], with the speaker's colour dot when it has one. */
@Composable
private fun SpeakerOption(label: String, selected: Boolean, speakerIndex: Int?, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)
    ) {
        RadioButton(selected = selected, onClick = null)
        if (speakerIndex != null) SpeakerDot(speakerIndex = speakerIndex)
        Text(text = label)
    }
}

/** [ChangeSpeakerDialog]'s choice for *New speaker*. */
private const val NEW_SPEAKER = -1
