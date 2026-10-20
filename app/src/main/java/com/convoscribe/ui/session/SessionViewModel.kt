package com.convoscribe.ui.session

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoscribe.R
import com.convoscribe.audio.AudioExporter
import com.convoscribe.audio.AudioPlayer
import com.convoscribe.audio.PlayerState
import com.convoscribe.data.ParticipantsCodec
import com.convoscribe.data.SessionEntity
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SessionStatus
import com.convoscribe.data.SpeakerEntity
import com.convoscribe.data.TranscriptFormatter
import com.convoscribe.data.TranscriptLine
import com.convoscribe.diarization.EnrollFromSessionUseCase
import com.convoscribe.ui.UiText
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Snapshot rendered by [SessionScreen]. Built by combining the
 * session row, the segment list and the speaker list; any of the
 * three sources changing triggers a new emission so renaming a
 * speaker in the DB re-renders the transcript labels without the
 * screen having to subscribe to each flow individually.
 *
 * @property title session title shown in the top bar.
 * @property status current [SessionStatus]; drives the
 *   "Identifying speakers…" / "Speaker detection failed" banner.
 * @property lines transcript lines already resolved against the
 *   current speaker labels, ordered by start time.
 * @property speakers per-session speaker list (1..5 entries
 *   usually); kept around so the screen can tint labels by index.
 * @property notFound `true` when the session id has no row. The
 *   screen renders a "Session not found" placeholder instead of
 *   the transcript tab.
 */
data class SessionUiState(
    val title: String = "",
    val status: SessionStatus? = null,
    val lines: List<TranscriptLine> = emptyList(),
    val speakers: List<SpeakerEntity> = emptyList(),
    val notFound: Boolean = false,
    /** Speaker count chosen for this session; `null` follows Settings. */
    val numSpeakers: Int? = null,
    /** Path of the session's WAV recording; blank until the session row has one. */
    val audioPath: String = "",
    /** ASR language the transcript is in. */
    val language: String = "",
    /** Expected enrolled participants; `null` anyone. */
    val participants: List<String>? = null,
    /** Engine / model that transcribed the session; `null` when not recorded. */
    val asrEngine: String? = null,
    val asrModel: String? = null,
    /** When the session was recorded, and its length (0 while recording). */
    val createdAt: Long = 0L,
    val durationMs: Long = 0L
)

/** State of an audio export (share or save); the screen shows a progress dialog while encoding. */
sealed interface ExportState {
    data object Idle : ExportState

    /** The recording is being encoded to M4A; [progress] is `0f..1f`. */
    data class Encoding(val progress: Float) : ExportState
}

/**
 * ViewModel for the session-detail screen. Exposes a single
 * [SessionUiState] `StateFlow` built by `combine`-ing
 * [SessionRepository.observeSession], [SessionRepository.observeSegments]
 * and [SessionRepository.observeSpeakers]. The three flows are
 * pulled together inside `flatMapLatest` so we only subscribe to
 * the segments/speakers streams for an id that actually has a
 * session row (unknown ids never start a child subscription).
 *
 * The StateFlow uses `WhileSubscribed(5000)` to match the rest of
 * the app — the upstream Room observers pause five seconds after
 * the screen leaves the back stack, but keep flowing while a
 * configuration change recomposes the composable.
 *
 * [transcriptText] is a non-suspending read of the latest cached
 * state; the share button calls it on the main thread because
 * building the share intent has to happen there.
 *
 * [snackbar] is a one-shot `Flow<UiText>` that the screen
 * collects in a `LaunchedEffect` to surface transient feedback
 * (the "remember this voice" enrollment either succeeds silently
 * or surfaces its error). Backed by a buffered [Channel] so a
 * message emitted while the screen is being recomposed is not
 * lost and never re-fires on rotation.
 *
 * Playback: when [playerFactory] is given (the screen passes the
 * real one; tests pass a fake, and `null` means no player) the
 * ViewModel owns an [AudioPlayer] for the session's recording. It
 * opens the file once the session is no longer `RECORDING`,
 * exposes [playerState], [activeLineIndex] and [following], and
 * releases the player in [onCleared]. With an [audioExporter] it
 * also encodes the recording to M4A for [shareAudio] / [saveAudio],
 * reporting [exportState] and cancellable with [cancelExport].
 *
 * [cueAtMs] (a search hit, issue #24) positions the player there as
 * soon as the recording is open, without playing; the seek can only
 * be applied once the player is ready, so it waits until then.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModel(
    private val sessionId: Long,
    private val repository: SessionRepository,
    private val enrollFromSession: EnrollFromSessionUseCase? = null,
    playerFactory: ((CoroutineScope) -> AudioPlayer)? = null,
    private val audioExporter: AudioExporter? = null,
    cueAtMs: Long? = null
) : ViewModel() {

    val uiState: StateFlow<SessionUiState> = repository.observeSession(sessionId)
        .flatMapLatest { session ->
            if (session == null) {
                flowOf(SessionUiState(notFound = true))
            } else {
                combine(
                    repository.observeSegments(sessionId),
                    repository.observeSpeakers(sessionId)
                ) { segments, speakers ->
                    SessionUiState(
                        title = session.title,
                        status = session.status,
                        lines = TranscriptFormatter.lines(segments, speakers),
                        speakers = speakers,
                        numSpeakers = session.numSpeakers,
                        audioPath = session.audioPath,
                        language = session.language,
                        participants = ParticipantsCodec.decode(session.participants),
                        asrEngine = session.asrEngine,
                        asrModel = session.asrModel,
                        createdAt = session.createdAt,
                        durationMs = session.durationMs
                    )
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SessionUiState()
        )

    private val player: AudioPlayer? = playerFactory?.invoke(viewModelScope)

    /** Player state for the player bar; stays at the idle default when there is no player. */
    val playerState: StateFlow<PlayerState> = player?.state ?: MutableStateFlow(PlayerState()).asStateFlow()

    private val _following = MutableStateFlow(true)

    /**
     * Whether the transcript scrolls to keep the current line in view.
     * A manual scroll turns it off; a seek or [resumeFollow] turns it on.
     */
    val following: StateFlow<Boolean> = _following.asStateFlow()

    /**
     * Index in [SessionUiState.lines] of the line being played, or
     * `null` while nothing has been played yet (or there is no player).
     */
    val activeLineIndex: StateFlow<Int?> = combine(
        uiState.map { it.lines }.distinctUntilChanged(),
        playerState
    ) { lines, p ->
        if (!p.ready || (!p.isPlaying && p.positionMs == 0L)) {
            null
        } else {
            PlaybackSync.activeLineIndex(lines, p.positionMs)
        }
    }.distinctUntilChanged().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    /** Where to position the player once it is ready; see [cueAtMs]. */
    private var pendingCue: Long? = cueAtMs

    init {
        val audio = player
        if (audio != null) {
            viewModelScope.launch {
                audio.state.map { it.ready }.distinctUntilChanged().collect { ready ->
                    val cue = pendingCue
                    if (ready && cue != null) {
                        pendingCue = null
                        audio.seekTo(cue)
                        _following.value = true
                    }
                }
            }
            viewModelScope.launch {
                uiState
                    .map { state ->
                        state.audioPath.takeIf { state.status != null && state.status != SessionStatus.RECORDING }
                    }
                    .distinctUntilChanged()
                    .collect { path -> if (path.isNullOrBlank()) audio.stop() else audio.prepare(File(path)) }
            }
        }
    }

    /**
     * Buffered channel of snackbar messages. Capacity `Channel.BUFFERED`
     * keeps the coroutine from suspending on emit (the screen may
     * be in the middle of a configuration change), and
     * [receiveAsFlow] guarantees each value is consumed exactly
     * once.
     */
    private val _snackbar = Channel<UiText>(Channel.BUFFERED)

    /** One-shot snackbar messages; the screen collects this in a [androidx.compose.runtime.LaunchedEffect]. */
    val snackbar: Flow<UiText> = _snackbar.receiveAsFlow()

    /**
     * Plain-text export of the current transcript. Reads the
     * cached [StateFlow.value] directly: the share button fires on
     * the main thread and we do not want to launch a coroutine
     * just to format a string. The state is always at least the
     * `SessionUiState()` initial (with empty `lines`), so
     * `toText` produces an empty string — which is what the
     * chooser would receive anyway on a not-found session.
     */
    fun transcriptText(): String = TranscriptFormatter.toText(uiState.value.lines)

    /**
     * Persist a new label for the speaker identified by
     * [index]. Delegates to [SessionRepository.renameSpeaker] on
     * [viewModelScope] so the screen can fire-and-forget from a
     * dialog callback. The repository trims the label and
     * ignores blank values, so the UI does not need to.
     * The speakers flow Room observes will re-emit, and the
     * `combine` block above will rebuild the transcript lines
     * with the new label automatically.
     */
    fun renameSpeaker(index: Int, label: String) {
        viewModelScope.launch {
            repository.renameSpeaker(sessionId = sessionId, speakerIndex = index, label = label)
        }
    }

    /**
     * Persist a new title for this session. Delegates to
     * [SessionRepository.renameSession] on [viewModelScope]. The
     * repository trims, ignores blanks and caps at
     * [SessionRepository.SESSION_TITLE_MAX_LEN]; the session
     * row's flow Room observes will re-emit so the top bar
     * title updates without any extra plumbing.
     */
    fun renameSession(title: String) {
        viewModelScope.launch {
            repository.renameSession(sessionId = sessionId, title = title)
        }
    }

    /**
     * Change who is expected in this session (`null` anyone). Takes
     * effect on the next diarization; the screen offers to run one.
     */
    fun setParticipants(participants: List<String>?) {
        viewModelScope.launch {
            repository.setParticipants(sessionId, participants)
        }
    }

    /**
     * Rename a speaker and, in the same call, enroll the
     * speaker's audio as a known voice so future recordings
     * label it automatically (TASK-34).
     *
     * The rename runs unconditionally — the screen's checkbox
     * gates whether this method is called in the first place —
     * and the use case handles the embedding + persistence. On
     * success, a positive snackbar message is emitted and the
     * transcript re-renders with the new label through the
     * speakers flow. On failure (typically the "not enough
     * speech from this speaker (need 3 s)" floor), the
     * exception's message is surfaced verbatim through the same
     * snackbar channel so the user can react to it without the
     * rename being undone (the rename still landed; the
     * enrollment was the failing step).
     *
     * No-op when [enrollFromSession] was not wired in (i.e. a
     * test that does not care about the snackbar / use case).
     */
    fun renameAndRemember(index: Int, name: String) {
        val useCase = enrollFromSession ?: return
        viewModelScope.launch {
            try {
                useCase.run(sessionId = sessionId, speakerIndex = index, name = name)
                _snackbar.trySend(UiText.res(R.string.session_voice_remembered, name.trim()))
            } catch (t: Throwable) {
                _snackbar.trySend(UiText.Plain(t.message ?: t.javaClass.simpleName))
            }
        }
    }

    /** Play button: pause when playing, otherwise play from the current position. */
    fun togglePlayback() {
        val audio = player ?: return
        if (audio.state.value.isPlaying) audio.pause() else audio.play()
    }

    /** Stop playback (leaving the screen, re-identifying speakers); the position is kept. */
    fun stopPlayback() {
        player?.stop()
    }

    /** Seek bar: jump to [positionMs] and follow the transcript again. */
    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
        _following.value = true
    }

    /** Skip buttons: move by [deltaMs] and follow the transcript again. */
    fun skip(deltaMs: Long) {
        player?.skip(deltaMs)
        _following.value = true
    }

    /** Speed button: 1x → 1.5x → 2x → 1x. */
    fun cycleSpeed() {
        val audio = player ?: return
        audio.setSpeed(PlaybackSync.nextSpeed(audio.state.value.speed))
    }

    /** A transcript line was tapped: play from where it starts. */
    fun playLine(line: TranscriptLine) {
        val audio = player ?: return
        audio.seekTo(PlaybackSync.seekTargetMs(line))
        audio.play()
        _following.value = true
    }

    /** The user scrolled the transcript by hand: stop following until they ask. */
    fun onUserScrolled() {
        _following.value = false
    }

    /** The "Follow" button: scroll back to the playing line and keep following. */
    fun resumeFollow() {
        _following.value = true
    }

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)

    /** Progress of the running audio export, or [ExportState.Idle]. */
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    private val shareAudioChannel = Channel<File>(Channel.BUFFERED)

    /** One-shot: an M4A is ready to hand to the system share sheet. */
    val shareAudioFiles: Flow<File> = shareAudioChannel.receiveAsFlow()

    private var exportJob: Job? = null

    /** Encode the recording to M4A and emit it on [shareAudioFiles]. */
    fun shareAudio() = export { exporter, session, wav ->
        shareAudioChannel.trySend(exporter.export(sessionId, session.title, wav, ::reportProgress))
    }

    /** Encode the recording to M4A and write it to the document the user picked at [target]. */
    fun saveAudio(target: Uri) = export { exporter, session, wav ->
        exporter.saveTo(target, sessionId, session.title, wav, ::reportProgress)
        _snackbar.trySend(UiText.res(R.string.session_audio_saved))
    }

    /** Abort the running export; nothing is shared or saved. */
    fun cancelExport() {
        exportJob?.cancel()
    }

    private fun reportProgress(progress: Float) {
        _exportState.value = ExportState.Encoding(progress)
    }

    private fun export(block: suspend (AudioExporter, SessionEntity, File) -> Unit) {
        val exporter = audioExporter ?: return
        if (exportJob?.isActive == true) return
        exportJob = viewModelScope.launch {
            _exportState.value = ExportState.Encoding(0f)
            try {
                val session = repository.getSession(sessionId)
                if (session == null || session.audioPath.isBlank()) return@launch
                block(exporter, session, File(session.audioPath))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _snackbar.trySend(UiText.res(R.string.session_audio_export_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                _exportState.value = ExportState.Idle
            }
        }
    }

    override fun onCleared() {
        player?.release()
    }
}
