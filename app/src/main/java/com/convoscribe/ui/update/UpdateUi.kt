package com.convoscribe.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.convoscribe.R
import com.convoscribe.service.UpdateDownloadService
import com.convoscribe.ui.components.ConfirmDialog
import com.convoscribe.ui.theme.AppTheme
import com.convoscribe.update.GitHubReleases
import com.convoscribe.update.InstallStart
import com.convoscribe.update.UpdateFailure
import com.convoscribe.update.UpdateManager
import com.convoscribe.update.UpdateOffer
import com.convoscribe.update.UpdateState
import com.convoscribe.update.updateFailureText

/**
 * "Install unknown apps" for ConvoScribe (issue #15): a special access
 * granted in system Settings, so the app explains it first, like
 * [com.convoscribe.overlay.OverlayPermission].
 */
object InstallPermission {
    fun granted(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun settingsIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** What the update banner and Settings › About can do; no-ops by default, for screenshots. */
class UpdateActions(
    val onUpdate: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onInstall: () -> Unit = {},
    val onNotNow: () -> Unit = {},
    val onSkip: (UpdateOffer) -> Unit = {},
    val onViewNotes: (UpdateOffer) -> Unit = {},
    val onOpenReleasePage: (UpdateOffer) -> Unit = {},
    val onCheck: () -> Unit = {}
)

/**
 * Wires [UpdateActions] to [manager] and hosts their dialogs: the
 * release notes, and the "Install unknown apps" explanation shown
 * before the first install (after which the install continues on
 * return from system Settings). [showMessage] gets the "finish the
 * recording first" refusal.
 */
@Composable
fun rememberUpdateActions(manager: UpdateManager, showMessage: (String) -> Unit): UpdateActions {
    val context = LocalContext.current
    var notesFor by remember { mutableStateOf<UpdateOffer?>(null) }
    var askPermission by rememberSaveable { mutableStateOf(false) }
    var installAfterPermission by rememberSaveable { mutableStateOf(false) }

    fun install() {
        if (manager.install() == InstallStart.BUSY) showMessage(context.getString(R.string.update_failed_busy))
    }
    LifecycleResumeEffect(installAfterPermission) {
        if (installAfterPermission && InstallPermission.granted(context)) {
            installAfterPermission = false
            install()
        }
        onPauseOrDispose {}
    }

    notesFor?.let { offer -> ReleaseNotesDialog(offer = offer, onDismiss = { notesFor = null }) }
    if (askPermission) {
        ConfirmDialog(
            title = stringResource(R.string.update_permission_title),
            message = stringResource(R.string.update_permission_message),
            confirmLabel = stringResource(R.string.update_permission_allow),
            dismissLabel = stringResource(R.string.update_permission_not_now),
            onConfirm = {
                askPermission = false
                installAfterPermission = true
                runCatching { context.startActivity(InstallPermission.settingsIntent(context)) }
            },
            onDismiss = { askPermission = false }
        )
    }

    return remember(manager) {
        UpdateActions(
            onUpdate = {
                ContextCompat.startForegroundService(context, UpdateDownloadService.startIntent(context))
            },
            onCancel = { context.startService(UpdateDownloadService.cancelIntent(context)) },
            onInstall = {
                if (InstallPermission.granted(context)) install() else askPermission = true
            },
            onNotNow = manager::dismissBanner,
            onSkip = manager::skip,
            onViewNotes = { notesFor = it },
            onOpenReleasePage = { offer ->
                val url = offer.release.htmlUrl.ifBlank { GitHubReleases.RELEASES_PAGE }
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            },
            onCheck = manager::checkNow
        )
    }
}

/** `true` when [state] has an update for the banner to show. */
fun UpdateState.hasBanner(): Boolean = when (this) {
    is UpdateState.Available, is UpdateState.Downloading, is UpdateState.ReadyToInstall,
    is UpdateState.Installing, is UpdateState.Failed -> true
    UpdateState.Idle, UpdateState.UpToDate, UpdateState.CheckFailed, is UpdateState.Checking -> false
}

/**
 * The update offer and its progress (issue #15): on Home above the
 * session list, and inside Settings › About ([dismissible] `false`
 * there: no *Not now* / *Skip*). Draws nothing for a state without an
 * offer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateBanner(
    state: UpdateState,
    actions: UpdateActions,
    modifier: Modifier = Modifier,
    dismissible: Boolean = true
) {
    val context = LocalContext.current
    val spacing = AppTheme.spacing
    val (title, body) = when (state) {
        is UpdateState.Available ->
            stringResource(R.string.update_available_title, state.offer.versionName) to
                stringResource(R.string.update_available_body)
        is UpdateState.Downloading ->
            stringResource(R.string.update_downloading_title, state.offer.versionName) to
                if (state.waiting) {
                    stringResource(R.string.notification_download_waiting)
                } else {
                    stringResource(
                        R.string.notification_download_progress,
                        state.bytesDone / 1_000_000,
                        state.bytesTotal / 1_000_000
                    )
                }
        is UpdateState.ReadyToInstall ->
            stringResource(R.string.update_ready_title, state.offer.versionName) to
                stringResource(R.string.update_ready_body)
        is UpdateState.Installing ->
            stringResource(R.string.update_installing_title, state.offer.versionName) to
                stringResource(R.string.update_installing_body)
        is UpdateState.Failed ->
            stringResource(R.string.update_failed_title, state.offer.versionName) to updateFailureText(context, state)
        else -> return
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = spacing.s),
        verticalArrangement = Arrangement.spacedBy(spacing.xs)
    ) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = if (state is UpdateState.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        if (state is UpdateState.Downloading) {
            if (state.bytesTotal > 0) {
                LinearProgressIndicator(
                    progress = { (state.bytesDone.toFloat() / state.bytesTotal).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.s, androidx.compose.ui.Alignment.End)
        ) {
            when (state) {
                is UpdateState.Available -> {
                    TextButton(onClick = { actions.onViewNotes(state.offer) }) {
                        Text(text = stringResource(R.string.update_view_notes))
                    }
                    if (dismissible) {
                        TextButton(onClick = { actions.onSkip(state.offer) }) {
                            Text(text = stringResource(R.string.update_skip))
                        }
                        TextButton(onClick = actions.onNotNow) {
                            Text(text = stringResource(R.string.update_not_now))
                        }
                    }
                    Button(onClick = actions.onUpdate) { Text(text = stringResource(R.string.update_update)) }
                }
                is UpdateState.Downloading -> TextButton(onClick = actions.onCancel) {
                    Text(text = stringResource(R.string.notification_download_cancel))
                }
                is UpdateState.ReadyToInstall -> {
                    if (dismissible) {
                        TextButton(onClick = actions.onNotNow) {
                            Text(text = stringResource(R.string.update_not_now))
                        }
                    }
                    Button(onClick = actions.onInstall) { Text(text = stringResource(R.string.update_install)) }
                }
                is UpdateState.Failed -> {
                    if (dismissible) {
                        TextButton(onClick = actions.onNotNow) {
                            Text(text = stringResource(R.string.update_not_now))
                        }
                    }
                    if (state.reason.retryable) {
                        Button(onClick = actions.onUpdate) { Text(text = stringResource(R.string.update_retry)) }
                    } else {
                        Button(onClick = { actions.onOpenReleasePage(state.offer) }) {
                            Text(text = stringResource(R.string.update_open_release_page))
                        }
                    }
                }
                else -> Unit
            }
        }
    }
}

/** A retry can fix it (the network, a damaged file, a failed install); the others need the release page. */
private val UpdateFailure.retryable: Boolean
    get() = this == UpdateFailure.DOWNLOAD || this == UpdateFailure.SHA256_MISMATCH ||
        this == UpdateFailure.INSTALL || this == UpdateFailure.BUSY

/** The release body as plain text (GitHub Markdown, not rendered), scrollable. */
@Composable
private fun ReleaseNotesDialog(offer: UpdateOffer, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = offer.release.name.ifBlank { offer.versionName }) },
        text = {
            Text(
                text = offer.release.notes.ifBlank { stringResource(R.string.update_notes_empty) },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.update_notes_close)) }
        }
    )
}
