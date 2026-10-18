package com.convoscribe.ui.record

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.convoscribe.R
import com.convoscribe.data.SessionRepository
import com.convoscribe.recording.ConversationOptions
import com.convoscribe.ui.components.FieldLabel
import com.convoscribe.ui.theme.AppTheme

/**
 * The options a new conversation is set up with (issue #6): language,
 * number of speakers, expected participants and an optional title,
 * plus "Set as default". Render-only; the Record screen feeds it from
 * [RecordViewModel.options]. Kept free of recording specifics so the
 * file import (#3) can show the same form.
 *
 * @param enrolledNames people the participants picker offers.
 * @param onSaveAsDefault `null` hides the "Set as default" button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationOptionsForm(
    options: ConversationOptions,
    enrolledNames: List<String>,
    onLanguageChange: (String) -> Unit,
    onNumSpeakersChange: (Int) -> Unit,
    onParticipantsChange: (List<String>?) -> Unit,
    onTitleChange: (String) -> Unit,
    onSaveAsDefault: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    var participantsOpen by remember { mutableStateOf(false) }
    var savedAsDefault by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
        FieldLabel(text = stringResource(R.string.conversation_language_label))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            LANGUAGES.forEachIndexed { index, (code, label) ->
                SegmentedButton(
                    selected = options.language == code,
                    onClick = {
                        savedAsDefault = false
                        onLanguageChange(code)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = LANGUAGES.size)
                ) {
                    Text(text = stringResource(label))
                }
            }
        }

        SpeakerCountPicker(
            numSpeakers = options.numSpeakers,
            onChange = {
                savedAsDefault = false
                onNumSpeakersChange(it)
            }
        )

        OutlinedButton(onClick = { participantsOpen = true }, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = stringResource(R.string.conversation_participants_label))
                Text(
                    text = participantsSummary(options.participants),
                    modifier = Modifier.padding(start = AppTheme.spacing.s),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        OutlinedTextField(
            value = options.title,
            onValueChange = { onTitleChange(it.take(SessionRepository.SESSION_TITLE_MAX_LEN)) },
            label = { Text(text = stringResource(R.string.conversation_title_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (onSaveAsDefault != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        onSaveAsDefault()
                        savedAsDefault = true
                    }
                ) {
                    Text(text = stringResource(R.string.conversation_set_default))
                }
                if (savedAsDefault) {
                    Text(
                        text = stringResource(R.string.conversation_set_default_done),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (participantsOpen) {
        ParticipantsDialog(
            enrolledNames = enrolledNames,
            selected = options.participants,
            onConfirm = {
                participantsOpen = false
                savedAsDefault = false
                onParticipantsChange(it)
            },
            onDismiss = { participantsOpen = false }
        )
    }
}

/** "Anyone", or the chosen names joined by commas. */
@Composable
internal fun participantsSummary(participants: List<String>?): String =
    participants?.joinToString(", ") ?: stringResource(R.string.conversation_participants_anyone)

/** Outlined dropdown for automatic / 2..6 speakers, matching the Settings one. */
@Composable
private fun SpeakerCountPicker(numSpeakers: Int, onChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = stringResource(R.string.conversation_speakers_label))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (numSpeakers <= 0) {
                            stringResource(R.string.conversation_speakers_automatic)
                        } else {
                            numSpeakers.toString()
                        }
                    )
                    Icon(imageVector = Icons.Filled.ArrowDropDown, contentDescription = null)
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.conversation_speakers_automatic)) },
                onClick = {
                    onChange(-1)
                    expanded = false
                }
            )
            (2..6).forEach { count ->
                DropdownMenuItem(
                    text = { Text(text = count.toString()) },
                    onClick = {
                        onChange(count)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * Pick who is expected in a conversation. Nobody ticked means anyone
 * (`null`). Used by the Record screen and the session screen.
 */
@Composable
internal fun ParticipantsDialog(
    enrolledNames: List<String>,
    selected: List<String>?,
    onConfirm: (List<String>?) -> Unit,
    onDismiss: () -> Unit
) {
    val ticked = remember(selected) { mutableStateListOf<String>().apply { addAll(selected.orEmpty()) } }
    // A saved name whose person was since deleted stays visible so it can be unticked.
    val names = (enrolledNames + selected.orEmpty()).distinct()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.conversation_participants_label)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(text = stringResource(R.string.conversation_participants_message))
                if (names.isEmpty()) {
                    Text(
                        text = stringResource(R.string.conversation_participants_none_enrolled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                names.forEach { name ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { if (name in ticked) ticked.remove(name) else ticked.add(name) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = name in ticked, onCheckedChange = null)
                        Text(text = name)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(ticked.toList().ifEmpty { null }) }) {
                Text(text = stringResource(R.string.conversation_participants_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.conversation_cancel))
            }
        }
    )
}

private val LANGUAGES = listOf(
    "en" to R.string.conversation_language_en,
    "fr" to R.string.conversation_language_fr
)
