package com.convoscribe.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import com.convoscribe.ui.components.SwitchRow
import com.convoscribe.ui.theme.AppTheme
import java.text.DateFormat
import java.util.Date

/**
 * The Server & sync page (issues #68, #98): the *Connection* card with
 * the sync server's address and key (saved
 * by Save, like the transcription server's), *Sync sessions* (written
 * immediately, after a confirmation that sessions leave the device),
 * *Sync now*, and the status: last synced, sessions waiting and the
 * last error. [configured] is whether an address is saved; [enabled]
 * whether sync is switched on.
 *
 * Two-way sync (issue #69) adds *Download recordings on mobile data*
 * ([audioOnMobileData]) and how many sessions or recordings are still
 * downloading.
 *
 * *Use this server's services* ([services], issue #94) has its own card
 * between them: either can be on without the other.
 *
 * When the server offers device sign-in ([signIn], issue #95), the card
 * offers *Sign in* through the browser instead of the key, which stays
 * behind *Use an API key instead*; once signed in, *Sign out*, and
 * *Sign in again* when the server refuses the key. Without it, the card
 * is the address and the key.
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
    onAudioOnMobileDataChange: (Boolean) -> Unit = {},
    services: ServerServicesUi = ServerServicesUi(),
    signIn: SignInUi = SignInUi(),
    onSignIn: () -> Unit = {},
    onCancelSignIn: () -> Unit = {},
    onSignOut: () -> Unit = {}
) {
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    var keyShown by rememberSaveable { mutableStateOf(false) }
    val active = enabled && configured
    // Sign-in is offered for the address as typed only once the server answered for it.
    val canSignIn = signIn.offer != null && signIn.checkedUrl == url.trim()
    // Connection first and on its own: browser sign-in (#95) replaces the key here.
    SectionCard(
        modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth),
        title = stringResource(R.string.sync_section)
    ) {
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
        if (canSignIn) {
            SignInBlock(
                signIn = signIn,
                hasKey = key.isNotBlank(),
                keyRefused = status.keyRefused,
                onSignIn = onSignIn,
                onCancel = onCancelSignIn,
                onSignOut = onSignOut
            )
            TextButton(onClick = { keyShown = !keyShown }) {
                Text(text = stringResource(if (keyShown) R.string.sign_in_hide_key else R.string.sign_in_use_key))
            }
        }
        if (!canSignIn || keyShown) {
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
                                if (keyVisible) {
                                    R.string.settings_llm_api_key_hide
                                } else {
                                    R.string.settings_llm_api_key_show
                                }
                            ),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    SectionCard(
        modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth),
        title = stringResource(R.string.settings_section_services)
    ) {
        ServerServicesBlock(ui = services, configured = configured, serverUrl = url)
        SupportingText(text = stringResource(R.string.settings_services_where))
    }
    SectionCard(
        modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth),
        title = stringResource(R.string.settings_section_sync)
    ) {
        SupportingText(text = stringResource(R.string.sync_help))
        SwitchRow(
            label = stringResource(R.string.sync_enabled_label),
            help = stringResource(R.string.sync_enabled_help),
            checked = active,
            enabled = configured,
            onCheckedChange = { on -> if (on) confirming = true else onEnabledChange(false) }
        )
        if (active) {
            SwitchRow(
                label = stringResource(R.string.sync_mobile_data_label),
                help = stringResource(R.string.sync_mobile_data_help),
                checked = audioOnMobileData,
                onCheckedChange = onAudioOnMobileDataChange
            )
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

/**
 * Device sign-in on the Connection card (issue #95): *Sign in*, the
 * wait for the browser, the outcome, and *Sign out* once this device
 * holds a key ([hasKey]). [keyRefused]: the server refused the key at
 * the last sync, so the user is asked to sign in again.
 */
@Composable
private fun SignInBlock(
    signIn: SignInUi,
    hasKey: Boolean,
    keyRefused: Boolean,
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
    onSignOut: () -> Unit
) {
    when (val phase = signIn.phase) {
        SignInPhase.Starting, SignInPhase.Waiting -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)
            ) {
                InlineProgress()
                NoticeText(
                    text = stringResource(
                        if (phase == SignInPhase.Starting) R.string.sign_in_starting else R.string.sign_in_waiting
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
            TextButton(onClick = onCancel) { Text(text = stringResource(R.string.sign_in_cancel)) }
            return
        }
        SignInPhase.SignedIn -> NoticeText(text = stringResource(R.string.sign_in_signed_in))
        SignInPhase.Declined -> ErrorBanner(message = stringResource(R.string.sign_in_declined))
        SignInPhase.Expired -> ErrorBanner(message = stringResource(R.string.sign_in_expired))
        is SignInPhase.Failed -> ErrorBanner(message = stringResource(R.string.sign_in_failed, phase.message))
        SignInPhase.Idle -> {}
    }
    when {
        hasKey && keyRefused -> {
            ErrorBanner(message = stringResource(R.string.sign_in_refused))
            Button(onClick = onSignIn) { Text(text = stringResource(R.string.sign_in_again_action)) }
        }
        hasKey -> {
            if (signIn.phase != SignInPhase.SignedIn) NoticeText(text = stringResource(R.string.sign_in_connected))
            OutlinedButton(onClick = onSignOut) { Text(text = stringResource(R.string.sign_in_sign_out)) }
            SupportingText(text = stringResource(R.string.sign_in_sign_out_help))
        }
        else -> {
            Button(onClick = onSignIn) { Text(text = stringResource(R.string.sign_in_action)) }
            SupportingText(text = stringResource(R.string.sign_in_help))
        }
    }
}
