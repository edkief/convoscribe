package com.convoscribe.ui.record

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.convoscribe.ConvoApp
import com.convoscribe.R
import com.convoscribe.data.SessionStatus
import com.convoscribe.models.ModelCatalog
import com.convoscribe.overlay.OverlayPermission
import com.convoscribe.overlay.OverlayPermissionDialog
import com.convoscribe.recording.ConversationOptions
import com.convoscribe.recording.ImportState
import com.convoscribe.recording.RecordingPhase
import com.convoscribe.recording.RetranscribeState
import com.convoscribe.service.RecordingService
import com.convoscribe.ui.components.AppCard
import com.convoscribe.ui.components.ErrorBanner
import com.convoscribe.ui.components.NoticeText
import com.convoscribe.ui.components.PausedBadge
import com.convoscribe.ui.components.ProgressBlock
import com.convoscribe.ui.components.ScreenScaffold
import com.convoscribe.ui.components.SectionCard
import com.convoscribe.ui.components.StatusBadge
import com.convoscribe.ui.theme.AppTheme

/**
 * Top-level destination entered from the home FAB to record a fresh
 * session. Lifecycle:
 *
 *  1. the Start button is always tappable while idle; without the
 *     `RECORD_AUDIO` runtime permission it launches the system
 *     permission dialog and starts recording once it is granted;
 *  2. it re-checks the permission on every tap;
 *     a denial shows the "Microphone permission is required…"
 *     message and never reaches the service start call;
 *  3. once the permission is granted the Start button asks the
 *     service to go foreground and the screen drives itself off
 *     [com.convoscribe.recording.RecordingController.state];
 *  4. when [com.convoscribe.recording.RecordingState.lastCompletedSessionId]
 *     becomes non-null the screen navigates to
 *     `session/{id}`, pops `record`, and clears the pointer so the
 *     same completion is not re-handled.
 *
 * `POST_NOTIFICATIONS` (Android 13+) is requested together with the
 * mic permission but a denial there is non-fatal: the foreground
 * notification will fail to display, but the recording itself keeps
 * working for the duration of the session.
 *
 * @param navController navigation controller the screen pops back
 *   to the home screen from, and pushes the session-detail route
 *   to after a successful stop.
 * @param resumeSessionId finished session to continue recording
 *   into, or `null` for a new session. When set, Start appends to
 *   that session and stopping pops back to its (already open)
 *   session screen instead of pushing a new one.
 */
@Composable
fun RecordScreen(navController: NavHostController, resumeSessionId: Long? = null) {
    val context = LocalContext.current
    val container = (context.applicationContext as ConvoApp).container
    val vm: RecordViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                RecordViewModel(
                    controller = container.recordingController,
                    settings = container.settingsRepository,
                    enrolled = container.enrolledSpeakers,
                    missingModels = { language ->
                        val engine = container.settingsRepository.settings.value.asrEngine
                        container.modelStore.missing(ModelCatalog.requiredFor(language, engine)).isNotEmpty()
                    }
                )
            }
        }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()
    val enrolledNames by vm.enrolledNames.collectAsStateWithLifecycle()
    val retranscribeState by container.retranscribeUseCase.state.collectAsStateWithLifecycle()
    val importState by container.importAudioUseCase.state.collectAsStateWithLifecycle()
    // A continued session keeps its own options; only a new one sends them.
    val startOptions = options.takeIf { resumeSessionId == null }

    // `rememberSaveable` keeps the denial message around process
    // death so a transient blank does not make it vanish.
    var micDeniedMessage by rememberSaveable { mutableStateOf<String?>(null) }
    // The one-time explanation of the floating control's permission.
    var askOverlay by rememberSaveable { mutableStateOf(false) }
    val startRecording: () -> Unit = {
        ContextCompat.startForegroundService(
            context,
            RecordingService.startIntent(context, resumeSessionId, startOptions)
        )
        if (vm.shouldAskForOverlay(OverlayPermission.granted(context))) askOverlay = true
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val micOk = result[Manifest.permission.RECORD_AUDIO] == true
        micDeniedMessage = if (micOk) {
            null
        } else {
            // Mic denial blocks recording; notification denial is
            // silently ignored — the session still records, the
            // notification just stays invisible.
            context.getString(R.string.record_error_mic_required)
        }
        if (micOk) startRecording()
    }

    // Pop the screen when the session has been acknowledged by the
    // navigation downstream: while the user is taken to the
    // session-detail placeholder the record screen is no longer on
    // the back stack.
    LaunchedEffect(state.lastCompletedSessionId) {
        val id = state.lastCompletedSessionId ?: return@LaunchedEffect
        if (resumeSessionId != null) {
            // Continued from that session's screen, which is right
            // below us on the back stack.
            navController.popBackStack()
        } else {
            navController.navigate("session/$id") {
                popUpTo("record") { inclusive = true }
            }
        }
        vm.onNavigatedToSession()
    }

    RecordBody(
        phase = state.phase,
        preparingNpu = state.preparingNpu,
        elapsedMs = state.elapsedMs,
        finalLines = state.finalLines,
        partialText = state.partialText,
        error = state.error,
        busyMessage = when {
            retranscribeState is RetranscribeState.Running -> stringResource(R.string.record_busy_retranscribing)
            importState is ImportState.Running -> stringResource(R.string.record_busy_importing)
            else -> null
        },
        micDeniedMessage = micDeniedMessage,
        continuing = resumeSessionId != null,
        inputLevel = state.inputLevel,
        options = options,
        enrolledNames = enrolledNames,
        optionActions = ConversationOptionActions(
            onLanguageChange = vm::setLanguage,
            onNumSpeakersChange = vm::setNumSpeakers,
            onParticipantsChange = vm::setParticipants,
            onTitleChange = vm::setTitle,
            onSaveAsDefault = vm::saveAsDefault
        ),
        onBack = { navController.popBackStack() },
        onStart = {
            // The chosen language's models must be on disk first:
            // download them in setup, which pops back here with the
            // options untouched.
            if (resumeSessionId == null && vm.modelsMissing()) {
                val engine = container.settingsRepository.settings.value.asrEngine
                navController.navigate("setup?engine=$engine&language=${options.language}&back=true")
                return@RecordBody
            }
            // Defensive: re-check at click time, not just at
            // recomposition. The user could have revoked the
            // permission from system Settings while the screen
            // was in the background.
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                launcher.launch(requiredPermissions())
                return@RecordBody
            }
            startRecording()
        },
        onStop = {
            // Plain startService is intentional: we are sending the
            // service an action, not promoting it. The service has
            // already been promoted to a foreground microphone
            // service, so the regular start path is correct.
            context.startService(RecordingService.stopIntent(context))
        },
        onPause = { context.startService(RecordingService.pauseIntent(context)) },
        onResume = { context.startService(RecordingService.resumeIntent(context)) }
    )

    if (askOverlay) {
        OverlayPermissionDialog(
            onAllow = {
                askOverlay = false
                context.startActivity(OverlayPermission.settingsIntent(context))
            },
            onDismiss = { askOverlay = false }
        )
    }
}

/**
 * Render-only variant of [RecordScreen] for preview / testing.
 *
 * Layout: while a new conversation is idle, its options in a section
 * card; once started, the elapsed time, a recording badge, the input
 * [InputLevelMeter] and the live transcript. The big round Start /
 * Stop control sits at the bottom in every phase, with Pause / Resume
 * next to Stop while a session is open (issue #7).
 *
 * @param inputLevel microphone level `0f..1f` while recording.
 * @param micDeniedMessage shown above the control when the user denied
 *   the runtime permission.
 * @param continuing `true` when appending to an existing session;
 *   changes the title and hides the new-conversation options.
 * @param busyMessage why Start is unavailable (another job holds the
 *   recorder), or `null`.
 * @param options the new conversation's options, shown while idle
 *   (see [ConversationOptionsForm]).
 */
@Composable
internal fun RecordBody(
    phase: RecordingPhase,
    preparingNpu: Boolean,
    elapsedMs: Long,
    finalLines: List<String>,
    partialText: String,
    error: String?,
    busyMessage: String? = null,
    micDeniedMessage: String?,
    continuing: Boolean = false,
    options: ConversationOptions? = null,
    enrolledNames: List<String> = emptyList(),
    optionActions: ConversationOptionActions = ConversationOptionActions(),
    inputLevel: Float = 0f,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {}
) {
    val spacing = AppTheme.spacing
    val showOptions = phase == RecordingPhase.IDLE && !continuing && options != null
    ScreenScaffold(
        title = stringResource(if (continuing) R.string.record_title_continue else R.string.record_title),
        onBack = onBack
    ) { innerPadding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = spacing.screen),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.m)
            ) {
                if (showOptions && options != null) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(max = AppTheme.sizes.contentMaxWidth)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = spacing.s),
                        verticalArrangement = Arrangement.spacedBy(spacing.m)
                    ) {
                        SectionCard(title = stringResource(R.string.record_section_conversation)) {
                            ConversationOptionsForm(
                                options = options,
                                enrolledNames = enrolledNames,
                                onLanguageChange = optionActions.onLanguageChange,
                                onNumSpeakersChange = optionActions.onNumSpeakersChange,
                                onParticipantsChange = optionActions.onParticipantsChange,
                                onTitleChange = optionActions.onTitleChange,
                                onSaveAsDefault = optionActions.onSaveAsDefault
                            )
                        }
                    }
                } else {
                    LiveTranscript(
                        phase = phase,
                        preparingNpu = preparingNpu,
                        elapsedMs = elapsedMs,
                        inputLevel = inputLevel,
                        finalLines = finalLines,
                        partialText = partialText,
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(max = AppTheme.sizes.contentMaxWidth)
                    )
                }
                if (busyMessage != null && phase == RecordingPhase.IDLE) {
                    NoticeText(text = busyMessage, modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth))
                }
                micDeniedMessage?.let { ErrorBanner(message = it) }
                error?.let { ErrorBanner(message = it) }
            }
            RecordControl(
                phase = phase,
                canStart = phase == RecordingPhase.IDLE && busyMessage == null,
                continuing = continuing,
                onStart = onStart,
                onStop = onStop,
                onPause = onPause,
                onResume = onResume
            )
        }
    }
}

/**
 * Timer, recording badge, level meter (or the loading / finishing
 * message) and the live transcript, newest line kept in view.
 */
@Composable
private fun LiveTranscript(
    phase: RecordingPhase,
    preparingNpu: Boolean,
    elapsedMs: Long,
    inputLevel: Float,
    finalLines: List<String>,
    partialText: String,
    modifier: Modifier = Modifier
) {
    val spacing = AppTheme.spacing
    Column(
        modifier = modifier.padding(top = spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.m)
    ) {
        Text(
            text = formatElapsed(elapsedMs),
            style = AppTheme.typography.timer,
            textAlign = TextAlign.Center
        )
        when (phase) {
            RecordingPhase.RECORDING -> {
                StatusBadge(status = SessionStatus.RECORDING)
                InputLevelMeter(level = inputLevel)
            }
            RecordingPhase.LOADING -> ProgressBlock(
                message = stringResource(
                    if (preparingNpu) R.string.record_phase_preparing_npu else R.string.record_phase_loading
                )
            )
            RecordingPhase.STOPPING -> ProgressBlock(message = stringResource(R.string.record_phase_stopping))
            RecordingPhase.PAUSED -> PausedBadge()
            RecordingPhase.IDLE -> Unit
        }

        // The list grows monotonically for the lifetime of a session,
        // so a remembered list state plus an effect over the size keeps
        // the latest line in view (a jump instead of a scroll on e-ink).
        val listState = rememberLazyListState()
        val showPartial = phase == RecordingPhase.RECORDING && partialText.isNotBlank()
        val itemCount = finalLines.size + (if (showPartial) 1 else 0)
        val reduceMotion = AppTheme.reduceMotion
        LaunchedEffect(itemCount) {
            if (itemCount > 0) {
                if (reduceMotion) {
                    listState.scrollToItem(
                        itemCount - 1
                    )
                } else {
                    listState.animateScrollToItem(itemCount - 1)
                }
            }
        }
        AppCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (itemCount == 0) {
                Text(
                    text = stringResource(
                        when (phase) {
                            RecordingPhase.RECORDING -> R.string.record_live_listening
                            RecordingPhase.PAUSED -> R.string.record_live_paused
                            else -> R.string.record_live_idle
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(spacing.l)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(spacing.l),
                    verticalArrangement = Arrangement.spacedBy(spacing.m)
                ) {
                    items(finalLines) { line ->
                        Text(text = line, style = AppTheme.typography.transcript)
                    }
                    if (showPartial) {
                        item {
                            Text(
                                text = partialText,
                                style = AppTheme.typography.transcript.copy(fontStyle = FontStyle.Italic),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The big round Start / Stop button with its caption. Disabled during
 * LOADING / STOPPING (and while another job holds the recorder) so the
 * start path cannot fire twice; the service rejects concurrent starts
 * anyway, but this shows it. Not gated on the mic permission: tapping
 * Start is what asks for it. While a session is open a smaller Pause /
 * Resume button sits to the left of Stop, balanced by an empty slot
 * on the right so Stop stays centred.
 */
@Composable
private fun RecordControl(
    phase: RecordingPhase,
    canStart: Boolean,
    continuing: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit
) {
    val spacing = AppTheme.spacing
    val scheme = MaterialTheme.colorScheme
    // "Recording" for the big button means a session is open, paused
    // or not: it is the Stop button then.
    val recording = phase.active
    val paused = phase == RecordingPhase.PAUSED
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.s)
    ) {
        val enabled = recording || canStart
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xl)
        ) {
            Box(modifier = Modifier.size(AppTheme.sizes.touchTarget + spacing.l)) {
                if (recording) {
                    val label = stringResource(if (paused) R.string.record_resume else R.string.record_pause)
                    FilledTonalIconButton(
                        onClick = if (paused) onResume else onPause,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            painter = painterResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause),
                            contentDescription = label,
                            modifier = Modifier.size(AppTheme.sizes.icon)
                        )
                    }
                }
            }
            MainRecordButton(recording = recording, enabled = enabled, onStart = onStart, onStop = onStop)
            Spacer(modifier = Modifier.size(AppTheme.sizes.touchTarget + spacing.l))
        }
        val caption = when {
            paused -> R.string.record_hint_paused
            recording -> R.string.record_hint_stop
            !canStart -> null
            continuing -> R.string.record_hint_continue
            else -> R.string.record_hint_start
        }
        Text(
            text = caption?.let { stringResource(it) }.orEmpty(),
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurfaceVariant
        )
    }
}

/** The big round button: Start while idle, Stop while a session is open. */
@Composable
private fun MainRecordButton(recording: Boolean, enabled: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = if (recording) onStop else onStart,
        enabled = enabled,
        modifier = Modifier.size(AppTheme.sizes.recordButton),
        shape = CircleShape,
        contentPadding = PaddingValues(),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (recording) scheme.error else scheme.primary,
            contentColor = if (recording) scheme.onError else scheme.onPrimary
        )
    ) {
        Icon(
            painter = painterResource(if (recording) R.drawable.ic_stop else R.drawable.ic_mic),
            contentDescription = stringResource(if (recording) R.string.record_stop else R.string.record_start),
            modifier = Modifier.size(AppTheme.sizes.iconLarge)
        )
    }
}

/** Callbacks of the new-conversation [ConversationOptionsForm]. */
internal data class ConversationOptionActions(
    val onLanguageChange: (String) -> Unit = {},
    val onNumSpeakersChange: (Int) -> Unit = {},
    val onParticipantsChange: (List<String>?) -> Unit = {},
    val onTitleChange: (String) -> Unit = {},
    val onSaveAsDefault: () -> Unit = {}
)

/**
 * Runtime permissions the Start button asks for. Mic + the Android
 * 13+ notification permission, which only matters while Android 13+
 * has the service in foreground. The notification permission is
 * intentionally requested alongside mic so the user only sees one
 * rationale prompt per session entry.
 */
private fun requiredPermissions(): Array<String> {
    val perms = mutableListOf<String>(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        perms += Manifest.permission.POST_NOTIFICATIONS
    }
    return perms.toTypedArray()
}
