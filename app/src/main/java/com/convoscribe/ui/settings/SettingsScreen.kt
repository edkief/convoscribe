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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.convoscribe.ConvoApp
import com.convoscribe.R
import com.convoscribe.settings.DisplayMode
import com.convoscribe.ui.theme.AppTheme
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
                    modelStore = container.modelStore
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
    SettingsBody(
        state = state,
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
            onDynamicColorChange = vm::setDynamicColor
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
    val onDynamicColorChange: (Boolean) -> Unit = {}
)

/** Render-only body of [SettingsScreen] for previews and screenshot tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsBody(state: SettingsUiState, actions: SettingsActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = actions.onSave) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = stringResource(R.string.settings_save)
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            AppearanceSection(
                displayMode = state.displayMode,
                dynamicColor = state.dynamicColor,
                onDisplayModeChange = actions.onDisplayModeChange,
                onDynamicColorChange = actions.onDynamicColorChange
            )
            LanguageModelSection(
                form = state.form,
                testing = state.testing,
                testResult = state.testResult,
                availableModels = state.availableModels,
                onFormChange = actions.onFormChange,
                onTestConnection = actions.onTestConnection
            )
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
            SpeakersSection(
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
            AboutSection()
            Spacer(Modifier.height(24.dp))
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
    SectionHeader(text = stringResource(R.string.settings_section_appearance))
    Text(
        text = stringResource(R.string.settings_display_mode_label),
        style = MaterialTheme.typography.titleSmall
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        DISPLAY_MODES.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = displayMode == mode.key,
                onClick = { onDisplayModeChange(mode.key) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = DISPLAY_MODES.size)
            ) {
                Text(text = stringResource(label))
            }
        }
    }
    Text(
        text = stringResource(R.string.settings_display_mode_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !AppTheme.isEink) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = dynamicColor, role = Role.Switch, onValueChange = onDynamicColorChange),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_dynamic_color_label),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(R.string.settings_dynamic_color_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = dynamicColor, onCheckedChange = null)
        }
    }
}

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
    SectionHeader(text = stringResource(R.string.settings_section_llm))
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
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onTestConnection,
            enabled = !testing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = stringResource(R.string.settings_llm_test))
        }
    }
    if (testResult != null) {
        Text(
            text = when (testResult) {
                is ConnectionTestResult.Connected -> pluralStringResource(
                    R.plurals.settings_test_connected,
                    testResult.count,
                    testResult.count,
                    testResult.models.joinToString(", ")
                )
                is ConnectionTestResult.Failed -> stringResource(R.string.settings_test_failed, testResult.message)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (testResult is ConnectionTestResult.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
    } else {
        Text(
            text = stringResource(R.string.settings_llm_test_idle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (availableModels.isNotEmpty()) {
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
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
    SectionHeader(text = stringResource(R.string.settings_section_transcription))
    Text(
        text = stringResource(R.string.settings_transcription_engine),
        style = MaterialTheme.typography.titleSmall
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        val npuSelected = engine == "npu"
        SegmentedButton(
            selected = npuSelected,
            onClick = { onEngineChange("npu") },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
        ) {
            Text(text = stringResource(R.string.settings_transcription_engine_npu))
        }
        SegmentedButton(
            selected = !npuSelected,
            onClick = { onEngineChange("cpu") },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
        ) {
            Text(text = stringResource(R.string.settings_transcription_engine_cpu))
        }
    }
    Text(
        text = stringResource(R.string.settings_transcription_engine_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (language == "fr") {
        // French is only supported on the CPU engine today;
        // explain that the engine selector is unchanged but
        // does not apply to a French recording.
        Text(
            text = stringResource(R.string.settings_transcription_french_uses_cpu),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.settings_transcription_language_label),
        style = MaterialTheme.typography.titleSmall
    )
    Text(
        text = stringResource(R.string.settings_defaults_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        val englishSelected = language == "en"
        SegmentedButton(
            selected = englishSelected,
            onClick = { onLanguageChange("en") },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
        ) {
            Text(text = stringResource(R.string.settings_transcription_language_en))
        }
        SegmentedButton(
            selected = !englishSelected,
            onClick = { onLanguageChange("fr") },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
        ) {
            Text(text = stringResource(R.string.settings_transcription_language_fr))
        }
    }
    Spacer(Modifier.height(4.dp))
    if (selectedLanguageModelInstalled) {
        Text(
            text = stringResource(R.string.settings_transcription_language_model_installed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
    Spacer(Modifier.height(8.dp))
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
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.settings_transcription_threads_label),
        style = MaterialTheme.typography.titleSmall
    )
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

/**
 * "Speakers" section: number-of-speakers dropdown (Automatic,
 * 2..6 — `-1` is the "automatic" sentinel persisted by the
 * repository) and a clustering threshold slider with the value
 * to two decimals and the "Lower = more speakers" helper text.
 * The speaker count and [defaultParticipants] are only the defaults
 * of a new conversation, which the Record screen can change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeakersSection(
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
    SectionHeader(text = stringResource(R.string.settings_section_speakers))
    var speakersExpanded by remember { mutableStateOf(false) }
    val speakersLabel = if (numSpeakers <= 0) {
        stringResource(R.string.settings_speakers_automatic)
    } else {
        stringResource(R.string.settings_speakers_count, numSpeakers)
    }
    OutlinedButton(
        onClick = { speakersExpanded = true },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_speakers_number_label)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = speakersLabel)
                Icon(imageVector = Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        }
    }
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
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.settings_speakers_threshold_label),
        style = MaterialTheme.typography.titleSmall
    )
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
    Text(
        text = stringResource(R.string.settings_speakers_threshold_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.settings_speakers_match_threshold_label),
        style = MaterialTheme.typography.titleSmall
    )
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
    Text(
        text = stringResource(R.string.settings_speakers_match_threshold_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))
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

/**
 * Section header used by every settings sub-section. Plain Text
 * in title-medium typography with top padding; kept inline so we
 * do not add another file for three call-sites.
 */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp)
    )
}

/**
 * "About" section: lists every model bundled by the on-device ASR and
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
private fun AboutSection() {
    SectionHeader(text = stringResource(R.string.settings_section_about))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

/**
 * One row in the [AboutSection]. Renders the model name, the licence
 * in bodySmall, and a `TextButton` that opens the upstream URL via
 * [Intent.ACTION_VIEW]. Using a plain `Text` clickable instead of a
 * link gives consistent focus / ripple behaviour inside the
 * scrollable settings column.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutModelRow(name: String, licence: String, url: String) {
    val context = LocalContext.current
    Column {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = licence,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            runCatching { context.startActivity(intent) }
        }) {
            Text(text = url)
        }
    }
}
