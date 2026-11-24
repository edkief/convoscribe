package com.convoscribe.asr

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperEngineTest {

    private class Fake(val engine: String, var failure: Throwable? = null) : WhisperRecognizer {
        var calls = 0
        var opened = 0
        var closed = 0
        var openFailure: Throwable? = null
        var decodeFailure: Throwable? = null

        override fun openDecoder(language: String): WhisperDecoder {
            openFailure?.let { throw it }
            opened++
            return object : WhisperDecoder {
                override val info = describe()
                override fun decode(samples: FloatArray): String {
                    decodeFailure?.let { throw it }
                    return "$engine ${samples.size}"
                }

                override fun close() {
                    closed++
                }
            }
        }

        override suspend fun transcribeWith(decoder: WhisperDecoder, file: File, onProgress: (Float) -> Unit) =
            emptyList<TranscriptEvent.Final>()

        override suspend fun transcribe(
            file: File,
            language: String,
            onProgress: (Float) -> Unit
        ): List<TranscriptEvent.Final> {
            calls++
            failure?.let { throw it }
            return listOf(TranscriptEvent.Final("from $engine", 0, 1_000))
        }

        override fun describe() = EngineInfo(engine, "whisper-$engine")
    }

    private class State(var blocked: String? = null, var verified: Boolean = false) : WhisperNpuState {
        override fun blockedReason() = blocked
        override fun isVerified() = verified
        override fun markFailed(reason: String) {
            blocked = reason
            verified = false
        }
    }

    private val npu = Fake("npu")
    private val cpu = Fake("cpu")
    private val state = State()
    private var chip: String? = "Snapdragon 8 Gen 3"
    private var npuInstalled = true
    private var cpuInstalled = true
    private val file = File("unused.wav")

    private val engine = WhisperEngine(npu, cpu, { chip }, { npuInstalled }, { cpuInstalled }, state)

    @Test
    fun verifiedNpu_isUsed_andRecorded() = runTest {
        state.verified = true

        val finals = engine.transcribe(file, "en")

        assertEquals("from npu", finals.single().text)
        assertEquals("npu", engine.describe().engine)
        assertEquals(0, cpu.calls)
    }

    @Test
    fun npuNotCheckedYet_usesTheCpu() = runTest {
        assertEquals("cpu", engine.status().engine)
        engine.transcribe(file, "en")
        assertEquals(0, npu.calls)
        assertEquals("cpu", engine.describe().engine)
    }

    @Test
    fun npuNotCheckedAndNoCpu_isNotAvailable() = runTest {
        cpuInstalled = false
        assertFalse(engine.isAvailable())
        val ex = runCatching { engine.transcribe(file, "en") }.exceptionOrNull()
        assertEquals("Whisper is not downloaded", ex?.message)
    }

    @Test
    fun blockedNpu_usesTheCpu() = runTest {
        state.verified = true
        state.blocked = "probe died"
        assertEquals("cpu", engine.status().engine)
        engine.transcribe(file, "en")
        assertEquals(0, npu.calls)
    }

    @Test
    fun unsupportedChip_usesTheCpu_evenWithNpuFilesLeftOver() = runTest {
        chip = null
        state.verified = true
        val status = engine.status()
        assertEquals("cpu", status.engine)
        assertFalse(status.npuSupported)
        assertFalse(status.npuInstalled)
    }

    @Test
    fun npuFailure_isRecorded_andTheSameCallRunsOnTheCpu() = runTest {
        state.verified = true
        npu.failure = IllegalStateException("QNN graph failed")

        val finals = engine.transcribe(file, "fr")

        assertEquals("from cpu", finals.single().text)
        assertEquals("QNN graph failed", state.blocked)
        assertEquals("cpu", engine.describe().engine)
        // Later calls skip the NPU.
        engine.transcribe(file, "fr")
        assertEquals(1, npu.calls)
    }

    @Test
    fun npuFailure_withoutTheCpuModel_isRethrown() = runTest {
        state.verified = true
        cpuInstalled = false
        npu.failure = IllegalStateException("QNN graph failed")

        val ex = runCatching { engine.transcribe(file, "en") }.exceptionOrNull()

        assertTrue(ex?.message.orEmpty(), ex?.message.orEmpty().contains("QNN graph failed"))
        assertEquals("QNN graph failed", state.blocked)
        assertNull(engine.status().engine)
    }

    @Test
    fun cancellation_isNotAnNpuFailure() {
        state.verified = true
        npu.failure = CancellationException("stop")

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { engine.transcribe(file, "en") }
        }
        assertNull(state.blocked)
        assertEquals(0, cpu.calls)
    }

    @Test
    fun describe_beforeAnyRun_namesWhatWouldBeUsed() {
        assertEquals("cpu", engine.describe().engine)
        state.verified = true
        assertEquals("npu", engine.describe().engine)
    }

    @Test
    fun openDecoder_verifiedNpu_decodesOnTheNpu() {
        state.verified = true

        engine.openDecoder("en").use { decoder ->
            assertEquals("npu 3", decoder.decode(FloatArray(3)))
            assertEquals("npu", decoder.info.engine)
        }
        assertEquals(0, cpu.opened)
        assertEquals(1, npu.closed)
    }

    @Test
    fun openDecoder_npuFailsToOpen_isRecorded_andTheCpuIsUsed() {
        state.verified = true
        npu.openFailure = IllegalStateException("context binary")

        val decoder = engine.openDecoder("en")

        assertEquals("cpu 2", decoder.decode(FloatArray(2)))
        assertEquals("context binary", state.blocked)
    }

    @Test
    fun openDecoder_npuFailsToDecode_retriesTheSegmentOnTheCpu_andStaysThere() {
        state.verified = true
        val decoder = engine.openDecoder("en")
        npu.decodeFailure = IllegalStateException("HTP error")

        assertEquals("cpu 4", decoder.decode(FloatArray(4)))
        assertEquals("cpu", decoder.info.engine)
        assertEquals("HTP error", state.blocked)
        assertEquals(1, npu.closed)

        npu.decodeFailure = null
        assertEquals("cpu 1", decoder.decode(FloatArray(1)))
        decoder.close()
        assertEquals(1, cpu.closed)
    }

    @Test
    fun openDecoder_npuFails_withoutTheCpuModel_isThrown() {
        state.verified = true
        cpuInstalled = false
        val decoder = engine.openDecoder("en")
        npu.decodeFailure = IllegalStateException("HTP error")

        val ex = runCatching { decoder.decode(FloatArray(1)) }.exceptionOrNull()

        assertTrue(ex?.message.orEmpty().contains("HTP error"))
        assertEquals(0, cpu.opened)
    }

    @Test
    fun openDecoder_nothingDownloaded_throws() {
        cpuInstalled = false
        val ex = runCatching { engine.openDecoder("en") }.exceptionOrNull()
        assertEquals("Whisper is not downloaded", ex?.message)
    }
}
