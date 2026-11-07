package com.convoscribe.ui.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Settings (issue #98): an overview of the pages, each opening in place
 * ([SettingsPage]), all in this one destination so they share one
 * [SettingsViewModel], the file pickers and the snackbar.
 *
 * Save behaviour: choices and switches are written immediately; text
 * fields and sliders when their page is left, and when the screen
 * leaves composition, whichever way that happens. There is no Save
 * action.
 *
 * @param navController app-level controller, used to leave and to push
 *   the screens opened from Settings (Engine check, Known speakers…).
 * @param initialPage [SettingsPage.key] of the page to open first,
 *   `null` for the overview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavHostController, initialPage: String? = null) {
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
                    refreshServerOffer = { container.serverOfferRefresher.refresh() },
                    pingDecision = container.decisionClient::ping
                )
            }
        }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    // Save the form when the screen leaves composition, whatever caused it.
    DisposableEffect(Unit) {
        onDispose { vm.save() }
    }
    // Survives rotation and the screens and pickers opened from a page.
    var pageKey by rememberSaveable { mutableStateOf(initialPage) }

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
        page = SettingsPage.of(pageKey),
        onPageChange = { next ->
            vm.save()
            pageKey = next?.key
        },
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
            onDecisionSourceChange = vm::setDecisionSource,
            onDecisionForTalkingPointsChange = vm::setDecisionForTalkingPoints,
            onTestDecision = vm::testDecision,
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
            onAutoSummaryChange = vm::setAutoSummary,
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
    val onAutoSummaryChange: (Boolean) -> Unit = {},
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
    val onDecisionSourceChange: (ServiceSource) -> Unit = {},
    val onDecisionForTalkingPointsChange: (Boolean) -> Unit = {},
    val onTestDecision: () -> Unit = {},
    val onBackup: () -> Unit = {},
    val onRestore: () -> Unit = {},
    val onCancelBackup: () -> Unit = {},
    val onDismissBackup: () -> Unit = {}
)

/** What the pages show apart from [SettingsUiState]. */
private class PageExtras(
    val cleanupDownloading: Boolean,
    val semanticDownloading: Boolean,
    val voiceModelDownloading: Boolean,
    val liveCleanupVerdict: LiveCleanupVerdict?,
    val overlayGranted: Boolean,
    val update: UpdateState,
    val installedVersion: String,
    val installedVersionCode: Long,
    val updateActions: UpdateActions,
    val backup: BackupState
)

/**
 * Render-only body of [SettingsScreen] for previews and screenshot tests:
 * the overview when [page] is `null`, otherwise that page. Back goes from
 * a page to the overview through [onPageChange], and from the overview out
 * of Settings through [SettingsActions.onBack]. In a window of at least
 * [TWO_PANES_MIN_WIDTH] the two show side by side (the first page while
 * [page] is `null`) and back leaves Settings.
 * [overlayGranted] is whether "Display over other apps" is allowed;
 * [update] and [updateActions] drive the update part of About (issue #15).
 * [cleanupDownloading] while the noise cleanup model downloads (issue #37),
 * [semanticDownloading] while the semantic search model does (issue #61);
 * [liveCleanupVerdict] is the engine check's verdict on cleaning live
 * with the selected engine, `null` when it was not checked. [backup] is
 * the running or last backup or restore (issue #25).
 */
@Composable
internal fun SettingsBody(
    state: SettingsUiState,
    actions: SettingsActions,
    page: SettingsPage? = null,
    onPageChange: (SettingsPage?) -> Unit = {},
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
    val extras = PageExtras(
        cleanupDownloading = cleanupDownloading,
        semanticDownloading = semanticDownloading,
        voiceModelDownloading = voiceModelDownloading,
        liveCleanupVerdict = liveCleanupVerdict,
        overlayGranted = overlayGranted,
        update = update,
        installedVersion = installedVersion,
        installedVersionCode = installedVersionCode,
        updateActions = updateActions,
        backup = backup
    )
    val inputs = OverviewInputs(
        overlayGranted = overlayGranted,
        update = update,
        installedVersion = installedVersion,
        backup = backup,
        formatTime = { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }
    )
    BoxWithConstraints {
        val twoPanes = maxWidth >= TWO_PANES_MIN_WIDTH
        val back: () -> Unit = { if (!twoPanes && page != null) onPageChange(null) else actions.onBack() }
        BackHandler(onBack = back)
        ScreenScaffold(
            title = stringResource(if (twoPanes) R.string.settings_title else page?.title ?: R.string.settings_title),
            onBack = back,
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { innerPadding: PaddingValues ->
            if (twoPanes) {
                // Side by side a page is always open; the list is there to change it.
                val open = page ?: SettingsPage.SERVER
                Row(modifier = Modifier.padding(innerPadding)) {
                    PaneColumn(modifier = Modifier.width(OVERVIEW_PANE_WIDTH)) {
                        SettingsOverview(
                            state = state,
                            inputs = inputs,
                            onOpen = onPageChange,
                            selected = open,
                            showArrows = false
                        )
                    }
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    key(open) {
                        PaneColumn(modifier = Modifier.weight(1f)) {
                            SettingsPageContent(page = open, state = state, actions = actions, extras = extras)
                        }
                    }
                }
            } else {
                // Each page starts at its top.
                key(page) {
                    PaneColumn(modifier = Modifier.padding(innerPadding)) {
                        if (page == null) {
                            SettingsOverview(
                                state = state,
                                inputs = inputs,
                                onOpen = onPageChange,
                                modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth)
                            )
                        } else {
                            SettingsPageContent(page = page, state = state, actions = actions, extras = extras)
                        }
                    }
                }
            }
        }
    }
}

/** From this window width (Material's "expanded") the overview and the open page show side by side. */
private val TWO_PANES_MIN_WIDTH = 840.dp
private val OVERVIEW_PANE_WIDTH = 360.dp

/** A scrolling column with the screen's margins, its content centred up to the reading width. */
@Composable
private fun PaneColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AppTheme.spacing.screen, vertical = AppTheme.spacing.s),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        content()
        Spacer(Modifier.height(AppTheme.spacing.xl))
    }
}

/** The sections of [page]. */
@Composable
private fun SettingsPageContent(
    page: SettingsPage,
    state: SettingsUiState,
    actions: SettingsActions,
    extras: PageExtras
) {
    when (page) {
        SettingsPage.SERVER -> SyncSection(
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
        SettingsPage.RECORDING -> {
            TranscriptionSection(
                engine = state.asrEngine,
                language = state.asrLanguage,
                frenchModelInstalled = state.frenchModelInstalled,
                selectedLanguageModelInstalled = state.selectedLanguageModelInstalled,
                selectedLanguageModelMissingBytes = state.selectedLanguageModelMissingBytes,
                onEngineChange = actions.onEngineChange,
                onLanguageChange = actions.onLanguageChange,
                onEngineCheck = actions.onEngineCheck,
                onDeleteFrenchModel = actions.onDeleteFrenchModel
            )
            RecordingSection(
                floatingControl = state.floatingControl,
                floatingShape = state.floatingShape,
                overlayGranted = extras.overlayGranted,
                onFloatingControlChange = actions.onFloatingControlChange,
                onFloatingShapeChange = actions.onFloatingShapeChange,
                onAllowOverlay = actions.onAllowOverlay
            )
            NoiseCleanupSection(
                installed = state.cleanupInstalled,
                downloading = extras.cleanupDownloading,
                cleanByDefault = state.cleanAudioDefault,
                liveVerdict = extras.liveCleanupVerdict,
                onDownload = actions.onDownloadCleanup,
                onDelete = actions.onDeleteCleanup,
                onCleanByDefaultChange = actions.onCleanAudioDefaultChange
            )
            TranscriptionServerSection(state = state, actions = actions)
            RecordingAdvanced(
                threads = state.form.numThreads,
                onThreadsChange = { threads -> actions.onFormChange(state.form.copy(numThreads = threads)) }
            )
        }
        SettingsPage.SPEAKERS -> SpeakersSection(
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
                downloading = extras.voiceModelDownloading,
                stepSeconds = state.diarizationStepSeconds,
                onSelect = actions.onDiarizationEmbeddingChange,
                onDownload = actions.onDownloadVoiceModel,
                onStepChange = actions.onDiarizationStepChange
            )
        )
        SettingsPage.AI -> {
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
            DecisionModelSection(state = state, actions = actions)
            SummariesSection(
                templates = state.summaryTemplates,
                defaultKey = state.defaultSummaryTemplate,
                onDefaultChange = actions.onDefaultSummaryTemplateChange,
                onOpenTemplates = actions.onOpenSummaryTemplates,
                autoMarkNotes = state.autoMarkNotes,
                onAutoMarkNotesChange = actions.onAutoMarkNotesChange,
                autoSummary = state.autoSummary,
                onAutoSummaryChange = actions.onAutoSummaryChange,
                autoTitle = state.autoTitle,
                onAutoTitleChange = actions.onAutoTitleChange
            )
            AiAdvanced(
                form = state.form,
                llmFromServer = state.serverServicesEnabled && state.llmSource == ServiceSource.SERVER,
                onFormChange = actions.onFormChange
            )
        }
        SettingsPage.SEARCH -> {
            SemanticSearchSection(
                installed = state.semanticInstalled,
                downloading = extras.semanticDownloading,
                progress = state.semanticProgress,
                onDownload = actions.onDownloadSemantic,
                onDelete = actions.onDeleteSemantic,
                indexMode = state.semanticIndexMode,
                onIndexModeChange = actions.onSemanticIndexModeChange,
                onIndexNow = actions.onIndexSemanticNow
            )
            OrganiseSection(
                onOpenTags = actions.onOpenTags,
                onOpenPresets = actions.onOpenPresets
            )
        }
        SettingsPage.BACKUP -> DataSection(
            state = extras.backup,
            onBackup = actions.onBackup,
            onRestore = actions.onRestore,
            onCancel = actions.onCancelBackup,
            onDismiss = actions.onDismissBackup
        )
        SettingsPage.APPEARANCE -> AppearanceSection(
            displayMode = state.displayMode,
            dynamicColor = state.dynamicColor,
            onDisplayModeChange = actions.onDisplayModeChange,
            onDynamicColorChange = actions.onDynamicColorChange
        )
        SettingsPage.ABOUT -> AboutSection(
            installedVersion = extras.installedVersion,
            installedVersionCode = extras.installedVersionCode,
            update = extras.update,
            checkAuto = state.updateCheckAuto,
            includePrereleases = state.updateIncludePrereleases,
            updateActions = extras.updateActions,
            onCheckAutoChange = actions.onUpdateCheckAutoChange,
            onIncludePrereleasesChange = actions.onUpdateIncludePrereleasesChange
        )
    }
}
