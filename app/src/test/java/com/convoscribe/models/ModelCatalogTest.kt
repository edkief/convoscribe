package com.convoscribe.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
                ModelKind.ENHANCEMENT
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
            }
        }
        assertEquals(ModelKind.ENHANCEMENT, ModelCatalog.CLEANUP.kind)
        assertEquals(1, ModelCatalog.CLEANUP.files.size)
    }
}
