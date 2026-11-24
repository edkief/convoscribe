package com.convoscribe.enginecheck

import com.convoscribe.asr.EngineInfo
import com.convoscribe.asr.TranscriptEvent
import com.convoscribe.asr.WhisperDecoder
import com.convoscribe.asr.WhisperRecognizer
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

    private class Fake(
        private val failure: Throwable? = null,
        private val openFailure: Throwable? = null,
        private val onOpen: () -> Unit = {}
    ) : WhisperRecognizer {
        var closed = 0

        override fun openDecoder(language: String): WhisperDecoder {
            openFailure?.let { throw it }
            onOpen()
            return object : WhisperDecoder {
                override val info = describe()
                override fun decode(samples: FloatArray) = ""
                override fun close() {
                    closed++
                }
            }
        }

        override suspend fun transcribeWith(
            decoder: WhisperDecoder,
            file: File,
            onProgress: (Float) -> Unit
        ): List<TranscriptEvent.Final> {
            failure?.let { throw it }
            return listOf(TranscriptEvent.Final("Hello", 0, 500), TranscriptEvent.Final("world.", 600, 1_000))
        }

        override suspend fun transcribe(file: File, language: String, onProgress: (Float) -> Unit) =
            openDecoder(language).use { transcribeWith(it, file, onProgress) }

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
    fun loading_isTimedApartFromTranscribing() = runTest {
        // Issue #159: loading the 2 GB NPU build took longer than the
        // clip, so counting it made the NPU look slower than real time.
        var now = 0L
        val recognizer = Fake(onOpen = { now += 4_000 })
        val result = measureWhisper(recognizer, clip(4_000)) { now.also { now += 1_000 } }
        assertNull(result.error)
        assertEquals(5_000L, result.initMs)
        assertEquals(1_000L, result.processMs)
        assertEquals(0.25, result.realTimeFactor, 1e-9)
        assertEquals(1, recognizer.closed)
    }

    @Test
    fun loadFailure_becomesAnErrorResult() = runTest {
        val result = measureWhisper(Fake(openFailure = IllegalStateException("QNN load")), clip(1_000)) { 0L }
        assertEquals("QNN load", result.error)
        assertEquals(0L, result.initMs)
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
