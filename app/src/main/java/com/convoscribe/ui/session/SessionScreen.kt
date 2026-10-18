package com.convoscribe.ui.session

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.convoscribe.ConvoApp
import com.convoscribe.R
import com.convoscribe.audio.AudioExporter
import com.convoscribe.audio.MediaPlayerAudioPlayer
import com.convoscribe.audio.PlayerState
import com.convoscribe.data.ChatMessageEntity
import com.convoscribe.data.MessageRole
import com.convoscribe.data.SessionStatus
import com.convoscribe.data.SpeakerEntity
import com.convoscribe.data.TranscriptLine
import com.convoscribe.models.ModelCatalog
import com.convoscribe.recording.RecordingPhase
import com.convoscribe.recording.RetranscribeState
import com.convoscribe.service.RecordingService
import com.convoscribe.ui.asString
import com.convoscribe.ui.components.SpeakerDot
import com.convoscribe.ui.record.ParticipantsDialog
import com.convoscribe.ui.theme.AppTheme
import kotlinx.coroutines.flow.map

/**
 * Statuses a session can be continued from: finished recording,
 * with no diarization in flight.
 */
private val CONTINUABLE_STATUSES = setOf(
    SessionStatus.TRANSCRIBED,
    SessionStatus.DIARIZED,
    SessionStatus.DIARIZATION_FAILED
)

/**
 * Entry point for the `session/{id}` route. Pulls the
 * [SessionViewModel] out of the application container, renders the
 * top bar with a back arrow and a share icon, and hands the rest
 * of the layout off to [SessionBody] so the screen can be
 * previewed / tested without a navigation controller.
 *
 * @param sessionId primary key from the `session/{id}` route;
 *   passed straight to the ViewModel, which observes the matching
 *   row through the repository.
 * @param navController app-level controller; the back arrow pops
 *   the back stack so the user lands back on the home list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(sessionId: Long, navController: NavHostController) {
    val context = LocalContext.current
    val container = (context.applicationContext as ConvoApp).container
    val vm: SessionViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SessionViewModel(
                    sessionId = sessionId,
                    repository = container.sessionRepository,
                    enrollFromSession = container.enrollFromSessionUseCase,
                    playerFactory = { scope -> MediaPlayerAudioPlayer(scope) },
                    audioExporter = container.audioExporter
                )
            }
        }
    )
    val summaryVm: SummaryViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SummaryViewModel(
                    sessionId = sessionId,
                    repository = container.sessionRepository,
                    chatClient = container.chatClient,
                    settings = container.settingsRepository
                )
            }
        }
    )
    val askVm: AskViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                AskViewModel(
                    sessionId = sessionId,
                    repository = container.sessionRepository,
                    chatClient = container.chatClient,
                    settings = container.settingsRepository
                )
            }
        }
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val summaryState by summaryVm.state.collectAsStateWithLifecycle()
    val askState by askVm.state.collectAsStateWithLifecycle()
    val playerState by vm.playerState.collectAsStateWithLifecycle()
    val activeLineIndex by vm.activeLineIndex.collectAsStateWithLifecycle()
    val following by vm.following.collectAsStateWithLifecycle()
    val exportState by vm.exportState.collectAsStateWithLifecycle()
    val saveAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/mp4")
    ) { uri -> if (uri != null) vm.saveAudio(uri) }
    LaunchedEffect(vm) {
        // An M4A is ready: hand it to the share sheet through the
        // FileProvider, with a one-off read grant for the receiving app.
        vm.shareAudioFiles.collect { file ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, state.title)
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, context.getString(R.string.session_share_audio_chooser)))
        }
    }
    // Playback ends when the user leaves the screen or the app goes to
    // the background (there is no background playback).
    DisposableEffect(vm) { onDispose { vm.stopPlayback() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.stopPlayback() }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        // Surface one-shot messages from the ViewModel: a
        // successful "remember this voice" enrollment emits a
        // confirmation, an enrollment failure emits the
        // exception's message verbatim (e.g. "Not enough speech
        // from this speaker (need 3 s)"). Each Channel entry is
        // consumed exactly once, so a configuration change does
        // not double-fire the snackbar.
        vm.snackbar.collect { message ->
            snackbarHostState.showSnackbar(message = message.asString(context), duration = SnackbarDuration.Short)
        }
    }

    // Continuing needs the recorder to be free: one recording at a
    // time, whichever session it belongs to.
    val recorderState by container.recordingController.state.collectAsStateWithLifecycle()
    val appSettings by container.settingsRepository.settings.collectAsStateWithLifecycle()
    val enrolledNames by remember(container) {
        container.enrolledSpeakers.observeAll().map { rows -> rows.map { it.name }.distinct() }
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val effectiveSpeakerCount = state.numSpeakers ?: appSettings.numSpeakers
    val retranscribeState by container.retranscribeUseCase.state.collectAsStateWithLifecycle()
    val retranscribing = retranscribeState is RetranscribeState.Running
    LaunchedEffect(retranscribeState) {
        val failed = retranscribeState as? RetranscribeState.Failed ?: return@LaunchedEffect
        if (failed.sessionId != sessionId) return@LaunchedEffect
        container.retranscribeUseCase.acknowledgeFailure()
        snackbarHostState.showSnackbar(
            message = context.getString(R.string.session_retranscribe_failed, failed.message),
            duration = SnackbarDuration.Long
        )
    }
    val reidentify: (Int) -> Unit = { count ->
        // Hand off to the foreground service, which saves the
        // count on the session and promotes itself to
        // `dataSync` while the diarizer runs. The status banner
        // already picks up the DIARIZING flip the use case
        // writes, so the screen mirrors the work without having
        // to track it locally.
        vm.stopPlayback()
        ContextCompat.startForegroundService(
            context,
            RecordingService.diarizeIntent(context, sessionId, numSpeakers = count)
        )
    }

    SessionBody(
        title = state.title,
        status = state.status,
        lines = state.lines,
        speakers = state.speakers,
        notFound = state.notFound,
        summaryState = summaryState,
        askState = askState,
        playerState = playerState,
        activeLineIndex = activeLineIndex,
        following = following,
        exportState = exportState,
        onShareAudio = { vm.shareAudio() },
        onSaveAudio = { saveAudioLauncher.launch(AudioExporter.fileName(state.title)) },
        onCancelExport = { vm.cancelExport() },
        playback = PlaybackActions(
            onTogglePlay = { vm.togglePlayback() },
            onSeek = { vm.seekTo(it) },
            onSkip = { vm.skip(it) },
            onCycleSpeed = { vm.cycleSpeed() },
            onLineTapped = { vm.playLine(it) },
            onUserScrolled = { vm.onUserScrolled() },
            onResumeFollow = { vm.resumeFollow() }
        ),
        snackbarHostState = snackbarHostState,
        canContinueRecording = !state.notFound &&
            recorderState.phase == RecordingPhase.IDLE &&
            !retranscribing &&
            state.status in CONTINUABLE_STATUSES,
        onContinueRecording = { navController.navigate("record/resume/$sessionId") },
        onBack = { navController.popBackStack() },
        onShare = {
            // Build the share intent on the main thread; the
            // chooser has to be launched from a context that owns
            // the current activity. We only fire the share when
            // the transcript is non-empty — sharing a blank
            // string would still produce a chooser, but there is
            // nothing useful for the recipient to read.
            val text = vm.transcriptText()
            if (text.isNotBlank()) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, state.title)
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                context.startActivity(Intent.createChooser(send, null))
            }
        },
        onRenameSpeaker = { index, label -> vm.renameSpeaker(index, label) },
        onRenameAndRemember = { index, label -> vm.renameAndRemember(index, label) },
        onRenameSession = { title -> vm.renameSession(title) },
        effectiveSpeakerCount = effectiveSpeakerCount,
        onReidentifySpeakers = reidentify,
        details = SessionDetails(
            language = state.language,
            numSpeakers = effectiveSpeakerCount,
            participants = state.participants,
            asrEngine = state.asrEngine,
            asrModel = state.asrModel
        ),
        enrolledNames = enrolledNames,
        onSetParticipants = { vm.setParticipants(it) },
        retranscribe = RetranscribeUi(
            progress = (retranscribeState as? RetranscribeState.Running)
                ?.takeIf { it.sessionId == sessionId }
                ?.progress,
            enabled = !state.notFound &&
                recorderState.phase == RecordingPhase.IDLE &&
                !retranscribing &&
                state.status in CONTINUABLE_STATUSES,
            onStart = { language ->
                val engine = appSettings.asrEngine
                val missing = container.modelStore.missing(ModelCatalog.requiredFor(language, engine))
                if (missing.isNotEmpty()) {
                    // Download first; setup pops back here and the user starts again.
                    navController.navigate("setup?engine=$engine&language=$language&back=true")
                } else {
                    vm.stopPlayback()
                    ContextCompat.startForegroundService(
                        context,
                        RecordingService.retranscribeIntent(context, sessionId, language)
                    )
                }
            },
            onCancel = { context.startService(RecordingService.cancelRetranscribeIntent(context)) }
        ),
        onSummarise = { summaryVm.summarise() },
        onCancelSummary = { summaryVm.cancel() },
        onAsk = { question -> askVm.ask(question) },
        onRetryAsk = { askVm.retryLast() },
        onCancelAsk = { askVm.cancel() },
        onClearQuestions = { askVm.clearHistory() },
        onOpenSettings = { navController.navigate("settings") }
    )
}

/**
 * Render-only variant of [SessionScreen] for preview and testing.
 * The status banner, the empty state and the tab body are pure
 * functions of the inputs, so the test/preview composable only
 * exercises the layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionBody(
    title: String,
    status: SessionStatus?,
    lines: List<TranscriptLine>,
    speakers: List<SpeakerEntity>,
    notFound: Boolean,
    summaryState: SummaryUiState,
    askState: AskUiState,
    playerState: PlayerState,
    activeLineIndex: Int?,
    following: Boolean,
    exportState: ExportState,
    onShareAudio: () -> Unit,
    onSaveAudio: () -> Unit,
    onCancelExport: () -> Unit,
    playback: PlaybackActions,
    snackbarHostState: SnackbarHostState,
    canContinueRecording: Boolean,
    onContinueRecording: () -> Unit,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onRenameSpeaker: (Int, String) -> Unit,
    onRenameAndRemember: (Int, String) -> Unit,
    onRenameSession: (String) -> Unit,
    effectiveSpeakerCount: Int,
    onReidentifySpeakers: (Int) -> Unit,
    onSummarise: () -> Unit,
    onCancelSummary: () -> Unit,
    onAsk: (String) -> Unit,
    onRetryAsk: () -> Unit,
    onCancelAsk: () -> Unit,
    onClearQuestions: () -> Unit,
    onOpenSettings: () -> Unit,
    details: SessionDetails = SessionDetails(),
    enrolledNames: List<String> = emptyList(),
    onSetParticipants: (List<String>?) -> Unit = {},
    retranscribe: RetranscribeUi = RetranscribeUi()
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var overflowExpanded by remember { mutableStateOf(false) }
    // The two rename flows share a dialog shell but different
    // pre-fill / save targets. `renameSpeakerIndex = null` means
    // we're editing the session title; otherwise the non-null
    // index is the speaker we're renaming.
    var renameSpeakerIndex by remember { mutableStateOf<Int?>(null) }
    var renameSessionOpen by remember { mutableStateOf(false) }
    var clearQuestionsOpen by remember { mutableStateOf(false) }
    var reidentifyOpen by remember { mutableStateOf(false) }
    var participantsOpen by remember { mutableStateOf(false) }
    var reidentifyAfterParticipants by remember { mutableStateOf(false) }
    var infoOpen by remember { mutableStateOf(false) }
    var retranscribeOpen by remember { mutableStateOf(false) }
    Scaffold(
        snackbarHost = {
            // One-line snackbar with a short duration: the
            // "remember this voice" success / failure message is
            // short and ephemeral, so a tall multi-line layout
            // would just take up vertical space without
            // conveying more information.
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(snackbarData = data)
            }
        },
        topBar = {
            TopAppBar(
                title = { Text(text = title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.session_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onContinueRecording, enabled = canContinueRecording) {
                        Icon(
                            painter = painterResource(R.drawable.ic_mic),
                            contentDescription = stringResource(R.string.session_continue_recording)
                        )
                    }
                    IconButton(onClick = onShare) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = stringResource(R.string.session_share)
                        )
                    }
                    IconButton(onClick = { overflowExpanded = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.session_overflow)
                        )
                    }
                    DropdownMenu(
                        expanded = overflowExpanded,
                        onDismissRequest = { overflowExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.session_overflow_rename)) },
                            onClick = {
                                overflowExpanded = false
                                renameSessionOpen = true
                            }
                        )
                        val hasAudio = status != SessionStatus.RECORDING && playerState.ready
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.session_overflow_share_audio)) },
                            enabled = hasAudio,
                            onClick = {
                                overflowExpanded = false
                                onShareAudio()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.session_overflow_save_audio)) },
                            enabled = hasAudio,
                            onClick = {
                                overflowExpanded = false
                                onSaveAudio()
                            }
                        )
                        val canDiarize = status != SessionStatus.DIARIZING &&
                            status != SessionStatus.RECORDING &&
                            retranscribe.progress == null
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.session_overflow_reidentify)) },
                            enabled = canDiarize,
                            onClick = {
                                overflowExpanded = false
                                reidentifyOpen = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.session_overflow_retranscribe)) },
                            enabled = retranscribe.enabled,
                            onClick = {
                                overflowExpanded = false
                                retranscribeOpen = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.session_overflow_participants)) },
                            enabled = !notFound,
                            onClick = {
                                overflowExpanded = false
                                participantsOpen = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.session_overflow_info)) },
                            enabled = !notFound,
                            onClick = {
                                overflowExpanded = false
                                infoOpen = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.session_overflow_clear_questions)) },
                            enabled = askState.messages.isNotEmpty(),
                            onClick = {
                                overflowExpanded = false
                                clearQuestionsOpen = true
                            }
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
        ) {
            retranscribe.progress?.let { progress ->
                RetranscribeBanner(progress = progress, onCancel = retranscribe.onCancel)
            }
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(text = stringResource(R.string.session_tab_transcript)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(text = stringResource(R.string.session_tab_summary)) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text(text = stringResource(R.string.session_tab_ask)) }
                )
            }
            // Finished sessions with a readable recording get the player;
            // it is not offered while the session is still being recorded.
            val canPlay = !notFound && status != SessionStatus.RECORDING && playerState.ready
            if (canPlay && selectedTab == 0) {
                PlayerBar(state = playerState, actions = playback)
            }
            when {
                notFound -> SessionNotFound()
                selectedTab == 0 -> TranscriptTab(
                    status = status,
                    lines = lines,
                    speakers = speakers,
                    canPlay = canPlay,
                    isPlaying = playerState.isPlaying,
                    positionMs = { playerState.positionMs },
                    activeLineIndex = activeLineIndex,
                    following = following,
                    playback = playback,
                    onSpeakerChipTapped = { index -> renameSpeakerIndex = index }
                )
                selectedTab == 1 -> SummaryTab(
                    state = summaryState,
                    onSummarise = onSummarise,
                    onCancel = onCancelSummary,
                    onOpenSettings = onOpenSettings
                )
                else -> AskTab(
                    state = askState,
                    onAsk = onAsk,
                    onRetry = onRetryAsk,
                    onCancel = onCancelAsk,
                    onOpenSettings = onOpenSettings
                )
            }
        }
    }

    if (exportState is ExportState.Encoding) {
        // Not dismissable by tapping outside: encoding a long session
        // takes a while, and Cancel is the explicit way out.
        AlertDialog(
            onDismissRequest = {},
            title = { Text(text = stringResource(R.string.session_export_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = stringResource(R.string.session_export_message))
                    LinearProgressIndicator(
                        progress = { exportState.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onCancelExport) {
                    Text(text = stringResource(R.string.session_export_cancel))
                }
            }
        )
    }
    // Rename-a-speaker dialog. `renameSpeakerIndex == null`
    // means the dialog is closed. We snapshot the speaker at
    // open-time so cancelling and re-opening starts fresh.
    renameSpeakerIndex?.let { idx ->
        val current = speakers.firstOrNull { it.speakerIndex == idx }?.label.orEmpty()
        RenameDialog(
            initialValue = current,
            titleRes = R.string.session_rename_speaker_title,
            checkboxLabelRes = R.string.session_rename_remember_voice,
            checkboxHelpRes = R.string.session_rename_remember_voice_help,
            onConfirm = { newLabel, rememberVoice ->
                if (rememberVoice) {
                    onRenameAndRemember(idx, newLabel)
                } else {
                    onRenameSpeaker(idx, newLabel)
                }
                renameSpeakerIndex = null
            },
            onDismiss = { renameSpeakerIndex = null }
        )
    }
    // Rename-the-session dialog.
    if (renameSessionOpen) {
        RenameDialog(
            initialValue = title,
            titleRes = R.string.session_rename_session_title,
            onConfirm = { newTitle, _ ->
                onRenameSession(newTitle)
                renameSessionOpen = false
            },
            onDismiss = { renameSessionOpen = false }
        )
    }
    if (reidentifyOpen) {
        ReidentifySpeakersDialog(
            current = effectiveSpeakerCount,
            onConfirm = { count ->
                reidentifyOpen = false
                onReidentifySpeakers(count)
            },
            onDismiss = { reidentifyOpen = false }
        )
    }
    if (participantsOpen) {
        ParticipantsDialog(
            enrolledNames = enrolledNames,
            selected = details.participants,
            onConfirm = { chosen ->
                participantsOpen = false
                onSetParticipants(chosen)
                // Re-identifying needs a finished recording and no diarization running.
                if (chosen != details.participants &&
                    status != SessionStatus.DIARIZING &&
                    status != SessionStatus.RECORDING
                ) {
                    reidentifyAfterParticipants = true
                }
            },
            onDismiss = { participantsOpen = false }
        )
    }
    if (reidentifyAfterParticipants) {
        ReidentifyAfterParticipantsDialog(
            onConfirm = {
                reidentifyAfterParticipants = false
                onReidentifySpeakers(effectiveSpeakerCount)
            },
            onDismiss = { reidentifyAfterParticipants = false }
        )
    }
    if (infoOpen) {
        SessionInfoDialog(details = details, onDismiss = { infoOpen = false })
    }
    if (retranscribeOpen) {
        RetranscribeDialog(
            currentLanguage = details.language,
            onConfirm = { language ->
                retranscribeOpen = false
                retranscribe.onStart(language)
            },
            onDismiss = { retranscribeOpen = false }
        )
    }
    // Clear-questions confirmation. The action itself is gated on
    // `messages.isNotEmpty()` already, so the dialog only opens
    // when there is something to clear.
    if (clearQuestionsOpen) {
        AlertDialog(
            onDismissRequest = { clearQuestionsOpen = false },
            title = { Text(text = stringResource(R.string.session_ask_clear_title)) },
            text = { Text(text = stringResource(R.string.session_ask_clear_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onClearQuestions()
                    clearQuestionsOpen = false
                }) {
                    Text(text = stringResource(R.string.session_ask_clear_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { clearQuestionsOpen = false }) {
                    Text(text = stringResource(R.string.session_ask_clear_cancel))
                }
            }
        )
    }
}

/**
 * "Re-identify speakers" dialog: pick how many people speak in the
 * recording (Automatic, 2, 3 or 4), preselected with [current] (the
 * session's count, else the Settings default; a Settings value
 * outside the list is offered too). [onConfirm] receives `-1` for
 * Automatic.
 */
@Composable
private fun ReidentifySpeakersDialog(current: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val options = (listOf(-1, 2, 3, 4) + current).distinct().sortedBy { if (it == -1) 0 else it }
    var selected by remember { mutableIntStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.session_reidentify_title)) },
        text = {
            Column {
                Text(text = stringResource(R.string.session_reidentify_message))
                for (option in options) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = option == selected, role = Role.RadioButton) { selected = option },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text(
                            text = if (option == -1) {
                                stringResource(R.string.session_reidentify_auto)
                            } else {
                                pluralStringResource(R.plurals.session_reidentify_count, option, option)
                            },
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) {
                Text(text = stringResource(R.string.session_reidentify_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.session_ask_clear_cancel))
            }
        }
    )
}

/**
 * Reusable rename dialog used for both the speaker and session
 * rename flows. Pre-fills with [initialValue], commits through
 * [onConfirm] on Save, and discards on Cancel. Empty inputs
 * disable the Save button so the user cannot accidentally blank
 * out a label (the repository also defends against this, but the
 * UI gate gives immediate feedback).
 *
 * When [checkboxLabelRes] is non-null the dialog also renders a
 * checkbox row beneath the text field. The checkbox starts
 * unchecked and the screen reads its value through
 * [onConfirm]'s second parameter. The session-rename flow passes
 * `null` so the checkbox row is hidden; the speaker-rename flow
 * passes the "Remember this voice" label / help string so the
 * user can promote a session speaker to a known voice in one
 * tap (TASK-34).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenameDialog(
    initialValue: String,
    titleRes: Int,
    onConfirm: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
    checkboxLabelRes: Int? = null,
    checkboxHelpRes: Int? = null
) {
    var text by rememberSaveable(initialValue) { mutableStateOf(initialValue) }
    var rememberVoice by rememberSaveable(initialValue) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(titleRes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.session_rename_label)) }
                )
                if (checkboxLabelRes != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = rememberVoice,
                            onCheckedChange = { rememberVoice = it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(checkboxLabelRes),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (checkboxHelpRes != null) {
                                Text(
                                    text = stringResource(checkboxHelpRes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text, rememberVoice) }
            ) {
                Text(text = stringResource(R.string.session_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.session_rename_cancel))
            }
        }
    )
}

/**
 * Status banner above the transcript: shows an indeterminate
 * progress bar and the "Identifying speakers…" caption while the
 * diarizer is running, and a plain error label once it failed.
 * Hidden for any other status (RECORDING, TRANSCRIBED, DIARIZED)
 * because there is nothing for the user to wait on or react to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusBanner(status: SessionStatus?) {
    when (status) {
        SessionStatus.DIARIZING -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.session_status_diarizing),
                style = MaterialTheme.typography.bodyMedium
            )
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        SessionStatus.DIARIZATION_FAILED -> Text(
            text = stringResource(R.string.session_status_diarization_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
        )
        else -> Spacer(modifier = Modifier.height(0.dp))
    }
}

/**
 * Transcript tab body: status banner, then either a "No speech
 * was recognised." placeholder or the list of [TranscriptLine]s.
 * Each row renders the timestamp and the speaker label in
 * `labelMedium` (the label is coloured from the theme's speaker
 * palette by [TranscriptLine.speakerIndex], so renames do not break
 * the colour assignment) and the text in `bodyLarge`.
 *
 * When the session has at least one speaker, a row of
 * [AssistChip]s above the transcript lets the user jump
 * straight to the rename dialog for that speaker — the same
 * dialog as the per-line tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranscriptTab(
    status: SessionStatus?,
    lines: List<TranscriptLine>,
    speakers: List<SpeakerEntity>,
    canPlay: Boolean,
    isPlaying: Boolean,
    positionMs: () -> Long,
    activeLineIndex: Int?,
    following: Boolean,
    playback: PlaybackActions,
    onSpeakerChipTapped: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        StatusBanner(status = status)
        if (speakers.isNotEmpty()) {
            SpeakerChipRow(speakers = speakers, onChipTapped = onSpeakerChipTapped)
        }
        if (lines.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.session_transcript_empty),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            val listState = rememberLazyListState()
            // A drag (or the fling it starts) is the user taking over the
            // scroll position; programmatic scrolls below do not emit one.
            LaunchedEffect(listState) {
                listState.interactionSource.interactions.collect {
                    if (it is DragInteraction.Start) playback.onUserScrolled()
                }
            }
            LaunchedEffect(activeLineIndex, following) {
                val index = activeLineIndex
                if (following && index != null && index < lines.size && !listState.isFullyVisible(index)) {
                    listState.animateScrollToItem(index)
                }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(items = lines, key = { _, line -> line.id }) { index, line ->
                        val active = index == activeLineIndex
                        TranscriptRow(
                            line = line,
                            active = active,
                            positionMs = if (active) positionMs else NO_POSITION,
                            onLabelTapped = onSpeakerChipTapped,
                            onTapped = if (canPlay) ({ playback.onLineTapped(line) }) else null
                        )
                    }
                }
                if (isPlaying && !following && activeLineIndex != null) {
                    FilledTonalButton(
                        onClick = {
                            playback.onResumeFollow()
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                    ) {
                        Text(text = stringResource(R.string.player_follow))
                    }
                }
            }
        }
    }
}

/**
 * Horizontally scrollable strip of speaker [AssistChip]s. Each
 * chip is tinted with the same palette colour used by the
 * transcript row labels, so the user can match a chip to its
 * lines at a glance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeakerChipRow(speakers: List<SpeakerEntity>, onChipTapped: (Int) -> Unit) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items = speakers, key = { it.speakerIndex }) { speaker ->
            AssistChip(
                onClick = { onChipTapped(speaker.speakerIndex) },
                label = { Text(text = speaker.label) },
                leadingIcon = { SpeakerDot(speakerIndex = speaker.speakerIndex) }
            )
        }
    }
}

/**
 * Render one [TranscriptLine]. The speaker label colour comes from
 * [TranscriptLine.speakerIndex], the same key as the speaker chips,
 * so a chip and its lines always match. The `labelMedium`
 * metadata line shows `"00:03 · Speaker 1"` or just `"00:03"` when
 * the segment is unlabelled. Tapping the label opens the rename
 * dialog for the matching speaker (when there is one); tapping the
 * rest of the row ([onTapped], only when the recording can be played)
 * plays from the start of the line. The [active] row is tinted and,
 * when the line has word timings, the word at [positionMs] is
 * emphasised.
 */
@Composable
private fun TranscriptRow(
    line: TranscriptLine,
    active: Boolean,
    positionMs: () -> Long,
    onLabelTapped: (Int) -> Unit,
    onTapped: (() -> Unit)?
) {
    val speakerIndex = line.speakerIndex
    val paletteColor = if (speakerIndex == null) Color.Unspecified else AppTheme.speakerColors[speakerIndex]
    val timestamp = remember(line.startMs) { com.convoscribe.data.TranscriptFormatter.timestamp(line.startMs) }
    val labelText = if (line.speaker.isNullOrEmpty()) {
        timestamp
    } else {
        "$timestamp · ${line.speaker}"
    }
    val words = remember(line) { PlaybackSync.words(line) }
    // Only the playing line reads the position, so the other rows do not
    // recompose on every playback tick.
    val wordIndex = if (active && words != null) PlaybackSync.activeWordIndex(line, positionMs()) else null
    val wordStyle = SpanStyle(
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline
    )
    val text = remember(line.text, words, wordIndex, wordStyle) {
        buildAnnotatedString {
            append(line.text)
            val word = if (wordIndex != null && words != null) words[wordIndex] else null
            if (word != null) addStyle(wordStyle, word.start, word.end)
        }
    }
    var rowModifier = Modifier.fillMaxWidth()
    if (active) {
        rowModifier = rowModifier.background(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(8.dp)
        )
    }
    if (onTapped != null) rowModifier = rowModifier.clickable(onClick = onTapped)
    Column(
        modifier = rowModifier.padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        val labelModifier = if (speakerIndex == null) {
            Modifier
        } else {
            Modifier.clickable { onLabelTapped(speakerIndex) }
        }
        Text(
            text = labelText,
            style = MaterialTheme.typography.labelMedium,
            color = paletteColor,
            modifier = labelModifier
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

private val NO_POSITION: () -> Long = { 0L }

/** `true` when the item at [index] is on screen and not cut off at either edge. */
private fun LazyListState.isFullyVisible(index: Int): Boolean {
    val info = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
    return info.offset >= layoutInfo.viewportStartOffset &&
        info.offset + info.size <= layoutInfo.viewportEndOffset
}

/**
 * Placeholder rendered for the Summary / Ask tabs until those
 * tasks wire their real bodies. Keeps the tab navigation
 * discoverable without claiming functionality that isn't
 * implemented yet.
 */
@Composable
private fun ComingSoonTab() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.session_coming_soon),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

/**
 * Summary tab body. Three layouts:
 *
 *  - the LLM endpoint is not configured: a single line of text and
 *    a button that navigates to the Settings screen;
 *  - not configured: hidden behind [SummaryUiState.configured];
 *  - running / saved / streaming / error: the streaming text or
 *    saved summary, the action buttons and the inline error.
 *
 * The summary is rendered through [SelectionContainer] so the user
 * can long-press to copy a phrase, and a `Copy` `TextButton` drops
 * the whole answer on the system clipboard via
 * [LocalClipboardManager]. No markdown library is involved: the
 * raw text from the LLM is shown verbatim.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SummaryTab(
    state: SummaryUiState,
    onSummarise: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!state.configured) {
            Text(
                text = stringResource(R.string.session_summary_not_configured),
                style = MaterialTheme.typography.bodyLarge
            )
            Button(onClick = onOpenSettings) {
                Text(text = stringResource(R.string.session_summary_open_settings))
            }
            return
        }
        if (state.running) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onCancel) {
                    Text(text = stringResource(R.string.session_summary_stop))
                }
                Spacer(modifier = Modifier.width(12.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            }
            if (state.streaming.isNotEmpty()) {
                SelectionContainer {
                    Text(
                        text = state.streaming,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        } else {
            val saved = state.saved
            val buttonLabel = if (saved.isNullOrBlank()) {
                R.string.session_summary_summarise
            } else {
                R.string.session_summary_regenerate
            }
            Button(onClick = onSummarise) {
                Text(text = stringResource(buttonLabel))
            }
            if (saved.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.session_summary_empty),
                    style = MaterialTheme.typography.bodyLarge
                )
            } else {
                SelectionContainer {
                    Text(
                        text = saved,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(saved))
                }) {
                    Text(text = stringResource(R.string.session_summary_copy))
                }
            }
        }
        state.error?.let { message ->
            Text(
                text = message.asString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            TextButton(onClick = onSummarise) {
                Text(text = stringResource(R.string.session_summary_retry))
            }
        }
    }
}

/**
 * Rendered when the route's id does not match a session row —
 * either it was deleted or the navigation came from somewhere
 * unexpected (e.g. a deep link). Showing a placeholder beats
 * crashing, and the user can hit Back to leave the screen.
 */
@Composable
private fun SessionNotFound() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.session_not_found),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

/**
 * Ask tab body. Three layouts:
 *
 *  - the LLM endpoint is not configured: a single line of text
 *    and a button that navigates to the Settings screen;
 *  - configured and idle: a column of chat bubbles (USER on the
 *    right with `primaryContainer`, ASSISTANT on the left with
 *    `surfaceVariant`, text selectable), with the in-flight
 *    streaming answer as a "live" ASSISTANT bubble when one is
 *    in flight, an inline error block with a Retry button when
 *    the previous call failed, and a bottom input row with an
 *    `OutlinedTextField` and a send `IconButton`.
 *
 * Auto-scroll: every time the rendered message list grows
 * (new user/assistant bubble appended or streaming bubble
 * expands) the list is scrolled to the bottom so the user does
 * not have to chase the conversation. While streaming the list
 * keeps following the tail as each new token arrives.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AskTab(
    state: AskUiState,
    onAsk: (String) -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var draft by rememberSaveable(state.running, state.messages.size) {
        // Reset the draft when a fresh round starts (either a
        // new USER bubble was added or `running` just turned
        // true). The key list keeps the input clear while the
        // streaming bubble expands and shrinks.
        mutableStateOf("")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!state.configured) {
            Text(
                text = stringResource(R.string.session_ask_not_configured),
                style = MaterialTheme.typography.bodyLarge
            )
            Button(onClick = onOpenSettings) {
                Text(text = stringResource(R.string.session_ask_open_settings))
            }
            return
        }

        // Lazily-built scroll state: the `LazyListState` is
        // remembered across recompositions so the scroll
        // position survives the streaming bubble updates.
        val listState = rememberLazyListState()
        val hasContent = state.messages.isNotEmpty() || state.streaming.isNotEmpty()
        if (hasContent) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = state.messages,
                    key = { it.id }
                ) { message ->
                    ChatBubble(message = message)
                }
                if (state.streaming.isNotEmpty()) {
                    item(key = "streaming") {
                        ChatBubble(
                            message = ChatMessageEntity(
                                id = -1L,
                                sessionId = 0L,
                                kind = com.convoscribe.data.MessageKind.QA,
                                role = MessageRole.ASSISTANT,
                                content = state.streaming,
                                createdAt = 0L
                            )
                        )
                    }
                }
                // The trailing spacer keeps the last bubble clear
                // of the keyboard when the IME is open.
                item(key = "tail") { Spacer(modifier = Modifier.height(4.dp)) }
            }
            LaunchedEffect(state.messages.size, state.streaming) {
                // Auto-scroll to the tail whenever the
                // conversation grows (new bubble appended) or the
                // streaming bubble expands by at least a few
                // tokens. Guarded by the LazyColumn's recompose so
                // we only fire when the inputs actually changed.
                if (state.messages.isNotEmpty() || state.streaming.isNotEmpty()) {
                    listState.scrollToItem(
                        index = (state.messages.size + if (state.streaming.isNotEmpty()) 1 else 0).coerceAtLeast(0)
                    )
                }
            }
        } else {
            // Empty placeholder when there is nothing to show yet.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.session_ask_empty),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        // Inline error + Retry, shown above the input row.
        state.error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            TextButton(onClick = onRetry) {
                Text(text = stringResource(R.string.session_ask_retry))
            }
        }

        // Bottom input row: text field + send button.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                singleLine = false,
                maxLines = 4,
                enabled = !state.running,
                placeholder = { Text(text = stringResource(R.string.session_ask_input_label)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        val text = draft.trim()
                        if (text.isNotEmpty() && !state.running) {
                            onAsk(text)
                            draft = ""
                        }
                    }
                )
            )
            if (state.running) {
                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.session_summary_stop)
                    )
                }
            } else {
                val canSend = draft.trim().isNotEmpty()
                IconButton(
                    enabled = canSend,
                    onClick = {
                        val text = draft.trim()
                        if (text.isNotEmpty()) {
                            onAsk(text)
                            draft = ""
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.session_ask_send_cd)
                    )
                }
            }
        }
    }
}

/**
 * Render a single chat bubble from a [ChatMessageEntity]. USER
 * bubbles are right-aligned with `primaryContainer`; ASSISTANT
 * bubbles are left-aligned with `surfaceVariant`. Text is wrapped
 * in [SelectionContainer] so the user can long-press to copy.
 *
 * The bubble width is capped at ~80 % of the row so a long
 * answer does not run edge-to-edge on a tablet.
 */
@Composable
private fun ChatBubble(message: ChatMessageEntity) {
    val isUser = message.role == MessageRole.USER
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val onBubble = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = bubbleColor,
            contentColor = onBubble,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .widthIn(max = 320.dp)
                .padding(0.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = onBubble
                    )
                }
            }
        }
    }
}
