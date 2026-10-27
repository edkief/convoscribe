package com.convoscribe.diarization

import java.io.Closeable

/**
 * Engine-agnostic diarization façade. Implementations turn a
 * finished mono 16 kHz PCM recording into a list of speaker turns
 * (who spoke when). The mapping from turns to transcript segments
 * is handled by [SpeakerAssigner], which is pure Kotlin and
 * separately tested.
 *
 * The interface is [Closeable] because every implementation today
 * holds native resources (sherpa-onnx retains the segmentation
 * and embedding models in `OfflineSpeakerDiarization`); the call
 * site is the only place that knows when diarization has finished.
 *
 * @see SherpaDiarizer
 * @see SpeakerAssigner
 */
interface Diarizer : Closeable {

    /**
     * The voice model and segmentation step this instance runs with,
     * kept on the session so runs can be compared; `null` when the
     * engine has no such settings (test fakes).
     */
    val setup: DiarizerSetup? get() = null

    /**
     * Run diarization on [samples] (mono 16 kHz float PCM, the
     * same shape the recorder writes). [numSpeakers] is forwarded
     * to the engine's clustering config: `-1` lets the engine
     * decide (sherpa-onnx's `FastClusteringConfig.numClusters =
     * -1` ⇒ auto), `1..10` pins the speaker count. [threshold] is
     * the clustering distance cutoff — lower means more speakers,
     * higher means fewer; the user's setting lives in
     * `SettingsRepository.settings.value.clusterThreshold`.
     *
     * [onProgress] is invoked with a value in `0f..1f` (the
     * fraction of audio chunks the engine has consumed) and may
     * be called from any thread the engine uses. Implementations
     * are expected to forward the progress and ultimately return
     * the full sorted list of [SpeakerTurn]s.
     *
     * [isCancelled] is polled at each progress step; once it returns
     * `true` an implementation should stop as soon as it can (the
     * result is then discarded), so unplugging the charger or the
     * system stopping the work does not leave the CPU busy (issue #11).
     */
    fun diarize(
        samples: FloatArray,
        numSpeakers: Int,
        threshold: Float,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): List<SpeakerTurn>
}

/**
 * What a [Diarizer] runs with: [embeddingModelId], the
 * [com.convoscribe.models.ModelBundle.id] of its voice model, and
 * [stepMs], how far its segmentation window moves each step.
 */
data class DiarizerSetup(val embeddingModelId: String, val stepMs: Int)

/**
 * Builds a fresh [Diarizer] when a recording finishes and needs
 * to be labelled. The factory is what the rest of the app holds a
 * reference to — the diarizer itself is short-lived (one per
 * session) because sherpa-onnx keeps the segmentation and
 * embedding models in native memory for the lifetime of the
 * `OfflineSpeakerDiarization` instance.
 */
fun interface DiarizerFactory {
    fun create(): Diarizer
}
