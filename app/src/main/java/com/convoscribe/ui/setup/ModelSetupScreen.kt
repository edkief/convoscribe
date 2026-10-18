package com.convoscribe.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.convoscribe.ConvoApp
import com.convoscribe.R
import com.convoscribe.models.ModelCatalog

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
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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

/** Render-only body of [ModelSetupScreen] for previews and screenshot tests. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun SetupBody(state: SetupUiState, onStart: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(text = stringResource(R.string.setup_title)) })
        }
    ) { innerPadding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(
                    R.string.setup_body,
                    state.totalBytes / 1_000_000
                ),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            if (state.running || state.finished) {
                val safeTotal = state.totalBytes.coerceAtLeast(1L)
                val progress = (state.doneBytes.toFloat() / safeTotal.toFloat())
                    .coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(
                        R.string.setup_progress,
                        state.doneBytes / 1_000_000,
                        state.totalBytes / 1_000_000
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (state.error != null) {
                Text(
                    text = state.error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            when {
                state.error != null -> {
                    Button(onClick = onStart) {
                        Text(text = stringResource(R.string.setup_retry))
                    }
                }
                !state.running && !state.finished -> {
                    Button(onClick = onStart) {
                        Text(text = stringResource(R.string.setup_download))
                    }
                }
            }
        }
    }
}
