package com.convoscribe.enginecheck

import com.convoscribe.asr.EngineInfo
import com.convoscribe.asr.FileRecognizer
import com.convoscribe.asr.TranscriptEvent
import com.convoscribe.audio.WavWriter
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WhisperCheckTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun clip(ms: Int): File = tmp.newFile("0.wav").also { file ->
        WavWriter(file).use { it.write(ShortArray(ms * 16)) }
    }

    private class Fake(private val failure: Throwable? = null) : FileRecognizer {
        override suspend fun transcribe(
            file: File,
            language: String,
            onProgress: (Float) -> Unit
        ): List<TranscriptEvent.Final> {
            failure?.let { throw it }
            return listOf(TranscriptEvent.Final("Hello", 0, 500), TranscriptEvent.Final("world.", 600, 1_000))
        }
        override fun describe() = EngineInfo("cpu", "whisper")
    }

    @Test
    fun measure_reportsTimeRtfAndText() = runTest {
        var now = 0L
        val result = measureWhisper(Fake(), clip(2_000)) { now.also { now += 1_000 } }
        assertNull(result.error)
        assertEquals(WHISPER_ENGINE, result.engine)
        assertEquals(2_000L, result.audioMs)
        assertEquals(1_000L, result.processMs)
        assertEquals(0.5, result.realTimeFactor, 1e-9)
        assertEquals("Hello world.", result.text)
    }

    @Test
    fun failure_becomesAnErrorResult() = runTest {
        val result = measureWhisper(Fake(IllegalStateException("Model not installed: x")), clip(1_000)) { 0L }
        assertEquals("Model not installed: x", result.error)
        assertEquals(1_000L, result.audioMs)
    }

    @Test
    fun npuCheck_isReportedAsWhisperNpu() = runTest {
        // Issue #159: the probe's run on the NPU is named apart from the CPU one.
        val sample = clip(1_000)
        val ok = measureWhisper(Fake(), sample, engine = WHISPER_NPU_ENGINE) { 0L }
        assertEquals("whisper-npu", ok.engine)
        val failed = measureWhisper(Fake(IllegalStateException("QNN")), sample, engine = WHISPER_NPU_ENGINE) { 0L }
        assertEquals("whisper-npu", failed.engine)
        assertEquals("QNN", failed.error)
    }
}
