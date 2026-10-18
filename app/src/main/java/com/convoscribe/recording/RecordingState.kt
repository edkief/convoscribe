package com.convoscribe.recording

/**
 * High-level phase of a recording session, observable through
 * [RecordingState.phase]. Transitions follow the path
 *
 *  IDLE → LOADING → RECORDING ⇄ PAUSED → STOPPING → IDLE
 *
 * (`STOPPING` is reachable from both `RECORDING` and `PAUSED`.)
 * `IDLE` is the only state from which [RecordingController.start]
 * accepts a new session; `LOADING` covers the brief window in which
 * the ASR model is being instantiated and the session row is being
 * created in Room. `RECORDING` is the steady state of an active
 * session. `PAUSED` (issue #7) keeps the session open with the
 * microphone released: nothing is captured, transcribed or written
 * until [RecordingController.resume], and the session is not
 * diarized. `STOPPING` is set the moment [RecordingController.stop]
 * is called and lasts until the WAV writer is closed and
 * `finishRecording` has stamped the duration.
 *
 * Any error during start or collection jumps back to `IDLE` with
 * [RecordingState.error] populated.
 */
enum class RecordingPhase {
    IDLE,
    LOADING,
    RECORDING,
    PAUSED,
    STOPPING;

    /** A session is open: recording or paused. */
    val active: Boolean get() = this == RECORDING || this == PAUSED
}

/**
 * Immutable snapshot of everything the UI needs to render the
 * recording screen. Owned by [RecordingController] as a `StateFlow`
 * — every state change is a fresh value, so the UI can rely on
 * structural equality (`!==`) to decide whether to redraw.
 *
 * @property phase current lifecycle stage (see [RecordingPhase]).
 * @property preparingNpu `true` when the LOADING window is being
 *   spent pre-compiling the NPU context binaries (first NPU run
 *   on a device, or after the on-disk `.bin` files were wiped).
 *   The recording screen uses this flag to swap the LOADING label
 *   for a "this can take a few minutes" message; it is `false` for
 *   the steady state and for the CPU engine.
 * @property sessionId id of the row in Room for the *active*
 *   session, or `null` while idle. Cleared once the session has
 *   finished and the UI has acknowledged it via
 *   [RecordingController.consumeCompleted].
 * @property elapsedMs milliseconds of audio actually captured
 *   (derived from the WAV writer's `samplesWritten`, not wall-clock
 *   time, so a paused microphone doesn't inflate the counter).
 * @property inputLevel loudness of the latest audio chunk in `0f..1f`
 *   (see [com.convoscribe.audio.inputLevel]) for the Record screen's
 *   level meter; 0 outside RECORDING (so also while PAUSED).
 * @property partialText best-so-far hypothesis the ASR is holding
 *   for the current utterance. Replaced on every
 *   [com.convoscribe.asr.TranscriptEvent.Partial].
 * @property finalLines list of closed utterances that have already
 *   been persisted, oldest first. Grows by one on every
 *   [com.convoscribe.asr.TranscriptEvent.Final].
 * @property lastCompletedSessionId id of the session that just
 *   finished, until the UI calls [RecordingController.consumeCompleted].
 *   The UI navigates to the session-detail screen on this and then
 *   clears it so the same id isn't re-handled on a recomposition.
 * @property error human-readable message of the last error that
 *   terminated a session, or `null` while no error is pending.
 *   Cleared on the next successful start.
 */
data class RecordingState(
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val preparingNpu: Boolean = false,
    val sessionId: Long? = null,
    val elapsedMs: Long = 0L,
    val inputLevel: Float = 0f,
    val partialText: String = "",
    val finalLines: List<String> = emptyList(),
    val lastCompletedSessionId: Long? = null,
    val error: String? = null
)
