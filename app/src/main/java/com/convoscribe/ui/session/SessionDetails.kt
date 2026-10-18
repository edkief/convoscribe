package com.convoscribe.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.convoscribe.R
import com.convoscribe.data.SessionRepository
import com.convoscribe.ui.components.InfoRow
import com.convoscribe.ui.record.participantsSummary
import com.convoscribe.ui.theme.AppTheme

/**
 * What a session was set up and transcribed with (issue #6), for the
 * session info view and the participants editor.
 *
 * @property numSpeakers the speaker count in use: the session's, else
 *   the Settings default for sessions from before it was stored.
 * @property participants expected enrolled people, `null` anyone.
 * @property asrEngine / [asrModel] `null` on sessions recorded before
 *   they were kept.
 */
data class SessionDetails(
    val language: String = "",
    val numSpeakers: Int = -1,
    val participants: List<String>? = null,
    val asrEngine: String? = null,
    val asrModel: String? = null
)

/** Read-only "Session info" dialog. */
@Composable
internal fun SessionInfoDialog(details: SessionDetails, onDismiss: () -> Unit) {
    val unknown = SessionRepository.UNKNOWN_ENGINE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.session_info_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
                InfoRow(stringResource(R.string.session_info_language), languageName(details.language))
                InfoRow(
                    stringResource(R.string.session_info_speakers),
                    if (details.numSpeakers <= 0) {
                        stringResource(R.string.session_reidentify_auto)
                    } else {
                        details.numSpeakers.toString()
                    }
                )
                InfoRow(stringResource(R.string.session_info_participants), participantsSummary(details.participants))
                InfoRow(stringResource(R.string.session_info_engine), details.asrEngine ?: unknown)
                InfoRow(stringResource(R.string.session_info_model), details.asrModel ?: unknown)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.session_info_close))
            }
        }
    )
}

@Composable
internal fun languageName(code: String): String = when (code) {
    "en" -> stringResource(R.string.conversation_language_en)
    "fr" -> stringResource(R.string.conversation_language_fr)
    else -> code
}

/** After saving participants: offer to re-run speaker identification with them. */
@Composable
internal fun ReidentifyAfterParticipantsDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.session_participants_reidentify_title)) },
        text = { Text(text = stringResource(R.string.session_participants_reidentify_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.session_reidentify_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.session_participants_reidentify_later))
            }
        }
    )
}

/**
 * Re-transcription controls for the session screen.
 *
 * @property progress `0f..1f` while this session is being
 *   re-transcribed, else `null`.
 * @property enabled whether the menu entry can start one (finished
 *   session, recorder idle, nothing else re-transcribing).
 */
data class RetranscribeUi(
    val progress: Float? = null,
    val enabled: Boolean = false,
    val onStart: (language: String) -> Unit = {},
    val onCancel: () -> Unit = {}
)

/** "Re-transcribing… 42%" with a progress bar and Cancel, above the tabs. */
@Composable
internal fun RetranscribeBanner(progress: Float, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppTheme.spacing.screen, vertical = AppTheme.spacing.s),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.session_retranscribe_progress, (progress * 100).toInt()),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCancel) {
                Text(text = stringResource(R.string.session_retranscribe_cancel))
            }
        }
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Pick the language to transcribe the recording in again. Warns that
 * the transcript is replaced and that an existing summary and Q&A
 * were based on the old one.
 */
@Composable
internal fun RetranscribeDialog(currentLanguage: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(if (currentLanguage == "fr") "en" else "fr") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.session_retranscribe_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
                Text(text = stringResource(R.string.session_retranscribe_message))
                listOf("en", "fr").forEach { code ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = code == selected, role = Role.RadioButton) { selected = code },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = code == selected, onClick = null)
                        Text(text = languageName(code), modifier = Modifier.padding(start = AppTheme.spacing.s))
                    }
                }
                Text(
                    text = stringResource(R.string.session_retranscribe_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) {
                Text(text = stringResource(R.string.session_retranscribe_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.conversation_cancel))
            }
        }
    )
}
