package com.convoscribe.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.convoscribe.R

/**
 * The note of a mark (issue #30), on the Record screen right after
 * marking and on the session screen. [time] is the mark's time as
 * shown, in the title. A note is never required: saving a blank one
 * removes it.
 */
@Composable
fun MarkNoteDialog(time: String, initialNote: String?, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var note by rememberSaveable(initialNote) { mutableStateOf(initialNote.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.mark_note_title, time)) },
        text = {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(NOTE_MAX_CHARS) },
                singleLine = true,
                label = { Text(text = stringResource(R.string.mark_note_label)) },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(note) }) {
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

/** Matches [com.convoscribe.data.SessionRepository.MARK_NOTE_MAX_LEN]. */
private const val NOTE_MAX_CHARS = 200
