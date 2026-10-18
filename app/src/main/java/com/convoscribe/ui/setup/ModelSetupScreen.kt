package com.convoscribe.ui.setup

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.convoscribe.ConvoApp
import com.convoscribe.R
import com.convoscribe.models.ModelCatalog
import com.convoscribe.service.ModelDownloadService
import com.convoscribe.ui.components.ErrorBanner
import com.convoscribe.ui.components.ProgressBlock
import com.convoscribe.ui.components.ScreenScaffold
import com.convoscribe.ui.components.SectionCard
import com.convoscribe.ui.theme.AppTheme

/**
 * First-run screen that downloads the speech models before
 * navigation hands control to the home screen.
 *
 * - Idle: a `Download` button.
 * - Running: a progress bar and an "x MB / y MB" line ("waiting for
 *   the network" during a retry, a percentage while unpacking), and
 *   Cancel. The download runs in [ModelDownloadService], so it goes on
 *   with the screen off, in another app or after leaving this screen,
 *   which shows its progress again when reopened (issue #10).
 * - Error: a `Retry` button with the error text in
 *   `MaterialTheme.colorScheme.error`; Retry resumes the download.
 *
 * [initialEngine] / [initialLanguage] pick the bundles to fetch
 * instead of the Settings engine and language (the Record screen
 * passes the new conversation's language).
 *
 * When [SetupUiState.finished] becomes true, [onFinished] runs once
 * so the caller can navigate (with `popUpTo("setup") { inclusive =
 * true }`) and the screen is not reachable after a successful
 * download.
 */
@Composable
fun ModelSetupScreen(onFinished: () -> Unit, initialEngine: String? = null, initialLanguage: String? = null) {
    val context = LocalContext.current
    val container = (context.applicationContext as ConvoApp).container
    val vm: ModelSetupViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val settings = container.settingsRepository.settings.value
                val engine = when (initialEngine) {
                    "npu", "cpu" -> initialEngine
                    else -> settings.asrEngine
                }
                val language = when (initialLanguage) {
                    "en", "fr" -> initialLanguage
                    else -> settings.asrLanguage
                }
                ModelSetupViewModel(
                    manager = container.modelDownloadManager,
                    store = container.modelStore,
                    bundles = ModelCatalog.requiredFor(language, engine),
                    startDownload = { bundles ->
                        ContextCompat.startForegroundService(
                            context,
                            ModelDownloadService.startIntent(context, bundles)
                        )
                    }
                )
            }
        }
    )
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.finished) {
        if (state.finished) onFinished()
    }

    // Android 13+: the download's progress notification needs the
    // notification permission. A denial does not block the download.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.start() }
    val start = {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.start()
        }
    }

    SetupBody(
        state = state,
        onStart = start,
        onCancel = { context.startService(ModelDownloadService.cancelIntent(context)) }
    )
}

/**
 * Render-only body of [ModelSetupScreen] for previews and screenshot
 * tests: what will be downloaded, then the Download button, the
 * progress while it runs, or the error with Retry.
 */
@Composable
internal fun SetupBody(state: SetupUiState, onStart: () -> Unit, onCancel: () -> Unit = {}) {
    val spacing = AppTheme.spacing
    ScreenScaffold(title = stringResource(R.string.setup_title)) { innerPadding: PaddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(spacing.xl),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.l)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_conversation),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(AppTheme.sizes.emptyStateIcon)
                )
                Text(
                    text = stringResource(R.string.setup_heading),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.setup_body, state.totalBytes / 1_000_000),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (state.running || state.finished) {
                    val unpacking = state.unpacking
                    SectionCard {
                        ProgressBlock(
                            message = stringResource(
                                when {
                                    unpacking != null -> R.string.setup_unpacking
                                    state.waitingForNetwork -> R.string.setup_waiting_network
                                    else -> R.string.setup_downloading
                                }
                            ),
                            progress = state.progress,
                            detail = if (unpacking != null) {
                                stringResource(R.string.setup_unpacking_progress, (unpacking * 100).toInt())
                            } else {
                                stringResource(
                                    R.string.setup_progress,
                                    state.doneBytes / 1_000_000,
                                    (state.downloadTotalBytes.takeIf { it > 0 } ?: state.totalBytes) / 1_000_000
                                )
                            }
                        )
                    }
                    if (state.running) {
                        Text(
                            text = stringResource(R.string.setup_background_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        TextButton(onClick = onCancel) {
                            Text(text = stringResource(R.string.setup_cancel))
                        }
                    }
                }
                state.error?.let { error ->
                    ErrorBanner(
                        message = error,
                        actionLabel = stringResource(R.string.setup_retry),
                        onAction = onStart
                    )
                }
                if (!state.running && !state.finished && state.error == null) {
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                        Text(text = stringResource(R.string.setup_download))
                    }
                }
            }
        }
    }
}
