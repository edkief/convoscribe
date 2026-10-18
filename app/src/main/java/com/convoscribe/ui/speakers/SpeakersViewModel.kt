package com.convoscribe.ui.speakers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoscribe.R
import com.convoscribe.data.EnrolledSpeakerEntity
import com.convoscribe.data.EnrolledSpeakerStore
import com.convoscribe.diarization.EnrollSpeakerUseCase
import com.convoscribe.ui.UiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * V2 enrollment pipeline's phase, observable through
 * [SpeakersUiState.phase]. The view model owns this finite state
 * machine so the screen can render a recording progress bar, an
 * "Embedding the voice sample…" caption or a list of enrolled
 * names without reaching into the use case.
 *
 * Transitions:
 *
 *  IDLE → RECORDING (via [SpeakersViewModel.startEnrollment], which
 *    kicks the [EnrollSpeakerUseCase.record] coroutine and updates
 *    [SpeakersUiState.progress] through the use case's progress
 *    callback).
 *  RECORDING → PROCESSING (the use case stopped emitting audio and
 *    the embedder is being asked for a vector — the screen shows an
 *    indeterminate "Processing…" indicator).
 *  PROCESSING → IDLE (the [EnrollSpeakerUseCase.enroll] call
 *    returned; the speaker is in the list, the name input is
 *    cleared, [SpeakersUiState.error] is null).
 *  any → IDLE on error ([SpeakersViewModel.startEnrollment] catches
 *    the exception and surfaces its message verbatim so the UI can
 *    decide whether to translate it).
 *
 * Cancellation: [SpeakersViewModel.cancel] flips the phase back
 * to `IDLE` and cancels the in-flight job (the use case's flow
 * stops being collected). The partially-collected samples are
 * dropped — the next [SpeakersViewModel.startEnrollment] call
 * starts a fresh recording.
 */
enum class EnrollmentPhase { IDLE, RECORDING, PROCESSING }

/**
 * Snapshot rendered by [SpeakersScreen].
 *
 * @property speakers current list of enrollments, sorted by name
 *   (Room orders the query with `COLLATE NOCASE`, so the screen
 *   just renders it as it arrives).
 * @property enrollName in-flight name the user typed. Cleared back
 *   to `""` on a successful enrollment; the UI keeps the text
 *   field in sync through [SpeakersViewModel.setEnrollName].
 * @property phase current [EnrollmentPhase] — drives the recording
 *   button's label, the progress bar's visibility and the
 *   "Processing…" caption.
 * @property progress recording progress in `0f..1f` while the
 *   phase is [EnrollmentPhase.RECORDING]. Stable at `0f` in the
 *   other phases. The progress bar's denominator is fixed at `1f`
 *   and the screen formats the seconds remaining from
 *   `(1f - progress) * SPEECH_DURATION_MS`.
 * @property embeddingModelInstalled `true` when the
 *   `wespeaker_en_voxceleb_resnet34_LM.onnx` file the embedder
 *   factory reads is on disk at the declared
 *   [com.convoscribe.models.ModelFile.sizeBytes]. Gates the
 *   "Open setup" hint that the screen renders when the user
 *   tries to record and the model is missing.
 * @property error human-readable message from the last failed
 *   [SpeakersViewModel.startEnrollment] / cancel cycle. `null`
 *   after a successful enrollment. The view model intentionally
 *   stores the message verbatim — the screen decides whether to
 *   translate pre-existing engine strings ("Audio too short for a
 *   voice sample", "Model not installed: …") or show them as-is.
 */
data class SpeakersUiState(
    val speakers: List<EnrolledSpeakerEntity> = emptyList(),
    val enrollName: String = "",
    val phase: EnrollmentPhase = EnrollmentPhase.IDLE,
    val progress: Float = 0f,
    val embeddingModelInstalled: Boolean = false,
    val error: UiText? = null
)

/**
 * ViewModel for the "Known speakers" screen. Drives the
 * [EnrollSpeakerUseCase] state machine, exposes the
 * [EnrolledSpeakerStore.observeAll] flow so the list reactively
 * reflects inserts and deletes, and lets the screen delete a
 * single speaker by its room id via [SpeakersViewModel.delete].
 *
 * Construction is by the `viewModelFactory` in
 * [SpeakersScreen] reading `AppContainer.enrolledSpeakers` and
 * `AppContainer.enrollSpeakerUseCase` so the screen test never
 * instantiates sherpa classes.
 *
 * @param repo persistence façade over the enrolled-speaker Room
 *   table. The view model collects from [EnrolledSpeakerStore.observeAll]
 *   so a delete from any other surface (a future "forget this
 *   voice" menu) shows up here without re-asking the database.
 * @param enroll use case that records a 15-second clip and runs
 *   it through the speaker embedder. Already wired with the
 *   sherpa-backed [com.convoscribe.engine.sherpa.SherpaSpeakerEmbedderFactory]
 *   via [com.convoscribe.AppContainer], so a test can inject a
 *   fake [com.convoscribe.audio.AudioSource] and the JVM-friendly
 *   embedder fake the previous task landed.
 */
class SpeakersViewModel(
    private val repo: EnrolledSpeakerStore,
    private val enroll: EnrollSpeakerUseCase
) : ViewModel() {

    private val _state: MutableStateFlow<SpeakersUiState> =
        MutableStateFlow(SpeakersUiState())

    /** Latest snapshot consumed by the screen. */
    val state: StateFlow<SpeakersUiState> = _state.asStateFlow()

    /**
     * In-flight recording / embedding job. Held so
     * [SpeakersViewModel.cancel] can stop the coroutine cleanly
     * and so a screen recomposition does not orphan it.
     */
    private var enrollmentJob: Job? = null

    init {
        // Mirror the repository flow into [SpeakersUiState.speakers]
        // so a Room insert or delete from any surface is reflected
        // on the next snapshot. `viewModelScope` keeps the
        // collection alive for the lifetime of the ViewModel and
        // cancels it on `onCleared`, so navigating away stops the
        // flow too.
        viewModelScope.launch {
            repo.observeAll().collect { rows ->
                _state.value = _state.value.copy(speakers = rows)
            }
        }
    }

    /**
     * Set the in-flight name typed in the screen's text field.
     * Plain setter: [SpeakersViewModel.startEnrollment] is the
     * method that validates blankness and surfaces the error.
     */
    fun setEnrollName(name: String) {
        _state.value = _state.value.copy(enrollName = name)
    }

    /**
     * Set whether the embedding model file is installed on disk.
     * Called from the screen on first composition (after
     * [SpeakersViewModel.refreshEmbeddingModelInstalled] has done
     * the on-disk check) and after a delete returns to a stable
     * state. Stored on the state directly because the value is
     * trivial to compute — a single `File.exists()` + size check.
     */
    fun setEmbeddingModelInstalled(installed: Boolean) {
        if (_state.value.embeddingModelInstalled == installed) return
        _state.value = _state.value.copy(embeddingModelInstalled = installed)
    }

    /**
     * Convenience bridge the screen uses when the missing-model
     * hint should be re-evaluated (the embedding model is a single
     * `.onnx` file in `ModelCatalog.EMBEDDING`, so the screen
     * forwards the result of [com.convoscribe.models.ModelStore.isInstalled]
     * through [SpeakersViewModel.setEmbeddingModelInstalled].
     */
    fun refreshEmbeddingModelInstalled(installed: Boolean) {
        setEmbeddingModelInstalled(installed)
    }

    /**
     * Validate the current [SpeakersUiState.enrollName], start
     * recording through [EnrollSpeakerUseCase.record] and, on
     * success, hand the captured samples to
     * [EnrollSpeakerUseCase.enroll] to compute and persist the
     * embedding. Errors from either step are caught and surfaced
     * through [SpeakersUiState.error]; a blank name short-circuits
     * the call before any recording starts (acceptance criterion:
     * "blank name sets error and does not start recording").
     *
     * The progress callback is invoked from the use case's
     * collector and forwarded verbatim — the screen formats the
     * "seconds remaining" string from
     * `(1f - progress) * SPEECH_DURATION_MS`.
     */
    fun startEnrollment() {
        val current = _state.value
        val name = current.enrollName
        if (name.isBlank()) {
            _state.value = current.copy(
                error = UiText.res(R.string.speakers_name_blank),
                phase = EnrollmentPhase.IDLE
            )
            return
        }
        if (current.phase != EnrollmentPhase.IDLE) {
            // A second start while one is in flight would race the
            // collector; ignore it. The UI hides the Start button
            // while recording, but keep the guard so a programmatic
            // caller cannot corrupt the state machine.
            return
        }

        enrollmentJob = viewModelScope.launch {
            try {
                _state.value = current.copy(
                    phase = EnrollmentPhase.RECORDING,
                    progress = 0f,
                    error = null
                )
                val samples = enroll.record(SPEECH_DURATION_MS) { p ->
                    // p is 0..1 — the use case's record() truncates
                    // so the final emission is exactly 1.0; the
                    // screen uses this to drive a LinearProgressIndicator
                    // and a "X s remaining" caption.
                    _state.value = _state.value.copy(progress = p)
                }
                _state.value = _state.value.copy(
                    phase = EnrollmentPhase.PROCESSING,
                    progress = 1f
                )
                enroll.enroll(name, samples)
                // Successful enrollment: clear the name and the
                // error so the form is ready for the next voice.
                _state.value = _state.value.copy(
                    phase = EnrollmentPhase.IDLE,
                    progress = 0f,
                    enrollName = "",
                    error = null
                )
            } catch (t: Throwable) {
                val message = t.message ?: t.javaClass.simpleName
                _state.value = _state.value.copy(
                    phase = EnrollmentPhase.IDLE,
                    progress = 0f,
                    error = UiText.Plain(message)
                )
            } finally {
                enrollmentJob = null
            }
        }
    }

    /**
     * Cancel the in-flight recording / embedding coroutine. The
     * partially-collected samples are dropped (the use case's
     * collector stops being collected when we cancel the job).
     * No-op when nothing is running; the screen disables the
     * Cancel button while the phase is `IDLE` so this is rarely
     * hit, but it is the right escape hatch when an exception
     * left the state machine in an unexpected corner.
     */
    fun cancel() {
        val job = enrollmentJob ?: return
        job.cancel()
        enrollmentJob = null
        _state.value = _state.value.copy(
            phase = EnrollmentPhase.IDLE,
            progress = 0f
        )
    }

    /**
     * Remove the speaker with [id] from the repository. The
     * Room delete cascades through the `observeAll` collector
     * in `init`, so the next snapshot already reflects it (the
     * UI does not have to remove the row locally).
     */
    fun delete(id: Long) {
        viewModelScope.launch {
            repo.delete(id)
        }
    }

    /**
     * Remove the enrolled person [name]: every voice sample stored
     * under that name.
     */
    fun deletePerson(name: String) {
        val ids = _state.value.speakers.filter { it.name == name }.map { it.id }
        viewModelScope.launch {
            ids.forEach { repo.delete(it) }
        }
    }

    private companion object {
        /**
         * How long the enrollment recording runs in milliseconds.
         * 15 seconds matches the screen's user-facing copy ("Record
         * about 15 seconds of one person speaking normally") and
         * the default the [EnrollSpeakerUseCase] ships with.
         */
        const val SPEECH_DURATION_MS: Long = 15_000L
    }
}
