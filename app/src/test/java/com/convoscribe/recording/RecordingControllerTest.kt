package com.convoscribe.recording

import com.convoscribe.asr.StreamingTranscriber
import com.convoscribe.asr.TranscriberFactory
import com.convoscribe.asr.TranscriptEvent
import com.convoscribe.audio.AudioSource
import com.convoscribe.audio.AudioSpec
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pure-JVM tests for [RecordingController]. The real dependencies
 * (Room, sherpa-onnx, AudioRecord) are swapped for fakes:
 *
 *  * [FakeRecordingStore] is an in-memory implementation of
 *    [RecordingStore] backed by the JUnit [TemporaryFolder].
 *  * [ChannelAudioSource] exposes the audio as a
 *    `Channel<ShortArray>` so the test controls when each chunk
 *    arrives.
 *  * [EveryThirdChunkTranscriber] emits one [TranscriptEvent.Final]
 *    every three [accept] calls, so the assertion is independent
 *    of how the controller batches events internally.
 *
 * Everything runs on a single [StandardTestDispatcher]: the
 * launched scope is the test's [TestScope.backgroundScope] and
 * the ASR dispatcher is the same instance, so a single
 * `advanceUntilIdle()` flushes both.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingControllerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** One 100 ms / mono PCM16 chunk of silence. */
    private fun silentChunk(): ShortArray = ShortArray(AudioSpec.CHUNK_SAMPLES)

    /**
     * In-memory [RecordingStore]. The session id is an auto-
     * increment counter; [audioPath] resolves to a file inside
     * [tmp.root] so the WAV writer has somewhere to write to. Every
     * [addSegment] call is recorded in [segments] and every
     * [finishRecording] call in [finishCalls].
     */
    private class FakeRecordingStore(val root: File) : RecordingStore {
        private val nextId = AtomicLong(0L)

        /** A persisted transcript segment. */
        data class Segment(
            val sessionId: Long,
            val startMs: Long,
            val endMs: Long,
            val text: String,
            val tokens: List<com.convoscribe.asr.TimedToken> = emptyList()
        )

        /** A call to [finishRecording] in the order it happened. */
        data class Finish(val sessionId: Long, val durationMs: Long)

        val segments: MutableList<Segment> = mutableListOf()
        val finishCalls: MutableList<Finish> = mutableListOf()

        /** Language per existing session, for [sessionLanguage]. */
        val languages: MutableMap<Long, String> = mutableMapOf()

        /** Sessions passed to [reopenSession], in order. */
        val reopenCalls: MutableList<Long> = mutableListOf()

        override suspend fun sessionLanguage(sessionId: Long): String =
            languages[sessionId] ?: error("unknown session $sessionId")

        override suspend fun reopenSession(sessionId: Long) {
            reopenCalls += sessionId
        }

        /** A call to [createSession], in order. */
        data class Create(
            val title: String,
            val language: String,
            val numSpeakers: Int?,
            val participants: List<String>?
        )

        /** A call to [recordEngine], in order. */
        data class Engine(val sessionId: Long, val engine: String, val model: String, val continued: Boolean)

        val createCalls: MutableList<Create> = mutableListOf()
        val engineCalls: MutableList<Engine> = mutableListOf()

        override suspend fun createSession(
            title: String,
            language: String,
            numSpeakers: Int?,
            participants: List<String>?
        ): Long {
            createCalls += Create(title, language, numSpeakers, participants)
            val id = nextId.incrementAndGet()
            // Match the production `SessionRepository`: a file at
            // <root>/<id>.wav that the WAV writer will overwrite.
            File(root, "$id.wav")
            return id
        }

        override suspend fun recordEngine(sessionId: Long, engine: String, model: String, continued: Boolean) {
            engineCalls += Engine(sessionId, engine, model, continued)
        }

        override suspend fun audioPath(sessionId: Long): String = File(root, "$sessionId.wav").absolutePath

        override suspend fun addSegment(
            sessionId: Long,
            startMs: Long,
            endMs: Long,
            text: String,
            tokens: List<com.convoscribe.asr.TimedToken>
        ): Long {
            val segId = segments.size.toLong() + 1L
            segments += Segment(sessionId, startMs, endMs, text, tokens)
            return segId
        }

        override suspend fun finishRecording(sessionId: Long, durationMs: Long) {
            finishCalls += Finish(sessionId, durationMs)
        }
    }

    /**
     * [AudioSource] backed by a [Channel]. Tests send chunks into
     * the channel and the controller receives them via
     * [Channel.receiveAsFlow]. Closing the channel terminates the
     * flow, just like a real `AudioRecord` would on a stop.
     */
    private class ChannelAudioSource(val channel: Channel<ShortArray>) : AudioSource {
        override fun chunks(): Flow<ShortArray> = channel.receiveAsFlow()
    }

    /**
     * [StreamingTranscriber] that returns one [TranscriptEvent.Final]
     * every three [accept] calls. The text is "line-<n>" where
     * [n] is the [accept] count, so the assertions do not depend
     * on any text-normalisation specifics. The timestamps also
     * align with the chunk cadence (`startMs = (n-3)*100`,
     * `endMs = n*100`) so a bug in `samplesToMs` shows up here.
     *
     * Tracks [acceptCount], [finishCount] and [closed] so tests
     * can assert on what the controller actually called.
     */
    private class EveryThirdChunkTranscriber : StreamingTranscriber {
        var acceptCount: Int = 0
            private set
        var finishCount: Int = 0
            private set
        var closed: Boolean = false
            private set

        override fun accept(samples: FloatArray): List<TranscriptEvent> {
            acceptCount += 1
            if (acceptCount % 3 == 0) {
                val n = acceptCount
                val startMs = ((n - 3) * 100L)
                val endMs = (n * 100L)
                return listOf(
                    TranscriptEvent.Final(
                        text = "line-$n",
                        startMs = startMs,
                        endMs = endMs,
                        tokens = listOf(com.convoscribe.asr.TimedToken(" line", startMs + 10))
                    )
                )
            }
            return emptyList()
        }

        override fun finish(): List<TranscriptEvent> {
            finishCount += 1
            return emptyList()
        }

        override fun close() {
            closed = true
        }
    }

    /**
     * [TranscriberFactory] returning the supplied transcriber (or
     * throwing if [error] is non-null). Records every call so tests
     * can assert that a second `start` did not build a second
     * recognizer. [needsPreparation] is a knob so a single test
     * can exercise both the steady-state LOADING label and the
     * NPU-first-run path.
     */
    private class FakeTranscriberFactory(
        private val transcriber: StreamingTranscriber,
        private val error: Throwable? = null,
        private val needsPreparation: Boolean = false
    ) : TranscriberFactory {
        var createCount: Int = 0
            private set
        var lastLanguage: String? = null
            private set
        override fun create(language: String): StreamingTranscriber {
            createCount += 1
            lastLanguage = language
            if (error != null) throw error
            return transcriber
        }
        override fun needsPreparation(language: String): Boolean = needsPreparation
    }

    /**
     * Bundle of the moving parts a typical scenario wires up. Tests
     * keep references to `controller`, `audio`, `transcriber` and
     * `store` to assert on them later.
     */
    private data class Harness(
        val controller: RecordingController,
        val audio: ChannelAudioSource,
        val transcriber: EveryThirdChunkTranscriber,
        val factory: FakeTranscriberFactory,
        val store: FakeRecordingStore
    )

    /**
     * Build a [Harness] sharing one [StandardTestDispatcher] across
     * the launched scope (caller's `backgroundScope`) and the ASR
     * dispatcher. The dispatcher carries the test scheduler the
     * caller passes into `runTest`, so `advanceUntilIdle()` flushes
     * both ends of the controller.
     */
    private fun TestScope.buildHarness(
        factoryError: Throwable? = null,
        transcriber: EveryThirdChunkTranscriber = EveryThirdChunkTranscriber(),
        store: FakeRecordingStore = FakeRecordingStore(tmp.root),
        clock: () -> Long = { 0L },
        needsPreparation: Boolean = false
    ): Harness {
        val audio = ChannelAudioSource(Channel(Channel.UNLIMITED))
        val factory = FakeTranscriberFactory(transcriber, factoryError, needsPreparation)
        val controller = RecordingController(
            audioSource = audio,
            transcriberFactory = factory,
            store = store,
            language = { "en" },
            asrDispatcher = StandardTestDispatcher(testScheduler),
            clock = clock
        )
        return Harness(controller, audio, transcriber, factory, store)
    }

    @Test
    fun start_movesThroughLoadingToRecording_andPersistsAFinalEveryThreeChunks() = runTest {
        val h = buildHarness()
        h.controller.start(this)

        // Phase LOADING while the recognizer is being built and the
        // session row is being inserted.
        assertEquals(RecordingPhase.LOADING, h.controller.state.value.phase)

        // Flush the launched coroutine: it builds the recognizer,
        // creates the session, opens the WAV and flips to RECORDING.
        advanceUntilIdle()
        assertEquals(RecordingPhase.RECORDING, h.controller.state.value.phase)
        assertNotNull(h.controller.state.value.sessionId)

        // 6 chunks → 2 finals.
        for (i in 0 until 6) {
            h.audio.channel.trySend(silentChunk())
        }
        advanceUntilIdle()

        assertEquals(6, h.transcriber.acceptCount)
        assertEquals(2, h.store.segments.size)
        assertEquals(listOf("line-3", "line-6"), h.store.segments.map { it.text })
        assertEquals(listOf("line-3", "line-6"), h.controller.state.value.finalLines)
        assertEquals(600L, h.controller.state.value.elapsedMs)

        // Stop the recording cleanly so the launched coroutine ends
        // and runTest does not complain about an open child.
        h.controller.stop()
        advanceUntilIdle()
    }

    /**
     * When the underlying factory reports `needsPreparation == true`
     * (the first NPU run that has to compile the `.so` models into
     * `.bin` context binaries), [RecordingController.start] must
     * set `preparingNpu == true` together with the LOADING phase
     * so the recording screen can show the longer "Preparing the
     * NPU model (first run only, this can take a few minutes)…"
     * label. The flag is sampled synchronously inside `start`,
     * before the launched coroutine runs, so the assertion is
     * valid without advancing the scheduler.
     */
    @Test
    fun start_factoryNeedsPreparation_setsPreparingNpuInLoadingState() = runTest {
        val h = buildHarness(needsPreparation = true)
        h.controller.start(this)

        // LOADING + preparingNpu == true, observed synchronously.
        assertEquals(RecordingPhase.LOADING, h.controller.state.value.phase)
        assertTrue(
            "preparingNpu should be true while the NPU first-run compile is in flight",
            h.controller.state.value.preparingNpu
        )

        // Once we move into RECORDING the flag is no longer
        // relevant (the user is past the LOADING window) and is
        // reset to false by the start() cleanup.
        advanceUntilIdle()
        assertEquals(RecordingPhase.RECORDING, h.controller.state.value.phase)
        assertEquals(false, h.controller.state.value.preparingNpu)

        h.controller.stop()
        advanceUntilIdle()
    }

    /**
     * The default path (CPU engine, or a steady-state NPU that
     * already has the context binaries on disk) must not flip
     * `preparingNpu`. This guards against an over-eager override
     * that would surface a "this can take a few minutes" label on
     * a normal CPU recording.
     */
    @Test
    fun start_factoryDoesNotNeedPreparation_leavesPreparingNpuFalse() = runTest {
        val h = buildHarness(needsPreparation = false)
        h.controller.start(this)
        assertEquals(RecordingPhase.LOADING, h.controller.state.value.phase)
        assertEquals(false, h.controller.state.value.preparingNpu)
        advanceUntilIdle()
        h.controller.stop()
        advanceUntilIdle()
    }

    @Test
    fun stop_returnsTheId_clearsStateAndRecordsTheDuration() = runTest {
        val h = buildHarness()
        h.controller.start(this)
        advanceUntilIdle()
        val id = h.controller.state.value.sessionId!!
        assertEquals(RecordingPhase.RECORDING, h.controller.state.value.phase)

        for (i in 0 until 6) {
            h.audio.channel.trySend(silentChunk())
        }
        advanceUntilIdle()

        // Stop while the channel is still open. The launched
        // coroutine is cancelled before reading any more chunks.
        val returnedId = h.controller.stop()
        advanceUntilIdle()

        assertEquals(id, returnedId)
        assertEquals(RecordingPhase.IDLE, h.controller.state.value.phase)
        assertEquals(id, h.controller.state.value.lastCompletedSessionId)
        assertEquals(1, h.store.finishCalls.size)
        assertEquals(id, h.store.finishCalls[0].sessionId)
        assertEquals(600L, h.store.finishCalls[0].durationMs)

        // WAV file exists and is exactly 44 (header) + 6*1600*2 (samples) bytes.
        val wav = File(tmp.root, "$id.wav")
        assertTrue("WAV file should exist: ${wav.absolutePath}", wav.exists())
        assertEquals(44L + 6L * 1600L * 2L, wav.length())

        // Transcriber was closed exactly once.
        assertTrue("transcriber was closed", h.transcriber.closed)
    }

    @Test
    fun transcriberFactoryThatThrows_landsInIdleWithThatMessageInError() = runTest {
        val factoryError = IllegalStateException("model not installed")
        val h = buildHarness(factoryError = factoryError)
        h.controller.start(this)
        advanceUntilIdle()

        assertEquals(RecordingPhase.IDLE, h.controller.state.value.phase)
        assertEquals("model not installed", h.controller.state.value.error)
        // No session was ever created — the error fires before
        // `store.createSession`, so finishRecording was never called.
        assertEquals(0, h.store.finishCalls.size)
        assertNull(h.controller.state.value.lastCompletedSessionId)
    }

    @Test
    fun callingStartTwice_createsOnlyOneSession_andStopWhenIdleReturnsNull() = runTest {
        val h = buildHarness()
        h.controller.start(this)
        advanceUntilIdle()
        val firstSessionId = h.controller.state.value.sessionId
        assertNotNull(firstSessionId)

        // Second start while RECORDING is a no-op: no second
        // recognizer, no second session row, no state churn.
        h.controller.start(this)
        advanceUntilIdle()
        // Factory was called exactly once: a second start must not
        // build a second recognizer.
        assertEquals(1, h.factory.createCount)
        assertEquals(firstSessionId, h.controller.state.value.sessionId)
        assertEquals(RecordingPhase.RECORDING, h.controller.state.value.phase)

        // Stop, then a second stop (or a stop while IDLE) is a no-op.
        val id = h.controller.stop()
        advanceUntilIdle()
        assertEquals(firstSessionId, id)

        val secondStop = h.controller.stop()
        assertNull("stop() while IDLE must return null", secondStop)
    }

    @Test
    fun consumeCompleted_clearsLastCompletedSessionId() = runTest {
        val h = buildHarness()
        h.controller.start(this)
        advanceUntilIdle()
        val id = h.controller.state.value.sessionId!!
        h.controller.stop()
        advanceUntilIdle()

        assertEquals(id, h.controller.state.value.lastCompletedSessionId)
        h.controller.consumeCompleted()
        assertNull(h.controller.state.value.lastCompletedSessionId)
    }

    @Test
    fun elapsedMs_tracksSamplesWritten_throughSamplesToMs() = runTest {
        val h = buildHarness()
        h.controller.start(this)
        advanceUntilIdle()

        // 3 chunks = 4800 samples = 300 ms.
        for (i in 0 until 3) {
            h.audio.channel.trySend(silentChunk())
        }
        advanceUntilIdle()
        assertEquals(300L, h.controller.state.value.elapsedMs)

        // 7 more = 10 chunks total = 16000 samples = 1000 ms.
        for (i in 0 until 7) {
            h.audio.channel.trySend(silentChunk())
        }
        advanceUntilIdle()
        assertEquals(1000L, h.controller.state.value.elapsedMs)

        h.controller.stop()
        advanceUntilIdle()
    }

    /**
     * Write a finished session's WAV holding [ms] of silence, as a
     * previous recording run would have left it.
     */
    private fun existingRecording(store: FakeRecordingStore, sessionId: Long, ms: Int, language: String): File {
        val file = File(tmp.root, "$sessionId.wav")
        com.convoscribe.audio.WavWriter(file).use { it.write(ShortArray(ms * 16)) }
        store.languages[sessionId] = language
        return file
    }

    @Test
    fun start_resume_appendsAfterExistingAudioWithOffsetSegmentsAndSessionLanguage() = runTest {
        val store = FakeRecordingStore(tmp.root)
        val wav = existingRecording(store, sessionId = 7L, ms = 600, language = "fr")
        val h = buildHarness(store = store)

        h.controller.start(this, resumeSessionId = 7L)
        advanceUntilIdle()

        val state = h.controller.state.value
        assertEquals(RecordingPhase.RECORDING, state.phase)
        assertEquals(7L, state.sessionId)
        assertEquals(600L, state.elapsedMs)
        assertEquals(listOf(7L), store.reopenCalls)
        // Settings say "en"; the session was recorded in French.
        assertEquals("fr", h.factory.lastLanguage)

        for (i in 0 until 3) h.audio.channel.trySend(silentChunk())
        advanceUntilIdle()
        assertEquals(900L, h.controller.state.value.elapsedMs)

        h.controller.stop()
        advanceUntilIdle()

        // The transcriber reported 0..300 ms (token at 10 ms); stored after the old 600 ms.
        assertEquals(
            listOf(
                FakeRecordingStore.Segment(
                    7L,
                    600L,
                    900L,
                    "line-3",
                    listOf(com.convoscribe.asr.TimedToken(" line", 610))
                )
            ),
            store.segments
        )
        assertEquals(FakeRecordingStore.Finish(7L, 900L), store.finishCalls.last())
        assertEquals(900L, com.convoscribe.audio.WavReader.readMono16k(wav).size / 16L)
    }

    @Test
    fun start_resume_failureBeforeRecordingLeavesSessionUntouched() = runTest {
        val store = FakeRecordingStore(tmp.root)
        val wav = existingRecording(store, sessionId = 7L, ms = 600, language = "en")
        val bytesBefore = wav.length()
        val h = buildHarness(store = store, factoryError = IllegalStateException("model missing"))

        h.controller.start(this, resumeSessionId = 7L)
        advanceUntilIdle()

        assertEquals(RecordingPhase.IDLE, h.controller.state.value.phase)
        assertEquals("model missing", h.controller.state.value.error)
        assertTrue(store.reopenCalls.isEmpty())
        assertTrue(store.finishCalls.isEmpty())
        assertEquals(bytesBefore, wav.length())
    }
}
