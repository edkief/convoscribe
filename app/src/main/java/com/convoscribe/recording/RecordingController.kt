package com.convoscribe.recording

import android.util.Log
import com.convoscribe.TAG
import com.convoscribe.asr.StreamingTranscriber
import com.convoscribe.asr.TranscriberFactory
import com.convoscribe.asr.TranscriptEvent
import com.convoscribe.audio.AudioSource
import com.convoscribe.audio.WavWriter
import com.convoscribe.audio.samplesToMs
import com.convoscribe.audio.toFloatSamples
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives one recording session end-to-end:
 *
 *  1. instantiates a streaming ASR [StreamingTranscriber] from the
 *     injected [TranscriberFactory] on [asrDispatcher];
 *  2. inserts a `RECORDING` session row through [RecordingStore];
 *  3. opens a [WavWriter] for the audio;
 *  4. collects from [AudioSource.chunks], feeding samples to the
 *     transcriber and the WAV file in lockstep;
 *  5. persists every final segment as it arrives;
 *  6. exposes the live [RecordingState] for the UI through
 *     [state].
 *
 * The class is intentionally not lifecycle-aware: it is owned by
 * the foreground service (TASK-13) and the recording screen
 * (TASK-12), which call [start] / [stop] at their convenience.
 * Concurrent starts are a programming error and the controller
 * silently ignores them.
 *
 * @param audioSource source of PCM16 mono 16 kHz chunks.
 * @param transcriberFactory builds a fresh [StreamingTranscriber]
 *   for the chosen language each session.
 * @param store persistence façade used to create the session, look
 *   up the WAV path, append segments and stamp the duration.
 * @param defaultOptions the options a new session starts with when
 *   [start] is not given any (typically the Settings defaults, read
 *   at start time).
 * @param asrDispatcher single-threaded dispatcher the ASR engine
 *   runs on; one in-flight [accept] / [finish] at a time so the
 *   recognizer's native state is never touched concurrently. In
 *   production this is
 *   `Dispatchers.Default.limitedParallelism(1)`.
 * @param clock wall-clock source for the session title; injectable
 *   so tests can pin the date.
 */
class RecordingController(
    private val audioSource: AudioSource,
    private val transcriberFactory: TranscriberFactory,
    private val store: RecordingStore,
    private val defaultOptions: () -> ConversationOptions,
    private val asrDispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val _state = MutableStateFlow(RecordingState())

    /** Latest immutable snapshot of the recording lifecycle. */
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    /**
     * Job that owns the audio collection loop while a session is
     * active. `null` outside an active session. Held so [stop] can
     * cancel the collector and join it before touching the
     * transcriber and writer from outside.
     */
    private var sessionJob: Job? = null

    /**
     * Start a new session, or continue [resumeSessionId]. No-op unless [RecordingState.phase] is
     * currently [RecordingPhase.IDLE]: the service may call this
     * twice by mistake (e.g. on a configuration change while a
     * session is already live) and we silently drop the second
     * call rather than wreck the in-flight recording.
     *
     * A new session is created with [options] (language, speaker
     * count, expected participants, title), or [defaultOptions] when
     * `null`. The engine and model that transcribe it are recorded on
     * the session once the transcriber is open.
     *
     * The work runs in [scope]; the caller owns that scope so a
     * cancelled service also cancels the recording cleanly. Errors
     * are surfaced through [RecordingState.error] (see the class
     * note), never thrown out of [start].
     *
     * With [resumeSessionId] the audio is appended to that session's
     * WAV and transcript instead of a new session: the session's own
     * language is used, new segments are offset by the audio already
     * recorded, and [RecordingState.elapsedMs] continues from it.
     * The session is only flipped back to `RECORDING` once the
     * transcriber and the WAV are open, so a failed continue leaves
     * it untouched. [options] are ignored when continuing.
     */
    fun start(scope: CoroutineScope, resumeSessionId: Long? = null, options: ConversationOptions? = null) {
        if (_state.value.phase != RecordingPhase.IDLE) return
        // Reset everything we know from a prior session: a fresh
        // start means a fresh error message, a fresh partial/final
        // list and no lingering "completed" pointer. The
        // `preparingNpu` flag is sampled from the factory *now*
        // (before the launched coroutine runs) so the UI gets the
        // right label even if the NPU first-run compile is slow
        // enough to be observed.
        val newOptions = options ?: defaultOptions()
        val newLang = newOptions.language
        // A continued session's language is only known once its row
        // is read, so its NPU flag is sampled inside the coroutine.
        val preparingNpu = resumeSessionId == null && transcriberFactory.needsPreparation(newLang)
        _state.update {
            RecordingState(
                phase = RecordingPhase.LOADING,
                preparingNpu = preparingNpu,
                lastCompletedSessionId = null,
                error = null
            )
        }
        sessionJob = scope.launch {
            // The whole session lives in a try/catch so an
            // unexpected throwable is funnelled through the same
            // cleanup path: close native handles, finish the DB
            // row with the partial caption so it is not lost, and
            // surface the message to the UI.
            var transcriber: StreamingTranscriber? = null
            var wav: WavWriter? = null
            var sessionId: Long? = null
            var lang = newLang
            try {
                if (resumeSessionId != null) {
                    lang = store.sessionLanguage(resumeSessionId)
                    if (transcriberFactory.needsPreparation(lang)) {
                        _state.update { it.copy(preparingNpu = true) }
                    }
                }
                // Building a streaming transcriber may load a model
                // from disk and allocate native memory; offload to
                // the ASR dispatcher so the caller's thread is free.
                transcriber = withContext(asrDispatcher) {
                    transcriberFactory.create(lang)
                }
                val id: Long
                if (resumeSessionId != null) {
                    id = resumeSessionId
                    wav = WavWriter(File(store.audioPath(id)), append = true)
                    store.reopenSession(id)
                } else {
                    id = store.createSession(
                        title = newOptions.sessionTitle(clock()),
                        language = lang,
                        numSpeakers = newOptions.numSpeakers,
                        participants = newOptions.participants
                    )
                    wav = WavWriter(File(store.audioPath(id)))
                }
                sessionId = id
                val engine = transcriberFactory.describe(lang)
                store.recordEngine(id, engine.engine, engine.model, continued = resumeSessionId != null)
                // The recognizer's timestamps start at 0 for this
                // run; shift them past the audio already in the file.
                segmentOffsetMs = samplesToMs(wav.samplesWritten)
                // Stash the handles so [stop] (which runs on the
                // caller's scope, not this launched one) can call
                // [StreamingTranscriber.finish] and close the WAV
                // writer after cancelling us. The launched coroutine
                // never touches them again after [collect] starts.
                activeTranscriber = transcriber
                activeWav = wav
                _state.update {
                    it.copy(
                        phase = RecordingPhase.RECORDING,
                        preparingNpu = false,
                        sessionId = id,
                        elapsedMs = samplesToMs(wav.samplesWritten),
                        partialText = "",
                        finalLines = emptyList(),
                        error = null
                    )
                }
                Log.i(TAG, "recording started session=$id lang=$lang resumed=${resumeSessionId != null}")
                // Drain the audio source until the collector is
                // cancelled (stop() / scope cancellation). Each
                // emission is persisted twice: once to the WAV on
                // the current scope's dispatcher (cheap), once to
                // the recognizer on the ASR dispatcher (expensive).
                audioSource.chunks().collect { chunk ->
                    wav.write(chunk)
                    val events = withContext(asrDispatcher) {
                        transcriber.accept(chunk.toFloatSamples())
                    }
                    applyEvents(events)
                    _state.update { it.copy(elapsedMs = samplesToMs(wav.samplesWritten)) }
                }
            } catch (ce: CancellationException) {
                // Cooperative cancellation: stop() / scope cancel
                // path. Do not call finishRecording here — stop()
                // owns that and has already replaced the state with
                // IDLE/STOPPING. Re-throw so structured concurrency
                // still sees the cancel.
                throw ce
            } catch (t: Throwable) {
                // Best-effort cleanup. Any throwable here is
                // swallowed because we are already on the error
                // path and the original exception is more useful.
                runCatching { transcriber?.close() }
                runCatching { wav?.close() }
                activeTranscriber = null
                activeWav = null
                val writtenMs = wav?.let { samplesToMs(it.samplesWritten) } ?: 0L
                val id = sessionId
                if (id != null) {
                    runCatching { store.finishRecording(id, writtenMs) }
                }
                _state.update {
                    RecordingState(
                        phase = RecordingPhase.IDLE,
                        lastCompletedSessionId = null,
                        error = t.message ?: "Recording failed"
                    )
                }
            }
        }
    }

    /**
     * Stop the active session, if any. Returns the id of the
     * completed session or `null` if there was nothing to stop
     * (already idle, or still loading).
     *
     * The cleanup sequence is:
     *  1. flip state to [RecordingPhase.STOPPING];
     *  2. cancel and join the audio collection job so the launched
     *     coroutine leaves its local handles alone (the launched
     *     coroutine's catch block will not fire because
     *     [CancellationException] is re-thrown before its catch
     *     arm);
     *  3. call [StreamingTranscriber.finish] on the ASR dispatcher
     *     to flush any pending partial into a [TranscriptEvent.Final];
     *  4. close the transcriber and the WAV writer;
     *  5. stamp the duration in the database through
     *     [RecordingStore.finishRecording];
     *  6. publish the new [RecordingState] with
     *     `lastCompletedSessionId = id` so the UI can navigate to
     *     the session-detail screen.
     *
     * To keep [stop] suspend-clean (without resorting to shared
     * mutable references to native handles) the transcriber and
     * WAV writer are stashed into [controller] fields the launched
     * coroutine populates as it sets them up.
     */
    suspend fun stop(): Long? {
        if (_state.value.phase != RecordingPhase.RECORDING) return null
        val id = _state.value.sessionId ?: return null
        _state.update { it.copy(phase = RecordingPhase.STOPPING) }
        sessionJob?.cancelAndJoin()
        val transcriber = activeTranscriber
        val wav = activeWav
        activeTranscriber = null
        activeWav = null
        try {
            val flushEvents = withContext(asrDispatcher) {
                transcriber?.finish().orEmpty()
            }
            applyEvents(flushEvents)
        } finally {
            runCatching { transcriber?.close() }
            runCatching { wav?.close() }
        }
        val durationMs = wav?.let { samplesToMs(it.samplesWritten) } ?: 0L
        store.finishRecording(id, durationMs)
        _state.value = RecordingState(
            phase = RecordingPhase.IDLE,
            lastCompletedSessionId = id
        )
        sessionJob = null
        Log.i(TAG, "recording stopped session=$id durationMs=$durationMs")
        return id
    }

    /**
     * Clear [RecordingState.lastCompletedSessionId]. The UI calls
     * this after navigating to the session-detail screen so the
     * same id is not re-handled on the next recomposition. Safe to
     * call when nothing is pending.
     */
    fun consumeCompleted() {
        _state.update { it.copy(lastCompletedSessionId = null) }
    }

    /** Transcriber the launched session body is using; `null` outside a session. */
    private var activeTranscriber: StreamingTranscriber? = null

    /** WAV writer the launched session body is using; `null` outside a session. */
    private var activeWav: WavWriter? = null

    /**
     * Added to every segment's start/end: the audio that was already
     * in the session's WAV when this run started (0 for a new session).
     */
    private var segmentOffsetMs: Long = 0L

    /**
     * Apply a list of [TranscriptEvent]s to the published state and
     * persist any closed utterances through [RecordingStore].
     * Partial events overwrite the placeholder; final events are
     * written to Room *before* the UI appends them so a crash
     * mid-emission does not lose data.
     */
    private suspend fun applyEvents(events: List<TranscriptEvent>) {
        if (events.isEmpty()) return
        // Persist the finals first (each one is a suspend call that
        // must not run inside the [_state.update] lambda, which is
        // synchronous), then update the snapshot atomically. The
        // ordering means the on-disk segment list and the in-memory
        // `finalLines` are always in lockstep.
        val finals = events.filterIsInstance<TranscriptEvent.Final>()
        val sessionId = _state.value.sessionId
        if (sessionId != null) {
            for (final in finals) {
                if (final.text.isNotBlank()) {
                    store.addSegment(
                        sessionId = sessionId,
                        startMs = final.startMs + segmentOffsetMs,
                        endMs = final.endMs + segmentOffsetMs,
                        text = final.text,
                        tokens = final.tokens.map { it.copy(ms = it.ms + segmentOffsetMs) }
                    )
                }
            }
        }
        _state.update { current ->
            var partial = current.partialText
            val lines = current.finalLines.toMutableList()
            for (event in events) {
                when (event) {
                    is TranscriptEvent.Partial -> partial = event.text
                    is TranscriptEvent.Final -> {
                        if (event.text.isNotBlank()) {
                            lines += event.text
                        }
                        partial = ""
                    }
                }
            }
            current.copy(partialText = partial, finalLines = lines)
        }
    }
}
