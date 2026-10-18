package com.convoscribe.ui.enginecheck

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.convoscribe.ConvoApp
import com.convoscribe.R
import com.convoscribe.enginecheck.EngineCheckResult
import com.convoscribe.enginecheck.NpuProbeClient
import com.convoscribe.enginecheck.verdict

/**
 * Diagnostics screen that lets the owner confirm the NPU works,
 * measure how fast it is, and compare it with the CPU engine.
 *
 * Layout:
 *  - device-info card (manufacturer, model, SoC, free storage,
 *    CDSP / HTP skel presence);
 *  - storage warning above the buttons when the device would
 *    struggle to prepare the NPU model;
 *  - "Check NPU" and "Check CPU" buttons (or "Download the
 *    `<engine>` model first" when the bundle is missing);
 *  - one result card per engine with the verdict prominent;
 *  - "Copy report" / share row at the bottom.
 *
 * The checks are gated on the recording state: while a session is
 * active the buttons read as "Stop the recording first" and the
 * check itself is suppressed (the recognizer would conflict with
 * the live one).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineCheckScreen(navController: androidx.navigation.NavHostController) {
    val context = LocalContext.current
    val container = (context.applicationContext as ConvoApp).container
    val vm: EngineCheckViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                EngineCheckViewModel(
                    context = context.applicationContext,
                    recordingController = container.recordingController,
                    runnerFactory = EngineCheckRunnerFactory { engine, sampleFile, clock ->
                        com.convoscribe.enginecheck.EngineCheckRunner(
                            factory = container.transcriberFactoryFor(engine),
                            loadSamples = { readEngineCheckSamples(sampleFile) },
                            clock = clock
                        )
                    },
                    npuProbe = NpuProbeClient(
                        context = context.applicationContext,
                        availability = container.npuAvailability,
                        onVerified = container.settingsRepository::refreshDefaults
                    ).let { client -> NpuProbe { sampleFile -> client.run(sampleFile) } }
                )
            }
        }
    )
    val state by vm.state.collectAsStateWithLifecycle()

    // Read the device snapshot once on first composition so the
    // card does not flash empty.
    LaunchedEffect(Unit) { vm.ensureDeviceInfo() }

    EngineCheckBody(
        state = state,
        storageWarning = vm.storageWarningText,
        onBack = { navController.popBackStack() },
        onRunCheck = vm::runCheck,
        onOpenSetup = { engine -> navController.navigate("setup?engine=$engine") },
        onCopyReport = { copyToClipboard(context, vm.buildReport()) },
        onShareReport = { shareText(context, vm.buildReport()) }
    )
}

/** Render-only body of [EngineCheckScreen] for previews and screenshot tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EngineCheckBody(
    state: EngineCheckUiState,
    storageWarning: String?,
    onBack: () -> Unit,
    onRunCheck: (String) -> Unit,
    onOpenSetup: (String) -> Unit,
    onCopyReport: () -> Unit,
    onShareReport: () -> Unit
) {
    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.enginecheck_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.record_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DeviceInfoCard(
                info = state.deviceInfo,
                htpSkelCount = state.deviceInfo?.htpSkelLibs?.size ?: 0
            )
            val warning = storageWarning
            if (warning != null) {
                Text(
                    text = warning,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (state.recordingBlocked) {
                Text(
                    text = stringResource(R.string.enginecheck_stop_recording_first),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            val modelMissing = state.modelMissing
            val missingEngine = state.missingEngine
            if (modelMissing && missingEngine != null) {
                Text(
                    text = stringResource(R.string.enginecheck_download_model_first, missingEngine),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedButton(
                    onClick = { onOpenSetup(missingEngine) }
                ) {
                    Text(text = stringResource(R.string.enginecheck_open_setup))
                }
            } else {
                if (state.runningEngine != null) {
                    Text(
                        text = stringResource(
                            if (state.runningEngine == "npu") {
                                R.string.enginecheck_running_npu
                            } else {
                                R.string.enginecheck_running
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                CheckButtons(
                    button1Enabled = state.runningEngine == null,
                    button2Enabled = state.runningEngine == null,
                    onCheckNpu = { onRunCheck("npu") },
                    onCheckCpu = { onRunCheck("cpu") }
                )
            }
            state.results["npu"]?.let { ResultCard(it) }
            state.results["cpu"]?.let { ResultCard(it) }
            Spacer(modifier = Modifier.height(8.dp))
            ActionRow(
                enabled = state.results.isNotEmpty(),
                onCopy = onCopyReport,
                onShare = onShareReport
            )
        }
    }
}

/**
 * Compose `Row`-shaped two-button block. Inlined here because the
 * imports needed for `Row` are not used anywhere else on the
 * screen.
 */
@Composable
private fun CheckButtons(
    button1Enabled: Boolean,
    button2Enabled: Boolean,
    onCheckNpu: () -> Unit,
    onCheckCpu: () -> Unit
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            modifier = Modifier.weight(1f),
            enabled = button1Enabled,
            onClick = onCheckNpu
        ) {
            Text(text = stringResource(R.string.enginecheck_check_npu))
        }
        Button(
            modifier = Modifier.weight(1f),
            enabled = button2Enabled,
            onClick = onCheckCpu
        ) {
            Text(text = stringResource(R.string.enginecheck_check_cpu))
        }
    }
}

/** Top card: who am I running on, and is the NPU even plausible? */
@Composable
private fun DeviceInfoCard(info: com.convoscribe.enginecheck.DeviceInfo?, htpSkelCount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.enginecheck_device_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (info == null) {
                Text(text = stringResource(R.string.enginecheck_device_loading))
            } else {
                InfoLine(stringResource(R.string.enginecheck_device_manufacturer), info.manufacturer)
                InfoLine(stringResource(R.string.enginecheck_device_model), info.model)
                InfoLine(stringResource(R.string.enginecheck_device_soc), "${info.socManufacturer} ${info.socModel}")
                InfoLine(stringResource(R.string.enginecheck_device_hardware), info.hardware)
                InfoLine(stringResource(R.string.enginecheck_device_android), info.androidVersion)
                InfoLine(
                    stringResource(R.string.enginecheck_device_cdsp),
                    if (info.cdspLibraryPresent) "yes" else "no"
                )
                InfoLine(
                    stringResource(R.string.enginecheck_device_htp),
                    htpSkelCount.toString()
                )
                InfoLine(
                    stringResource(R.string.enginecheck_device_free_storage),
                    "%.1f GB".format(info.freeStorageBytes / 1_000_000_000.0)
                )
            }
        }
    }
}

/** One `key: value` line in the device card. */
@Composable
private fun InfoLine(label: String, value: String) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** One result card per checked engine. Verdict is the headline. */
@Composable
private fun ResultCard(result: EngineCheckResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = result.engine.uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = verdict(result),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = when {
                    result.error != null -> MaterialTheme.colorScheme.error
                    result.realTimeFactor < 0.5 -> MaterialTheme.colorScheme.primary
                    result.realTimeFactor < 1.0 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.error
                }
            )
            InfoLine(
                stringResource(R.string.enginecheck_result_first_run),
                if (result.firstRun) "yes" else "no"
            )
            InfoLine(
                stringResource(R.string.enginecheck_result_init),
                "${result.initMs} ms"
            )
            InfoLine(
                stringResource(R.string.enginecheck_result_audio),
                "${result.audioMs} ms"
            )
            InfoLine(
                stringResource(R.string.enginecheck_result_process),
                "${result.processMs} ms"
            )
            InfoLine(
                stringResource(R.string.enginecheck_result_rtf),
                "%.2f".format(result.realTimeFactor)
            )
            if (result.error != null) {
                Text(
                    text = stringResource(R.string.enginecheck_result_error, result.error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                if (result.diagnostics != null) {
                    Text(
                        text = stringResource(R.string.enginecheck_result_diagnostics_hint),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else if (result.text.isNotBlank()) {
                Text(
                    text = stringResource(R.string.enginecheck_result_text, result.text),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/** Copy + share buttons. Disabled when no check has run yet. */
@Composable
private fun ActionRow(enabled: Boolean, onCopy: () -> Unit, onShare: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        TextButton(enabled = enabled, onClick = onCopy) {
            Text(text = stringResource(R.string.enginecheck_copy_report))
        }
        androidx.compose.material3.IconButton(enabled = enabled, onClick = onShare) {
            Icon(
                imageVector = Icons.Filled.Share,
                contentDescription = stringResource(R.string.enginecheck_share_report)
            )
        }
    }
}

/** Clipboard helper: copies [text] into the system clipboard. */
private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Engine check report", text))
}

/** Share helper: fires an `ACTION_SEND` chooser for [text]. */
private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(intent, context.getString(R.string.enginecheck_share_report))
    chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK
    context.startActivity(chooser)
}
