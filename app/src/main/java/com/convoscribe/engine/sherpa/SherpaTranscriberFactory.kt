package com.convoscribe.engine.sherpa

import com.convoscribe.asr.EndpointSegmenter
import com.convoscribe.asr.EngineInfo
import com.convoscribe.asr.StreamingTranscriber
import com.convoscribe.asr.TranscriberFactory
import com.convoscribe.models.ModelCatalog
import com.convoscribe.models.ModelStore
import com.convoscribe.settings.SettingsRepository

/**
 * [TranscriberFactory] for the sherpa-onnx engine. Picks the
 * matching ASR bundle from [ModelCatalog.asrFor] using the user's
 * selected language and engine, refuses to build a transcriber if
 * the files aren't on disk yet and forwards the user's
 * [SettingsRepository.settings] thread count to the CPU
 * recognizer.
 *
 * The factory also owns the QNN runtime preparation: the first
 * NPU recognizer in this process triggers [QnnRuntime.ensurePrepared]
 * so the Hexagon DSP libraries can find `libcdsprpc.so` and the
 * other native libraries bundled with the app. If the QNN
 * recognizer then fails to instantiate (e.g. missing context
 * binaries, mismatched HTP backend version, the device not
 * exposing an NPU), the original exception is rethrown as a
 * user-facing [IllegalStateException] with a hint to switch to
 * the CPU engine in Settings.
 *
 * Every call to [create] returns a fresh recognizer + stream pair
 * — sherpa-onnx does not safely share them across sessions.
 */
class SherpaTranscriberFactory(
    private val store: ModelStore,
    private val settings: SettingsRepository,
    /**
     * Absolute path of the app's `nativeLibraryDir` (e.g.
     * `/data/app/~~XYZ/convoscribe-/lib/arm64-v8a`). Forwarded
     * to [QnnRuntime.ensurePrepared] the first time we build an
     * NPU recognizer so the Hexagon DSP libraries can resolve
     * each other through `dlopen`. The CPU engine never reads
     * this value.
     */
    private val nativeLibraryDir: String,
    /**
     * Engine to build regardless of the user's Settings choice, or
     * `null` to follow `settings.asrEngine`. Set through
     * [withEngine] by the engine check, whose "Check NPU" / "Check
     * CPU" buttons must test that exact engine.
     */
    private val engineOverride: String? = null,
    /**
     * Returns why the NPU must not be started in this process, or
     * `null` when it may be. Backed by
     * [com.convoscribe.enginecheck.NpuAvailability]: once the NPU
     * probe has seen the QNN runtime kill its process, starting
     * the NPU here would kill the whole app (sherpa-onnx calls
     * `exit()` on a QNN init failure, so no exception reaches us),
     * so [create] refuses with a hint to switch to the CPU engine.
     */
    private val npuBlockedReason: () -> String? = { null }
) : TranscriberFactory {

    /**
     * A copy of this factory pinned to [engine] (`"npu"` or
     * `"cpu"`). The copy skips the [npuBlockedReason] guard: an
     * explicit engine choice comes from the engine check, which
     * must be able to retry the NPU (the NPU probe runs it in a
     * separate process so a native exit cannot take the app down).
     */
    fun withEngine(engine: String): SherpaTranscriberFactory = SherpaTranscriberFactory(
        store = store,
        settings = settings,
        nativeLibraryDir = nativeLibraryDir,
        engineOverride = engine
    )

    private fun selectedEngine(): String = engineOverride ?: settings.settings.value.asrEngine

    override fun create(language: String): StreamingTranscriber {
        val engine = selectedEngine()
        val bundle = ModelCatalog.asrFor(language, engine)
        val effective = ModelCatalog.effectiveEngine(language, engine)
        val asr = bundle.asr ?: error("ASR bundle ${bundle.id} has no AsrFiles")
        if (!store.isInstalled(bundle)) {
            throw IllegalStateException("Model not installed: ${bundle.id}")
        }
        val modelDir = store.bundleDir(bundle)
        return if (effective == "npu") {
            // NPU: make sure the ADSP loader knows about the app's
            // native library directory, then ask sherpa to build a
            // QNN-backed recognizer. Any failure during construction
            // is surfaced verbatim so the user sees the engine-
            // specific message and the call to switch to CPU in
            // Settings; we never silently fall back to the CPU
            // engine because the user chose NPU explicitly.
            npuBlockedReason()?.let { reason ->
                throw IllegalStateException(
                    "The NPU engine is not available on this device ($reason). " +
                        "You can switch to the CPU engine in Settings, or run " +
                        "Check NPU again from Engine check."
                )
            }
            QnnRuntime.ensurePrepared(nativeLibraryDir)
            val backend = try {
                SherpaRecognizerBackend(config = SherpaAsrConfigs.npu(modelDir, asr))
            } catch (e: Throwable) {
                throw IllegalStateException(
                    "The NPU engine failed to start (${e.message}). " +
                        "You can switch to the CPU engine in Settings.",
                    e
                )
            }
            EndpointSegmenter(backend = backend)
        } else {
            val numThreads = settings.settings.value.numThreads
            val backend = SherpaRecognizerBackend(
                config = SherpaAsrConfigs.cpu(modelDir, asr, numThreads)
            )
            EndpointSegmenter(backend = backend)
        }
    }

    /**
     * `true` when the next [create] for [language] will spin up
     * the QNN runtime against an NPU bundle that has not been
     * pre-compiled yet — i.e. the on-device compile of the
     * `.so` models into `.bin` context binaries is still to be
     * done. The CPU path is always `false`. The recording screen
     * reads this to swap the LOADING label from "Loading speech
     * model…" to a longer message explaining the multi-minute
     * first-run cost.
     */
    override fun describe(language: String): EngineInfo {
        val engine = selectedEngine()
        return EngineInfo(
            engine = ModelCatalog.effectiveEngine(language, engine),
            model = ModelCatalog.asrFor(language, engine).id
        )
    }

    override fun needsPreparation(language: String): Boolean {
        val engine = selectedEngine()
        val effective = ModelCatalog.effectiveEngine(language, engine)
        if (effective != "npu") return false
        val bundle = ModelCatalog.asrFor(language, engine)
        val modelDir = store.bundleDir(bundle)
        return SherpaAsrConfigs.contextBinaries(modelDir).any { !it.exists() }
    }
}
