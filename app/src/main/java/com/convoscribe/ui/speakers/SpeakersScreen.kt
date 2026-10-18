package com.convoscribe.ui.speakers

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import com.convoscribe.data.EnrolledSpeakerEntity
import com.convoscribe.models.ModelCatalog
import com.convoscribe.models.ModelStore
import com.convoscribe.ui.asString
import kotlin.math.roundToLong

/**
 * "Known speakers" screen. The user types a name, hits "Record
 * sample", and reads out loud for 15 seconds; ConvoScribe records
 * the clip, runs it through the [com.convoscribe.diarization.SpeakerEmbedder]
 * (sherpa-onnx's [com.convoscribe.models.ModelCatalog.EMBEDDING]
 * bundle) and persists the resulting vector through
 * [com.convoscribe.data.EnrolledSpeakerStore] so future
 * recordings can label matched voices with the user's real name
 * (goal 5).
 *
 * Layout:
 *
 *  - short headline explaining the 15-second prompt;
 *  - name text field (`OutlinedTextField`, saveable on rotation);
 *  - "Record sample" button (or "Cancel" while recording). The
 *    button asks for `RECORD_AUDIO` the first time it is tapped
 *    and re-checks after the permission returns;
 *  - recording progress: a `LinearProgressIndicator` driven by
 *    [SpeakersUiState.progress] and a "N s remaining" caption,
 *    visible only while [EnrollmentPhase.RECORDING];
 *  - "Processing…" caption visible only while
 *    [EnrollmentPhase.PROCESSING];
 *  - a `LazyColumn` of every enrolled name with a trash icon next
 *    to each; tapping the trash opens a confirm dialog and the
 *    delete is forwarded to [SpeakersViewModel.delete];
 *  - if the embedding model is not on disk, an "Open setup"
 *    button that pushes `setup` on the navigator.
 *
 * The screen does not own its own coroutine scope: every action
 * routes through the ViewModel so the lifecycle is consistent
 * across configuration changes.
 */
@Composable
fun SpeakersScreen(navController: NavHostController) {
    val context = LocalContext.current
    val container = (context.applicationContext as ConvoApp).container
    val vm: SpeakersViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SpeakersViewModel(
                    repo = container.enrolledSpeakers,
                    enroll = container.enrollSpeakerUseCase
                )
            }
        }
    )
    val state by vm.state.collectAsStateWithLifecycle()

    // Re-evaluate the RECORD_AUDIO permission and the embedding-
    // model on-disk state on every recomposition. The state
    // collector only stores the latest, but a permission grant
    // may flip while this screen is in the background, so the
    // LaunchedEffect keys on `Unit` and reads directly.
    var micGranted by rememberSaveable {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    LaunchedEffect(Unit) {
        micGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        // The store is the only on-disk check we need: the embedder
        // factory throws `IllegalStateException("Model not
        // installed: emb-wespeaker-resnet34-lm")` whenever the
        // `.onnx` is missing or truncated, so a permissive boolean
        // derived from `isInstalled(EMBEDDING)` is enough to gate
        // the "Open setup" hint shown when the user hits Record.
        val store: ModelStore = container.modelStore
        vm.refreshEmbeddingModelInstalled(
            store.isInstalled(ModelCatalog.EMBEDDING)
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        micGranted = granted
        if (granted) {
            vm.startEnrollment()
        }
    }

    // The delete confirmation dialog opens with a specific row id
    // and closes on Cancel/Delete. Kept local to the screen so the
    // ViewModel does not have to know about a dialog framework.
    // Name of the person whose removal awaits confirmation.
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    SpeakersBody(
        speakers = state.speakers,
        enrollName = state.enrollName,
        phase = state.phase,
        progress = state.progress,
        embeddingModelInstalled = state.embeddingModelInstalled,
        error = state.error?.asString(),
        micGranted = micGranted,
        onBack = { navController.popBackStack() },
        onEnrollNameChange = vm::setEnrollName,
        onStartRecording = {
            // Defensive re-check: the permission could have been
            // revoked from system Settings while the screen was
            // in the background; cheap to re-check on click.
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                launcher.launch(Manifest.permission.RECORD_AUDIO)
                return@SpeakersBody
            }
            vm.startEnrollment()
        },
        onCancel = vm::cancel,
        onRequestDelete = { pendingDelete = it },
        onOpenSetup = { navController.navigate("setup") }
    )

    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(text = stringResource(R.string.speakers_delete_title)) },
            text = {
                Text(
                    text = stringResource(R.string.speakers_delete_message, toDelete)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deletePerson(toDelete)
                    pendingDelete = null
                }) {
                    Text(text = stringResource(R.string.speakers_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(text = stringResource(R.string.speakers_delete_cancel))
                }
            }
        )
    }
}

/**
 * Render-only body of [SpeakersScreen]. Used in previews (none
 * today, but the split keeps the screen easy to render in a
 * snapshot test) and called from the screen composable above
 * with the live state.
 *
 * @param speakers list rendered in the lower `LazyColumn`.
 * @param enrollName current text in the Name field.
 * @param phase current [EnrollmentPhase] — gates the Start
 *   button's label and the visibility of the progress /
 *   processing indicators.
 * @param progress recording progress in `0f..1f` for the
 *   `LinearProgressIndicator`. Zero outside RECORDING.
 * @param embeddingModelInstalled when `false`, an "Open setup"
 *   button replaces the Record button so the user can fix the
 *   missing-model state without going through Settings first.
 * @param error most-recent error from the enrollment pipeline
 *   (kept verbatim — the screen does not translate the engine's
 *   exception messages).
 * @param micGranted state of the `RECORD_AUDIO` runtime
 *   permission. When `false`, a hint is shown under the Record
 *   button; tapping Record then launches the permission request
 *   instead of calling the ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeakersBody(
    speakers: List<EnrolledSpeakerEntity>,
    enrollName: String,
    phase: EnrollmentPhase,
    progress: Float,
    embeddingModelInstalled: Boolean,
    error: String?,
    micGranted: Boolean,
    onBack: () -> Unit,
    onEnrollNameChange: (String) -> Unit,
    onStartRecording: () -> Unit,
    onCancel: () -> Unit,
    onRequestDelete: (String) -> Unit,
    onOpenSetup: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.speakers_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.speakers_back)
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
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.speakers_explanation),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Start
            )
            OutlinedTextField(
                value = enrollName,
                onValueChange = onEnrollNameChange,
                label = { Text(text = stringResource(R.string.speakers_name_label)) },
                singleLine = true,
                enabled = phase != EnrollmentPhase.PROCESSING,
                modifier = Modifier.fillMaxWidth()
            )
            // Two states for the primary action:
            //  - IDLE: a "Record sample" button. If the embedding
            //    model is missing, an "Open setup" button is the
            //    only affordance — the recording path would throw
            //    `Model not installed: emb-wespeaker-resnet34-lm`
            //    on the use case's first call.
            //  - RECORDING: a "Cancel" button that runs
            //    [SpeakersViewModel.cancel] and stops the audio
            //    collector. The progress bar + "X s remaining"
            //    caption sit beneath the button.
            //  - PROCESSING: the button is replaced by a
            //    deterministic "Processing…" caption.
            when (phase) {
                EnrollmentPhase.IDLE -> {
                    if (embeddingModelInstalled) {
                        // Not gated on the mic permission: tapping
                        // Record is what asks for it.
                        Button(
                            onClick = onStartRecording,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = stringResource(R.string.speakers_record))
                        }
                        if (!micGranted) {
                            Text(
                                text = stringResource(R.string.speakers_mic_required),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else {
                        OutlinedButton(
                            onClick = onOpenSetup,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = stringResource(R.string.speakers_open_setup))
                        }
                    }
                }
                EnrollmentPhase.RECORDING -> {
                    Button(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(R.string.speakers_cancel))
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    val secondsRemaining = ((1f - progress) * SPEECH_DURATION_MS / 1000f)
                        .roundToLong()
                        .coerceAtLeast(0L)
                    Text(
                        text = stringResource(
                            R.string.speakers_remaining,
                            secondsRemaining.toInt()
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                EnrollmentPhase.PROCESSING -> {
                    Text(
                        text = stringResource(R.string.speakers_processing),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            if (error != null) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.speakers_section_enrolled),
                style = MaterialTheme.typography.titleMedium
            )
            if (speakers.isEmpty()) {
                Text(
                    text = stringResource(R.string.speakers_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // One row per person; each may have several voice samples.
                    val people = speakers.groupBy { it.name }.toList()
                    items(people, key = { it.first }) { (name, samples) ->
                        EnrolledRow(name = name, sampleCount = samples.size, onRequestDelete = onRequestDelete)
                    }
                }
            }
        }
    }
}

/**
 * One row in the enrolled-speakers list: the person's name and how
 * many voice samples they have, with a trash icon on the right that
 * opens the confirm dialog owned by the screen (removing the person
 * removes all their samples).
 */
@Composable
private fun EnrolledRow(name: String, sampleCount: Int, onRequestDelete: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        ) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = pluralStringResource(R.plurals.speakers_sample_count, sampleCount, sampleCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = { onRequestDelete(name) }) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.speakers_delete_cd, name)
            )
        }
    }
}

/**
 * Recording duration in milliseconds. Kept in one place so the
 * screen's "X s remaining" caption and the ViewModel's
 * `record(durationMs = …)` call stay in lockstep. Mirrors the
 * default the [com.convoscribe.diarization.EnrollSpeakerUseCase]
 * ships with so an unmigrated ViewModel that omits the parameter
 * would record the same length.
 */
private const val SPEECH_DURATION_MS: Long = 15_000L
