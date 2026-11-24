package com.convoscribe.models

/**
 * What kind of model a bundle represents. Used by the engines and the
 * downloader to pick the right on-disk paths and engine code.
 * [ENHANCEMENT] is the optional noise cleanup model (issue #37).
 * [EMBEDDING] is a speaker (voice) embedding model; [TEXT_EMBEDDING]
 * is the optional semantic search model (issue #61). [OFFLINE_ASR] is a
 * whole-recording (non-streaming) recognizer and [VAD] the voice
 * activity detector that cuts its input into speech segments (#158).
 */
enum class ModelKind { ASR, SEGMENTATION, EMBEDDING, ENHANCEMENT, TEXT_EMBEDDING, OFFLINE_ASR, VAD }

/**
 * One file that belongs to a [ModelBundle]: its on-disk name (which may
 * contain a sub-directory, e.g. `test_wavs/0.wav`), the URL to download
 * it from and the expected size in bytes. The downloader uses the size
 * for progress reporting and to verify a download completed.
 *
 * [archivePath] is where the file sits inside its bundle's
 * [ModelArchive], below [ModelArchive.rootDir], when that differs from
 * [fileName]: Qualcomm AI Hub's zips keep each model in a sub-directory
 * (`encoder_onnx/encoder.onnx`), and the app stores them side by side.
 */
data class ModelFile(
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val archivePath: String = fileName
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
    val rootDir: String,
    val format: ArchiveFormat = ArchiveFormat.TAR_BZ2
)

/**
 * How a [ModelArchive] is packed: sherpa-onnx's `.tar.bz2`, or the
 * `.zip` Qualcomm AI Hub publishes (issue #159).
 */
enum class ArchiveFormat { TAR_BZ2, ZIP }

/**
 * One published Qualcomm build of Whisper for the NPU (issue #159):
 * the chips it runs on, by `Build.SOC_MODEL` without any suffix after
 * `-` (e.g. `SM8750` for `SM8750-AC`), the chip's marketing [chipName],
 * and the [bundle] holding its EPContext wrappers and QNN context
 * binaries.
 */
data class WhisperNpuAsset(
    val socModels: Set<String>,
    val chipName: String,
    val bundle: ModelBundle
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
 * optional model ([CLEANUP], [TEXT_EMBEDDING_GEMMA], [whisperBundles]) is in [all] but never in [requiredFor].
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
    private const val SPEAKER_MODELS_BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/"

    private const val EMBEDDING_URL = SPEAKER_MODELS_BASE + "wespeaker_en_voxceleb_resnet34_LM.onnx"

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
     * Optional voice model for speaker identification: WeSpeaker CAM++,
     * trained on the same data as [EMBEDDING] with fewer operations per
     * pass. Downloaded from Settings → Speakers when chosen; known voices
     * are still matched with [EMBEDDING], so enrollments stay valid.
     */
    val EMBEDDING_CAMPPLUS: ModelBundle = ModelBundle(
        id = "emb-wespeaker-campplus-lm",
        displayName = "Speaker embeddings (CAM++)",
        kind = ModelKind.EMBEDDING,
        language = null,
        files = listOf(
            // `+` must be escaped in the URL path; the file keeps its name.
            ModelFile(
                "wespeaker_en_voxceleb_CAM++_LM.onnx",
                SPEAKER_MODELS_BASE + "wespeaker_en_voxceleb_CAM%2B%2B_LM.onnx",
                29_292_687
            )
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

    // Pinned to a revision: the downloader checks each file's exact size.
    private const val TEXT_EMBEDDING_GEMMA_BASE =
        "https://huggingface.co/onnx-community/embeddinggemma-300m-ONNX/resolve/" +
            "5090578d9565bb06545b4552f76e6bc2c93e4a66/"

    /**
     * Optional semantic search for Ask across sessions (issue #61):
     * EmbeddingGemma 300m, 4-bit ONNX export, about 197 MB, run on the
     * CPU through onnxruntime. `model_q4.onnx` names its weights file
     * `model_q4.onnx_data`, so both sit side by side. Gemma licence;
     * downloaded from Settings, never required.
     */
    val TEXT_EMBEDDING_GEMMA: ModelBundle = ModelBundle(
        id = "text-embeddinggemma-300m-q4",
        displayName = "Semantic search",
        kind = ModelKind.TEXT_EMBEDDING,
        language = null,
        files = listOf(
            ModelFile("model_q4.onnx", TEXT_EMBEDDING_GEMMA_BASE + "onnx/model_q4.onnx", 519_322),
            ModelFile("model_q4.onnx_data", TEXT_EMBEDDING_GEMMA_BASE + "onnx/model_q4.onnx_data", 196_725_760),
            ModelFile("tokenizer.model", TEXT_EMBEDDING_GEMMA_BASE + "tokenizer.model", 4_689_074)
        )
    )

    // Pinned to a revision: the downloader checks each file's exact size.
    private const val WHISPER_TURBO_BASE =
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo/resolve/" +
            "2ca6ff69fc878651b770880507669577ac41c2ff/"

    /**
     * Optional on-device Whisper Large-v3-Turbo (issue #158): sherpa-onnx's
     * int8 export, about 1 GB, run on the CPU by an offline (whole
     * recording) recognizer. It transcribes a recording again after Stop,
     * imports and re-transcriptions; live captions keep using the
     * streaming engines unless live Whisper (experimental, issue #160)
     * is on. Multilingual; MIT licence (OpenAI). Needs
     * [VAD_SILERO] to cut recordings into segments of at most 30 s.
     */
    val ASR_WHISPER_TURBO: ModelBundle = ModelBundle(
        id = "asr-whisper-large-v3-turbo-int8",
        displayName = "Whisper Large-v3-Turbo",
        kind = ModelKind.OFFLINE_ASR,
        language = null,
        files = listOf(
            ModelFile("turbo-encoder.int8.onnx", WHISPER_TURBO_BASE + "turbo-encoder.int8.onnx", 674_716_297),
            ModelFile("turbo-decoder.int8.onnx", WHISPER_TURBO_BASE + "turbo-decoder.int8.onnx", 361_080_764),
            ModelFile("turbo-tokens.txt", WHISPER_TURBO_BASE + "turbo-tokens.txt", 816_730)
        )
    )

    /**
     * Silero voice activity detector (issue #158), about 0.6 MB: cuts a
     * recording into speech segments for [ASR_WHISPER_TURBO]. Downloaded
     * with it.
     */
    val VAD_SILERO: ModelBundle = ModelBundle(
        id = "vad-silero",
        displayName = "Voice activity detection",
        kind = ModelKind.VAD,
        language = null,
        files = listOf(
            ModelFile(
                "silero_vad.onnx",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
                643_854
            )
        )
    )

    /**
     * Whether a session's recorded ASR model ([asrModel], several joined
     * by `" + "` for a continued session) is on-device Whisper alone, as
     * live Whisper (issue #160) records it.
     */
    fun isWhisperOnly(asrModel: String?): Boolean =
        !asrModel.isNullOrBlank() && asrModel.split(" + ").all { it.startsWith(WHISPER_ID_PREFIX) }

    private const val WHISPER_ID_PREFIX = "asr-whisper-"

    /** What on-device Whisper (issue #158) needs, downloaded and deleted together. */
    val whisperBundles: List<ModelBundle> = listOf(ASR_WHISPER_TURBO, VAD_SILERO)

    /**
     * Whisper's token table on its own (issue #159), about 0.8 MB: the
     * NPU build decodes with it and does not need [ASR_WHISPER_TURBO].
     * The same file as in that bundle.
     */
    val WHISPER_TOKENS: ModelBundle = ModelBundle(
        id = "asr-whisper-large-v3-turbo-tokens",
        displayName = "Whisper tokens",
        kind = ModelKind.OFFLINE_ASR,
        language = null,
        files = listOf(
            ModelFile("turbo-tokens.txt", WHISPER_TURBO_BASE + "turbo-tokens.txt", 816_730)
        )
    )

    private const val WHISPER_NPU_BASE =
        "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/" +
            "whisper_large_v3_turbo_quantized/releases/v0.67.0/"

    /** EPContext wrappers: the same size for every chip. */
    private const val WHISPER_NPU_ENCODER_ONNX = 4_644L
    private const val WHISPER_NPU_DECODER_ONNX = 13_849L

    /**
     * Qualcomm AI Hub's precompiled Whisper Large-v3-Turbo build for one
     * chip (Whisper-Large-V3-Turbo-Quantized, release v0.67.0, w8a16,
     * QAIRT 2.51 or 2.50, ONNX Runtime 1.30): `encoder.onnx`/`decoder.onnx`
     * are EPContext wrappers that point at the `.bin` QNN context
     * binaries next to them (`./encoder.bin`). 8-bit weights and 16-bit
     * activations run the encoder about three times as fast as the float
     * build and the download is under half the size (issue #159); the
     * session quantizes its inputs ([com.convoscribe.engine.qnn.WhisperQuant]).
     * Published as one zip of about 0.83 GB; [slug] names it. Most zips keep each model in a folder of its own
     * (`encoder_onnx/`, `decoder_onnx/`); the Snapdragon 8 Gen 3 one is
     * [flat]. The files are stored flat either way, which keeps every
     * wrapper beside its binary.
     */
    private fun whisperNpuBundle(
        slug: String,
        archiveBytes: Long,
        encoderBinBytes: Long,
        decoderBinBytes: Long,
        flat: Boolean = false
    ): ModelBundle {
        fun inZip(model: String, file: String) = if (flat) file else "${model}_onnx/$file"
        val name = "whisper_large_v3_turbo_quantized-precompiled_qnn_onnx-w8a16-qualcomm_$slug"
        return ModelBundle(
            id = "asr-whisper-turbo-npu-${slug.replace('_', '-')}",
            displayName = "Whisper Large-v3-Turbo (NPU)",
            kind = ModelKind.OFFLINE_ASR,
            language = null,
            files = listOf(
                ModelFile("encoder.onnx", "", WHISPER_NPU_ENCODER_ONNX, inZip("encoder", "encoder.onnx")),
                ModelFile("encoder.bin", "", encoderBinBytes, inZip("encoder", "encoder.bin")),
                ModelFile("decoder.onnx", "", WHISPER_NPU_DECODER_ONNX, inZip("decoder", "decoder.onnx")),
                ModelFile("decoder.bin", "", decoderBinBytes, inZip("decoder", "decoder.bin"))
            ),
            archive = ModelArchive(
                url = "$WHISPER_NPU_BASE$name.zip",
                sizeBytes = archiveBytes,
                rootDir = name,
                format = ArchiveFormat.ZIP
            )
        )
    }

    /**
     * The chips with a published Whisper NPU build that Android runs on
     * (issue #159). Each zip's `metadata.json` names the chip; the
     * `qcs8550_proxy` build targets QNN SoC id 43, which is the SM8550
     * (Snapdragon 8 Gen 2) as well as the QCS8550. Qualcomm also
     * publishes builds for the SA8775P, SA7255P and SA8295P (context
     * binaries only, with no ONNX Runtime wrapper) and the X Elite and
     * X2 Elite (Windows PCs); those use the CPU.
     */
    val whisperNpuAssets: List<WhisperNpuAsset> = listOf(
        WhisperNpuAsset(
            setOf("SM8450"),
            "Snapdragon 8 Gen 1",
            whisperNpuBundle("snapdragon_8gen1", 823_392_842, 681_562_112, 294_694_912)
        ),
        WhisperNpuAsset(
            setOf("SM8550", "QCS8550"),
            "Snapdragon 8 Gen 2",
            whisperNpuBundle("qcs8550_proxy", 825_864_854, 686_071_808, 295_849_984)
        ),
        WhisperNpuAsset(
            setOf("SM8650"),
            "Snapdragon 8 Gen 3",
            whisperNpuBundle("snapdragon_8gen3", 823_721_740, 686_112_520, 295_856_032, flat = true)
        ),
        WhisperNpuAsset(
            setOf("SM8750"),
            "Snapdragon 8 Elite",
            whisperNpuBundle("snapdragon_8_elite_for_galaxy", 825_757_593, 685_924_352, 295_772_160)
        ),
        WhisperNpuAsset(
            setOf("SM8850"),
            "Snapdragon 8 Elite Gen 5",
            whisperNpuBundle("snapdragon_8_elite_gen5_for_galaxy", 826_785_704, 684_933_120, 295_407_616)
        ),
        WhisperNpuAsset(
            setOf("SM8950"),
            "Snapdragon 8 Elite Gen 6",
            whisperNpuBundle("snapdragon_8_elite_gen6", 826_147_339, 683_667_456, 294_748_160)
        ),
        WhisperNpuAsset(
            setOf("SM8975"),
            "Snapdragon 8 Elite Extreme Gen 6",
            whisperNpuBundle("snapdragon_8_elite_extreme_gen6", 825_709_722, 683_229_184, 294_735_872)
        ),
        WhisperNpuAsset(
            setOf("QCS8275"),
            "Dragonwing IQ-8275",
            whisperNpuBundle("qcs8275", 825_884_595, 686_026_752, 295_858_176)
        ),
        WhisperNpuAsset(
            setOf("QCS9075"),
            "Dragonwing IQ-9075",
            whisperNpuBundle("qcs9075", 825_852_687, 686_063_616, 295_866_368)
        )
    )

    /**
     * The Whisper NPU build for the chip `Build.SOC_MODEL` reports, or
     * `null` for any other chip (or no report, before Android 12). Case
     * and a suffix after `-` (`SM8750-AC`) are ignored.
     */
    fun whisperNpuFor(socModel: String?): WhisperNpuAsset? {
        val key = socModel?.trim()?.uppercase()?.substringBefore('-')?.takeIf { it.isNotEmpty() } ?: return null
        return whisperNpuAssets.firstOrNull { key in it.socModels }
    }

    /**
     * What Whisper on the NPU needs on this chip (issue #159),
     * downloaded together: the chip's build, the token table and the
     * VAD. Empty when the chip has no published build.
     */
    fun whisperNpuBundles(socModel: String?): List<ModelBundle> =
        whisperNpuFor(socModel)?.let { listOf(it.bundle, WHISPER_TOKENS, VAD_SILERO) } ?: emptyList()

    /** Every Whisper bundle (CPU, NPU, tokens, VAD), for *Delete*. */
    val allWhisperBundles: List<ModelBundle>
        get() = whisperBundles + WHISPER_TOKENS + whisperNpuAssets.map { it.bundle }

    val all: List<ModelBundle> =
        listOf(
            ASR_EN, ASR_EN_NPU, ASR_FR, SEGMENTATION, EMBEDDING, EMBEDDING_CAMPPLUS, CLEANUP, TEXT_EMBEDDING_GEMMA,
            ASR_WHISPER_TURBO, VAD_SILERO, WHISPER_TOKENS
        ) + whisperNpuAssets.map { it.bundle }

    /**
     * Voice models speaker identification can cluster with, by their
     * [com.convoscribe.settings.AppSettings.diarizationEmbedding] key.
     * The first, [EMBEDDING], is the default and always installed.
     */
    val diarizationEmbeddings: Map<String, ModelBundle> = linkedMapOf(
        "resnet34" to EMBEDDING,
        "campplus" to EMBEDDING_CAMPPLUS
    )

    /** The voice model stored as [key], or [EMBEDDING] for an unknown key. */
    fun embeddingFor(key: String): ModelBundle = diarizationEmbeddings[key] ?: EMBEDDING

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
