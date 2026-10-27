package com.convoscribe.engine.sherpa

import com.convoscribe.diarization.Diarizer
import com.convoscribe.diarization.DiarizerSetup
import com.convoscribe.diarization.SpeakerTurn
import com.convoscribe.settings.SettingsRepository
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File

/**
 * [Diarizer] backed by sherpa-onnx's `OfflineSpeakerDiarization`.
 * The native engine holds the segmentation (`model.int8.onnx`,
 * pyannote-3.0) and embedding (`wespeaker_en_voxceleb_resnet34_LM.onnx`)
 * models for the lifetime of this instance, so the class is
 * `Closeable` and the factory gives every recording session its
 * own.
 *
 * The config's clustering is left at `numClusters = -1` /
 * `threshold = 0.5` for construction: the per-invocation overrides
 * from [diarize] are applied via [OfflineSpeakerDiarization.setConfig]
 * before the audio is fed in. Re-clustering every call would be
 * pointless (the embedding extractor output is deterministic for
 * the same audio), but the sherpa-onnx API exposes the knob only
 * that way.
 *
 * @param segmentationModel absolute path to the segmentation
 *   `.onnx` (pyannote-3.0 int8, the file `ModelCatalog.SEGMENTATION`
 *   tracks).
 * @param embeddingModel absolute path to the embedding `.onnx`
 *   (`wespeaker_en_voxceleb_resnet34_LM`, the file
 *   `ModelCatalog.EMBEDDING` tracks).
 * @param numThreads forwarded to both sub-configs; today the
 *   CPU is the only supported backend for diarization.
 * @param stepSeconds how far the segmentation model's
 *   [SEGMENTATION_WINDOW_SECONDS] window moves each step. sherpa-onnx's
 *   default is a tenth of the window (1 s), so every moment is segmented,
 *   and each speaker in it embedded, about ten times; a longer step does
 *   proportionally less work.
 * @param setup reported as [Diarizer.setup].
 */
class SherpaDiarizer(
    segmentationModel: File,
    embeddingModel: File,
    numThreads: Int,
    stepSeconds: Float = 1f,
    override val setup: DiarizerSetup? = null
) : Diarizer {

    private val sd: OfflineSpeakerDiarization

    init {
        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                    model = segmentationModel.absolutePath,
                    windowShiftRatio = stepSeconds / SEGMENTATION_WINDOW_SECONDS
                ),
                numThreads = numThreads
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = embeddingModel.absolutePath,
                numThreads = numThreads
            ),
            clustering = FastClusteringConfig(
                numClusters = -1,
                threshold = SettingsRepository.DEFAULT_CLUSTER_THRESHOLD,
                computeConfidence = true
            ),
            minDurationOn = 0.3f,
            minDurationOff = 0.5f
        )
        sd = OfflineSpeakerDiarization(assetManager = null, config = config)
    }

    override fun diarize(
        samples: FloatArray,
        numSpeakers: Int,
        threshold: Float,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): List<SpeakerTurn> {
        // sherpa-onnx re-clusters on every process() call using
        // whatever's currently in the config — push the
        // caller-supplied (numSpeakers, threshold) pair before
        // kicking off the work.
        sd.setConfig(
            sd.config.copy(
                // computeConfidence: per-turn confidence lets the
                // enrolled-voice matching use each speaker's clearest
                // audio (SpeakerSampleCollector).
                clustering = FastClusteringConfig(
                    numClusters = numSpeakers,
                    threshold = threshold,
                    computeConfidence = true
                )
            )
        )
        // The callback must return 0 to continue; any other value
        // asks sherpa-onnx to abort the run, which we do once the
        // caller is cancelled. It also forwards a normalised 0f..1f
        // progress fraction.
        val raw = sd.processWithCallback(samples, callback = ProgressCallback(onProgress, isCancelled))
        return raw.map { seg ->
            SpeakerTurn(
                startMs = (seg.start * 1000f).toLong(),
                endMs = (seg.end * 1000f).toLong(),
                speaker = seg.speaker,
                confidence = seg.confidence
            )
        }
    }

    override fun close() {
        sd.release()
    }

    companion object {
        /** pyannote-3.0's segmentation window ([com.convoscribe.models.ModelCatalog.SEGMENTATION]). */
        const val SEGMENTATION_WINDOW_SECONDS = 10f
    }

    /**
     * The sherpa-onnx JNI resolves the callback with
     * `GetMethodID(cls, "invoke", "(IIJ)Ljava/lang/Integer;")`.
     * A plain Kotlin lambda is compiled via invokedynamic (Kotlin 2.x
     * default) into a synthetic class exposing only the erased
     * `invoke(Object, Object, Object)`, so the lookup fails and ART
     * aborts the process. A named class gets the specialised
     * `invoke(int, int, long): Integer` the native side expects.
     */
    private class ProgressCallback(
        private val onProgress: (Float) -> Unit,
        private val isCancelled: () -> Boolean
    ) : (Int, Int, Long) -> Int {
        override fun invoke(done: Int, total: Int, arg: Long): Int {
            if (isCancelled()) return 1
            onProgress(if (total <= 0) 0f else done.toFloat() / total)
            return 0
        }
    }
}
