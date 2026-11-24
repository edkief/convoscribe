package com.convoscribe.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    @Test
    fun requiredForEnglishAndCpu_listsAsrSegAndEmbedding() {
        val ids = ModelCatalog.requiredFor("en", "cpu").map { it.id }
        assertEquals(
            listOf(
                "asr-en-kroko-2025-08-06",
                "seg-pyannote-3-0-int8",
                "emb-wespeaker-resnet34-lm"
            ),
            ids
        )
    }

    @Test
    fun requiredForEnglishAndNpu_listsNpuAsrSegAndEmbedding() {
        val ids = ModelCatalog.requiredFor("en", "npu").map { it.id }
        assertEquals(
            listOf(
                "asr-en-nemotron-0.6b-560ms-qnn",
                "seg-pyannote-3-0-int8",
                "emb-wespeaker-resnet34-lm"
            ),
            ids
        )
    }

    @Test
    fun requiredForFrench_usesFrenchAsr() {
        val bundles = ModelCatalog.requiredFor("fr", "cpu")
        assertEquals("asr-fr-kroko-2025-08-06", bundles[0].id)
        assertEquals("fr", bundles[0].language)
        // Same diarizer models as English.
        assertEquals("seg-pyannote-3-0-int8", bundles[1].id)
        assertEquals("emb-wespeaker-resnet34-lm", bundles[2].id)
    }

    @Test
    fun requiredForFrenchAndNpu_startsWithFrenchCpuAsr() {
        // French is only supported on the CPU engine; the first
        // bundle must be the French Kroko bundle, not the English NPU.
        val first = ModelCatalog.requiredFor("fr", "npu").first()
        assertEquals("asr-fr-kroko-2025-08-06", first.id)
    }

    @Test
    fun englishTotalBytes() {
        assertEquals(71_237_822L, ModelCatalog.ASR_EN.totalBytes)
    }

    @Test
    fun frenchTotalBytes() {
        assertEquals(71_052_319L, ModelCatalog.ASR_FR.totalBytes)
    }

    @Test
    fun asrEnNpu_downloadBytesAndTotalBytes() {
        assertEquals(421_938_876L, ModelCatalog.ASR_EN_NPU.downloadBytes)
        assertEquals(609_181_852L, ModelCatalog.ASR_EN_NPU.totalBytes)
    }

    @Test
    fun everyUrlIsHttps() {
        ModelCatalog.all.flatMap { it.files }.forEach { file ->
            // The NPU bundle's files are extracted from an archive
            // and have no URL of their own; skip those.
            if (file.url.isNotEmpty()) {
                assertTrue(
                    "URL ${file.url} should start with https://",
                    file.url.startsWith("https://")
                )
            }
        }
    }

    @Test
    fun asrForUnknownLanguage_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelCatalog.asrFor("de", "cpu")
        }
    }

    @Test
    fun asrForEnglishAndNpu_returnsNpuBundle() {
        val bundle = ModelCatalog.asrFor("en", "npu")
        assertEquals("asr-en-nemotron-0.6b-560ms-qnn", bundle.id)
    }

    @Test
    fun effectiveEngine_returnsNpuOnlyForEnglishAndNpu() {
        assertEquals("npu", ModelCatalog.effectiveEngine("en", "npu"))
        assertEquals("cpu", ModelCatalog.effectiveEngine("en", "cpu"))
        assertEquals("cpu", ModelCatalog.effectiveEngine("fr", "npu"))
        assertEquals("cpu", ModelCatalog.effectiveEngine("fr", "cpu"))
        // Unknown engine also falls back to CPU.
        assertEquals("cpu", ModelCatalog.effectiveEngine("en", "gpu"))
    }

    @Test
    fun asrFiles_arePopulatedForAsrBundles() {
        val asr = ModelCatalog.ASR_EN.asr
        requireNotNull(asr)
        assertEquals("encoder.onnx", asr.encoder)
        assertEquals("decoder.onnx", asr.decoder)
        assertEquals("joiner.onnx", asr.joiner)
        assertEquals("tokens.txt", asr.tokens)
        assertEquals("zipformer2", asr.modelType)
    }

    @Test
    fun asrEnNpuFiles_referenceSharedLibraries() {
        val asr = ModelCatalog.ASR_EN_NPU.asr
        requireNotNull(asr)
        assertEquals("libencoder.so", asr.encoder)
        assertEquals("libdecoder.so", asr.decoder)
        assertEquals("libjoiner.so", asr.joiner)
        assertEquals("tokens.txt", asr.tokens)
        assertEquals("nemo_transducer", asr.modelType)
    }

    @Test
    fun nonAsrBundles_haveNoAsrFiles() {
        assertEquals(null, ModelCatalog.SEGMENTATION.asr)
        assertEquals(null, ModelCatalog.EMBEDDING.asr)
    }

    @Test
    fun asrEnNpuHasArchiveRootDirMatchingReleaseName() {
        val archive = ModelCatalog.ASR_EN_NPU.archive
        assertNotNull(archive)
        assertEquals(
            "sherpa-onnx-qnn-nemotron-speech-streaming-en-0.6b-560s-android-aarch64",
            archive!!.rootDir
        )
        assertTrue(
            "NPU archive URL should be on github.com",
            archive.url.startsWith("https://github.com/k2-fsa/sherpa-onnx/")
        )
    }

    @Test
    fun all_isConsistent() {
        // The downloader iterates `all`; make sure every kind is covered.
        val kinds = ModelCatalog.all.map { it.kind }.toSet()
        assertEquals(
            setOf(
                ModelKind.ASR,
                ModelKind.SEGMENTATION,
                ModelKind.EMBEDDING,
                ModelKind.ENHANCEMENT,
                ModelKind.TEXT_EMBEDDING,
                ModelKind.OFFLINE_ASR,
                ModelKind.VAD
            ),
            kinds
        )
        // And the NPU bundle is part of the catalog.
        assertTrue(
            "ASR_EN_NPU must appear in all",
            ModelCatalog.all.any { it.id == ModelCatalog.ASR_EN_NPU.id }
        )
    }

    @Test
    fun cleanup_isOptional() {
        // Issue #37: the noise cleanup model is never required for a session.
        for (language in listOf("en", "fr")) {
            for (engine in listOf("cpu", "npu")) {
                assertTrue(ModelCatalog.CLEANUP !in ModelCatalog.requiredFor(language, engine))
                assertTrue(ModelCatalog.TEXT_EMBEDDING_GEMMA !in ModelCatalog.requiredFor(language, engine))
            }
        }
        assertEquals(ModelKind.ENHANCEMENT, ModelCatalog.CLEANUP.kind)
        assertEquals(1, ModelCatalog.CLEANUP.files.size)
    }

    @Test
    fun whisper_isOptionalAndDownloadedWithItsVad() {
        // Issue #158: Whisper and its VAD are never required for a session.
        for (language in listOf("en", "fr")) {
            for (engine in listOf("cpu", "npu")) {
                val required = ModelCatalog.requiredFor(language, engine)
                ModelCatalog.whisperBundles.forEach { assertTrue(it !in required) }
            }
        }
        assertEquals(
            listOf("asr-whisper-large-v3-turbo-int8", "vad-silero"),
            ModelCatalog.whisperBundles.map { it.id }
        )
        assertEquals(1_036_613_791L, ModelCatalog.ASR_WHISPER_TURBO.totalBytes)
        assertEquals(null, ModelCatalog.ASR_WHISPER_TURBO.asr)
        assertTrue(ModelCatalog.whisperBundles.all { it in ModelCatalog.all })
    }

    @Test
    fun bundleIds_areUnique() {
        val ids = ModelCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun whisperNpuFor_mapsPublishedChips_ignoringCaseAndSuffix() {
        // Issue #159: Build.SOC_MODEL picks the published Qualcomm build.
        val expected = mapOf(
            "SM8450" to "snapdragon_8gen1",
            "SM8550" to "qcs8550_proxy",
            "QCS8550" to "qcs8550_proxy",
            "SM8650" to "snapdragon_8gen3",
            "SM8750" to "snapdragon_8_elite_for_galaxy",
            "SM8750-AC" to "snapdragon_8_elite_for_galaxy",
            "sm8750" to "snapdragon_8_elite_for_galaxy",
            "SM8850-AD" to "snapdragon_8_elite_gen5_for_galaxy",
            "SM8950" to "snapdragon_8_elite_gen6",
            "SM8975" to "snapdragon_8_elite_extreme_gen6",
            "QCS8275" to "qcs8275",
            "QCS9075" to "qcs9075"
        )
        for ((soc, slug) in expected) {
            val asset = ModelCatalog.whisperNpuFor(soc)
            assertNotNull("no NPU build for $soc", asset)
            assertTrue(
                "$soc should use $slug, got ${asset!!.bundle.archive?.rootDir}",
                asset.bundle.archive!!.rootDir.endsWith("-qualcomm_$slug")
            )
        }
    }

    @Test
    fun whisperNpuFor_otherChipsUseTheCpu() {
        // The Boox's QCS6690, chips with no published build, automotive
        // chips (no ONNX Runtime wrapper), and no SOC_MODEL at all.
        for (soc in listOf("QCS6690", "SM7225", "SM8475", "SA8775P", "SA8295P", "SC8380XP", "", " ", null)) {
            assertNull("$soc should have no NPU build", ModelCatalog.whisperNpuFor(soc))
            assertTrue(ModelCatalog.whisperNpuBundles(soc).isEmpty())
        }
    }

    @Test
    fun whisperNpuBundles_areTheChipBuildTokensAndVad() {
        val bundles = ModelCatalog.whisperNpuBundles("SM8650")
        assertEquals(
            listOf("asr-whisper-turbo-npu-snapdragon-8gen3", "asr-whisper-large-v3-turbo-tokens", "vad-silero"),
            bundles.map { it.id }
        )
        // Not the 1 GB CPU model.
        assertTrue(ModelCatalog.ASR_WHISPER_TURBO !in bundles)
    }

    @Test
    fun whisperNpuAssets_areZipArchivesWithWrappersAndBinaries() {
        val ids = ModelCatalog.whisperNpuAssets.map { it.bundle.id }
        assertEquals(ids.size, ids.toSet().size)
        val socs = ModelCatalog.whisperNpuAssets.flatMap { it.socModels }
        assertEquals("a chip maps to one build", socs.size, socs.toSet().size)
        for (asset in ModelCatalog.whisperNpuAssets) {
            val bundle = asset.bundle
            val archive = bundle.archive!!
            assertEquals(ArchiveFormat.ZIP, archive.format)
            assertEquals("${archive.rootDir}.zip", archive.url.substringAfterLast('/'))
            assertTrue(archive.url.startsWith("https://qaihub-public-assets.s3.us-west-2.amazonaws.com/"))
            assertTrue(archive.url.contains("/releases/v0.67.0/"))
            assertEquals(
                listOf("encoder.onnx", "encoder.bin", "decoder.onnx", "decoder.bin"),
                bundle.files.map { it.fileName }
            )
            // Deflate barely shrinks fp16 weights: the zip is a little
            // smaller than what it holds.
            assertTrue(archive.sizeBytes in (bundle.totalBytes * 9 / 10)..bundle.totalBytes)
            assertTrue(bundle in ModelCatalog.all)
            assertTrue(bundle in ModelCatalog.allWhisperBundles)
            ModelCatalog.requiredFor("en", "npu").forEach { assertTrue(it != bundle) }
        }
    }

    @Test
    fun whisperTokens_isTheCpuBundlesTokenFile() {
        val tokens = ModelCatalog.WHISPER_TOKENS.files.single()
        val cpuTokens = ModelCatalog.ASR_WHISPER_TURBO.files.single { it.fileName == "turbo-tokens.txt" }
        assertEquals(cpuTokens, tokens)
    }

    @Test
    fun tarBz2_isTheDefaultArchiveFormat() {
        assertEquals(ArchiveFormat.TAR_BZ2, ModelCatalog.ASR_EN_NPU.archive!!.format)
    }
}
