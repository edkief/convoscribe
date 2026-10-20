package com.convoscribe.recording

import android.util.Log
import com.convoscribe.TAG
import com.convoscribe.asr.FileTranscriber
import com.convoscribe.asr.TranscriberFactory
import com.convoscribe.audio.AudioCleanerFactory
import com.convoscribe.audio.AudioCleaning
import com.convoscribe.data.CleanedAudio
import com.convoscribe.data.SegmentEntity
import com.convoscribe.data.SegmentPiece
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SessionStatus
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
     * audio is being cleaned first (issue #37).
     */
    data class Running(val sessionId: Long, val progress: Float, val cleaning: Boolean = false) : RetranscribeState

    /** The last run for [sessionId] failed; the previous transcript was kept. */
    data class Failed(val sessionId: Long, val message: String) : RetranscribeState
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
 */
class RetranscribeUseCase(
    private val repository: SessionRepository,
    private val transcriberFactory: TranscriberFactory,
    private val diarize: DiarizeSessionUseCase,
    private val asrDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val cleanerFactory: AudioCleanerFactory? = null
) {
    private val _state = MutableStateFlow<RetranscribeState>(RetranscribeState.Idle)
    val state: StateFlow<RetranscribeState> = _state.asStateFlow()

    val isRunning: Boolean get() = _state.value is RetranscribeState.Running

    /**
     * Re-transcribe [sessionId] in [language], cleaning its audio
     * first when [clean] is set. Returns `true` when the
     * transcript was replaced (diarization then runs, or is left
     * pending, and reports through the session status as usual). Rethrows cancellation
     * after resetting [state].
     */
    suspend fun run(sessionId: Long, language: String, clean: Boolean = false): Boolean {
        _state.value = RetranscribeState.Running(sessionId, 0f, cleaning = clean)
        var work: File? = null
        try {
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
                session.cleanAudioFile().takeIf { session.cleanedWith != null && it.exists() } ?: original
            }
            val finals = withContext(asrDispatcher) {
                transcriberFactory.create(language).use { transcriber ->
                    FileTranscriber.transcribe(source, transcriber) { progress ->
                        _state.value = RetranscribeState.Running(
                            sessionId,
                            transcribeFrom + progress * (1f - transcribeFrom)
                        )
                    }
                }
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
            val engine = transcriberFactory.describe(language)
            repository.replaceTranscript(sessionId, language, pieces, engine.engine, engine.model, cleaned)
            work = null
            Log.i(TAG, "re-transcribed session=$sessionId lang=$language lines=${pieces.size} cleaned=$clean")
            _state.value = RetranscribeState.Idle
        } catch (ce: CancellationException) {
            work?.delete()
            _state.value = RetranscribeState.Idle
            throw ce
        } catch (t: Throwable) {
            work?.delete()
            Log.w(TAG, "re-transcription failed for session $sessionId", t)
            _state.value = RetranscribeState.Failed(sessionId, t.message ?: t.javaClass.simpleName)
            return false
        }
        diarize.runOrDefer(sessionId)
        return true
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
