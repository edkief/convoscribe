package com.convoscribe.engine.sherpa

import android.util.Log
import com.convoscribe.TAG
import com.convoscribe.diarization.DiarizerFactory
import com.convoscribe.diarization.DiarizerSetup
import com.convoscribe.models.ModelBundle
import com.convoscribe.models.ModelCatalog
import com.convoscribe.models.ModelStore
import com.convoscribe.settings.SettingsRepository
import java.io.File
import kotlin.math.roundToInt

/**
 * [DiarizerFactory] for sherpa-onnx. Resolves the segmentation and
 * embedding `.onnx` files from [store] using [ModelCatalog.SEGMENTATION]
 * (`model.int8.onnx`) and the voice model chosen in Settings
 * ([ModelCatalog.embeddingFor], [ModelCatalog.EMBEDDING] by default),
 * and forwards the user's `numThreads` and segmentation step settings
 * to the underlying diarizer.
 *
 * A chosen voice model that is not downloaded yet falls back to
 * [ModelCatalog.EMBEDDING], with a log line: speakers are still
 * identified, and the session records the model that was used.
 *
 * The factory refuses to build a [com.convoscribe.diarization.Diarizer]
 * if the segmentation or default embedding file is missing — that's the
 * same user-facing "Model not installed: <id>" message the transcriber
 * factory uses, so the Settings screen can show the same download hint
 * for missing diarization models.
 *
 * Every [create] returns a fresh instance: sherpa-onnx's
 * `OfflineSpeakerDiarization` retains the segmentation and
 * embedding models in native memory, so reusing one across
 * recordings would just leak that memory.
 */
class SherpaDiarizerFactory(
    private val store: ModelStore,
    private val settings: SettingsRepository
) : DiarizerFactory {

    override fun create(): com.convoscribe.diarization.Diarizer {
        val segmentation: File = store.fileFor(
            ModelCatalog.SEGMENTATION,
            ModelCatalog.SEGMENTATION.files.single().fileName
        )
        if (!segmentation.exists()) {
            throw IllegalStateException("Model not installed: ${ModelCatalog.SEGMENTATION.id}")
        }
        val current = settings.settings.value
        val bundle = diarizationEmbedding(store, current.diarizationEmbedding)
        val embedding = store.fileFor(bundle, bundle.files.single().fileName)
        if (!embedding.exists()) {
            throw IllegalStateException("Model not installed: ${bundle.id}")
        }
        val step = current.diarizationStepSeconds
        Log.i(TAG, "diarizer embedding=${bundle.id} stepS=$step threads=${current.numThreads}")
        return SherpaDiarizer(
            segmentationModel = segmentation,
            embeddingModel = embedding,
            numThreads = current.numThreads,
            stepSeconds = step,
            setup = DiarizerSetup(embeddingModelId = bundle.id, stepMs = (step * 1000).roundToInt())
        )
    }
}

/**
 * The voice model speaker identification should use for the setting
 * [key]: its bundle once fully downloaded, else [ModelCatalog.EMBEDDING]
 * (a missing or half-downloaded optional model must not stop speakers
 * from being identified).
 */
internal fun diarizationEmbedding(store: ModelStore, key: String): ModelBundle {
    val chosen = ModelCatalog.embeddingFor(key)
    if (chosen == ModelCatalog.EMBEDDING || store.isInstalled(chosen)) return chosen
    Log.w(TAG, "voice model ${chosen.id} not downloaded; using ${ModelCatalog.EMBEDDING.id}")
    return ModelCatalog.EMBEDDING
}
