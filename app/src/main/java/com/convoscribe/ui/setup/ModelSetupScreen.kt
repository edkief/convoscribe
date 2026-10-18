package com.convoscribe.ui.setup

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.convoscribe.ConvoApp
import com.convoscribe.R
import com.convoscribe.models.ModelCatalog
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
 * - Running: a progress bar and an "x MB / y MB" line.
 * - Error: a `Retry` button with the error text in
 *   `MaterialTheme.colorScheme.error`.
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
                    downloader = container.modelDownloader,
                    store = container.modelStore,
                    bundles = ModelCatalog.requiredFor(language, engine)
                )
            }
        }
    )
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.finished) {
        if (state.finished) onFinished()
    }

    SetupBody(state = state, onStart = vm::start)
}

/**
 * Render-only body of [ModelSetupScreen] for previews and screenshot
 * tests: what will be downloaded, then the Download button, the
 * progress while it runs, or the error with Retry.
 */
@Composable
internal fun SetupBody(state: SetupUiState, onStart: () -> Unit) {
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
                    val safeTotal = state.totalBytes.coerceAtLeast(1L)
                    SectionCard {
                        ProgressBlock(
                            message = stringResource(R.string.setup_downloading),
                            progress = state.doneBytes.toFloat() / safeTotal.toFloat(),
                            detail = stringResource(
                                R.string.setup_progress,
                                state.doneBytes / 1_000_000,
                                state.totalBytes / 1_000_000
                            )
                        )
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
