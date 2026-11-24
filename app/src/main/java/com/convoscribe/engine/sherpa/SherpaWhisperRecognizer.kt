package com.convoscribe.engine.sherpa

import com.convoscribe.asr.EngineInfo
import com.convoscribe.asr.SpeechChunker
import com.convoscribe.asr.SpeechSpan
import com.convoscribe.asr.TextNormalizer
import com.convoscribe.asr.TranscriptEvent
import com.convoscribe.asr.VoiceDetector
import com.convoscribe.asr.WhisperDecoder
import com.convoscribe.asr.WhisperRecognizer
import com.convoscribe.audio.AudioSpec
import com.convoscribe.models.ModelCatalog
import com.convoscribe.models.ModelStore
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device Whisper Large-v3-Turbo (issue #158): a [FileRecognizer]
 * that transcribes a whole stored recording on the CPU, next to the
 * transcription server (#58). [SpeechChunker] cuts the recording into
 * speech segments of at most 28 s with Silero VAD, and each segment is
 * decoded by sherpa-onnx's offline Whisper recognizer into one line.
 *
 * The lines carry no word times (the published export has no attention
 * outputs); callers give them borrowed ones with
 * [com.convoscribe.asr.WordTimer].
 *
 * The recognizer (about 1 GB of weights) is loaded for each call and
 * released afterwards, so it holds no memory between recordings. Live
 * Whisper (issue #160) keeps one loaded for a recording through
 * [openDecoder].
 */
class SherpaWhisperRecognizer(
    private val store: ModelStore,
    private val numThreads: () -> Int,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : WhisperRecognizer {

    /** Whisper and its VAD are both downloaded. */
    fun isAvailable(): Boolean = ModelCatalog.whisperBundles.all(store::isInstalled)

    override suspend fun transcribe(
        file: File,
        language: String,
        onProgress: (Float) -> Unit
    ): List<TranscriptEvent.Final> = withContext(dispatcher) {
        openDecoder(language).use { transcribeWith(it, file, onProgress) }
    }

    override suspend fun transcribeWith(
        decoder: WhisperDecoder,
        file: File,
        onProgress: (Float) -> Unit
    ): List<TranscriptEvent.Final> = withContext(dispatcher) {
        SherpaVoiceDetector(store.fileFor(ModelCatalog.VAD_SILERO, "silero_vad.onnx")).use { vad ->
            val finals = mutableListOf<TranscriptEvent.Final>()
            SpeechChunker.chunk(file, vad, onProgress = onProgress) { span ->
                span.lineOf(decoder.decode(span.samples))?.let(finals::add)
            }
            finals
        }
    }

    override fun openDecoder(language: String): WhisperDecoder {
        ModelCatalog.whisperBundles.firstOrNull { !store.isInstalled(it) }?.let {
            throw IllegalStateException("Model not installed: ${it.id}")
        }
        return Decoder(OfflineRecognizer(assetManager = null, config = config(language)), describe())
    }

    override fun describe(): EngineInfo = EngineInfo(engine = "cpu", model = ModelCatalog.ASR_WHISPER_TURBO.id)

    /** One recognizer kept for a run of segments. */
    private class Decoder(private val recognizer: OfflineRecognizer, override val info: EngineInfo) : WhisperDecoder {
        override fun decode(samples: FloatArray): String {
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, AudioSpec.SAMPLE_RATE)
                recognizer.decode(stream)
                return TextNormalizer.normalize(recognizer.getResult(stream).text)
            } finally {
                stream.release()
            }
        }

        override fun close() = recognizer.release()
    }

    private fun config(language: String): OfflineRecognizerConfig {
        val bundle = ModelCatalog.ASR_WHISPER_TURBO
        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = AudioSpec.SAMPLE_RATE, featureDim = WHISPER_MEL_BINS),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = store.fileFor(bundle, "turbo-encoder.int8.onnx").absolutePath,
                    decoder = store.fileFor(bundle, "turbo-decoder.int8.onnx").absolutePath,
                    language = language,
                    task = "transcribe"
                ),
                tokens = store.fileFor(bundle, "turbo-tokens.txt").absolutePath,
                numThreads = numThreads().coerceAtLeast(1),
                provider = "cpu",
                modelType = "whisper"
            ),
            decodingMethod = "greedy_search"
        )
    }

    private companion object {
        /** Large-v3 and turbo read 128 mel bins (earlier Whisper models 80). */
        const val WHISPER_MEL_BINS = 128
    }
}

/**
 * [VoiceDetector] over sherpa-onnx's Silero VAD, configured for
 * [SpeechChunker]: segments are closed after 0.5 s of silence and
 * capped at [maxSpeechSeconds], its 28 s limit unless live Whisper asks
 * for shorter segments (issue #160).
 */
class SherpaVoiceDetector(
    model: File,
    maxSpeechSeconds: Float = SpeechChunker.MAX_SAMPLES.toFloat() / AudioSpec.SAMPLE_RATE
) : VoiceDetector {

    private val vad = Vad(
        assetManager = null,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = model.absolutePath,
                threshold = 0.5f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = maxSpeechSeconds
            ),
            sampleRate = AudioSpec.SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu"
        )
    )

    override fun accept(samples: FloatArray) = vad.acceptWaveform(samples)

    override fun flush() = vad.flush()

    override fun drain(): List<SpeechSpan> {
        val spans = mutableListOf<SpeechSpan>()
        while (!vad.empty()) {
            val segment = vad.front()
            spans += SpeechSpan(segment.start.toLong(), segment.samples)
            vad.pop()
        }
        return spans
    }

    override fun close() = vad.release()
}
