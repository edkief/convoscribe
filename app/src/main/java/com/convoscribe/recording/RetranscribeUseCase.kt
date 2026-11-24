package com.convoscribe.recording

import android.util.Log
import com.convoscribe.TAG
import com.convoscribe.asr.FileRecognizer
import com.convoscribe.asr.FileTranscriber
import com.convoscribe.asr.TimedToken
import com.convoscribe.asr.TranscriberFactory
import com.convoscribe.asr.TranscriptEvent
import com.convoscribe.asr.WordTimer
import com.convoscribe.audio.AudioCleanerFactory
import com.convoscribe.audio.AudioCleaning
import com.convoscribe.data.CleanedAudio
import com.convoscribe.data.SegmentEntity
import com.convoscribe.data.SegmentPiece
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SessionStatus
import com.convoscribe.data.TokenTimingCodec
import com.convoscribe.data.cleanAudioFile
import com.convoscribe.diarization.DiarizeSessionUseCase
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Progress of [RetranscribeUseCase], observed by the session screen and the service notification. */
sealed interface RetranscribeState {
    data object Idle : RetranscribeState

    /**
     * [sessionId]'s audio is being transcribed again; [progress] is
     * `0f..1f` over the whole run. [cleaning] is `true` while its
     * audio is being cleaned first (issue #37). [onServer] is `true`
     * when the transcription server does the work (issue #58),
     * [onWhisper] when on-device Whisper does (issue #158).
     */
    data class Running(
        val sessionId: Long,
        val progress: Float,
        val cleaning: Boolean = false,
        val onServer: Boolean = false,
        val onWhisper: Boolean = false
    ) : RetranscribeState {
        /**
         * The phone does the work: it transcribes, or cleans the audio
         * before the server transcribes it. Starting a recording stops
         * such a run (issue #123); one on the server carries on.
         */
        val onDevice: Boolean get() = cleaning || !onServer
    }

    /** The last run for [sessionId] failed; the previous transcript was kept. */
    data class Failed(val sessionId: Long, val message: String) : RetranscribeState
}

/** What transcribes a session's audio again. */
enum class RetranscribeTarget {
    /** The streaming engine chosen in Settings. */
    DEVICE,

    /** The transcription server (issue #58). */
    SERVER,

    /** Whisper Large-v3-Turbo on the phone (issue #158). */
    WHISPER
}

/**
 * Transcribe a session's stored audio again in another language
 * (issue #6), then re-identify its speakers, or leave them pending,
 * as the *Identify speakers* setting says (issue #11).
 *
 * The new transcript is built in memory and only swapped in, together
 * with the new language and engine, in one transaction
 * ([SessionRepository.replaceTranscript]) once the whole file is done.
 * Cancelling, a failure, or the process dying before that leaves the
 * previous transcript as it was; there is no intermediate status to
 * recover. Each new line inherits the speaker of the old line it
 * overlaps most, so names typed by the user carry over through the
 * following diarization.
 *
 * Summary and Q&A are left alone; the screen warns that they were
 * based on the old transcript.
 *
 * *Clean up audio* (issue #37) is the same run with `clean = true`:
 * the original recording is first cleaned by [cleanerFactory] into a
 * work file next to it, which is transcribed and then swapped in as
 * the session's cleaned recording together with the new transcript.
 * A session already cleaned is transcribed from its cleaned
 * recording. Speakers are still identified from the original audio,
 * which enrolled voices were recorded like.
 *
 * With `onServer` (issue #58) the same audio goes to the transcription
 * server ([server]) instead of [transcriberFactory], and the session
 * records the server's engine and model. With [RetranscribeTarget.WHISPER]
 * (issue #158) on-device Whisper ([whisper]) does it, with word times
 * borrowed from the previous transcript. [improveAfterStop] is the
 * automatic form run after Stop: the server when [serverAuto] says so,
 * then Whisper when [whisperAuto] does (or if the server failed). It
 * never replaces a transcript edited by hand, and a failure only keeps
 * the device transcript, without an error on screen.
 */
class RetranscribeUseCase(
    private val repository: SessionRepository,
    private val transcriberFactory: TranscriberFactory,
    private val diarize: DiarizeSessionUseCase,
    private val asrDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val cleanerFactory: AudioCleanerFactory? = null,
    private val server: FileRecognizer? = null,
    private val serverAuto: () -> Boolean = { false },
    private val whisper: WhisperPass? = null,
    private val whisperAuto: () -> Boolean = { false }
) {
    private val _state = MutableStateFlow<RetranscribeState>(RetranscribeState.Idle)
    val state: StateFlow<RetranscribeState> = _state.asStateFlow()

    val isRunning: Boolean get() = _state.value is RetranscribeState.Running

    /** A run is working on the phone ([RetranscribeState.Running.onDevice]). */
    val runsOnDevice: Boolean get() = (_state.value as? RetranscribeState.Running)?.onDevice == true

    /**
     * Re-transcribe [sessionId] in [language], cleaning its audio
     * first when [clean] is set, with [target]. Returns `true` when the
     * transcript was replaced (diarization then runs, or is left
     * pending, and reports through the session status as usual). Rethrows cancellation
     * after resetting [state].
     */
    suspend fun run(
        sessionId: Long,
        language: String,
        clean: Boolean = false,
        target: RetranscribeTarget = RetranscribeTarget.DEVICE
    ): Boolean {
        if (!transcribeAndReplace(sessionId, language, clean, target, showFailure = true)) return false
        diarize.runOrDefer(sessionId)
        return true
    }

    /** On-device Whisper is set up and downloaded (issue #158). */
    val whisperAvailable: Boolean get() = whisper?.isAvailable() == true

    /**
     * What [improveAfterStop] would try first for [sessionId]: the
     * server when *Improve recordings on the server* is on with a server
     * set up, else Whisper when *Transcribe again with Whisper* is on and
     * downloaded; `null` for neither, or when the transcript was edited
     * by hand (a continued session may have been).
     */
    suspend fun afterStopTarget(sessionId: Long): RetranscribeTarget? {
        val session = repository.getSession(sessionId) ?: return null
        if (session.transcriptEdited) return null
        return when {
            server != null && serverAuto() -> RetranscribeTarget.SERVER
            wantsWhisper() -> RetranscribeTarget.WHISPER
            else -> null
        }
    }

    private fun wantsWhisper(): Boolean = whisper != null && whisperAuto() && whisper.isAvailable()

    /**
     * After Stop (issues #58, #158): transcribe [sessionId] again, in
     * its own language, on the server, then with Whisper if the server
     * is off or failed and Whisper is on ([afterStopTarget]). Returns
     * `true` when the transcript was replaced. A failure is logged and
     * keeps the device transcript; [state] goes back to Idle, not Failed.
     * Speakers are left to the caller, who identifies them either way.
     * Rethrows cancellation after resetting [state].
     */
    suspend fun improveAfterStop(sessionId: Long): Boolean {
        val first = afterStopTarget(sessionId) ?: return false
        val language = repository.getSession(sessionId)?.language ?: return false
        if (transcribeAndReplace(sessionId, language, clean = false, first, showFailure = false)) return true
        if (first != RetranscribeTarget.SERVER || !wantsWhisper()) return false
        Log.i(TAG, "server transcript failed for session $sessionId; trying Whisper")
        return transcribeAndReplace(sessionId, language, clean = false, RetranscribeTarget.WHISPER, showFailure = false)
    }

    /**
     * Transcribe and swap in the new transcript; `false` on failure,
     * shown as [RetranscribeState.Failed] when [showFailure].
     */
    private suspend fun transcribeAndReplace(
        sessionId: Long,
        language: String,
        clean: Boolean,
        target: RetranscribeTarget,
        showFailure: Boolean
    ): Boolean {
        val onServer = target == RetranscribeTarget.SERVER
        _state.value = RetranscribeState.Running(
            sessionId,
            0f,
            cleaning = clean,
            onServer = onServer,
            onWhisper = target == RetranscribeTarget.WHISPER
        )
        var work: File? = null
        try {
            check(!onServer || server != null) { "The transcription server is not set up" }
            check(target != RetranscribeTarget.WHISPER || whisper != null) { "Whisper is not set up" }
            val session = repository.getSession(sessionId) ?: error("Session not found")
            check(session.status != SessionStatus.RECORDING && session.status != SessionStatus.DIARIZING) {
                "The session is busy"
            }
            val oldSegments = repository.getSegments(sessionId)
            val original = File(session.audioPath)
            var cleaned: CleanedAudio? = null
            // Cleaning takes the first half of the progress bar.
            val transcribeFrom = if (clean) 0.5f else 0f
            val source = if (clean) {
                val factory = cleanerFactory?.takeIf { it.isAvailable() }
                    ?: throw IllegalStateException("The noise cleanup model is not downloaded")
                val target = File(original.parentFile, "${original.nameWithoutExtension}.cleaning.wav")
                work = target
                withContext(asrDispatcher) {
                    factory.create().use { cleaner ->
                        AudioCleaning.cleanFile(original, target, cleaner) { progress ->
                            _state.value = RetranscribeState.Running(
                                sessionId,
                                progress * transcribeFrom,
                                cleaning = true
                            )
                        }
                    }
                }
                cleaned = CleanedAudio(target, factory.modelId())
                target
            } else {
                session.cleanAudioFile().takeIf { session.cleanedWith != null && it.exists() }
            }
            // The recognizer hears the cleaned recording blended with the
            // original (AudioCleaning.RECOGNIZER_CLEAN_SHARE), written to a
            // second work file that is always deleted afterwards.
            val mix = source?.let { File(original.parentFile, "${original.nameWithoutExtension}.mix.wav") }
            val finals = try {
                if (source != null && mix != null) {
                    withContext(asrDispatcher) { AudioCleaning.writeRecognizerMix(original, source, mix) }
                }
                // Whisper borrows word times from the old transcript when
                // it is in the same language (issue #158).
                val anchors = if (session.language == language) {
                    WordTimer.anchorsOf(oldSegments.map { TokenTimingCodec.decode(it.tokens) })
                } else {
                    emptyList()
                }
                transcribe(language, mix ?: original, sessionId, transcribeFrom, target, anchors)
            } finally {
                mix?.delete()
            }
            val pieces = finals.map { final ->
                SegmentPiece(
                    startMs = final.startMs,
                    endMs = final.endMs,
                    text = final.text,
                    tokens = final.tokens,
                    speakerIndex = dominantSpeaker(oldSegments, final.startMs, final.endMs)
                )
            }
            val engine = when (target) {
                RetranscribeTarget.SERVER -> server!!.describe()
                RetranscribeTarget.WHISPER -> whisper!!.describe()
                RetranscribeTarget.DEVICE -> transcriberFactory.describe(language)
            }
            repository.replaceTranscript(sessionId, language, pieces, engine.engine, engine.model, cleaned)
            work = null
            Log.i(
                TAG,
                "re-transcribed session=$sessionId lang=$language lines=${pieces.size} cleaned=$clean " +
                    "engine=${engine.engine}"
            )
            _state.value = RetranscribeState.Idle
        } catch (ce: CancellationException) {
            work?.delete()
            _state.value = RetranscribeState.Idle
            throw ce
        } catch (t: Throwable) {
            work?.delete()
            Log.w(TAG, "re-transcription failed for session $sessionId (target=$target)", t)
            _state.value = if (showFailure) {
                RetranscribeState.Failed(sessionId, t.message ?: t.javaClass.simpleName)
            } else {
                RetranscribeState.Idle
            }
            return false
        }
        return true
    }

    /**
     * Transcribe [file] with [target], reporting progress from [from]
     * to 1 of the whole run. [anchors] are the old transcript's word
     * tokens, which Whisper borrows its word times from.
     */
    private suspend fun transcribe(
        language: String,
        file: File,
        sessionId: Long,
        from: Float,
        target: RetranscribeTarget,
        anchors: List<TimedToken>
    ): List<TranscriptEvent.Final> {
        val report = { progress: Float ->
            _state.value = RetranscribeState.Running(
                sessionId,
                from + progress * (1f - from),
                onServer = target == RetranscribeTarget.SERVER,
                onWhisper = target == RetranscribeTarget.WHISPER
            )
        }
        // Cleaning (on the phone) is over: say so before the first
        // progress report, which a server may be slow to send (issue #123).
        report(0f)
        when (target) {
            RetranscribeTarget.SERVER -> return server!!.transcribe(file, language, report)
            RetranscribeTarget.WHISPER -> return whisper!!.transcribe(file, language, anchors, report)
            RetranscribeTarget.DEVICE -> Unit
        }
        return withContext(asrDispatcher) {
            transcriberFactory.create(language).use { transcriber ->
                FileTranscriber.transcribe(file, transcriber, onProgress = report)
            }
        }
    }

    /** Clear a [RetranscribeState.Failed] once the screen has shown it. */
    fun acknowledgeFailure() {
        if (_state.value is RetranscribeState.Failed) _state.value = RetranscribeState.Idle
    }

    internal companion object {
        /**
         * Speaker of the old line overlapping `[startMs, endMs)` the
         * most, or `null` when nothing labelled overlaps it.
         */
        fun dominantSpeaker(old: List<SegmentEntity>, startMs: Long, endMs: Long): Int? = old
            .filter { it.speakerIndex != null }
            .map { it.speakerIndex!! to minOf(it.endMs, endMs) - maxOf(it.startMs, startMs) }
            .filter { (_, overlap) -> overlap > 0 }
            .groupBy({ it.first }, { it.second })
            .maxByOrNull { (_, overlaps) -> overlaps.sum() }
            ?.key
    }
}
