package com.convoscribe.ui.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.convoscribe.ConvoApp
import com.convoscribe.R
import com.convoscribe.backup.BackupKind
import com.convoscribe.backup.BackupState
import com.convoscribe.enginecheck.LiveCleanupVerdict
import com.convoscribe.models.ModelCatalog
import com.convoscribe.models.ModelDownloadState
import com.convoscribe.overlay.OverlayPermission
import com.convoscribe.overlay.OverlayPermissionDialog
import com.convoscribe.service.BackupService
import com.convoscribe.service.ModelDownloadService
import com.convoscribe.settings.ServiceSource
import com.convoscribe.ui.components.ScreenScaffold
import com.convoscribe.ui.theme.AppTheme
import com.convoscribe.ui.update.UpdateActions
import com.convoscribe.ui.update.rememberUpdateActions
import com.convoscribe.update.UpdateState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
                    templates = container.summaryTemplates,
                    listServerModels = container.serverTranscriber::listModels,
                    semanticIndex = container.semanticIndexRunner,
                    onSemanticModelChanged = container.semanticIndexScheduler::sync,
                    syncStatus = container.syncStatusStore.status,
                    fetchServerOffer = container.serverConfigClient::fetch,
                    refreshServerOffer = { container.serverOfferRefresher.refresh() }
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
    val semanticDownloading = (downloadState as? ModelDownloadState.Running)
        ?.bundleIds?.contains(ModelCatalog.TEXT_EMBEDDING_GEMMA.id) == true
    // Sessions get indexed by the charging job meanwhile; re-count on return.
    LifecycleResumeEffect(Unit) {
        vm.refreshSemanticProgress()
        onPauseOrDispose {}
    }
    val voiceModel = ModelCatalog.embeddingFor(state.diarizationEmbedding)
    val voiceModelDownloading = (downloadState as? ModelDownloadState.Running)
        ?.bundleIds?.contains(voiceModel.id) == true
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
    // Back up and restore (issue #25): the file is picked here, the
    // work runs in BackupService.
    val backupState by container.backupUseCase.state.collectAsStateWithLifecycle()
    val busyMessage = stringResource(R.string.backup_busy)
    val whenFree: (() -> Unit) -> Unit = { action ->
        if (container.busyForBackup()) {
            coroutineScope.launch { snackbarHostState.showSnackbar(busyMessage) }
        } else {
            action()
        }
    }
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME)) { uri ->
        if (uri != null) ContextCompat.startForegroundService(context, BackupService.backupIntent(context, uri))
    }
    var restoreUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        restoreUri = uri
    }
    restoreUri?.let { uri ->
        RestoreConfirmDialog(
            onConfirm = { restoreSettings ->
                restoreUri = null
                whenFree {
                    ContextCompat.startForegroundService(
                        context,
                        BackupService.restoreIntent(context, uri, restoreSettings)
                    )
                }
            },
            onDismiss = { restoreUri = null }
        )
    }
    // A restore may have replaced the settings this form was loaded
    // from; reload it so leaving the screen does not write them back.
    LaunchedEffect(backupState) {
        if ((backupState as? BackupState.Done)?.kind == BackupKind.RESTORE) vm.reloadForm()
    }
    val backupFileName = stringResource(
        R.string.backup_file_name,
        SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())
    )
    SettingsBody(
        state = state,
        cleanupDownloading = cleanupDownloading,
        semanticDownloading = semanticDownloading,
        voiceModelDownloading = voiceModelDownloading,
        liveCleanupVerdict = liveCleanupVerdict,
        overlayGranted = overlayGranted,
        update = updateState,
        installedVersion = container.installedApp.versionName,
        installedVersionCode = container.installedApp.versionCode,
        updateActions = updateActions,
        backup = backupState,
        snackbarHostState = snackbarHostState,
        actions = SettingsActions(
            onBack = leave,
            onSave = leave,
            onFormChange = vm::update,
            onTestConnection = { coroutineScope.launch { vm.testConnection() } },
            onTestServer = vm::testServer,
            onServerAutoChange = vm::setServerAuto,
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
            onOpenPresets = { navController.navigate("presets") },
            onDownloadCleanup = {
                ContextCompat.startForegroundService(
                    context,
                    ModelDownloadService.startIntent(context, listOf(ModelCatalog.CLEANUP))
                )
            },
            onDeleteCleanup = vm::deleteCleanupModel,
            onDownloadSemantic = {
                ContextCompat.startForegroundService(
                    context,
                    ModelDownloadService.startIntent(context, listOf(ModelCatalog.TEXT_EMBEDDING_GEMMA))
                )
            },
            onDeleteSemantic = vm::deleteSemanticModel,
            onSemanticIndexModeChange = vm::setSemanticIndexMode,
            onIndexSemanticNow = container.semanticIndexScheduler::indexNow,
            onSyncEnabledChange = vm::setSyncEnabled,
            onRequestServerServices = vm::requestServerServices,
            onAcceptServerServices = vm::acceptServerServices,
            onDismissServerServices = vm::dismissServerServices,
            onTurnOffServerServices = vm::turnOffServerServices,
            onLlmSourceChange = vm::setLlmSource,
            onAsrSourceChange = vm::setAsrSource,
            onSyncNow = container.syncScheduler::syncNow,
            onSyncAudioOnMobileDataChange = { on ->
                vm.setSyncAudioOnMobileData(on)
                if (on) container.syncScheduler.syncNow()
            },
            onDiarizationEmbeddingChange = vm::setDiarizationEmbedding,
            onDownloadVoiceModel = {
                ContextCompat.startForegroundService(
                    context,
                    ModelDownloadService.startIntent(context, listOf(voiceModel))
                )
            },
            onDiarizationStepChange = vm::setDiarizationStep,
            onCleanAudioDefaultChange = vm::setCleanAudioDefault,
            onAutoMarkNotesChange = vm::setAutoMarkNotes,
            onAutoTitleChange = vm::setAutoTitle,
            onBackup = { whenFree { createBackup.launch(backupFileName) } },
            onRestore = { whenFree { pickBackup.launch(arrayOf(BACKUP_MIME, "application/octet-stream")) } },
            onCancelBackup = { context.startService(BackupService.cancelIntent(context)) },
            onDismissBackup = container.backupUseCase::dismiss
        )
    )
}

/** Type of a backup file in the system file picker. */
private const val BACKUP_MIME = "application/zip"

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
    val onOpenPresets: () -> Unit = {},
    val onDownloadCleanup: () -> Unit = {},
    val onDeleteCleanup: () -> Unit = {},
    val onDownloadSemantic: () -> Unit = {},
    val onDeleteSemantic: () -> Unit = {},
    val onSemanticIndexModeChange: (String) -> Unit = {},
    val onIndexSemanticNow: () -> Unit = {},
    val onDiarizationEmbeddingChange: (String) -> Unit = {},
    val onDownloadVoiceModel: () -> Unit = {},
    val onDiarizationStepChange: (Float) -> Unit = {},
    val onCleanAudioDefaultChange: (Boolean) -> Unit = {},
    val onAutoMarkNotesChange: (Boolean) -> Unit = {},
    val onAutoTitleChange: (Boolean) -> Unit = {},
    val onTestServer: () -> Unit = {},
    val onServerAutoChange: (Boolean) -> Unit = {},
    val onSyncEnabledChange: (Boolean) -> Unit = {},
    val onSyncNow: () -> Unit = {},
    val onSyncAudioOnMobileDataChange: (Boolean) -> Unit = {},
    val onRequestServerServices: () -> Unit = {},
    val onAcceptServerServices: () -> Unit = {},
    val onDismissServerServices: () -> Unit = {},
    val onTurnOffServerServices: () -> Unit = {},
    val onLlmSourceChange: (ServiceSource) -> Unit = {},
    val onAsrSourceChange: (ServiceSource) -> Unit = {},
    val onBackup: () -> Unit = {},
    val onRestore: () -> Unit = {},
    val onCancelBackup: () -> Unit = {},
    val onDismissBackup: () -> Unit = {}
)

/**
 * Render-only body of [SettingsScreen] for previews and screenshot tests.
 * [overlayGranted] is whether "Display over other apps" is allowed;
 * [update] and [updateActions] drive the update part of About (issue #15).
 * [cleanupDownloading] while the noise cleanup model downloads (issue #37),
 * [semanticDownloading] while the semantic search model does (issue #61);
 * [liveCleanupVerdict] is the engine check's verdict on cleaning live
 * with the selected engine, `null` when it was not checked. [backup] is
 * the running or last backup or restore (issue #25).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsBody(
    state: SettingsUiState,
    actions: SettingsActions,
    cleanupDownloading: Boolean = false,
    semanticDownloading: Boolean = false,
    voiceModelDownloading: Boolean = false,
    liveCleanupVerdict: LiveCleanupVerdict? = null,
    overlayGranted: Boolean = true,
    update: UpdateState = UpdateState.Idle,
    installedVersion: String = "",
    installedVersionCode: Long = 0L,
    updateActions: UpdateActions = UpdateActions(),
    backup: BackupState = BackupState.Idle,
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
                onTestConnection = actions.onTestConnection,
                serverServices = state.serverServicesEnabled,
                source = state.llmSource,
                offered = state.serverOffer?.llm,
                onSourceChange = actions.onLlmSourceChange
            )
            SummariesSection(
                templates = state.summaryTemplates,
                defaultKey = state.defaultSummaryTemplate,
                onDefaultChange = actions.onDefaultSummaryTemplateChange,
                onOpenTemplates = actions.onOpenSummaryTemplates,
                autoMarkNotes = state.autoMarkNotes,
                onAutoMarkNotesChange = actions.onAutoMarkNotesChange,
                autoTitle = state.autoTitle,
                onAutoTitleChange = actions.onAutoTitleChange
            )
            SemanticSearchSection(
                installed = state.semanticInstalled,
                downloading = semanticDownloading,
                progress = state.semanticProgress,
                onDownload = actions.onDownloadSemantic,
                onDelete = actions.onDeleteSemantic,
                indexMode = state.semanticIndexMode,
                onIndexModeChange = actions.onSemanticIndexModeChange,
                onIndexNow = actions.onIndexSemanticNow
            )
            OrganiseSection(onOpenTags = actions.onOpenTags, onOpenPresets = actions.onOpenPresets)
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
            TranscriptionServerSection(state = state, actions = actions)
            SyncSection(
                url = state.form.syncUrl,
                key = state.form.syncKey,
                onUrlChange = { actions.onFormChange(state.form.copy(syncUrl = it)) },
                onKeyChange = { actions.onFormChange(state.form.copy(syncKey = it)) },
                configured = state.form.syncUrl.isNotBlank(),
                enabled = state.syncEnabled,
                status = state.syncStatus,
                onEnabledChange = actions.onSyncEnabledChange,
                onSyncNow = actions.onSyncNow,
                audioOnMobileData = state.syncAudioOnMobileData,
                onAudioOnMobileDataChange = actions.onSyncAudioOnMobileDataChange,
                services = ServerServicesUi(
                    enabled = state.serverServicesEnabled,
                    offer = state.serverOffer,
                    check = state.servicesCheck,
                    onRequest = actions.onRequestServerServices,
                    onAccept = actions.onAcceptServerServices,
                    onDismiss = actions.onDismissServerServices,
                    onTurnOff = actions.onTurnOffServerServices
                )
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
                onOpenKnownSpeakers = actions.onOpenKnownSpeakers,
                voiceModel = VoiceModelUi(
                    selected = state.diarizationEmbedding,
                    installed = state.diarizationEmbeddingInstalled,
                    downloading = voiceModelDownloading,
                    stepSeconds = state.diarizationStepSeconds,
                    onSelect = actions.onDiarizationEmbeddingChange,
                    onDownload = actions.onDownloadVoiceModel,
                    onStepChange = actions.onDiarizationStepChange
                )
            )
            DataSection(
                state = backup,
                onBackup = actions.onBackup,
                onRestore = actions.onRestore,
                onCancel = actions.onCancelBackup,
                onDismiss = actions.onDismissBackup
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
