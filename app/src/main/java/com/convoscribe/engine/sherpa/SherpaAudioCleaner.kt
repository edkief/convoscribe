package com.convoscribe.engine.sherpa

import com.convoscribe.audio.AudioCleanerFactory
import com.convoscribe.audio.AudioSpec
import com.convoscribe.audio.StreamingCleaner
import com.convoscribe.models.ModelCatalog
import com.convoscribe.models.ModelStore
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig

/**
 * [StreamingCleaner] over sherpa-onnx's streaming speech denoiser
 * with the GTCRN model (issue #37). The denoiser works in hops of
 * [OnlineSpeechDenoiser.frameShiftInSamples] and keeps the output
 * sample-aligned with the input: it holds back up to one hop, which
 * its `flush()` returns, trimmed to the input length, before resetting
 * itself for the next run.
 *
 * Only GTCRN is index-aligned like this: sherpa-onnx's streaming
 * DPDFNet delays its output by 640 samples (40 ms), so switching
 * models means shifting that out to keep the cleaned recording in
 * step (`scripts/eval/eval_cleanup.py` measures both).
 */
class SherpaAudioCleaner(modelPath: String) : StreamingCleaner {

    private val denoiser = OnlineSpeechDenoiser(
        config = OnlineSpeechDenoiserConfig(
            model = OfflineSpeechDenoiserModelConfig(
                gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model = modelPath),
                // One thread: the model is tiny and live cleaning runs
                // next to the recognizer, which gets the cores.
                numThreads = 1,
                provider = "cpu"
            )
        )
    )

    override fun process(samples: FloatArray): FloatArray =
        if (samples.isEmpty()) samples else denoiser.run(samples, AudioSpec.SAMPLE_RATE).samples

    override fun flush(): FloatArray = denoiser.flush().samples

    override fun close() = denoiser.release()
}

/**
 * [AudioCleanerFactory] for [ModelCatalog.CLEANUP]. Refuses to build a
 * cleaner when the model is missing, with the same "Model not
 * installed" message as the other sherpa factories.
 */
class SherpaAudioCleanerFactory(private val store: ModelStore) : AudioCleanerFactory {

    override fun isAvailable(): Boolean = store.isInstalled(ModelCatalog.CLEANUP)

    override fun create(): StreamingCleaner {
        val bundle = ModelCatalog.CLEANUP
        if (!store.isInstalled(bundle)) {
            throw IllegalStateException("Model not installed: ${bundle.id}")
        }
        return SherpaAudioCleaner(store.fileFor(bundle, bundle.files.single().fileName).absolutePath)
    }

    override fun modelId(): String = ModelCatalog.CLEANUP.id
}
