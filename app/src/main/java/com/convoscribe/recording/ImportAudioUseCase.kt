package com.convoscribe.recording

import android.util.Log
import com.convoscribe.TAG
import com.convoscribe.asr.FileTranscriber
import com.convoscribe.asr.TranscriberFactory
import com.convoscribe.audio.AudioDecoder
import com.convoscribe.audio.AudioImportException
import com.convoscribe.audio.PcmConverter
import com.convoscribe.audio.WavWriter
import com.convoscribe.audio.samplesToMs
import com.convoscribe.audio.toPcm16Samples
import com.convoscribe.data.SegmentPiece
import com.convoscribe.data.SessionRepository
import com.convoscribe.diarization.DiarizeSessionUseCase
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** The steps of an import, in order; the UI words each one. */
enum class ImportStep { COPYING, DECODING, TRANSCRIBING, IDENTIFYING_SPEAKERS }

/** Progress of [ImportAudioUseCase], observed by the home screen and the service notification. */
sealed interface ImportState {
    data object Idle : ImportState

    /**
     * Importing [name] (the file's display name, `null` if unknown):
     * [step] is [progress] `0f..1f` done. [sessionId] is set once the
     * session exists, during [ImportStep.IDENTIFYING_SPEAKERS].
     */
    data class Running(
        val name: String?,
        val step: ImportStep,
        val progress: Float,
        val sessionId: Long? = null
    ) : ImportState {
        /** Cancel is offered until the session has been created. */
        val cancellable: Boolean get() = step != ImportStep.IDENTIFYING_SPEAKERS
    }

    /**
     * Importing [name] failed and nothing was kept. [reason] is set
     * for a file that could not be decoded; otherwise [message] says
     * what went wrong (e.g. a missing model).
     */
    data class Failed(val name: String?, val reason: AudioImportException.Reason?, val message: String?) : ImportState
}

/**
 * An audio file to import: its [displayName] (for the title), its
 * size if known (for progress), and how to [open] it. The content is
 * read once, at the start of [ImportAudioUseCase.run].
 */
class ImportSource(val displayName: String?, val sizeBytes: Long?, val open: () -> InputStream)

/**
 * Turn an audio file into a session (issue #3):
 *
 *  1. copy the file into [workDir] first, so a share grant may expire
 *     without harm;
 *  2. decode it with [decoder] and convert it to 16 kHz mono
 *     ([PcmConverter]) into a WAV in [workDir];
 *  3. transcribe the WAV with [FileTranscriber], in the language of
 *     [defaultOptions] (the Settings defaults);
 *  4. create the session, its transcript and its recording in one
 *     transaction ([SessionRepository.createImportedSession]);
 *  5. identify speakers with [diarize], as after a recording.
 *
 * No session exists before step 4, so cancelling, a failure or the
 * process dying earlier leaves nothing in the session list; the work
 * files are removed on the way out and by [cleanUp] at start-up.
 */
class ImportAudioUseCase(
    private val repository: SessionRepository,
    private val transcriberFactory: TranscriberFactory,
    private val diarize: DiarizeSessionUseCase,
    private val decoder: AudioDecoder,
    private val workDir: File,
    private val defaultOptions: () -> ConversationOptions,
    private val asrDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()

    private val _createdSessionId = MutableStateFlow<Long?>(null)

    /**
     * Session the last import created, until the UI calls
     * [consumeCreated]; the home screen opens it.
     */
    val createdSessionId: StateFlow<Long?> = _createdSessionId.asStateFlow()

    val isRunning: Boolean get() = _state.value is ImportState.Running

    /**
     * Import [source]. Returns the new session's id, or `null` when the
     * import failed ([state] then holds [ImportState.Failed]).
     * Rethrows cancellation after resetting [state]; a cancel during
     * speaker identification keeps the session.
     */
    suspend fun run(source: ImportSource): Long? {
        val name = source.displayName?.takeIf { it.isNotBlank() }
        _state.value = ImportState.Running(name, ImportStep.COPYING, 0f)
        val id = try {
            createSession(source, name)
        } catch (ce: CancellationException) {
            _state.value = ImportState.Idle
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "import failed for $name", t)
            val reason = (t as? AudioImportException)?.reason
            val message = if (reason == null) t.message ?: t.javaClass.simpleName else null
            _state.value = ImportState.Failed(name, reason, message)
            return null
        } finally {
            cleanUp()
        }
        _createdSessionId.value = id
        _state.value = ImportState.Running(name, ImportStep.IDENTIFYING_SPEAKERS, 0f, sessionId = id)
        try {
            diarize.run(id, onProgress = { p ->
                _state.value = ImportState.Running(name, ImportStep.IDENTIFYING_SPEAKERS, p, sessionId = id)
            })
        } finally {
            _state.value = ImportState.Idle
        }
        return id
    }

    private suspend fun createSession(source: ImportSource, name: String?): Long {
        workDir.mkdirs()
        val raw = File(workDir, SOURCE_FILE)
        val wav = File(workDir, DECODED_FILE)
        withContext(ioDispatcher) { copy(source, raw, name) }

        _state.value = ImportState.Running(name, ImportStep.DECODING, 0f)
        val samples = withContext(ioDispatcher) { decodeToWav(raw, wav, name) }
        raw.delete()
        if (samples == 0L) throw AudioImportException(AudioImportException.Reason.EMPTY)

        _state.value = ImportState.Running(name, ImportStep.TRANSCRIBING, 0f)
        val options = defaultOptions()
        val finals = withContext(asrDispatcher) {
            transcriberFactory.create(options.language).use { transcriber ->
                FileTranscriber.transcribe(wav, transcriber) { p ->
                    _state.value = ImportState.Running(name, ImportStep.TRANSCRIBING, p)
                }
            }
        }
        currentCoroutineContext().ensureActive()
        val engine = transcriberFactory.describe(options.language)
        val id = repository.createImportedSession(
            title = options.copy(title = titleFor(name)).sessionTitle(clock()),
            language = options.language,
            numSpeakers = options.numSpeakers,
            participants = options.participants,
            durationMs = samplesToMs(samples),
            engine = engine.engine,
            model = engine.model,
            segments = finals.map { SegmentPiece(it.startMs, it.endMs, it.text, it.tokens, speakerIndex = null) },
            audio = wav
        )
        Log.i(TAG, "imported session=$id lines=${finals.size} durationMs=${samplesToMs(samples)}")
        return id
    }

    /** Copy [source] to [into], reporting progress when its size is known. */
    private suspend fun copy(source: ImportSource, into: File, name: String?) {
        val input = try {
            source.open()
        } catch (e: IOException) {
            throw AudioImportException(AudioImportException.Reason.UNREADABLE, e)
        } catch (e: SecurityException) {
            throw AudioImportException(AudioImportException.Reason.UNREADABLE, e)
        }
        val size = source.sizeBytes?.takeIf { it > 0 }
        input.use { stream ->
            into.outputStream().use { out ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var copied = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = try {
                        stream.read(buffer)
                    } catch (e: IOException) {
                        throw AudioImportException(AudioImportException.Reason.UNREADABLE, e)
                    }
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    copied += n
                    if (size != null) {
                        val progress = (copied.toFloat() / size).coerceIn(0f, 1f)
                        _state.value = ImportState.Running(name, ImportStep.COPYING, progress)
                    }
                }
            }
        }
    }

    /** Decode [raw] to a 16 kHz mono WAV at [wav]; returns its sample count. */
    private suspend fun decodeToWav(raw: File, wav: File, name: String?): Long {
        val converter = PcmConverter()
        return WavWriter(wav).use { writer ->
            decoder.decode(
                raw,
                onBlock = { block -> writer.write(converter.convert(block).toPcm16Samples()) },
                onProgress = { p -> _state.value = ImportState.Running(name, ImportStep.DECODING, p) }
            )
            writer.write(converter.flush().toPcm16Samples())
            writer.samplesWritten
        }
    }

    /** The file's name without its extension, or `"Import <date>"` when there is none. */
    private fun titleFor(name: String?): String {
        val base = name?.substringBeforeLast('.')?.trim().orEmpty()
        if (base.isNotEmpty()) return base
        return "Import " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(clock()))
    }

    /** Clear [createdSessionId] once the UI has opened the session. */
    fun consumeCreated() {
        _createdSessionId.value = null
    }

    /** Clear an [ImportState.Failed] once the UI has shown it. */
    fun acknowledgeFailure() {
        if (_state.value is ImportState.Failed) _state.value = ImportState.Idle
    }

    /**
     * Delete the work files: after every import, and at start-up for
     * an import the process died in. Safe while idle only.
     */
    fun cleanUp() {
        workDir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val SOURCE_FILE = "source"
        const val DECODED_FILE = "decoded.wav"
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
