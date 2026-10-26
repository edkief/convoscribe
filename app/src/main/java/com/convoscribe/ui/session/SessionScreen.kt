package com.convoscribe.ui.session

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import com.convoscribe.data.SessionStatus
import com.convoscribe.data.SpeakerEntity
import com.convoscribe.data.TranscriptLine
import com.convoscribe.data.TranscriptMark
import com.convoscribe.models.ModelCatalog
import com.convoscribe.recording.ImportState
import com.convoscribe.recording.RecordingPhase
import com.convoscribe.recording.RetranscribeState
import com.convoscribe.service.RecordingService
import com.convoscribe.settings.SpeakerIdMode
import com.convoscribe.ui.asString
import com.convoscribe.ui.components.ConfirmDialog
import com.convoscribe.ui.components.EmptyState
import com.convoscribe.ui.components.ScreenScaffold
import com.convoscribe.ui.components.StatusBadge
import com.convoscribe.ui.components.TagChipRow
import com.convoscribe.ui.components.TagPickerDialog
import com.convoscribe.ui.home.formatDuration
import com.convoscribe.ui.home.formatSessionDate
import com.convoscribe.ui.home.languageTag
import com.convoscribe.ui.record.ParticipantsDialog
import com.convoscribe.ui.theme.AppTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Statuses a session can be continued from: finished recording,
 * with no diarization in flight.
 */
private val CONTINUABLE_STATUSES = setOf(
    SessionStatus.TRANSCRIBED,
    SessionStatus.DIARIZED,
    SessionStatus.DIARIZATION_FAILED,
    SessionStatus.DIARIZATION_PENDING
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
 * @param initialTab tab to open on (0 transcript, 1 summary, 2 ask).
 * @param targetLineId transcript line a search hit opened, scrolled
 *   to and highlighted (issue #24).
 * @param cueAtMs where to position the player once it is ready,
 *   without playing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    sessionId: Long,
    navController: NavHostController,
    initialTab: Int = 0,
    targetLineId: Long? = null,
    cueAtMs: Long? = null
) {
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
                    audioExporter = container.audioExporter,
                    cueAtMs = cueAtMs
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
                    settings = container.settingsRepository,
                    templates = container.summaryTemplates,
                    markNotes = container.markNotesWriter,
                    autoTitler = container.autoTitler
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
    val allTags by vm.allTags.collectAsStateWithLifecycle()
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

    LaunchedEffect(vm) {
        // A hand edit landed: offer to undo it. A newer edit replaces
        // the snackbar (collectLatest cancels the one showing).
        vm.editDone.collectLatest { done ->
            val result = snackbarHostState.showSnackbar(
                message = done.message.asString(context),
                actionLabel = context.getString(R.string.session_line_undo),
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) vm.undoEdit(done.undo)
        }
    }

    LaunchedEffect(vm) {
        // The LLM just named this session (issue #32): offer to undo it.
        container.autoTitler.titled.filter { it.sessionId == sessionId }.collectLatest { titled ->
            val result = snackbarHostState.showSnackbar(
                message = context.getString(R.string.session_auto_titled, titled.title),
                actionLabel = context.getString(R.string.session_line_undo),
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) vm.undoAutoTitle()
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
    val playCleaned by vm.playCleaned.collectAsStateWithLifecycle()
    // An import holds the recorder and the ASR engine too.
    val importState by container.importAudioUseCase.state.collectAsStateWithLifecycle()
    val importing = importState is ImportState.Running
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
        createdAt = state.createdAt,
        durationMs = state.durationMs,
        title = state.title,
        status = state.status,
        lines = state.lines,
        speakers = state.speakers,
        marks = state.marks,
        markActions = MarkActions(
            onEditNote = { mark, note -> vm.editMarkNote(mark, note) },
            onDelete = { mark -> vm.deleteMark(mark) }
        ),
        tagging = TagsUi(tags = state.tags, allTags = allTags, onSetTags = vm::setTags),
        replacedTitle = state.replacedTitle,
        onRestoreTitle = vm::undoAutoTitle,
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
            !importing &&
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
        waitingForCharger = appSettings.speakerIdMode == SpeakerIdMode.CHARGING.key,
        details = SessionDetails(
            language = state.language,
            numSpeakers = effectiveSpeakerCount,
            participants = state.participants,
            asrEngine = state.asrEngine,
            asrModel = state.asrModel,
            cleanedWith = state.cleanedWith
        ),
        enrolledNames = enrolledNames,
        onSetParticipants = { vm.setParticipants(it) },
        retranscribe = RetranscribeUi(
            progress = (retranscribeState as? RetranscribeState.Running)
                ?.takeIf { it.sessionId == sessionId }
                ?.progress,
            cleaning = (retranscribeState as? RetranscribeState.Running)?.cleaning == true,
            enabled = !state.notFound &&
                recorderState.phase == RecordingPhase.IDLE &&
                !retranscribing &&
                !importing &&
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
        cleanup = CleanupUi(
            cleaned = state.cleanedWith != null,
            available = remember(state.cleanedWith, retranscribing) { container.audioCleanerFactory.isAvailable() },
            enabled = !state.notFound &&
                recorderState.phase == RecordingPhase.IDLE &&
                !retranscribing &&
                !importing &&
                state.status in CONTINUABLE_STATUSES,
            playCleaned = playCleaned,
            onClean = {
                val engine = appSettings.asrEngine
                val missing = container.modelStore.missing(ModelCatalog.requiredFor(state.language, engine))
                if (missing.isNotEmpty()) {
                    navController.navigate("setup?engine=$engine&language=${state.language}&back=true")
                } else {
                    vm.stopPlayback()
                    ContextCompat.startForegroundService(
                        context,
                        RecordingService.retranscribeIntent(context, sessionId, state.language, clean = true)
                    )
                }
            },
            onRemove = { vm.removeCleaning() },
            onPlayCleaned = vm::setPlayCleaned
        ),
        onSummarise = { summaryVm.summarise() },
        onCancelSummary = { summaryVm.cancel() },
        onSelectSummaryTemplate = summaryVm::selectTemplate,
        onWriteNotes = { summaryVm.writeNotes() },
        onCancelNotes = { summaryVm.cancelNotes() },
        onAsk = { question -> askVm.ask(question) },
        onRetryAsk = { askVm.retryLast() },
        onCancelAsk = { askVm.cancel() },
        onClearQuestions = { askVm.clearHistory() },
        onOpenSettings = { navController.navigate("settings") },
        initialTab = initialTab,
        targetLineId = targetLineId,
        hasEdits = state.hasEdits,
        // Not while something else is writing the transcript.
        lineEdits = LineEditActions(
            onEditText = { line, text -> vm.editLineText(line.id, text) },
            onSetSpeaker = { line, index -> vm.setLineSpeaker(line.id, index) },
            onNewSpeaker = { line, name -> vm.setLineNewSpeaker(line.id, name) },
            onMergeWithNext = { line -> vm.mergeWithNext(line.id) },
            onSplit = { line, offset -> vm.splitLine(line.id, offset) },
            onDelete = { line -> vm.deleteLine(line.id) }
        ).takeIf {
            !state.notFound &&
                state.status != SessionStatus.RECORDING &&
                state.status != SessionStatus.DIARIZING &&
                !retranscribing
        }
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
    onSelectSummaryTemplate: (String) -> Unit = {},
    onWriteNotes: () -> Unit = {},
    onCancelNotes: () -> Unit = {},
    onAsk: (String) -> Unit,
    onRetryAsk: () -> Unit,
    onCancelAsk: () -> Unit,
    onClearQuestions: () -> Unit,
    onOpenSettings: () -> Unit,
    details: SessionDetails = SessionDetails(),
    enrolledNames: List<String> = emptyList(),
    onSetParticipants: (List<String>?) -> Unit = {},
    retranscribe: RetranscribeUi = RetranscribeUi(),
    cleanup: CleanupUi = CleanupUi(),
    waitingForCharger: Boolean = false,
    createdAt: Long = 0L,
    durationMs: Long = 0L,
    initialTab: Int = 0,
    targetLineId: Long? = null,
    hasEdits: Boolean = false,
    lineEdits: LineEditActions? = null,
    initialLineMenu: Long? = null,
    initialLineDialog: LineDialog? = null,
    marks: List<TranscriptMark> = emptyList(),
    markActions: MarkActions = MarkActions(),
    initialMarksOpen: Boolean = false,
    tagging: TagsUi = TagsUi(),
    replacedTitle: String? = null,
    onRestoreTitle: () -> Unit = {}
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(initialTab) }
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
    // Language chosen for a re-transcription that waits for "discard your edits?".
    var retranscribeOverEdits by remember { mutableStateOf<String?>(null) }
    var marksOpen by remember { mutableStateOf(initialMarksOpen) }
    var cleanupOpen by remember { mutableStateOf(false) }
    var removeCleaningOpen by remember { mutableStateOf(false) }
    var tagsOpen by remember { mutableStateOf(false) }
    ScreenScaffold(
        title = title,
        onBack = onBack,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(snackbarData = data)
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
                if (replacedTitle != null) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.session_overflow_restore_title, replacedTitle)) },
                        onClick = {
                            overflowExpanded = false
                            onRestoreTitle()
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.session_overflow_tags)) },
                    enabled = !notFound,
                    onClick = {
                        overflowExpanded = false
                        tagsOpen = true
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
                if (cleanup.cleaned) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.session_overflow_remove_cleanup)) },
                        enabled = cleanup.enabled,
                        onClick = {
                            overflowExpanded = false
                            removeCleaningOpen = true
                        }
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.session_overflow_cleanup)) },
                        enabled = cleanup.enabled,
                        onClick = {
                            overflowExpanded = false
                            cleanupOpen = true
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.session_overflow_participants)) },
                    enabled = !notFound,
                    onClick = {
                        overflowExpanded = false
                        participantsOpen = true
                    }
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.session_overflow_marks)) },
                    enabled = marks.isNotEmpty(),
                    onClick = {
                        overflowExpanded = false
                        marksOpen = true
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
    ) { innerPadding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!notFound) {
                SessionHeader(
                    createdAt = createdAt,
                    durationMs = durationMs,
                    speakerCount = speakers.size,
                    language = details.language,
                    status = status,
                    tags = tagging.tags
                )
            }
            retranscribe.progress?.let { progress ->
                RetranscribeBanner(
                    progress = progress,
                    onCancel = retranscribe.onCancel,
                    cleaning = retranscribe.cleaning
                )
            }
            SessionTabs(selectedTab = selectedTab, onSelect = { selectedTab = it })
            // Finished sessions with a readable recording get the player;
            // it is not offered while the session is still being recorded.
            val canPlay = !notFound && status != SessionStatus.RECORDING && playerState.ready
            if (canPlay && selectedTab == 0) {
                PlayerBar(
                    state = playerState,
                    actions = playback,
                    marks = marks,
                    onOpenMarks = { marksOpen = true },
                    cleanup = cleanup
                )
            }
            when {
                notFound -> EmptyState(
                    icon = painterResource(R.drawable.ic_conversation),
                    title = stringResource(R.string.session_not_found)
                )
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
                    onSpeakerChipTapped = { index -> renameSpeakerIndex = index },
                    waitingForCharger = waitingForCharger,
                    // The manual trigger works whatever the setting says.
                    onIdentifySpeakersNow = { onReidentifySpeakers(effectiveSpeakerCount) }
                        .takeIf { retranscribe.progress == null },
                    targetLineId = targetLineId,
                    lineEdits = lineEdits,
                    initialMenuLineId = initialLineMenu,
                    initialDialog = initialLineDialog,
                    marks = marks
                )
                selectedTab == 1 -> SummaryTab(
                    state = summaryState,
                    onSummarise = onSummarise,
                    onCancel = onCancelSummary,
                    onSelectTemplate = onSelectSummaryTemplate,
                    onOpenSettings = onOpenSettings,
                    marks = marks,
                    onWriteNotes = onWriteNotes,
                    onCancelNotes = onCancelNotes,
                    // To the transcript, at the mark when it can be played.
                    onOpenMark = { mark ->
                        selectedTab = 0
                        if (canPlay) playback.onSeek(mark.atMs)
                    }
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

    if (marksOpen) {
        val canPlay = !notFound && status != SessionStatus.RECORDING && playerState.ready
        MarksDialog(
            marks = marks,
            onSeek = if (canPlay) ({ mark -> playback.onSeek(mark.atMs) }) else null,
            actions = markActions,
            onDismiss = { marksOpen = false }
        )
    }

    if (exportState is ExportState.Encoding) {
        // Not dismissable by tapping outside: encoding a long session
        // takes a while, and Cancel is the explicit way out.
        AlertDialog(
            onDismissRequest = {},
            title = { Text(text = stringResource(R.string.session_export_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
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
    if (tagsOpen) {
        TagPickerDialog(
            allTags = tagging.allTags,
            selected = tagging.tags,
            onConfirm = {
                tagsOpen = false
                tagging.onSetTags(it)
            },
            onDismiss = { tagsOpen = false }
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
                if (hasEdits) retranscribeOverEdits = language else retranscribe.onStart(language)
            },
            onDismiss = { retranscribeOpen = false }
        )
    }
    retranscribeOverEdits?.let { language ->
        ConfirmDialog(
            title = stringResource(R.string.session_retranscribe_edits_title),
            message = stringResource(R.string.session_retranscribe_edits_message),
            confirmLabel = stringResource(R.string.session_retranscribe_edits_confirm),
            dismissLabel = stringResource(R.string.conversation_cancel),
            destructive = true,
            onConfirm = {
                retranscribeOverEdits = null
                retranscribe.onStart(language)
            },
            onDismiss = { retranscribeOverEdits = null }
        )
    }
    if (cleanupOpen) {
        if (cleanup.available) {
            ConfirmDialog(
                title = stringResource(R.string.session_cleanup_title),
                message = stringResource(R.string.session_cleanup_message) +
                    if (hasEdits) "\n\n" + stringResource(R.string.session_retranscribe_edits_message) else "",
                confirmLabel = stringResource(R.string.session_cleanup_confirm),
                dismissLabel = stringResource(R.string.conversation_cancel),
                destructive = hasEdits,
                onConfirm = {
                    cleanupOpen = false
                    cleanup.onClean()
                },
                onDismiss = { cleanupOpen = false }
            )
        } else {
            ConfirmDialog(
                title = stringResource(R.string.session_cleanup_title),
                message = stringResource(R.string.session_cleanup_missing),
                confirmLabel = stringResource(R.string.session_cleanup_open_settings),
                dismissLabel = stringResource(R.string.conversation_cancel),
                onConfirm = {
                    cleanupOpen = false
                    onOpenSettings()
                },
                onDismiss = { cleanupOpen = false }
            )
        }
    }
    if (removeCleaningOpen) {
        ConfirmDialog(
            title = stringResource(R.string.session_remove_cleanup_title),
            message = stringResource(R.string.session_remove_cleanup_message),
            confirmLabel = stringResource(R.string.session_remove_cleanup_confirm),
            dismissLabel = stringResource(R.string.conversation_cancel),
            destructive = true,
            onConfirm = {
                removeCleaningOpen = false
                cleanup.onRemove()
            },
            onDismiss = { removeCleaningOpen = false }
        )
    }
    // Clear-questions confirmation. The action itself is gated on
    // `messages.isNotEmpty()` already, so the dialog only opens
    // when there is something to clear.
    if (clearQuestionsOpen) {
        ConfirmDialog(
            title = stringResource(R.string.session_ask_clear_title),
            message = stringResource(R.string.session_ask_clear_message),
            confirmLabel = stringResource(R.string.session_ask_clear_confirm),
            dismissLabel = stringResource(R.string.session_ask_clear_cancel),
            destructive = true,
            onConfirm = {
                onClearQuestions()
                clearQuestionsOpen = false
            },
            onDismiss = { clearQuestionsOpen = false }
        )
    }
}

/** A session's tags (issue #32), every existing tag for the picker, and how to change them. */
internal data class TagsUi(
    val tags: List<String> = emptyList(),
    val allTags: List<String> = emptyList(),
    val onSetTags: (List<String>) -> Unit = {}
)

/**
 * Below the top bar: when the session was recorded, how long it is,
 * how many speakers were found and its language, with a badge while
 * it is recording, identifying speakers or failed, then its tags.
 */
@Composable
private fun SessionHeader(
    createdAt: Long,
    durationMs: Long,
    speakerCount: Int,
    language: String,
    status: SessionStatus?,
    tags: List<String>
) {
    val spacing = AppTheme.spacing
    val meta = listOfNotNull(
        createdAt.takeIf { it > 0L }?.let { formatSessionDate(it) },
        formatDuration(durationMs).ifEmpty { null },
        if (speakerCount > 0) pluralStringResource(R.plurals.home_speaker_count, speakerCount, speakerCount) else null,
        languageTag(language)
    ).joinToString(" · ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = spacing.screen, end = spacing.screen, top = spacing.xs, bottom = spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s)
    ) {
        Text(
            text = meta,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (status != null) StatusBadge(status = status)
    }
    TagChipRow(
        tags = tags,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = spacing.screen, end = spacing.screen, bottom = spacing.s)
    )
}

/**
 * Transcript / Summary / Ask. Fixed equal-width tabs normally; at large
 * font scales scrollable tabs sized to their labels, so a label is
 * never broken across lines.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionTabs(selectedTab: Int, onSelect: (Int) -> Unit) {
    val tabs = listOf(R.string.session_tab_transcript, R.string.session_tab_summary, R.string.session_tab_ask)
    val content: @Composable () -> Unit = {
        tabs.forEachIndexed { index, label ->
            Tab(
                selected = selectedTab == index,
                onClick = { onSelect(index) },
                text = { Text(text = stringResource(label), maxLines = 1) }
            )
        }
    }
    if (AppTheme.largeText) {
        PrimaryScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = AppTheme.spacing.s, tabs = content)
    } else {
        PrimaryTabRow(selectedTabIndex = selectedTab, tabs = content)
    }
}
