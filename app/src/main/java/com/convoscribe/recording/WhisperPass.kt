package com.convoscribe.recording

import android.util.Log
import com.convoscribe.TAG
import com.convoscribe.asr.EngineInfo
import com.convoscribe.asr.FileRecognizer
import com.convoscribe.asr.FileTranscriber
import com.convoscribe.asr.TimedToken
import com.convoscribe.asr.TranscriberFactory
import com.convoscribe.asr.TranscriptEvent
import com.convoscribe.asr.WordTimer
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device Whisper (issue #158) as the re-transcribe and import use
 * cases run it: [recognizer] does the words, and their times are
 * borrowed with [WordTimer] from a transcript that has them, so
 * tap-to-seek, highlighting and speaker-change splits keep working.
 *
 * The anchors are the caller's current transcript when it has word
 * times in the same language. Otherwise (an import, a text-only server
 * transcript, another language) [liveFactory], the streaming engine, runs
 * over the file first: Kroko takes about 3 % of the audio's length. If
 * that pass fails, words are spread evenly over each line.
 */
class WhisperPass(
    private val recognizer: FileRecognizer,
    private val available: () -> Boolean,
    private val liveFactory: TranscriberFactory,
    private val asrDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    /** Whisper and its VAD are downloaded. */
    fun isAvailable(): Boolean = available()

    fun describe(): EngineInfo = recognizer.describe()

    /**
     * Transcribe [file] in [language] with Whisper. [anchors] are the
     * word tokens of an existing transcript in [language], or empty.
     * [onProgress] covers the whole run, `0f..1f`.
     */
    suspend fun transcribe(
        file: File,
        language: String,
        anchors: List<TimedToken>,
        onProgress: (Float) -> Unit = {}
    ): List<TranscriptEvent.Final> {
        check(available()) { "Whisper is not downloaded" }
        val livePass = anchors.isEmpty()
        val from = if (livePass) LIVE_SHARE else 0f
        val timed = if (livePass) liveAnchors(file, language) { onProgress(it * LIVE_SHARE) } else anchors
        val finals = recognizer.transcribe(file, language) { onProgress(from + it * (1f - from)) }
        return WordTimer.retime(finals, timed)
    }

    private suspend fun liveAnchors(file: File, language: String, onProgress: (Float) -> Unit): List<TimedToken> {
        return try {
            val finals = withContext(asrDispatcher) {
                liveFactory.create(language).use { transcriber ->
                    FileTranscriber.transcribe(file, transcriber, onProgress = onProgress)
                }
            }
            WordTimer.anchorsOf(finals.map { it.tokens })
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "live pass for Whisper word times failed; spreading words evenly", t)
            emptyList()
        }
    }

    private companion object {
        /** Share of the progress bar the live pass takes; Whisper is far slower. */
        const val LIVE_SHARE = 0.1f
    }
}
