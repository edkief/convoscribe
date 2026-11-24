package com.convoscribe.engine.sherpa

import com.convoscribe.models.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pure-JVM tests for [SherpaAsrConfigs]. Every builder here
 * returns a plain sherpa data class (no `OnlineRecognizer`
 * instance, no native library), so the suite is fast and runs
 * on the build machine even though we cannot actually load
 * `libsherpa-onnx-jni.so` outside a real device.
 *
 * The fixtures use a [TemporaryFolder] so the absolute paths
 * the config builders compute are predictable on every CI run.
 */
class SherpaAsrConfigsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dir
        get() = tmp.root

    @Test
    fun cpu_providerAndThreadCountAndModelType() {
        val asr = ModelCatalog.ASR_EN.asr ?: error("ASR_EN must have AsrFiles")
        val config = SherpaAsrConfigs.cpu(dir, asr, numThreads = 4)
        assertEquals("cpu", config.modelConfig.provider)
        assertEquals(4, config.modelConfig.numThreads)
        assertEquals("zipformer2", config.modelConfig.modelType)
        // Endpoint rules are pinned: rule2 fires 0.8 s after any
        // non-silence (the "backchannel" guard).
        assertEquals(0.8f, config.endpointConfig.rule2.minTrailingSilence, 0.0f)
    }

    @Test
    fun npu_providerModelTypeAndQnnConfig() {
        val asr = ModelCatalog.ASR_EN_NPU.asr ?: error("ASR_EN_NPU must have AsrFiles")
        val config = SherpaAsrConfigs.npu(dir, asr)
        // The QNN engine is the one shipped by the Nemotron
        // bundle: provider `qnn`, modelType `nemo_transducer`.
        assertEquals("qnn", config.modelConfig.provider)
        assertEquals("nemo_transducer", config.modelConfig.modelType)
        // The encoder path is absolute and ends with the shared
        // library name sherpa-onnx will dlopen.
        assertTrue(
            "encoder path should end with /libencoder.so, was ${config.modelConfig.transducer.encoder}",
            config.modelConfig.transducer.encoder.endsWith("/libencoder.so")
        )
        // QNN config pins the HTP backend + system lib and
        // provides all three context-binary paths joined by
        // commas. Backend / system lib names match what the
        // Hexagon NPU runtime ships inside the app.
        val qnn = config.modelConfig.transducer.qnnConfig
        assertEquals("libQnnHtp.so", qnn.backendLib)
        assertEquals("libQnnSystem.so", qnn.systemLib)
        val expectedBinaries = listOf("encoder.bin", "decoder.bin", "joiner.bin")
            .joinToString(",") { "${dir.absolutePath}/$it" }
        assertEquals(expectedBinaries, qnn.contextBinary)
    }

    @Test
    fun contextBinaries_returnsAllThreeAbsoluteBinFiles() {
        val binaries = SherpaAsrConfigs.contextBinaries(dir)
        assertEquals(listOf("encoder.bin", "decoder.bin", "joiner.bin"), binaries.map { it.name })
        // The factory must hand QNN absolute paths: the runtime
        // resolves them against the current working directory,
        // which on Android is not the bundle's directory.
        assertTrue(binaries.all { it.isAbsolute })
    }

    @Test
    fun npu_forcesOneThread() {
        val asr = ModelCatalog.ASR_EN_NPU.asr ?: error("ASR_EN_NPU must have AsrFiles")
        val config = SherpaAsrConfigs.npu(dir, asr)
        // sherpa's QNN path does its own dispatch on the Hexagon
        // cores; passing more than one thread would just race.
        assertEquals(1, config.modelConfig.numThreads)
    }

    @Test
    fun staleContextBinaries_withoutStamp_areDeletedAndNotCurrent() {
        // Binaries compiled by the QNN 2.40 runtime (before issue #159)
        // carry no stamp: they must be compiled again by QNN 2.51.
        SherpaAsrConfigs.contextBinaries(dir).forEach { it.writeText("old") }
        assertFalse(SherpaAsrConfigs.contextBinariesCurrent(dir))
        assertTrue(SherpaAsrConfigs.dropStaleContextBinaries(dir))
        assertTrue(SherpaAsrConfigs.contextBinaries(dir).none { it.exists() })
    }

    @Test
    fun staleContextBinaries_fromAnotherVersion_areDeleted() {
        SherpaAsrConfigs.contextBinaries(dir).forEach { it.writeText("old") }
        SherpaAsrConfigs.runtimeStamp(dir).writeText("2.40.0.251030")
        assertTrue(SherpaAsrConfigs.dropStaleContextBinaries(dir))
        assertTrue(SherpaAsrConfigs.contextBinaries(dir).none { it.exists() })
    }

    @Test
    fun currentContextBinaries_areKept() {
        SherpaAsrConfigs.contextBinaries(dir).forEach { it.writeText("new") }
        SherpaAsrConfigs.markContextBinariesCurrent(dir)
        assertTrue(SherpaAsrConfigs.contextBinariesCurrent(dir))
        assertFalse(SherpaAsrConfigs.dropStaleContextBinaries(dir))
        assertTrue(SherpaAsrConfigs.contextBinaries(dir).all { it.exists() })
    }
}
