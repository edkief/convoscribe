package com.convoscribe.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.convoscribe.R
import com.convoscribe.data.SessionRepository
import com.convoscribe.ui.record.participantsSummary

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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InfoRow(R.string.session_info_language, languageName(details.language))
                InfoRow(
                    R.string.session_info_speakers,
                    if (details.numSpeakers <= 0) {
                        stringResource(R.string.session_reidentify_auto)
                    } else {
                        details.numSpeakers.toString()
                    }
                )
                InfoRow(R.string.session_info_participants, participantsSummary(details.participants))
                InfoRow(R.string.session_info_engine, details.asrEngine ?: unknown)
                InfoRow(R.string.session_info_model, details.asrModel ?: unknown)
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
private fun InfoRow(labelRes: Int, value: String) {
    Text(text = stringResource(labelRes), style = MaterialTheme.typography.labelMedium)
    Text(text = value, style = MaterialTheme.typography.bodyMedium)
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
