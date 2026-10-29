package com.convoscribe.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.convoscribe.R
import com.convoscribe.settings.isUnencryptedUrl
import com.convoscribe.sync.SyncStatus
import com.convoscribe.ui.components.ConfirmDialog
import com.convoscribe.ui.components.ErrorBanner
import com.convoscribe.ui.components.InlineProgress
import com.convoscribe.ui.components.NoticeText
import com.convoscribe.ui.components.SectionCard
import com.convoscribe.ui.components.SupportingText
import com.convoscribe.ui.theme.AppTheme
import java.text.DateFormat
import java.util.Date

/**
 * "Sync" section (issue #68): the sync server's address and key (saved
 * by Save, like the transcription server's), *Sync sessions* (written
 * immediately, after a confirmation that sessions leave the device),
 * *Sync now*, and the status: last synced, sessions waiting and the
 * last error. [configured] is whether an address is saved; [enabled]
 * whether sync is switched on.
 *
 * Two-way sync (issue #69) adds *Download recordings on mobile data*
 * ([audioOnMobileData]) and how many sessions or recordings are still
 * downloading.
 */
@Composable
internal fun SyncSection(
    url: String,
    key: String,
    onUrlChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    configured: Boolean,
    enabled: Boolean,
    status: SyncStatus,
    onEnabledChange: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    audioOnMobileData: Boolean = false,
    onAudioOnMobileDataChange: (Boolean) -> Unit = {}
) {
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    val active = enabled && configured
    SectionCard(
        modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth),
        title = stringResource(R.string.sync_section)
    ) {
        SupportingText(text = stringResource(R.string.sync_help))
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            label = { Text(text = stringResource(R.string.sync_url_label)) },
            placeholder = { Text(text = stringResource(R.string.sync_url_placeholder)) },
            singleLine = true,
            supportingText = if (isUnencryptedUrl(url)) {
                { Text(text = stringResource(R.string.sync_url_cleartext)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = key,
            onValueChange = onKeyChange,
            label = { Text(text = stringResource(R.string.sync_key_label)) },
            singleLine = true,
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { keyVisible = !keyVisible }) {
                    Text(
                        text = stringResource(
                            if (keyVisible) R.string.settings_llm_api_key_hide else R.string.settings_llm_api_key_show
                        ),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = active,
                    enabled = configured,
                    role = Role.Switch,
                    onValueChange = { on -> if (on) confirming = true else onEnabledChange(false) }
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.sync_enabled_label), style = MaterialTheme.typography.bodyLarge)
                SupportingText(text = stringResource(R.string.sync_enabled_help))
            }
            Switch(checked = active, onCheckedChange = null, enabled = configured)
        }
        if (active) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = audioOnMobileData,
                        role = Role.Switch,
                        onValueChange = onAudioOnMobileDataChange
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.sync_mobile_data_label),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    SupportingText(text = stringResource(R.string.sync_mobile_data_help))
                }
                Switch(checked = audioOnMobileData, onCheckedChange = null)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)
            ) {
                OutlinedButton(onClick = onSyncNow, enabled = !status.running) {
                    Text(text = stringResource(R.string.sync_now))
                }
                if (status.running) {
                    InlineProgress()
                    NoticeText(text = stringResource(R.string.sync_running), modifier = Modifier.weight(1f))
                }
            }
            val lastSynced = status.lastSyncedAt?.let {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
            }
            NoticeText(
                text = lastSynced?.let { stringResource(R.string.sync_last, it) } ?: stringResource(R.string.sync_never)
            )
            if (status.waiting > 0) {
                NoticeText(text = pluralStringResource(R.plurals.sync_waiting, status.waiting, status.waiting))
            }
            if (status.downloading > 0) {
                NoticeText(
                    text = pluralStringResource(R.plurals.sync_downloading, status.downloading, status.downloading)
                )
            }
            status.lastError?.let { ErrorBanner(message = stringResource(R.string.sync_error, it)) }
        }
    }
    if (confirming) {
        ConfirmDialog(
            title = stringResource(R.string.sync_confirm_title),
            message = stringResource(R.string.sync_confirm_text, url.trim()),
            confirmLabel = stringResource(R.string.sync_confirm_action),
            dismissLabel = stringResource(R.string.sync_confirm_cancel),
            onConfirm = {
                confirming = false
                onEnabledChange(true)
            },
            onDismiss = { confirming = false }
        )
    }
}
