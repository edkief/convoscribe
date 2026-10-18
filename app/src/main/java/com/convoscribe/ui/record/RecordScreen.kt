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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.convoscribe.ConvoApp
import com.convoscribe.R
import com.convoscribe.models.ModelCatalog
import com.convoscribe.recording.ConversationOptions
import com.convoscribe.recording.RecordingPhase
import com.convoscribe.recording.RetranscribeState
import com.convoscribe.service.RecordingService

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
    // A continued session keeps its own options; only a new one sends them.
    val startOptions = options.takeIf { resumeSessionId == null }

    // `rememberSaveable` keeps the denial message around process
    // death so a transient blank does not make it vanish.
    var micDeniedMessage by rememberSaveable { mutableStateOf<String?>(null) }

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
        if (micOk) {
            ContextCompat.startForegroundService(
                context,
                RecordingService.startIntent(context, resumeSessionId, startOptions)
            )
        }
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
        busyMessage = if (retranscribeState is RetranscribeState.Running) {
            stringResource(R.string.record_busy_retranscribing)
        } else {
            null
        },
        micDeniedMessage = micDeniedMessage,
        continuing = resumeSessionId != null,
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
            ContextCompat.startForegroundService(
                context,
                RecordingService.startIntent(context, resumeSessionId, startOptions)
            )
        },
        onStop = {
            // Plain startService is intentional: we are sending the
            // service an action, not promoting it. The service has
            // already been promoted to a foreground microphone
            // service, so the regular start path is correct.
            context.startService(RecordingService.stopIntent(context))
        }
    )
}

/**
 * Render-only variant of [RecordScreen] for preview / testing.
 *
 * @param micDeniedMessage shown between the elapsed time and the
 *   final transcript when the user denied the runtime permission.
 * @param continuing `true` when appending to an existing session;
 *   changes the title and hides the new-conversation options.
 * @param busyMessage why Start is unavailable (another job holds the
 *   recorder), or `null`.
 * @param options the new conversation's options, shown while idle
 *   (see [ConversationOptionsForm]).
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    onBack: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val titleRes = if (continuing) R.string.record_title_continue else R.string.record_title
                    Text(text = stringResource(titleRes))
                },
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
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = formatElapsed(elapsedMs),
                style = MaterialTheme.typography.displayMedium,
                textAlign = TextAlign.Center
            )

            // Phase label: blank while IDLE, "Loading speech model…"
            // during the brief LOADING window (or the longer
            // "Preparing the NPU model…" message on the first NPU
            // run), "Finishing…" during STOPPING. Keeping this off
            // the IDLE branch keeps the home/start view visually
            // quiet.
            val phaseLabel = when (phase) {
                RecordingPhase.LOADING -> stringResource(
                    if (preparingNpu) {
                        R.string.record_phase_preparing_npu
                    } else {
                        R.string.record_phase_loading
                    }
                )
                RecordingPhase.STOPPING -> stringResource(R.string.record_phase_stopping)
                RecordingPhase.IDLE, RecordingPhase.RECORDING -> null
            }
            if (phaseLabel != null) {
                Text(
                    text = phaseLabel,
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            if (phase == RecordingPhase.IDLE && !continuing && options != null) {
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

            if (busyMessage != null && phase == RecordingPhase.IDLE) {
                Text(
                    text = busyMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }

            if (micDeniedMessage != null) {
                Text(
                    text = micDeniedMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }

            // Transcript column. The list grows monotonically for
            // the lifetime of a session so a stable list-state
            // `rememberLazyListState` plus a `LaunchedEffect` over
            // the size is enough to keep the latest line in view.
            val listState = rememberLazyListState()
            val showPartial = phase == RecordingPhase.RECORDING &&
                partialText.isNotBlank()
            val itemCount = finalLines.size + (if (showPartial) 1 else 0)
            LaunchedEffect(itemCount) {
                if (itemCount > 0) {
                    listState.animateScrollToItem(index = itemCount - 1)
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(finalLines) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                if (showPartial) {
                    item {
                        Text(
                            text = partialText,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontStyle = FontStyle.Italic
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (error != null) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }

            // Big round Start/Stop button. Disabled during the
            // LOADING window so the user cannot fire the start
            // path twice while the recognizer is being built; the
            // service already rejects concurrent starts but the
            // disabling here gives clearer visual feedback.
            // Not gated on the mic permission: tapping Start is what
            // asks for it.
            val showStart = phase == RecordingPhase.IDLE && busyMessage == null
            val showStop = phase == RecordingPhase.RECORDING
            // The round FAB is large (96dp) but it's structured as
            // a `Button` (not `FloatingActionButton`) so its state
            // can be disabled without pulling in two composables.
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                when {
                    showStart -> Button(
                        onClick = onStart,
                        modifier = Modifier.fillMaxSize(),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_mic),
                            contentDescription = stringResource(R.string.record_start),
                            tint = Color.White
                        )
                    }
                    showStop -> Button(
                        onClick = onStop,
                        modifier = Modifier.fillMaxSize(),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_stop),
                            contentDescription = stringResource(R.string.record_stop),
                            tint = Color.White
                        )
                    }
                    else -> Button(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxSize(),
                        shape = CircleShape
                    ) {}
                }
            }
        }
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
