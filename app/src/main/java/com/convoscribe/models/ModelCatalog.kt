package com.convoscribe.models

/**
 * What kind of model a bundle represents. Used by the engines and the
 * downloader to pick the right on-disk paths and engine code.
 * [ENHANCEMENT] is the optional noise cleanup model (issue #37).
 */
enum class ModelKind { ASR, SEGMENTATION, EMBEDDING, ENHANCEMENT }

/**
 * One file that belongs to a [ModelBundle]: its on-disk name (which may
 * contain a sub-directory, e.g. `test_wavs/0.wav`), the URL to download
 * it from and the expected size in bytes. The downloader uses the size
 * for progress reporting and to verify a download completed.
 */
data class ModelFile(
    val fileName: String,
    val url: String,
    val sizeBytes: Long
)

/**
 * A bundle whose [ModelBundle.files] are not individually downloadable:
 * the upstream only ships a single `.tar.bz2` (or similar) archive
 * that has to be streamed and selectively extracted. The downloader
 * GETs [url], decompresses the bzip2 layer, walks the tar entries and
 * writes only the ones whose path matches one of the bundle's listed
 * [ModelFile.fileName]s; any other entry is skipped. [sizeBytes] is
 * the compressed body length and drives the progress bar.
 */
data class ModelArchive(
    val url: String,
    val sizeBytes: Long,
    val rootDir: String
)

/**
 * ASR-specific filenames for a sherpa-onnx streaming recognizer. The
 * engine check (TASK-17) uses [encoder]/[decoder]/[joiner]/[tokens] to
 * build an `OnlineRecognizer`, and the engine check itself uses a
 * bundled sample wave (`test_wavs/0.wav`) referenced by [ModelBundle.files].
 */
data class AsrFiles(
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String,
    val modelType: String
)

/**
 * A downloadable model: an id used as the on-disk directory, a
 * human-readable display name, its kind and (for ASR) language, the
 * individual [ModelFile]s to fetch, and the ASR-specific filenames.
 * `totalBytes` is the sum of every file's size and drives the
 * downloader's progress UI.
 */
data class ModelBundle(
    val id: String,
    val displayName: String,
    val kind: ModelKind,
    val language: String?,
    val files: List<ModelFile>,
    val asr: AsrFiles? = null,
    val archive: ModelArchive? = null
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }

    /**
     * Byte count this bundle will move on the wire this download:
     * the size of the upstream archive for archive-based bundles,
     * otherwise the sum of every listed file (the same as
     * [totalBytes]). The downloader uses this so the progress bar's
     * denominator matches what actually flows over the network.
     */
    val downloadBytes: Long get() = archive?.sizeBytes ?: totalBytes
}

/**
 * Static catalog of every model ConvoScribe downloads. Bundles are
 * listed in [all]; helpers below filter by language or kind. Adding a
 * new language means adding one more ASR bundle and extending
 * [asrFor]; adding a new model kind means adding a constant and
 * listing it in [requiredFor] for any language that needs it. An
 * optional model ([CLEANUP]) is in [all] but never in [requiredFor].
 */
object ModelCatalog {

    private const val ASR_EN_BASE =
        "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06/resolve/main/"

    private const val ASR_FR_BASE =
        "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-fr-kroko-2025-08-06/resolve/main/"

    private const val SEGMENTATION_URL =
        "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.int8.onnx"

    // The misspelling `recongition` is in the upstream GitHub release URL
    // and must be preserved; sherpa-onnx's docs link to it as-is.
    @Suppress("ktlint:standard:max-line-length")
    private const val EMBEDDING_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
            "speaker-recongition-models/wespeaker_en_voxceleb_resnet34_LM.onnx"

    val ASR_EN: ModelBundle = ModelBundle(
        id = "asr-en-kroko-2025-08-06",
        displayName = "English speech recognition",
        kind = ModelKind.ASR,
        language = "en",
        files = listOf(
            ModelFile("encoder.onnx", ASR_EN_BASE + "encoder.onnx", 70_092_599),
            ModelFile("decoder.onnx", ASR_EN_BASE + "decoder.onnx", 617_488),
            ModelFile("joiner.onnx", ASR_EN_BASE + "joiner.onnx", 336_817),
            ModelFile("tokens.txt", ASR_EN_BASE + "tokens.txt", 6_310),
            ModelFile("test_wavs/0.wav", ASR_EN_BASE + "test_wavs/0.wav", 184_608)
        ),
        asr = AsrFiles(
            encoder = "encoder.onnx",
            decoder = "decoder.onnx",
            joiner = "joiner.onnx",
            tokens = "tokens.txt",
            modelType = "zipformer2"
        )
    )

    val ASR_FR: ModelBundle = ModelBundle(
        id = "asr-fr-kroko-2025-08-06",
        displayName = "French speech recognition",
        kind = ModelKind.ASR,
        language = "fr",
        files = listOf(
            ModelFile("encoder.onnx", ASR_FR_BASE + "encoder.onnx", 70_092_599),
            ModelFile("decoder.onnx", ASR_FR_BASE + "decoder.onnx", 617_488),
            ModelFile("joiner.onnx", ASR_FR_BASE + "joiner.onnx", 336_817),
            ModelFile("tokens.txt", ASR_FR_BASE + "tokens.txt", 5_415)
        ),
        asr = AsrFiles(
            encoder = "encoder.onnx",
            decoder = "decoder.onnx",
            joiner = "joiner.onnx",
            tokens = "tokens.txt",
            modelType = "zipformer2"
        )
    )

    val SEGMENTATION: ModelBundle = ModelBundle(
        id = "seg-pyannote-3-0-int8",
        displayName = "Speaker segmentation",
        kind = ModelKind.SEGMENTATION,
        language = null,
        files = listOf(
            ModelFile("model.int8.onnx", SEGMENTATION_URL, 1_540_506)
        )
    )

    val EMBEDDING: ModelBundle = ModelBundle(
        id = "emb-wespeaker-resnet34-lm",
        displayName = "Speaker embeddings",
        kind = ModelKind.EMBEDDING,
        language = null,
        files = listOf(
            ModelFile("wespeaker_en_voxceleb_resnet34_LM.onnx", EMBEDDING_URL, 26_530_550)
        )
    )

    /**
     * English Nemotron Speech Streaming EN 0.6B model that runs on
     * the Hexagon NPU via sherpa-onnx's QNN backend. The upstream
     * ships it as a single `.tar.bz2` archive containing
     * `libencoder.so`, `libdecoder.so`, `libjoiner.so`, `tokens.txt`
     * and a sample wave; the QNN context binaries
     * (`encoder.bin`/`decoder.bin`/`joiner.bin`) are produced on the
     * first NPU start and cached next to the shared libraries. Only
     * the files listed in [files] are extracted from the archive —
     * everything else inside is discarded.
     */
    val ASR_EN_NPU: ModelBundle = ModelBundle(
        id = "asr-en-nemotron-0.6b-560ms-qnn",
        displayName = "English speech recognition (NPU)",
        kind = ModelKind.ASR,
        language = "en",
        files = listOf(
            ModelFile("libencoder.so", "", 591_964_328),
            ModelFile("libdecoder.so", "", 14_888_744),
            ModelFile("libjoiner.so", "", 2_107_784),
            ModelFile("tokens.txt", "", 8_952),
            ModelFile("test_wavs/0.wav", "", 212_044)
        ),
        asr = AsrFiles(
            encoder = "libencoder.so",
            decoder = "libdecoder.so",
            joiner = "libjoiner.so",
            tokens = "tokens.txt",
            modelType = "nemo_transducer"
        ),
        archive = ModelArchive(
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
                "asr-models-qnn-2/" +
                "sherpa-onnx-qnn-nemotron-speech-streaming-en-0.6b-560s-android-aarch64.tar.bz2",
            sizeBytes = 421_938_876,
            rootDir = "sherpa-onnx-qnn-nemotron-speech-streaming-en-0.6b-560s-android-aarch64"
        )
    )

    /**
     * Optional noise cleanup before transcription (issue #37):
     * sherpa-onnx's GTCRN speech-enhancement model, about 0.5 MB, run
     * on the CPU by its streaming denoiser. Downloaded from Settings;
     * setup and recording never require it.
     */
    val CLEANUP: ModelBundle = ModelBundle(
        id = "enh-gtcrn-simple",
        displayName = "Noise cleanup",
        kind = ModelKind.ENHANCEMENT,
        language = null,
        files = listOf(
            ModelFile(
                "gtcrn_simple.onnx",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
                    "speech-enhancement-models/gtcrn_simple.onnx",
                535_638
            )
        )
    )

    val all: List<ModelBundle> = listOf(ASR_EN, ASR_EN_NPU, ASR_FR, SEGMENTATION, EMBEDDING, CLEANUP)

    /**
     * Resolve the user-facing engine selection (currently `"npu"` or
     * `"cpu"`) into the value the catalog uses to pick a bundle:
     * `npu` is only valid for English; any other combination (FR +
     * npu, unknown engine, unknown language) falls back to the CPU
     * bundle for the requested language. Exposed so callers that do
     * not have access to the catalog otherwise (the
     * sherpa-onnx-specific factory, the engine-check screen, …) can
     * make the same decision.
     */
    fun effectiveEngine(language: String, engine: String): String =
        if (language == "en" && engine == "npu") "npu" else "cpu"

    /**
     * The ASR bundle for [language] (`"en"` or `"fr"` today) and the
     * chosen [engine] (`"npu"` or `"cpu"`). When the effective engine
     * is `npu` (English only) this returns [ASR_EN_NPU]; otherwise the
     * CPU zipformer for the language. Throws
     * [IllegalArgumentException] for unsupported languages: ConvoScribe
     * does not silently fall back to another language, and a typo
     * here is easier to debug than a wrong-language transcript later.
     */
    fun asrFor(language: String, engine: String): ModelBundle {
        val effective = effectiveEngine(language, engine)
        if (effective == "npu") return ASR_EN_NPU
        return when (language) {
            "en" -> ASR_EN
            "fr" -> ASR_FR
            else -> throw IllegalArgumentException(
                "Unsupported ASR language '$language'. Supported: 'en', 'fr'."
            )
        }
    }

    /**
     * The full set of bundles a session in [language] with the chosen
     * [engine] needs: the ASR for that combination, plus the
     * language-agnostic segmentation and embedding models used by the
     * diarizer.
     */
    fun requiredFor(language: String, engine: String): List<ModelBundle> =
        listOf(asrFor(language, engine), SEGMENTATION, EMBEDDING)
}
