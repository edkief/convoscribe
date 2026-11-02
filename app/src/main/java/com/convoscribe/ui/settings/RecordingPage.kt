package com.convoscribe.ui.settings

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
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
import com.convoscribe.enginecheck.LiveCleanupVerdict
import com.convoscribe.models.ModelCatalog
import com.convoscribe.settings.FloatingShape
import com.convoscribe.settings.ServiceSource
import com.convoscribe.settings.isUnencryptedUrl
import com.convoscribe.ui.components.ChoiceGroup
import com.convoscribe.ui.components.Disclosure
import com.convoscribe.ui.components.ErrorBanner
import com.convoscribe.ui.components.FieldLabel
import com.convoscribe.ui.components.InlineProgress
import com.convoscribe.ui.components.NavRow
import com.convoscribe.ui.components.NoticeText
import com.convoscribe.ui.components.SectionCard
import com.convoscribe.ui.components.SupportingText
import com.convoscribe.ui.components.SwitchRow
import com.convoscribe.ui.theme.AppTheme

/**
 * "Recording" section: the floating control over other apps (issue
 * #7) and, while it is on, its shape (issue #21; [floatingShape]
 * `null` shows the device default). While it is on but the permission
 * is missing, says so and offers to open the system page.
 */
@Composable
internal fun RecordingSection(
    floatingControl: Boolean,
    floatingShape: String?,
    overlayGranted: Boolean,
    onFloatingControlChange: (Boolean) -> Unit,
    onFloatingShapeChange: (String) -> Unit,
    onAllowOverlay: () -> Unit
) {
    SectionCard(
        modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth),
        title = stringResource(R.string.settings_section_recording)
    ) {
        SwitchRow(
            label = stringResource(R.string.settings_floating_control_label),
            help = stringResource(R.string.settings_floating_control_help),
            checked = floatingControl,
            onCheckedChange = onFloatingControlChange
        )
        if (floatingControl) {
            FieldLabel(text = stringResource(R.string.settings_floating_shape_label))
            ChoiceGroup(
                options = FLOATING_SHAPES.map { (shape, label) -> shape.key to stringResource(label) },
                selected = FloatingShape.resolve(floatingShape, AppTheme.isEink).key,
                onSelect = onFloatingShapeChange
            )
            SupportingText(text = stringResource(R.string.settings_floating_shape_help))
        }
        if (floatingControl && !overlayGranted) {
            NoticeText(text = stringResource(R.string.settings_floating_control_needs_permission))
            TextButton(onClick = onAllowOverlay) {
                Text(text = stringResource(R.string.settings_floating_control_allow))
            }
        }
    }
}

private val FLOATING_SHAPES = listOf(
    FloatingShape.MINIMAL to R.string.settings_floating_shape_minimal,
    FloatingShape.STANDARD to R.string.settings_floating_shape_standard,
    FloatingShape.EXPANDED to R.string.settings_floating_shape_expanded
)

/**
 * "Language and engine": the default language with its model status
 * (and *Delete French model* whenever that bundle is on disk, in use or
 * not, as it is worth ≈71 MB), the speech engine, and Engine check. All
 * written immediately.
 */
@Composable
internal fun TranscriptionSection(
    engine: String,
    language: String,
    frenchModelInstalled: Boolean,
    selectedLanguageModelInstalled: Boolean,
    selectedLanguageModelMissingBytes: Long,
    onEngineChange: (String) -> Unit,
    onLanguageChange: (String) -> Unit,
    onEngineCheck: () -> Unit,
    onDeleteFrenchModel: () -> Unit
) {
    SectionCard(
        modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth),
        title = stringResource(R.string.settings_section_transcription)
    ) {
        FieldLabel(text = stringResource(R.string.settings_transcription_language_label))
        SupportingText(text = stringResource(R.string.settings_defaults_help))
        ChoiceGroup(
            options = listOf(
                "en" to stringResource(R.string.settings_transcription_language_en),
                "fr" to stringResource(R.string.settings_transcription_language_fr)
            ),
            selected = if (language == "en") "en" else "fr",
            onSelect = onLanguageChange
        )
        if (selectedLanguageModelInstalled) {
            SupportingText(text = stringResource(R.string.settings_transcription_language_model_installed))
        } else {
            SupportingText(
                text = stringResource(
                    R.string.settings_transcription_language_model_missing,
                    (selectedLanguageModelMissingBytes / 1_000_000L).toInt()
                )
            )
        }
        if (frenchModelInstalled) {
            TextButton(onClick = onDeleteFrenchModel) {
                Text(text = stringResource(R.string.settings_transcription_language_delete_fr))
            }
        }
        FieldLabel(text = stringResource(R.string.settings_transcription_engine))
        ChoiceGroup(
            options = listOf(
                "npu" to stringResource(R.string.settings_transcription_engine_npu),
                "cpu" to stringResource(R.string.settings_transcription_engine_cpu)
            ),
            selected = if (engine == "npu") "npu" else "cpu",
            onSelect = onEngineChange
        )
        SupportingText(text = stringResource(R.string.settings_transcription_engine_help))
        if (language == "fr") {
            // French only runs on the CPU engine; the selector keeps its value for English.
            SupportingText(text = stringResource(R.string.settings_transcription_french_uses_cpu))
        }
        NavRow(
            label = stringResource(R.string.settings_transcription_engine_check),
            onClick = onEngineCheck
        )
    }
}

/** The page's expert setting: CPU threads. */
@Composable
internal fun RecordingAdvanced(threads: Int, onThreadsChange: (Int) -> Unit) {
    Disclosure(
        title = stringResource(R.string.settings_advanced),
        summary = stringResource(R.string.settings_recording_advanced_summary, threads),
        modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth)
    ) {
        FieldLabel(text = stringResource(R.string.settings_transcription_threads_label))
        Slider(
            value = threads.toFloat(),
            onValueChange = { onThreadsChange(it.toInt().coerceIn(1, 8)) },
            valueRange = 1f..8f,
            steps = 6,
            modifier = Modifier.fillMaxWidth()
        )
        Text(text = threads.toString(), style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * "Noise cleanup" section (issue #37): the optional cleanup model,
 * downloaded or removed here, and whether new conversations clean
 * their audio live ([cleanByDefault], written immediately; the Record
 * screen can change it per conversation). A finished session can be
 * cleaned afterwards from its menu either way.
 */
@Composable
internal fun NoiseCleanupSection(
    installed: Boolean,
    downloading: Boolean,
    cleanByDefault: Boolean,
    liveVerdict: LiveCleanupVerdict?,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onCleanByDefaultChange: (Boolean) -> Unit
) {
    SectionCard(
        modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth),
        title = stringResource(R.string.settings_section_cleanup)
    ) {
        SupportingText(text = stringResource(R.string.settings_cleanup_help))
        when {
            downloading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)
            ) {
                InlineProgress()
                NoticeText(text = stringResource(R.string.settings_cleanup_downloading))
            }
            installed -> {
                SupportingText(text = stringResource(R.string.settings_cleanup_installed))
                TextButton(onClick = onDelete) {
                    Text(text = stringResource(R.string.settings_cleanup_delete))
                }
            }
            else -> OutlinedButton(onClick = onDownload) {
                Text(
                    text = stringResource(
                        R.string.settings_cleanup_download,
                        ModelCatalog.CLEANUP.totalBytes.toFloat() / 1_000_000f
                    )
                )
            }
        }
        SwitchRow(
            label = stringResource(R.string.settings_cleanup_default_label),
            help = stringResource(R.string.settings_cleanup_default_help),
            checked = cleanByDefault && installed,
            enabled = installed,
            onCheckedChange = onCleanByDefaultChange
        )
        if (installed) {
            when (liveVerdict) {
                null -> SupportingText(text = stringResource(R.string.settings_cleanup_not_checked))
                LiveCleanupVerdict.RECOMMENDED -> SupportingText(
                    text = stringResource(R.string.enginecheck_cleanup_verdict_ok)
                )
                else -> NoticeText(text = stringResource(R.string.conversation_cleanup_slow), isError = true)
            }
        }
    }
}

/**
 * "Transcription server" section (issue #58): the base URL (with a
 * warning under a plain `http://` one), API key and
 * model, saved by Save like the LLM's, a Test that lists the server's
 * models, and *Improve recordings on the server* (written immediately,
 * enabled once a server is set up). While the sync server's services
 * are on (issue #94) it starts with the choice between the sync
 * server's transcription and the user's own, whose fields show only
 * when it is chosen.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun TranscriptionServerSection(state: SettingsUiState, actions: SettingsActions) {
    val form = state.form
    val onFormChange = actions.onFormChange
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    SectionCard(
        modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth),
        title = stringResource(R.string.settings_section_server)
    ) {
        SupportingText(text = stringResource(R.string.settings_server_help))
        if (state.serverServicesEnabled) {
            ServiceSourceChoice(
                source = state.asrSource,
                offered = state.serverOffer?.transcription,
                unavailableText = stringResource(R.string.service_source_asr_unavailable),
                onSourceChange = actions.onAsrSourceChange
            )
        }
        // The user's own server, kept while the sync server's is used (issue #94).
        if (!state.serverServicesEnabled || state.asrSource == ServiceSource.OWN) {
            Disclosure(
                title = stringResource(R.string.settings_server_own),
                summary = form.serverUrl.takeIf { it.isNotBlank() }?.let { serverHost(it) }
                    ?: stringResource(R.string.settings_server_own_none),
                outlined = false
            ) {
                OutlinedTextField(
                    value = form.serverUrl,
                    onValueChange = { onFormChange(form.copy(serverUrl = it)) },
                    label = { Text(text = stringResource(R.string.settings_llm_base_url_label)) },
                    placeholder = { Text(text = stringResource(R.string.settings_server_url_placeholder)) },
                    singleLine = true,
                    supportingText = if (isUnencryptedUrl(form.serverUrl)) {
                        { Text(text = stringResource(R.string.settings_server_url_cleartext)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = form.serverKey,
                    onValueChange = { onFormChange(form.copy(serverKey = it)) },
                    label = { Text(text = stringResource(R.string.settings_server_key_label)) },
                    singleLine = true,
                    visualTransformation = if (keyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
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
                OutlinedTextField(
                    value = form.serverModel,
                    onValueChange = { onFormChange(form.copy(serverModel = it)) },
                    label = { Text(text = stringResource(R.string.settings_llm_model_label)) },
                    placeholder = { Text(text = stringResource(R.string.settings_server_model_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)
                ) {
                    OutlinedButton(
                        onClick = actions.onTestServer,
                        enabled = !state.serverTesting && form.serverUrl.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = stringResource(R.string.settings_llm_test))
                    }
                    if (state.serverTesting) InlineProgress()
                }
                when (val result = state.serverTestResult) {
                    is ConnectionTestResult.Connected -> NoticeText(
                        text = pluralStringResource(
                            R.plurals.settings_test_connected,
                            result.count,
                            result.count,
                            result.models.joinToString(", ")
                        )
                    )
                    is ConnectionTestResult.Failed -> ErrorBanner(
                        message = stringResource(R.string.settings_test_failed, result.message)
                    )
                    null -> Unit
                }
                if (state.serverModels.isNotEmpty()) {
                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s),
                        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)
                    ) {
                        state.serverModels.forEach { id ->
                            SuggestionChip(
                                onClick = { onFormChange(form.copy(serverModel = id)) },
                                label = { Text(text = id) }
                            )
                        }
                    }
                }
            }
        }
        SwitchRow(
            label = stringResource(R.string.settings_server_auto_label),
            help = stringResource(R.string.settings_server_auto_help),
            checked = state.serverAuto && state.serverConfigured,
            enabled = state.serverConfigured,
            onCheckedChange = actions.onServerAutoChange
        )
    }
}
