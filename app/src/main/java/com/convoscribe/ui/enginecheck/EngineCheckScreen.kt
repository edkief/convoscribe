package com.convoscribe.ui.enginecheck

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.convoscribe.ConvoApp
import com.convoscribe.R
import com.convoscribe.enginecheck.DeviceInfo
import com.convoscribe.enginecheck.EngineCheckResult
import com.convoscribe.enginecheck.NpuProbeClient
import com.convoscribe.ui.components.ErrorBanner
import com.convoscribe.ui.components.InfoRow
import com.convoscribe.ui.components.NoticeText
import com.convoscribe.ui.components.ProgressBlock
import com.convoscribe.ui.components.ScreenScaffold
import com.convoscribe.ui.components.SectionCard
import com.convoscribe.ui.components.SupportingText
import com.convoscribe.ui.theme.AppTheme

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
        storageLow = vm.storageLow,
        onBack = { navController.popBackStack() },
        onRunCheck = vm::runCheck,
        onOpenSetup = { engine -> navController.navigate("setup?engine=$engine") },
        onCopyReport = { copyToClipboard(context, vm.buildReport()) },
        onShareReport = { shareText(context, vm.buildReport()) }
    )
}

/** Render-only body of [EngineCheckScreen] for previews and screenshot tests. */
@Composable
internal fun EngineCheckBody(
    state: EngineCheckUiState,
    storageLow: Boolean,
    onBack: () -> Unit,
    onRunCheck: (String) -> Unit,
    onOpenSetup: (String) -> Unit,
    onCopyReport: () -> Unit,
    onShareReport: () -> Unit
) {
    val spacing = AppTheme.spacing
    ScreenScaffold(title = stringResource(R.string.enginecheck_title), onBack = onBack) { innerPadding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screen, vertical = spacing.s),
            verticalArrangement = Arrangement.spacedBy(spacing.m),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val width = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth)
            DeviceInfoCard(
                info = state.deviceInfo,
                htpSkelCount = state.deviceInfo?.htpSkelLibs?.size ?: 0,
                modifier = width
            )
            SectionCard(modifier = width, title = stringResource(R.string.enginecheck_checks_title)) {
                if (storageLow) ErrorBanner(message = stringResource(R.string.enginecheck_storage_low))
                if (state.recordingBlocked) {
                    ErrorBanner(
                        message = stringResource(R.string.enginecheck_stop_recording_first)
                    )
                }
                val missingEngine = state.missingEngine
                if (state.modelMissing && missingEngine != null) {
                    NoticeText(text = stringResource(R.string.enginecheck_download_model_first, missingEngine))
                    OutlinedButton(onClick = { onOpenSetup(missingEngine) }) {
                        Text(text = stringResource(R.string.enginecheck_open_setup))
                    }
                } else {
                    if (state.runningEngine != null) {
                        val running = if (state.runningEngine == "npu") {
                            R.string.enginecheck_running_npu
                        } else {
                            R.string.enginecheck_running
                        }
                        ProgressBlock(message = stringResource(running))
                    }
                    CheckButtons(
                        enabled = state.runningEngine == null,
                        onCheckNpu = { onRunCheck("npu") },
                        onCheckCpu = { onRunCheck("cpu") }
                    )
                }
            }
            state.results["npu"]?.let { ResultCard(it, modifier = width) }
            state.results["cpu"]?.let { ResultCard(it, modifier = width) }
            ActionRow(
                enabled = state.results.isNotEmpty(),
                onCopy = onCopyReport,
                onShare = onShareReport,
                modifier = width
            )
        }
    }
}

/** "Check NPU" and "Check CPU", side by side. */
@Composable
private fun CheckButtons(enabled: Boolean, onCheckNpu: () -> Unit, onCheckCpu: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)
    ) {
        Button(modifier = Modifier.weight(1f), enabled = enabled, onClick = onCheckNpu) {
            Text(text = stringResource(R.string.enginecheck_check_npu))
        }
        Button(modifier = Modifier.weight(1f), enabled = enabled, onClick = onCheckCpu) {
            Text(text = stringResource(R.string.enginecheck_check_cpu))
        }
    }
}

/** Top card: who am I running on, and is the NPU even plausible? */
@Composable
private fun DeviceInfoCard(info: DeviceInfo?, htpSkelCount: Int, modifier: Modifier = Modifier) {
    SectionCard(modifier = modifier, title = stringResource(R.string.enginecheck_device_title)) {
        if (info == null) {
            ProgressBlock(message = stringResource(R.string.enginecheck_device_loading))
        } else {
            InfoRow(stringResource(R.string.enginecheck_device_manufacturer), info.manufacturer)
            InfoRow(stringResource(R.string.enginecheck_device_model), info.model)
            InfoRow(stringResource(R.string.enginecheck_device_soc), "${info.socManufacturer} ${info.socModel}")
            InfoRow(stringResource(R.string.enginecheck_device_hardware), info.hardware)
            InfoRow(stringResource(R.string.enginecheck_device_android), info.androidVersion)
            InfoRow(stringResource(R.string.enginecheck_device_cdsp), yesNo(info.cdspLibraryPresent))
            InfoRow(stringResource(R.string.enginecheck_device_htp), htpSkelCount.toString())
            InfoRow(
                stringResource(R.string.enginecheck_device_free_storage),
                stringResource(R.string.unit_gigabytes, info.freeStorageBytes / 1_000_000_000.0)
            )
        }
    }
}

/** One result card per checked engine. Verdict is the headline. */
@Composable
private fun ResultCard(result: EngineCheckResult, modifier: Modifier = Modifier) {
    SectionCard(modifier = modifier, title = result.engine.uppercase()) {
        Text(
            text = verdictText(result),
            style = MaterialTheme.typography.titleLarge,
            color = when {
                result.error != null -> MaterialTheme.colorScheme.error
                result.realTimeFactor < 0.5 -> MaterialTheme.colorScheme.primary
                result.realTimeFactor < 1.0 -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.error
            }
        )
        InfoRow(stringResource(R.string.enginecheck_result_first_run), yesNo(result.firstRun))
        InfoRow(
            stringResource(R.string.enginecheck_result_init),
            stringResource(R.string.unit_milliseconds, result.initMs)
        )
        InfoRow(
            stringResource(R.string.enginecheck_result_audio),
            stringResource(R.string.unit_milliseconds, result.audioMs)
        )
        InfoRow(
            stringResource(R.string.enginecheck_result_process),
            stringResource(R.string.unit_milliseconds, result.processMs)
        )
        InfoRow(stringResource(R.string.enginecheck_result_rtf), "%.2f".format(result.realTimeFactor))
        if (result.error != null) {
            ErrorBanner(message = stringResource(R.string.enginecheck_result_error, result.error))
            if (result.diagnostics != null) {
                SupportingText(text = stringResource(R.string.enginecheck_result_diagnostics_hint))
            }
        } else if (result.text.isNotBlank()) {
            SupportingText(text = stringResource(R.string.enginecheck_result_text, result.text))
        }
    }
}

/** Copy + share buttons. Disabled when no check has run yet. */
@Composable
private fun ActionRow(enabled: Boolean, onCopy: () -> Unit, onShare: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(enabled = enabled, onClick = onCopy) {
            Text(text = stringResource(R.string.enginecheck_copy_report))
        }
        IconButton(enabled = enabled, onClick = onShare) {
            Icon(
                imageVector = Icons.Filled.Share,
                contentDescription = stringResource(R.string.enginecheck_share_report)
            )
        }
    }
}

@Composable
private fun yesNo(value: Boolean): String = stringResource(if (value) R.string.common_yes else R.string.common_no)

/** The verdict headline in the UI language; the copied report keeps [verdict]'s English. */
@Composable
private fun verdictText(result: EngineCheckResult): String = stringResource(
    when {
        result.error != null -> R.string.enginecheck_verdict_failed
        result.realTimeFactor < 0.5 -> R.string.enginecheck_verdict_ok
        result.realTimeFactor < 1.0 -> R.string.enginecheck_verdict_marginal
        else -> R.string.enginecheck_verdict_too_slow
    }
)

/** Clipboard helper: copies [text] into the system clipboard. */
private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.enginecheck_copy_report), text))
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
