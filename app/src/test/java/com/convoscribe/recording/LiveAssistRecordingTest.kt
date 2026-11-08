package com.convoscribe.recording

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.asr.EngineInfo
import com.convoscribe.asr.StreamingTranscriber
import com.convoscribe.asr.TranscriberFactory
import com.convoscribe.asr.TranscriptEvent
import com.convoscribe.audio.AudioSource
import com.convoscribe.audio.AudioSpec
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.SessionRepository
import com.convoscribe.llm.AssistLibrary
import com.convoscribe.llm.AssistSources
import com.convoscribe.llm.AssistStatus
import com.convoscribe.llm.ChatClient
import com.convoscribe.llm.LiveAssistant
import com.convoscribe.llm.LlmException
import com.convoscribe.llm.LlmMessage
import com.convoscribe.settings.InMemorySecretStore
import com.convoscribe.settings.SettingsRepository
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Live assist (issue #50) never gets in the way of a recording: with
 * the endpoint failing or hanging, every line is still transcribed and
 * saved, and Stop still finishes the session.
 */
@RunWith(RobolectricTestRunner::class)
class LiveAssistRecordingTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: AppDatabase
    private lateinit var repo: SessionRepository
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val syncExecutor = java.util.concurrent.Executor { it.run() }
        val queryExecutor = java.util.concurrent.Executor {
            dispatcher.dispatch(kotlin.coroutines.EmptyCoroutineContext, it)
        }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(queryExecutor)
            .setTransactionExecutor(syncExecutor)
            .build()
        repo = SessionRepository(db, File(context.filesDir, "assist-rec-${System.nanoTime()}").apply { mkdirs() })
        val prefs = context.getSharedPreferences("settings-${System.nanoTime()}", Context.MODE_PRIVATE)
        settings = SettingsRepository(prefs, InMemorySecretStore())
        settings.update { it.copy(llmBaseUrl = "http://llm.test/v1", llmModel = "m", liveAssistDefault = true) }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private class ChannelAudioSource(val channel: Channel<ShortArray>) : AudioSource {
        override fun chunks(): Flow<ShortArray> = channel.receiveAsFlow()
    }

    /** A question every three chunks. */
    private class QuestionTranscriber : StreamingTranscriber {
        private var accepted = 0
        private var boundary = 0

        override fun accept(samples: FloatArray): List<TranscriptEvent> {
            accepted++
            if (accepted - boundary < 3) return emptyList()
            val event = TranscriptEvent.Final(
                text = "What did we decide about item number $accepted",
                startMs = boundary * 100L,
                endMs = accepted * 100L
            )
            boundary = accepted
            return listOf(event)
        }

        override fun finish(): List<TranscriptEvent> = emptyList()
        override fun endUtterance(): List<TranscriptEvent> = emptyList()
        override fun close() {}
    }

    private class Factory(private val transcriber: StreamingTranscriber) : TranscriberFactory {
        override fun create(language: String): StreamingTranscriber = transcriber
        override fun needsPreparation(language: String): Boolean = false
        override fun describe(language: String): EngineInfo = EngineInfo("cpu", "asr-$language")
    }

    /** The endpoint went away mid-meeting: the probe passes once, then everything fails or hangs. */
    private class LostEndpoint(private val hang: Boolean) : ChatClient {
        val probes = AtomicInteger(0)
        val calls = AtomicInteger(0)

        override fun stream(messages: List<LlmMessage>): Flow<String> = flow {
            calls.incrementAndGet()
            if (hang) awaitCancellation()
            throw LlmException("Network error")
        }

        override suspend fun listModels(): List<String> {
            if (probes.incrementAndGet() == 1) return listOf("m")
            if (hang) awaitCancellation()
            throw LlmException("Network error")
        }
    }

    private object NoLibrary : AssistSources {
        override suspend fun find(question: String, excludeId: Long, semantic: Boolean) = AssistLibrary(emptyList())
    }

    private suspend fun TestScope.recordWith(client: LostEndpoint): Long {
        val audio = ChannelAudioSource(Channel(Channel.UNLIMITED))
        val controller = RecordingController(
            audioSource = audio,
            transcriberFactory = Factory(QuestionTranscriber()),
            store = repo,
            defaultOptions = { ConversationOptions(language = "en") },
            asrDispatcher = StandardTestDispatcher(testScheduler),
            clock = { testScheduler.currentTime }
        )
        val assistant = LiveAssistant(
            repository = repo,
            sources = NoLibrary,
            chatClient = client,
            settings = settings,
            mark = { note, atMs -> controller.mark(note, atMs) },
            scope = backgroundScope,
            clock = { testScheduler.currentTime }
        )
        assistant.watch(controller.state)
        controller.start(this)
        runCurrent()
        val id = controller.state.value.sessionId
        assertNotNull(id)
        assertTrue("on by default", assistant.state.value.enabled)

        // 30 chunks → 10 questions, a second apart in virtual time.
        repeat(30) {
            audio.channel.trySend(ShortArray(AudioSpec.CHUNK_SAMPLES))
            runCurrent()
            advanceTimeBy(333)
        }
        advanceTimeBy(LiveAssistant.ANSWER_TIMEOUT_MS * 2)
        runCurrent()

        assertEquals(10, controller.state.value.finalLines.size)
        assertEquals(AssistStatus.PAUSED, assistant.state.value.status)
        assertTrue(client.calls.get() >= 1)

        val stopped = async { controller.stop() }
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue("Stop does not wait for the endpoint", stopped.isCompleted)
        assertEquals(id, stopped.await())
        assertEquals(RecordingPhase.IDLE, controller.state.value.phase)
        return id!!
    }

    @Test
    fun anEndpointThatFailsNeverStopsTheRecording() = runTest(dispatcher) {
        val client = LostEndpoint(hang = false)
        val session = repo.getSession(recordWith(client))!!
        assertEquals(10, repo.getSegments(session.id).size)
        assertTrue(session.durationMs > 0)
    }

    @Test
    fun anEndpointThatHangsNeverStopsTheRecording() = runTest(dispatcher) {
        val client = LostEndpoint(hang = true)
        val session = repo.getSession(recordWith(client))!!
        assertEquals(10, repo.getSegments(session.id).size)
        assertTrue(session.durationMs > 0)
    }
}
