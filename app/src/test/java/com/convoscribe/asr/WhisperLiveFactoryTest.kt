package com.convoscribe.asr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperLiveFactoryTest {

    private class Whisper : WhisperRecognizer {
        val languages = mutableListOf<String>()
        override fun openDecoder(language: String): WhisperDecoder {
            languages += language
            return object : WhisperDecoder {
                override val info = EngineInfo("cpu", "asr-whisper-large-v3-turbo-int8")
                override fun decode(samples: FloatArray) = "hi"
                override fun close() = Unit
            }
        }

        override suspend fun transcribe(file: File, language: String, onProgress: (Float) -> Unit) =
            emptyList<TranscriptEvent.Final>()

        override fun describe() = EngineInfo("cpu", "asr-whisper-large-v3-turbo-int8")
    }

    private object NoState : WhisperNpuState {
        override fun blockedReason(): String? = null
        override fun isVerified() = false
        override fun markFailed(reason: String) = Unit
    }

    private val streamingTranscriber = object : StreamingTranscriber {
        override fun accept(samples: FloatArray) = emptyList<TranscriptEvent>()
        override fun finish() = emptyList<TranscriptEvent>()
        override fun close() = Unit
    }

    private val streaming = object : TranscriberFactory {
        override fun create(language: String) = streamingTranscriber
        override fun needsPreparation(language: String) = true
        override fun describe(language: String) = EngineInfo("npu", "asr-en-npu")
    }

    private val cpu = Whisper()
    private var cpuInstalled = true
    private var enabled = true
    private val maxSeconds = mutableListOf<Int>()

    private val factory = WhisperLiveFactory(
        streaming = streaming,
        whisper = WhisperEngine(Whisper(), cpu, { null }, { false }, { cpuInstalled }, NoState),
        enabled = { enabled },
        maxSeconds = { 7 },
        voiceDetector = { seconds ->
            maxSeconds += seconds
            object : VoiceDetector {
                override fun accept(samples: FloatArray) = Unit
                override fun flush() = Unit
                override fun drain() = emptyList<SpeechSpan>()
                override fun close() = Unit
            }
        }
    )

    @Test
    fun on_andDownloaded_recordsWithWhisper() {
        val transcriber = factory.create("de")

        assertTrue(transcriber is WhisperLiveTranscriber)
        assertEquals(listOf("de"), cpu.languages)
        assertEquals(listOf(7), maxSeconds)
        assertEquals(EngineInfo("cpu", "asr-whisper-large-v3-turbo-int8"), factory.describe("de"))
        assertFalse(factory.needsPreparation("de"))
        transcriber.close()
    }

    @Test
    fun off_usesTheStreamingEngine() {
        enabled = false
        assertSame(streamingTranscriber, factory.create("en"))
        assertEquals("asr-en-npu", factory.describe("en").model)
        assertTrue(factory.needsPreparation("en"))
        assertTrue(cpu.languages.isEmpty())
    }

    @Test
    fun on_butNotDownloaded_usesTheStreamingEngine() {
        cpuInstalled = false
        assertSame(streamingTranscriber, factory.create("en"))
        assertEquals("asr-en-npu", factory.describe("en").model)
    }
}
