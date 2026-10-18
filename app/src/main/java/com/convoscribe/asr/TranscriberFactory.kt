package com.convoscribe.asr

/**
 * Factory the rest of the app uses to obtain a [StreamingTranscriber]
 * without depending on sherpa-onnx directly. Implementations are
 * language-aware — `"en"` and `"fr"` are wired today — and choose
 * the right ASR model bundle from [com.convoscribe.models.ModelCatalog].
 *
 * Exists so the recording screen (TASK-11/12) can ask for a fresh
 * transcriber per session without knowing how to build one.
 */
fun interface TranscriberFactory {

    /**
     * Build a ready-to-use streaming transcriber for [language].
     * Implementations may throw [IllegalStateException] if the ASR
     * model for that language isn't installed yet.
     */
    fun create(language: String): StreamingTranscriber

    /**
     * Whether the next [create] for [language] will need an
     * expensive one-time setup step before the recognizer is
     * ready to consume audio. The recording screen surfaces this
     * as `RecordingState.preparingNpu == true` so the user knows
     * why the LOADING window is taking longer than usual.
     *
     * Default: `false` — only engines with a heavy first-run
     * cost (the QNN-backed NPU) need to override this. The
     * sherpa factory returns `true` when the effective engine
     * is `npu` and the QNN context binaries are not yet on
     * disk; the CPU path is always `false`.
     */
    fun needsPreparation(language: String): Boolean = false

    /**
     * The engine and model the next [create] for [language] will
     * use, recorded on the session so its info view can say what
     * produced the transcript. Default: unknown.
     */
    fun describe(language: String): EngineInfo = EngineInfo.UNKNOWN
}

/**
 * Which ASR [engine] (`"cpu"` / `"npu"`, after the French → CPU
 * fallback) and [model] (bundle id) transcribe a session.
 */
data class EngineInfo(val engine: String, val model: String) {
    companion object {
        val UNKNOWN = EngineInfo("unknown", "unknown")
    }
}
