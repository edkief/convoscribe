package com.convoscribe.engine.qnn

import com.convoscribe.asr.EngineInfo
import com.convoscribe.asr.FileRecognizer
import com.convoscribe.asr.SpeechChunker
import com.convoscribe.asr.TextNormalizer
import com.convoscribe.asr.TranscriptEvent
import com.convoscribe.asr.WhisperTokens
import com.convoscribe.engine.sherpa.QnnRuntime
import com.convoscribe.engine.sherpa.SherpaVoiceDetector
import com.convoscribe.models.ModelCatalog
import com.convoscribe.models.ModelStore
import com.convoscribe.models.WhisperNpuAsset
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * On-device Whisper Large-v3-Turbo on the Hexagon NPU (issue #159): a
 * [FileRecognizer] over Qualcomm's precompiled build for this device's
 * chip ([ModelCatalog.whisperNpuFor] of [socModel]). Like the CPU
 * recognizer ([com.convoscribe.engine.sherpa.SherpaWhisperRecognizer]),
 * Silero VAD cuts the recording into speech segments of at most 28 s
 * ([SpeechChunker]) and each one becomes one line; the build's fixed
 * 30 s window takes each segment padded.
 *
 * The model (about 2.2 GB) is loaded for each call and released
 * afterwards. A failure to load or run it is thrown;
 * [com.convoscribe.asr.WhisperEngine] then records it and falls back to
 * the CPU.
 */
class QnnWhisperRecognizer(
    private val store: ModelStore,
    private val socModel: () -> String?,
    private val nativeLibraryDir: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : FileRecognizer {

    /** The published build for this device's chip, or `null` (the CPU is used). */
    fun asset(): WhisperNpuAsset? = ModelCatalog.whisperNpuFor(socModel())

    /** This chip has a build and it, the token table and the VAD are downloaded. */
    fun isInstalled(): Boolean {
        val bundles = ModelCatalog.whisperNpuBundles(socModel())
        return bundles.isNotEmpty() && bundles.all(store::isInstalled)
    }

    override suspend fun transcribe(
        file: File,
        language: String,
        onProgress: (Float) -> Unit
    ): List<TranscriptEvent.Final> = withContext(dispatcher) {
        val asset = asset() ?: throw IllegalStateException("This chip has no Whisper build for the NPU")
        ModelCatalog.whisperNpuBundles(socModel()).firstOrNull { !store.isInstalled(it) }?.let {
            throw IllegalStateException("Model not installed: ${it.id}")
        }
        val prompt = WhisperTokens.prompt(language)
        val tokens = WhisperTokens.load(store.fileFor(ModelCatalog.WHISPER_TOKENS, "turbo-tokens.txt"))
        QnnRuntime.ensurePrepared(nativeLibraryDir)
        WhisperQnnSession.open(store.bundleDir(asset.bundle), nativeLibraryDir).use { session ->
            SherpaVoiceDetector(store.fileFor(ModelCatalog.VAD_SILERO, "silero_vad.onnx")).use { vad ->
                val finals = mutableListOf<TranscriptEvent.Final>()
                SpeechChunker.chunk(file, vad, onProgress = onProgress) { span ->
                    currentCoroutineContext().ensureActive()
                    val text = TextNormalizer.normalize(tokens.decode(session.transcribe(span.samples, prompt)))
                    if (text.isNotBlank()) finals += TranscriptEvent.Final(text, span.startMs, span.endMs)
                }
                finals
            }
        }
    }

    override fun describe(): EngineInfo = EngineInfo(engine = "npu", model = asset()?.bundle?.id ?: MODEL_FAMILY)

    private companion object {
        const val MODEL_FAMILY = "asr-whisper-turbo-npu"
    }
}
