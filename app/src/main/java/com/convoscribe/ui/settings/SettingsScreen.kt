package com.convoscribe.ui.settings

import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.convoscribe.ConvoApp
import com.convoscribe.R
import com.convoscribe.enginecheck.LiveCleanupVerdict
import com.convoscribe.llm.SummaryTemplate
import com.convoscribe.models.ModelCatalog
import com.convoscribe.models.ModelDownloadState
import com.convoscribe.overlay.OverlayPermission
import com.convoscribe.overlay.OverlayPermissionDialog
import com.convoscribe.service.ModelDownloadService
import com.convoscribe.settings.DisplayMode
import com.convoscribe.settings.FloatingShape
import com.convoscribe.settings.SpeakerIdMode
import com.convoscribe.ui.components.ChoiceGroup
import com.convoscribe.ui.components.ErrorBanner
import com.convoscribe.ui.components.FieldLabel
import com.convoscribe.ui.components.InlineProgress
import com.convoscribe.ui.components.NoticeText
import com.convoscribe.ui.components.PickerField
import com.convoscribe.ui.components.ScreenScaffold
import com.convoscribe.ui.components.SectionCard
import com.convoscribe.ui.components.SupportingText
import com.convoscribe.ui.templates.SummaryTemplatePicker
import com.convoscribe.ui.theme.AppTheme
import com.convoscribe.ui.update.UpdateActions
import com.convoscribe.ui.update.UpdateBanner
import com.convoscribe.ui.update.hasBanner
import com.convoscribe.ui.update.rememberUpdateActions
import com.convoscribe.update.UpdateState
import kotlinx.coroutines.launch

/**
 * Settings screen. Replaces the placeholder route from TASK-21.
 *
 * Layout (top to bottom, single scrollable column):
 *  1. **Language model** section — Base URL (with `Uri` keyboard
 *     and the documented `192.168.1.10:8000/v1` placeholder), API
 *     key (with show / hide toggle), Model name, Max transcript
 *     characters (number keyboard, the `save()` validator keeps
 *     the previous value when the field is empty), the "Test
 *     connection" button (greys out while running) and the
 *     formatted `testResult` line. When the most recent test
 *     returned model ids they appear below as `SuggestionChip`s
 *     that fill the Model field when tapped.
 *  2. **Transcription** section — ASR engine as a
 *     `SingleChoiceSegmentedButtonRow` bound to
 *     `AppSettings.asrEngine` and saved immediately, an Engine
 *     check row that navigates to `engine-check`, a 1..8 thread
 *     slider (6 steps so each step is 1 thread on the
 *     min/max range: 1, 2, 4, 6, 8 is 5 stops; we use 7 steps:
 *     1..8 inclusive which matches the repository's clamping
 *     rule), a disabled "English" language row.
 *  3. **Speakers** section — number-of-speakers dropdown
 *     ("Automatic", 2..6), clustering threshold slider
 *     (0.3..0.8) with the value to two decimals and the
 *     "Lower = more speakers" help text.
 *
 * Save behaviour:
 *  - the "Save" action in the top bar calls [SettingsViewModel.save]
 *    immediately and pops the back stack;
 *  - the segmented button writes the engine through
 *    [SettingsViewModel.setEngine] (no Save round-trip needed);
 *  - the screen-level [BackHandler] + [DisposableEffect] also
 *    call `save()` when the screen leaves the back stack, so the
 *    implicit "OK" gesture is honoured even when the user just
 *    hits the system back without tapping Save.
 *
 * @param navController app-level controller, used for the back
 *   affordance and to push the Engine check destination.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavHostController) {
    val context = LocalContext.current
    val container = (context.applicationContext as ConvoApp).container
    val vm: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    repo = container.settingsRepository,
                    chatClient = container.chatClient,
                    modelStore = container.modelStore,
                    templates = container.summaryTemplates
                )
            }
        }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    // Save the form when the screen leaves composition (back,
    // pop, system back). DisposableEffect's onDispose runs once
    // the composable is removed from the tree, regardless of the
    // path that caused the removal — paired with a BackHandler so
    // the back press navigates to the previous screen while the
    // onDispose persists the in-flight form.
    DisposableEffect(Unit) {
        onDispose { vm.save() }
    }
    BackHandler(enabled = true) {
        vm.save()
        navController.popBackStack()
    }

    val onSavedAck: () -> Unit = { vm.acknowledgeSaved() }
    LaunchedEffect(state.saved) {
        if (state.saved) {
            // Future: trigger a Snackbar here. For now we just
            // acknowledge so `saved` only flips true once per Save
            // tap.
            onSavedAck()
        }
    }

    val leave: () -> Unit = {
        vm.save()
        navController.popBackStack()
    }
    // Re-checked on every resume: the permission is granted on a
    // system page, outside the app.
    var overlayGranted by remember { mutableStateOf(OverlayPermission.granted(context)) }
    LifecycleResumeEffect(Unit) {
        overlayGranted = OverlayPermission.granted(context)
        onPauseOrDispose {}
    }
    var askOverlay by rememberSaveable { mutableStateOf(false) }
    if (askOverlay) {
        OverlayPermissionDialog(
            onAllow = {
                askOverlay = false
                context.startActivity(OverlayPermission.settingsIntent(context))
            },
            onDismiss = { askOverlay = false }
        )
    }
    val snackbarHostState = remember { SnackbarHostState() }
    // The cleanup model downloads through the shared download service
    // (issue #37); re-read the model state once it lands.
    val downloadState by container.modelDownloadManager.state.collectAsStateWithLifecycle()
    val cleanupDownloading = (downloadState as? ModelDownloadState.Running)
        ?.bundleIds?.contains(ModelCatalog.CLEANUP.id) == true
    LaunchedEffect(downloadState) {
        if (downloadState is ModelDownloadState.Completed) vm.refreshModels()
    }
    val updateState by container.updateManager.state.collectAsStateWithLifecycle()
    val updateActions = rememberUpdateActions(container.updateManager) { message ->
        coroutineScope.launch { snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long) }
    }
    // Re-read on resume: the engine check that records it is another screen.
    var liveCleanupVerdict by remember { mutableStateOf<LiveCleanupVerdict?>(null) }
    LifecycleResumeEffect(state.asrEngine, state.asrLanguage) {
        val engine = ModelCatalog.effectiveEngine(state.asrLanguage, state.asrEngine)
        liveCleanupVerdict = container.liveCleanup.verdict(engine)
        onPauseOrDispose {}
    }
    SettingsBody(
        state = state,
        cleanupDownloading = cleanupDownloading,
        liveCleanupVerdict = liveCleanupVerdict,
        overlayGranted = overlayGranted,
        update = updateState,
        installedVersion = container.installedApp.versionName,
        installedVersionCode = container.installedApp.versionCode,
        updateActions = updateActions,
        snackbarHostState = snackbarHostState,
        actions = SettingsActions(
            onBack = leave,
            onSave = leave,
            onFormChange = vm::update,
            onTestConnection = { coroutineScope.launch { vm.testConnection() } },
            onEngineChange = vm::setEngine,
            onLanguageChange = vm::setLanguage,
            onEngineCheck = { navController.navigate("engine-check") },
            onDeleteFrenchModel = vm::deleteFrenchModel,
            onResetDefaultParticipants = vm::resetDefaultParticipants,
            onOpenKnownSpeakers = { navController.navigate("speakers") },
            onDisplayModeChange = vm::setDisplayMode,
            onDynamicColorChange = vm::setDynamicColor,
            onFloatingControlChange = { enabled ->
                vm.setFloatingControl(enabled)
                if (enabled && !overlayGranted) askOverlay = true
            },
            onAllowOverlay = { context.startActivity(OverlayPermission.settingsIntent(context)) },
            onFloatingShapeChange = vm::setFloatingShape,
            onUpdateCheckAutoChange = vm::setUpdateCheckAuto,
            onUpdateIncludePrereleasesChange = vm::setUpdateIncludePrereleases,
            onSpeakerIdModeChange = vm::setSpeakerIdMode,
            onDefaultSummaryTemplateChange = vm::setDefaultSummaryTemplate,
            onOpenSummaryTemplates = { navController.navigate("summary-templates") },
            onOpenTags = { navController.navigate("tags") },
            onDownloadCleanup = {
                ContextCompat.startForegroundService(
                    context,
                    ModelDownloadService.startIntent(context, listOf(ModelCatalog.CLEANUP))
                )
            },
            onDeleteCleanup = vm::deleteCleanupModel,
            onCleanAudioDefaultChange = vm::setCleanAudioDefault,
            onAutoMarkNotesChange = vm::setAutoMarkNotes
        )
    )
}

/** What the Settings screen can do; filled from [SettingsViewModel] and the nav controller. */
internal class SettingsActions(
    val onBack: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onFormChange: (SettingsForm) -> Unit = {},
    val onTestConnection: () -> Unit = {},
    val onEngineChange: (String) -> Unit = {},
    val onLanguageChange: (String) -> Unit = {},
    val onEngineCheck: () -> Unit = {},
    val onDeleteFrenchModel: () -> Unit = {},
    val onResetDefaultParticipants: () -> Unit = {},
    val onOpenKnownSpeakers: () -> Unit = {},
    val onDisplayModeChange: (String) -> Unit = {},
    val onDynamicColorChange: (Boolean) -> Unit = {},
    val onFloatingControlChange: (Boolean) -> Unit = {},
    val onAllowOverlay: () -> Unit = {},
    val onFloatingShapeChange: (String) -> Unit = {},
    val onUpdateCheckAutoChange: (Boolean) -> Unit = {},
    val onUpdateIncludePrereleasesChange: (Boolean) -> Unit = {},
    val onSpeakerIdModeChange: (String) -> Unit = {},
    val onDefaultSummaryTemplateChange: (String) -> Unit = {},
    val onOpenSummaryTemplates: () -> Unit = {},
    val onOpenTags: () -> Unit = {},
    val onDownloadCleanup: () -> Unit = {},
    val onDeleteCleanup: () -> Unit = {},
    val onCleanAudioDefaultChange: (Boolean) -> Unit = {},
    val onAutoMarkNotesChange: (Boolean) -> Unit = {}
)

/**
 * Render-only body of [SettingsScreen] for previews and screenshot tests.
 * [overlayGranted] is whether "Display over other apps" is allowed;
 * [update] and [updateActions] drive the update part of About (issue #15).
 * [cleanupDownloading] while the noise cleanup model downloads (issue #37);
 * [liveCleanupVerdict] is the engine check's verdict on cleaning live
 * with the selected engine, `null` when it was not checked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsBody(
    state: SettingsUiState,
    actions: SettingsActions,
    cleanupDownloading: Boolean = false,
    liveCleanupVerdict: LiveCleanupVerdict? = null,
    overlayGranted: Boolean = true,
    update: UpdateState = UpdateState.Idle,
    installedVersion: String = "",
    installedVersionCode: Long = 0L,
    updateActions: UpdateActions = UpdateActions(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    ScreenScaffold(
        title = stringResource(R.string.settings_title),
        onBack = actions.onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        actions = {
            IconButton(onClick = actions.onSave) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringResource(R.string.settings_save)
                )
            }
        }
    ) { innerPadding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppTheme.spacing.screen, vertical = AppTheme.spacing.s),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.l),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AppearanceSection(
                displayMode = state.displayMode,
                dynamicColor = state.dynamicColor,
                onDisplayModeChange = actions.onDisplayModeChange,
                onDynamicColorChange = actions.onDynamicColorChange
            )
            RecordingSection(
                floatingControl = state.floatingControl,
                floatingShape = state.floatingShape,
                overlayGranted = overlayGranted,
                onFloatingControlChange = actions.onFloatingControlChange,
                onFloatingShapeChange = actions.onFloatingShapeChange,
                onAllowOverlay = actions.onAllowOverlay
            )
            LanguageModelSection(
                form = state.form,
                testing = state.testing,
                testResult = state.testResult,
                availableModels = state.availableModels,
                onFormChange = actions.onFormChange,
                onTestConnection = actions.onTestConnection
            )
            SummariesSection(
                templates = state.summaryTemplates,
                defaultKey = state.defaultSummaryTemplate,
                onDefaultChange = actions.onDefaultSummaryTemplateChange,
                onOpenTemplates = actions.onOpenSummaryTemplates,
                autoMarkNotes = state.autoMarkNotes,
                onAutoMarkNotesChange = actions.onAutoMarkNotesChange
            )
            OrganiseSection(onOpenTags = actions.onOpenTags)
            TranscriptionSection(
                engine = state.asrEngine,
                language = state.asrLanguage,
                threads = state.form.numThreads,
                frenchModelInstalled = state.frenchModelInstalled,
                selectedLanguageModelInstalled = state.selectedLanguageModelInstalled,
                selectedLanguageModelMissingBytes = state.selectedLanguageModelMissingBytes,
                onEngineChange = actions.onEngineChange,
                onLanguageChange = actions.onLanguageChange,
                onThreadsChange = { threads ->
                    actions.onFormChange(state.form.copy(numThreads = threads))
                },
                onEngineCheck = actions.onEngineCheck,
                onDeleteFrenchModel = actions.onDeleteFrenchModel
            )
            NoiseCleanupSection(
                installed = state.cleanupInstalled,
                downloading = cleanupDownloading,
                cleanByDefault = state.cleanAudioDefault,
                liveVerdict = liveCleanupVerdict,
                onDownload = actions.onDownloadCleanup,
                onDelete = actions.onDeleteCleanup,
                onCleanByDefaultChange = actions.onCleanAudioDefaultChange
            )
            SpeakersSection(
                speakerIdMode = state.speakerIdMode,
                onSpeakerIdModeChange = actions.onSpeakerIdModeChange,
                numSpeakers = state.form.numSpeakers,
                defaultParticipants = state.defaultParticipants,
                onResetDefaultParticipants = actions.onResetDefaultParticipants,
                threshold = state.form.clusterThreshold,
                matchThreshold = state.form.speakerMatchThreshold,
                onNumSpeakersChange = { count ->
                    actions.onFormChange(state.form.copy(numSpeakers = count))
                },
                onThresholdChange = { value ->
                    actions.onFormChange(state.form.copy(clusterThreshold = value))
                },
                onMatchThresholdChange = { value ->
                    actions.onFormChange(state.form.copy(speakerMatchThreshold = value))
                },
                onOpenKnownSpeakers = actions.onOpenKnownSpeakers
            )
            AboutSection(
                installedVersion = installedVersion,
                installedVersionCode = installedVersionCode,
                update = update,
                checkAuto = state.updateCheckAuto,
                includePrereleases = state.updateIncludePrereleases,
                updateActions = updateActions,
                onCheckAutoChange = actions.onUpdateCheckAutoChange,
                onIncludePrereleasesChange = actions.onUpdateIncludePrereleasesChange
            )
            Spacer(Modifier.height(AppTheme.spacing.xl))
        }
    }
}

/**
 * "Appearance" section: the display mode (Auto / Standard / E-ink) and,
 * on Android 12+ outside e-ink mode, "Use device colours". Both are
 * written immediately and the app re-themes at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSection(
    displayMode: String,
    dynamicColor: Boolean,
    onDisplayModeChange: (String) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit
) {
    SectionCard(
        modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth),
        title = stringResource(R.string.settings_section_appearance)
    ) {
        FieldLabel(text = stringResource(R.string.settings_display_mode_label))
        ChoiceGroup(
            options = DISPLAY_MODES.map { (mode, label) -> mode.key to stringResource(label) },
            selected = displayMode,
            onSelect = onDisplayModeChange
        )
        SupportingText(text = stringResource(R.string.settings_display_mode_help))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !AppTheme.isEink) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = dynamicColor, role = Role.Switch, onValueChange = onDynamicColorChange),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_dynamic_color_label),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    SupportingText(text = stringResource(R.string.settings_dynamic_color_help))
                }
                Switch(checked = dynamicColor, onCheckedChange = null)
            }
        }
    }
}

/**
 * "Recording" section: the floating control over other apps (issue
 * #7) and, while it is on, its shape (issue #21; [floatingShape]
 * `null` shows the device default). While it is on but the permission
 * is missing, says so and offers to open the system page.
 */
@Composable
private fun RecordingSection(
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = floatingControl, role = Role.Switch, onValueChange = onFloatingControlChange),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_floating_control_label),
                    style = MaterialTheme.typography.bodyLarge
                )
                SupportingText(text = stringResource(R.string.settings_floating_control_help))
            }
            Switch(checked = floatingControl, onCheckedChange = null)
        }
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

private val SPEAKER_ID_MODES = listOf(
    SpeakerIdMode.AUTO to R.string.settings_speakers_id_mode_auto,
    SpeakerIdMode.CHARGING to R.string.settings_speakers_id_mode_charging,
    SpeakerIdMode.MANUAL to R.string.settings_speakers_id_mode_manual
)

private val DISPLAY_MODES = listOf(
    DisplayMode.AUTO to R.string.settings_display_mode_auto,
    DisplayMode.STANDARD to R.string.settings_display_mode_standard,
    DisplayMode.EINK to R.string.settings_display_mode_eink
)

/**
 * "Language model" section: base URL, API key (with show/hide
 * toggle), model name, max transcript characters, the "Test
 * connection" button and the formatted result line. Model id
 * chips appear below the result when the last `listModels` call
 * returned at least one id.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)
@Composable
private fun LanguageModelSection(
    form: SettingsForm,
    testing: Boolean,
    testResult: ConnectionTestResult?,
    availableModels: List<String>,
    onFormChange: (SettingsForm) -> Unit,
    onTestConnection: () -> Unit
) {
    var apiKeyVisible by rememberSaveable { mutableStateOf(false) }
    SectionCard(
        modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth),
        title = stringResource(R.string.settings_section_llm)
    ) {
        OutlinedTextField(
            value = form.baseUrl,
            onValueChange = { onFormChange(form.copy(baseUrl = it)) },
            label = { Text(text = stringResource(R.string.settings_llm_base_url_label)) },
            placeholder = { Text(text = stringResource(R.string.settings_llm_base_url_placeholder)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = form.apiKey,
            onValueChange = { onFormChange(form.copy(apiKey = it)) },
            label = { Text(text = stringResource(R.string.settings_llm_api_key_label)) },
            singleLine = true,
            visualTransformation = if (apiKeyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                    Text(
                        text = stringResource(
                            if (apiKeyVisible) {
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
            value = form.model,
            onValueChange = { onFormChange(form.copy(model = it)) },
            label = { Text(text = stringResource(R.string.settings_llm_model_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = form.maxChars,
            onValueChange = { onFormChange(form.copy(maxChars = it)) },
            label = { Text(text = stringResource(R.string.settings_llm_max_chars_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)
        ) {
            OutlinedButton(
                onClick = onTestConnection,
                enabled = !testing,
                modifier = Modifier.weight(1f)
            ) {
                Text(text = stringResource(R.string.settings_llm_test))
            }
            if (testing) InlineProgress()
        }
        when (testResult) {
            is ConnectionTestResult.Connected -> NoticeText(
                text = pluralStringResource(
                    R.plurals.settings_test_connected,
                    testResult.count,
                    testResult.count,
                    testResult.models.joinToString(", ")
                )
            )
            is ConnectionTestResult.Failed -> ErrorBanner(
                message = stringResource(R.string.settings_test_failed, testResult.message)
            )
            null -> SupportingText(text = stringResource(R.string.settings_llm_test_idle))
        }
        if (availableModels.isNotEmpty()) {
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)
            ) {
                availableModels.forEach { id ->
                    SuggestionChip(
                        onClick = { onFormChange(form.copy(model = id)) },
                        label = { Text(text = id) }
                    )
                }
            }
        }
    }
}

/**
 * "Transcription" section: ASR engine segmented button (saves
 * immediately on click) with the "French uses the CPU engine."
 * hint when Français is selected, a Language
 * `SingleChoiceSegmentedButtonRow` ("English" / "Français")
 * bound to `AppSettings.asrLanguage`, the model status caption
 * under it ("Model installed" / "Model not downloaded (N MB) –
 * it will be downloaded before your next recording") and a
 * "Delete French model" text button when French is selected but
 * not installed (or simply installed while English is active).
 * Then the "Engine check" navigation row and a CPU threads
 * 1..8 slider.
 */
/**
 * "Summaries" section (issue #27): the template new conversations
 * start with (written immediately), Templates to add or edit them, and
 * whether the notes of marks are written once speakers are identified
 * (issue #48, written immediately).
 */
@Composable
private fun SummariesSection(
    templates: List<SummaryTemplate>,
    defaultKey: String,
    onDefaultChange: (String) -> Unit,
    onOpenTemplates: () -> Unit,
    autoMarkNotes: Boolean,
    onAutoMarkNotesChange: (Boolean) -> Unit
) {
    SectionCard(
        modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth),
        title = stringResource(R.string.settings_section_summaries)
    ) {
        SummaryTemplatePicker(
            label = stringResource(R.string.settings_summaries_default_label),
            templates = templates,
            selectedKey = defaultKey,
            onSelect = onDefaultChange
        )
        SupportingText(text = stringResource(R.string.settings_summaries_default_help))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_summaries_templates_row),
                style = MaterialTheme.typography.bodyLarge
            )
            OutlinedButton(onClick = onOpenTemplates) {
                Text(text = stringResource(R.string.settings_summaries_templates_button))
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = autoMarkNotes, role = Role.Switch, onValueChange = onAutoMarkNotesChange),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_auto_mark_notes_label),
                    style = MaterialTheme.typography.bodyLarge
                )
                SupportingText(text = stringResource(R.string.settings_auto_mark_notes_help))
            }
            Switch(checked = autoMarkNotes, onCheckedChange = null)
        }
    }
}

/** Tags (and presets) of conversations (issue #32), each managed on its own screen. */
@Composable
private fun OrganiseSection(onOpenTags: () -> Unit) {
    SectionCard(
        modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth),
        title = stringResource(R.string.settings_section_organise)
    ) {
        ManageRow(
            label = stringResource(R.string.settings_organise_tags_row),
            help = stringResource(R.string.settings_organise_tags_help),
            onClick = onOpenTags
        )
    }
}

/** A setting kept on its own screen: [label], [help] below it, and Manage. */
@Composable
private fun ManageRow(label: String, help: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.m),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            SupportingText(text = help)
        }
        OutlinedButton(onClick = onClick) {
            Text(text = stringResource(R.string.settings_summaries_templates_button))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranscriptionSection(
    engine: String,
    language: String,
    threads: Int,
    frenchModelInstalled: Boolean,
    selectedLanguageModelInstalled: Boolean,
    selectedLanguageModelMissingBytes: Long,
    onEngineChange: (String) -> Unit,
    onLanguageChange: (String) -> Unit,
    onThreadsChange: (Int) -> Unit,
    onEngineCheck: () -> Unit,
    onDeleteFrenchModel: () -> Unit
) {
    SectionCard(
        modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth),
        title = stringResource(R.string.settings_section_transcription)
    ) {
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
            // French is only supported on the CPU engine today;
            // explain that the engine selector is unchanged but
            // does not apply to a French recording.
            SupportingText(text = stringResource(R.string.settings_transcription_french_uses_cpu))
        }
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
            Text(
                text = stringResource(
                    R.string.settings_transcription_language_model_missing,
                    (selectedLanguageModelMissingBytes / 1_000_000L).toInt()
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // The "Delete French model" action is only useful when the
        // French bundle is actually on disk. Show it whenever it's
        // installed, regardless of the active language: a user with
        // English selected and French sitting unused on disk probably
        // wants the ≈71 MB back.
        if (frenchModelInstalled) {
            TextButton(onClick = onDeleteFrenchModel) {
                Text(text = stringResource(R.string.settings_transcription_language_delete_fr))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_transcription_engine_check),
                style = MaterialTheme.typography.bodyLarge
            )
            OutlinedButton(onClick = onEngineCheck) {
                Text(text = stringResource(R.string.settings_transcription_engine_check))
            }
        }
        FieldLabel(text = stringResource(R.string.settings_transcription_threads_label))
        Slider(
            value = threads.toFloat(),
            onValueChange = { onThreadsChange(it.toInt().coerceIn(1, 8)) },
            valueRange = 1f..8f,
            steps = 6,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = threads.toString(),
            style = MaterialTheme.typography.bodySmall
        )
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
private fun NoiseCleanupSection(
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = cleanByDefault,
                    enabled = installed,
                    role = Role.Switch,
                    onValueChange = onCleanByDefaultChange
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_cleanup_default_label),
                    style = MaterialTheme.typography.bodyLarge
                )
                SupportingText(text = stringResource(R.string.settings_cleanup_default_help))
            }
            Switch(checked = cleanByDefault && installed, onCheckedChange = null, enabled = installed)
        }
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
 * "Speakers" section: when speakers are identified ([speakerIdMode],
 * issue #11, written immediately), number-of-speakers dropdown (Automatic,
 * 2..6 — `-1` is the "automatic" sentinel persisted by the
 * repository) and a clustering threshold slider with the value
 * to two decimals and the "Lower = more speakers" helper text.
 * The speaker count and [defaultParticipants] are only the defaults
 * of a new conversation, which the Record screen can change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeakersSection(
    speakerIdMode: String,
    onSpeakerIdModeChange: (String) -> Unit,
    numSpeakers: Int,
    defaultParticipants: List<String>?,
    onResetDefaultParticipants: () -> Unit,
    threshold: Float,
    matchThreshold: Float,
    onNumSpeakersChange: (Int) -> Unit,
    onThresholdChange: (Float) -> Unit,
    onMatchThresholdChange: (Float) -> Unit,
    onOpenKnownSpeakers: () -> Unit
) {
    var speakersExpanded by remember { mutableStateOf(false) }
    SectionCard(
        modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth),
        title = stringResource(R.string.settings_section_speakers)
    ) {
        FieldLabel(text = stringResource(R.string.settings_speakers_id_mode_label))
        ChoiceGroup(
            options = SPEAKER_ID_MODES.map { (mode, label) -> mode.key to stringResource(label) },
            selected = SpeakerIdMode.of(speakerIdMode).key,
            onSelect = onSpeakerIdModeChange
        )
        SupportingText(text = stringResource(R.string.settings_speakers_id_mode_help))
        val speakersLabel = if (numSpeakers <= 0) {
            stringResource(R.string.settings_speakers_automatic)
        } else {
            stringResource(R.string.settings_speakers_count, numSpeakers)
        }
        PickerField(
            label = stringResource(R.string.settings_speakers_number_label),
            value = speakersLabel,
            onClick = { speakersExpanded = true }
        ) {
            DropdownMenu(
                expanded = speakersExpanded,
                onDismissRequest = { speakersExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.settings_speakers_automatic)) },
                    onClick = {
                        onNumSpeakersChange(-1)
                        speakersExpanded = false
                    }
                )
                (2..6).forEach { count ->
                    DropdownMenuItem(
                        text = { Text(text = count.toString()) },
                        onClick = {
                            onNumSpeakersChange(count)
                            speakersExpanded = false
                        }
                    )
                }
            }
        }
        FieldLabel(text = stringResource(R.string.settings_speakers_threshold_label))
        Slider(
            value = threshold.coerceIn(0.4f, 0.95f),
            onValueChange = { onThresholdChange(it.coerceIn(0.4f, 0.95f)) },
            valueRange = 0.4f..0.95f,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = stringResource(R.string.settings_speakers_threshold_value, threshold),
            style = MaterialTheme.typography.bodySmall
        )
        SupportingText(text = stringResource(R.string.settings_speakers_threshold_help))
        FieldLabel(text = stringResource(R.string.settings_speakers_match_threshold_label))
        Slider(
            value = matchThreshold.coerceIn(0.3f, 0.8f),
            onValueChange = { onMatchThresholdChange(it.coerceIn(0.3f, 0.8f)) },
            valueRange = 0.3f..0.8f,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = stringResource(R.string.settings_speakers_match_threshold_value, matchThreshold),
            style = MaterialTheme.typography.bodySmall
        )
        SupportingText(text = stringResource(R.string.settings_speakers_match_threshold_help))
        // "Known speakers" row: navigates to the SpeakersScreen where
        // the user records a 15-second voice sample and labels
        // diarization clusters with the speaker's real name. Lives
        // inside the Speakers section because both controls feed the
        // diarization pipeline (number-of-speakers / threshold for the
        // clustering algorithm; the enrollment list for the cosine
        // match against each cluster's centroid).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_speakers_known_row),
                style = MaterialTheme.typography.bodyLarge
            )
            OutlinedButton(onClick = onOpenKnownSpeakers) {
                Text(text = stringResource(R.string.settings_speakers_known_button))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    R.string.settings_speakers_default_participants,
                    defaultParticipants?.joinToString(", ")
                        ?: stringResource(R.string.settings_speakers_default_participants_anyone)
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            if (defaultParticipants != null) {
                TextButton(onClick = onResetDefaultParticipants) {
                    Text(text = stringResource(R.string.settings_speakers_default_participants_reset))
                }
            }
        }
    }
}

/**
 * "About" section: the installed version and updates (issue #15) —
 * *Check for updates* with its result (or the offer and its download,
 * as on the Home banner), *Check for updates automatically* and
 * *Include pre-releases* — then every model bundled by the on-device ASR and
 * diarization pipeline alongside its licence and a source link. Renders
 * after the three editable sections as a read-only credit roll so a
 * user who skipped the README can still see which upstream / licence
 * applies to a given model.
 *
 * Each [AboutModelRow] shows the model name, the licence in bodySmall,
 * and a tappable `Uri` link to the upstream source (`Intent.ACTION_VIEW`,
 * `chooser` skipped so a single browser is launched directly). The
 * Kroko rows in particular highlight the `CC-BY-SA 4.0` attribution
 * requirement (the LICENSE places copyleft on derivative works, so the
 * README mirrors this list with the same wording).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutSection(
    installedVersion: String,
    installedVersionCode: Long,
    update: UpdateState,
    checkAuto: Boolean,
    includePrereleases: Boolean,
    updateActions: UpdateActions,
    onCheckAutoChange: (Boolean) -> Unit,
    onIncludePrereleasesChange: (Boolean) -> Unit
) {
    SectionCard(
        modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth),
        title = stringResource(R.string.settings_section_about)
    ) {
        Text(
            text = stringResource(R.string.settings_about_version, installedVersion, installedVersionCode),
            style = MaterialTheme.typography.bodyLarge
        )
        val checking = update is UpdateState.Checking
        val busy = checking || update is UpdateState.Downloading || update is UpdateState.Installing ||
            update is UpdateState.ReadyToInstall
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)
        ) {
            OutlinedButton(onClick = updateActions.onCheck, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.settings_update_check))
            }
            if (checking) InlineProgress()
        }
        when {
            checking -> SupportingText(text = stringResource(R.string.settings_update_checking))
            update == UpdateState.UpToDate -> NoticeText(text = stringResource(R.string.settings_update_up_to_date))
            update == UpdateState.CheckFailed -> NoticeText(
                text = stringResource(R.string.settings_update_check_failed),
                isError = true
            )
            update.hasBanner() -> UpdateBanner(state = update, actions = updateActions, dismissible = false)
        }
        SwitchRow(
            label = stringResource(R.string.settings_update_auto_label),
            help = stringResource(R.string.settings_update_auto_help),
            checked = checkAuto,
            onCheckedChange = onCheckAutoChange
        )
        SwitchRow(
            label = stringResource(R.string.settings_update_prerelease_label),
            help = stringResource(R.string.settings_update_prerelease_help),
            checked = includePrereleases,
            onCheckedChange = onIncludePrereleasesChange
        )
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
            AboutModelRow(
                name = stringResource(R.string.settings_about_nemotron_name),
                licence = stringResource(R.string.settings_about_nemotron_licence),
                url = "https://www.nvidia.com/en-us/agreements/enterprise-software/nvidia-open-model-license/"
            )
            AboutModelRow(
                name = stringResource(R.string.settings_about_kroko_name),
                licence = stringResource(R.string.settings_about_kroko_licence),
                url = "https://huggingface.co/Banafo/Kroko-ASR"
            )
            AboutModelRow(
                name = stringResource(R.string.settings_about_pyannote_name),
                licence = stringResource(R.string.settings_about_pyannote_licence),
                url = "https://github.com/pyannote/pyannote-segmentation"
            )
            AboutModelRow(
                name = stringResource(R.string.settings_about_wespeaker_name),
                licence = stringResource(R.string.settings_about_wespeaker_licence),
                url = "https://github.com/wenet-e2e/wespeaker"
            )
            AboutModelRow(
                name = stringResource(R.string.settings_about_gtcrn_name),
                licence = stringResource(R.string.settings_about_gtcrn_licence),
                url = "https://github.com/Xiaobin-Rong/gtcrn"
            )
            AboutModelRow(
                name = stringResource(R.string.settings_about_sherpa_name),
                licence = stringResource(R.string.settings_about_sherpa_licence),
                url = "https://github.com/k2-fsa/sherpa-onnx"
            )
            AboutModelRow(
                name = stringResource(R.string.settings_about_qnn_name),
                licence = stringResource(R.string.settings_about_qnn_licence),
                url = "https://developer.qualcomm.com/software/qualcomm-neural-processing-sdk"
            )
        }
    }
}

/** A labelled switch with help text; the whole row toggles it. */
@Composable
private fun SwitchRow(label: String, help: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            SupportingText(text = help)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * One row in the [AboutSection]: the model name and licence, and a
 * "Source" button that opens the upstream URL via [Intent.ACTION_VIEW]. Using a plain `Text` clickable instead of a
 * link gives consistent focus / ripple behaviour inside the
 * scrollable settings column.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutModelRow(name: String, licence: String, url: String) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            SupportingText(text = licence)
        }
        TextButton(onClick = {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            runCatching { context.startActivity(intent) }
        }) {
            Text(text = stringResource(R.string.settings_about_source))
        }
    }
}
