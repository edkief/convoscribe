package com.convoscribe.engine.sherpa

import com.convoscribe.audio.AudioSpec
import com.convoscribe.models.AsrFiles
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.QnnConfig
import java.io.File

/**
 * Builders for the sherpa-onnx [OnlineRecognizerConfig]s the
 * recognizer backend instantiates. The two engines we ship today
 * (CPU zipformer and QNN-backed Nemotron) need different
 * `provider`/`modelType`/`qnnConfig` combinations, but they share
 * the same endpoint rules and feature extraction settings.
 *
 * Centralising the configuration here means the recording screen,
 * the engine-check screen and the unit tests all read the same
 * values — a change in endpoint rules only needs to land in one
 * place. The data classes are pure Kotlin / sherpa data classes, so
 * a unit test can construct a config and assert on it without
 * loading `libsherpa-onnx-jni.so`.
 */
object SherpaAsrConfigs {

    /**
     * Endpoint rules shared by every recognizer built here.
     *
     *  * `rule1`: fire an endpoint after `2.4 s` of trailing silence
     *    (so the user gets a clean "you're done talking" boundary
     *    when they stop mid-thought);
     *  * `rule2`: fire an endpoint `0.8 s` after any non-silence so
     *    a backchannel ("uh-huh", "right") doesn't prematurely
     *    split an utterance;
     *  * `rule3`: hard cap a single utterance at `20 s` so a runaway
     *    speaker can't grow the partial text without bound.
     *
     * Matches sherpa-onnx 1.13.8's defaults for streaming
     * zipformer / Nemotron.
     */
    val endpoint: EndpointConfig = EndpointConfig(
        rule1 = EndpointRule(false, 2.4f, 0.0f),
        rule2 = EndpointRule(true, 0.8f, 0.0f),
        rule3 = EndpointRule(false, 0.0f, 20.0f)
    )

    /**
     * 16 kHz / 80-dim log-mel feature extractor shared by every
     * engine. The Nemotron NPU and the Kroko CPU zipformer were
     * both trained on the same 80-mel FBank, so we only need one
     * config.
     */
    private val feature: FeatureConfig = FeatureConfig(
        sampleRate = AudioSpec.SAMPLE_RATE,
        featureDim = 80
    )

    /**
     * Build the [OnlineRecognizerConfig] for the CPU engine:
     * provider `"cpu"`, the user's selected [numThreads], and the
     * `modelType` carried by the [AsrFiles] for the bundle
     * (typically `"zipformer2"` for Kroko, but the catalog
     * controls the exact string).
     */
    fun cpu(modelDir: File, asr: AsrFiles, numThreads: Int): OnlineRecognizerConfig {
        return OnlineRecognizerConfig(
            featConfig = feature,
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(modelDir, asr.encoder).absolutePath,
                    decoder = File(modelDir, asr.decoder).absolutePath,
                    joiner = File(modelDir, asr.joiner).absolutePath
                ),
                tokens = File(modelDir, asr.tokens).absolutePath,
                numThreads = numThreads,
                provider = "cpu",
                modelType = asr.modelType
            ),
            endpointConfig = endpoint,
            enableEndpoint = true,
            decodingMethod = "greedy_search"
        )
    }

    /**
     * Build the [OnlineRecognizerConfig] for the Hexagon NPU
     * (QNN) engine. The transducer [OnlineTransducerModelConfig]
     * gets a [QnnConfig] pinned to the HTP backend (`libQnnHtp.so`
     * + `libQnnSystem.so`) and a comma-separated list of
     * pre-compiled context binaries. The runtime will:
     *
     *  1. skip the `qnnConfig` entirely if all three `.bin` files
     *     are already present (and load them as cached contexts);
     *  2. otherwise run an offline compile of the `.so` models and
     *     write the `.bin` next to them — this is the "first run
     *     takes a few minutes" window [RecordingState] surfaces as
     *     `preparingNpu == true`.
     *
     * `numThreads` is forced to `1` because sherpa's QNN path does
     * its own internal dispatch on the Hexagon cores; passing more
     * threads would just race the runtime.
     */
    fun npu(modelDir: File, asr: AsrFiles): OnlineRecognizerConfig {
        return OnlineRecognizerConfig(
            featConfig = feature,
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(modelDir, asr.encoder).absolutePath,
                    decoder = File(modelDir, asr.decoder).absolutePath,
                    joiner = File(modelDir, asr.joiner).absolutePath,
                    qnnConfig = QnnConfig(
                        backendLib = "libQnnHtp.so",
                        systemLib = "libQnnSystem.so",
                        contextBinary = contextBinaries(modelDir)
                            .joinToString(",") { it.absolutePath }
                    )
                ),
                tokens = File(modelDir, asr.tokens).absolutePath,
                numThreads = 1,
                provider = "qnn",
                modelType = asr.modelType
            ),
            endpointConfig = endpoint,
            enableEndpoint = true,
            decodingMethod = "greedy_search"
        )
    }

    /**
     * The three QNN context-binary files QNN writes on first run
     * and reuses thereafter. Named to match the `.so` they shadow:
     * `encoder.bin` for `libencoder.so`, etc. Returning a fresh
     * [File] each call keeps callers from accidentally caching a
     * `File` whose path moves across installs.
     */
    fun contextBinaries(modelDir: File): List<File> = listOf(
        File(modelDir, "encoder.bin"),
        File(modelDir, "decoder.bin"),
        File(modelDir, "joiner.bin")
    )

    /**
     * The QNN runtime the app bundles (scripts/build-sherpa-onnx.sh).
     * [contextBinaries] written by another QNN version are not reused
     * (issue #159 moved from 2.40 to 2.51): they are compiled again on
     * the next NPU start.
     */
    const val QNN_RUNTIME_VERSION = "2.51.0.260929"

    /** Names the QNN version that wrote [contextBinaries] in [modelDir]. */
    fun runtimeStamp(modelDir: File): File = File(modelDir, "qnn-runtime.txt")

    /**
     * `true` when every [contextBinaries] file exists and was written by
     * [QNN_RUNTIME_VERSION], so the next NPU start skips compiling.
     */
    fun contextBinariesCurrent(modelDir: File): Boolean = contextBinaries(modelDir).all { it.exists() } &&
        runCatching { runtimeStamp(modelDir).readText().trim() }.getOrNull() == QNN_RUNTIME_VERSION

    /**
     * Delete [contextBinaries] left by another QNN version (or with no
     * stamp, as before issue #159) so QNN compiles them again. Returns
     * `true` when anything was deleted.
     */
    fun dropStaleContextBinaries(modelDir: File): Boolean {
        val stamp = runCatching { runtimeStamp(modelDir).readText().trim() }.getOrNull()
        if (stamp == QNN_RUNTIME_VERSION) return false
        var deleted = false
        for (file in contextBinaries(modelDir)) {
            if (file.exists() && file.delete()) deleted = true
        }
        return deleted
    }

    /** Record that the [contextBinaries] in [modelDir] come from [QNN_RUNTIME_VERSION]. */
    fun markContextBinariesCurrent(modelDir: File) {
        runtimeStamp(modelDir).writeText(QNN_RUNTIME_VERSION)
    }
}
