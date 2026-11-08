package com.convoscribe.llm

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.TitleSource
import com.convoscribe.data.TranscriptLine
import com.convoscribe.recording.RecordingPhase
import com.convoscribe.recording.RecordingState
import com.convoscribe.settings.InMemorySecretStore
import com.convoscribe.settings.SettingsRepository
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LiveAssistantTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: AppDatabase
    private lateinit var repo: SessionRepository
    private lateinit var settings: SettingsRepository
    private val state = MutableStateFlow(RecordingState())

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        // Transactions inline, queries on the test dispatcher, so virtual time drives everything.
        val syncExecutor = java.util.concurrent.Executor { it.run() }
        val queryExecutor = java.util.concurrent.Executor {
            dispatcher.dispatch(kotlin.coroutines.EmptyCoroutineContext, it)
        }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(queryExecutor)
            .setTransactionExecutor(syncExecutor)
            .build()
        repo = SessionRepository(db, File(context.filesDir, "assist-${System.nanoTime()}").apply { mkdirs() })
        val prefs = context.getSharedPreferences("settings-${System.nanoTime()}", Context.MODE_PRIVATE)
        settings = SettingsRepository(prefs, InMemorySecretStore())
        settings.update { it.copy(llmBaseUrl = "http://llm.test/v1", llmModel = "m") }
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Answers each call with [reply]; [models] is the probe. Records what each call was asked. */
    private class ScriptedChatClient(
        var reply: () -> Flow<String>,
        var models: suspend () -> List<String> = { listOf("m") }
    ) : ChatClient {
        val calls = CopyOnWriteArrayList<List<LlmMessage>>()
        val probes = AtomicInteger(0)

        override fun stream(messages: List<LlmMessage>): Flow<String> {
            calls += messages
            return reply()
        }

        override suspend fun listModels(): List<String> {
            probes.incrementAndGet()
            return models()
        }
    }

    /** Past sessions for every question; records how it was asked. */
    private class FakeSources(var sessions: List<SessionSource> = listOf(PLANNING)) : AssistSources {
        val asked = CopyOnWriteArrayList<Triple<String, Long, Boolean>>()
        var released = 0

        override suspend fun find(question: String, excludeId: Long, semantic: Boolean): AssistLibrary {
            asked += Triple(question, excludeId, semantic)
            return AssistLibrary(sessions)
        }

        override fun release() {
            released++
        }
    }

    private val marks = CopyOnWriteArrayList<Pair<String, Long>>()

    private fun answering(text: String) = ScriptedChatClient({ flow { emit(text) } })

    private fun TestScope.assistant(
        client: ChatClient,
        sources: AssistSources = FakeSources(),
        calls: Mutex = Mutex()
    ): LiveAssistant {
        val assistant = LiveAssistant(
            repository = repo,
            sources = sources,
            chatClient = client,
            settings = settings,
            mark = { note, atMs ->
                marks += note to atMs
                marks.size.toLong()
            },
            scope = backgroundScope,
            calls = calls,
            clock = { testScheduler.currentTime }
        )
        assistant.watch(state)
        runCurrent()
        return assistant
    }

    private suspend fun session(): Long = repo.createSession(
        title = "Launch sync",
        language = "en",
        numSpeakers = null,
        participants = null,
        summaryTemplate = null,
        cleanedWith = null,
        tags = emptyList(),
        titleSource = TitleSource.DEFAULT,
        talkingPoints = emptyList()
    )

    private fun record(id: Long) {
        state.value = RecordingState(phase = RecordingPhase.RECORDING, sessionId = id)
    }

    /** [text] said at [startS]: saved, then shown as a new final line, as the controller does. */
    private suspend fun hear(id: Long, startS: Long, text: String) {
        val startMs = startS * 1_000L
        repo.addSegment(id, startMs = startMs, endMs = startMs + 3_000L, text = text, tokens = emptyList())
        state.update { it.copy(finalLines = it.finalLines + text, elapsedMs = startMs + 3_000L) }
    }

    private fun stop(id: Long) {
        state.value = RecordingState(phase = RecordingPhase.STOPPING, sessionId = id)
        state.value = RecordingState(phase = RecordingPhase.IDLE, lastCompletedSessionId = id)
    }

    /** An assistant on for a recording session. */
    private suspend fun TestScope.recordingWith(assistant: LiveAssistant): Long {
        val id = session()
        record(id)
        runCurrent()
        assistant.setEnabled(true)
        runCurrent()
        return id
    }

    @Test
    fun offByDefault_andNeverOnWithoutALanguageModel() = runTest(dispatcher) {
        val client = answering("NONE")
        val assistant = assistant(client)
        val id = session()
        record(id)
        runCurrent()
        assertEquals(id, assistant.state.value.sessionId)
        assertFalse(assistant.state.value.enabled)

        settings.update { it.copy(llmBaseUrl = "") }
        assistant.setEnabled(true)
        runCurrent()
        assertFalse(assistant.state.value.enabled)
        assertEquals(0, client.probes.get())
    }

    @Test
    fun onByDefault_probesTheEndpointWhenRecordingStarts() = runTest(dispatcher) {
        settings.update { it.copy(liveAssistDefault = true) }
        val client = answering("NONE")
        val assistant = assistant(client)
        val id = session()
        record(id)
        runCurrent()
        assertTrue(assistant.state.value.enabled)
        assertEquals(AssistStatus.IDLE, assistant.state.value.status)
        assertEquals(1, client.probes.get())
    }

    @Test
    fun anUnreachableEndpointLeavesItOff() = runTest(dispatcher) {
        val client = ScriptedChatClient({ flow { emit("NONE") } }, models = { throw LlmException("Network error") })
        val assistant = assistant(client)
        recordingWith(assistant)
        assertFalse(assistant.state.value.enabled)
        assertEquals(AssistStatus.UNREACHABLE, assistant.state.value.status)
    }

    @Test
    fun aProbeThatHangsGivesUpAfterThreeSeconds() = runTest(dispatcher) {
        val client = ScriptedChatClient({ flow { emit("NONE") } }, models = { awaitCancellation() })
        val assistant = assistant(client)
        val id = session()
        record(id)
        runCurrent()
        assistant.setEnabled(true)
        runCurrent()
        assertTrue(assistant.state.value.enabled)
        assertEquals(AssistStatus.CHECKING, assistant.state.value.status)

        advanceTimeBy(LiveAssistant.PROBE_TIMEOUT_MS - 100)
        runCurrent()
        assertEquals(AssistStatus.CHECKING, assistant.state.value.status)
        advanceTimeBy(200)
        runCurrent()
        assertFalse(assistant.state.value.enabled)
        assertEquals(AssistStatus.UNREACHABLE, assistant.state.value.status)
    }

    @Test
    fun aQuestionGetsACardFromPastSessions() = runTest(dispatcher) {
        val client = answering("We set the launch to March 3 (Planning, 2026-10-01, 00:12).")
        val sources = FakeSources()
        val assistant = assistant(client, sources)
        val id = recordingWith(assistant)

        hear(id, 5, "Good morning everyone.")
        hear(id, 10, "What did we agree last time on the launch date")
        runCurrent()

        assertEquals(1, client.calls.size)
        val system = client.calls[0].first().content
        val user = client.calls[0].last().content
        assertTrue(system.startsWith(PromptBuilder.LIVE_ASSIST_SYSTEM))
        assertTrue(system.contains("We set the launch to March 3."))
        assertTrue(user.contains("[00:05] Good morning everyone."))
        assertTrue(user.endsWith("Line just said:\nWhat did we agree last time on the launch date"))
        assertEquals(Triple("What did we agree last time on the launch date", id, false), sources.asked.single())

        val card = assistant.state.value.cards.single()
        assertEquals("What did we agree last time on the launch date", card.question)
        assertEquals("We set the launch to March 3.", card.answer)
        assertEquals(PLANNING.id, card.cites.single().sessionId)
        assertEquals(10_000L, card.questionAtMs)
    }

    @Test
    fun earlierInThisSessionIsAnExcerptAndTheLastTwoMinutesAreRecent() = runTest(dispatcher) {
        val client = answering("NONE")
        val assistant = assistant(client, FakeSources(emptyList()))
        val id = recordingWith(assistant)
        hear(id, 10, "The budget is forty thousand euros for the launch.")
        hear(id, 200, "Now about the hiring plan.")
        hear(id, 260, "How much is the budget for the launch")
        runCurrent()

        val system = client.calls.single().first().content
        val user = client.calls.single().last().content
        assertTrue(system.contains("## Launch sync"))
        assertTrue(system.contains("The budget is forty thousand euros"))
        assertFalse(user.contains("forty thousand"))
        assertTrue(user.contains("[03:20] Now about the hiring plan."))
    }

    @Test
    fun statementsAreNotSent() = runTest(dispatcher) {
        val client = answering("NONE")
        val assistant = assistant(client)
        val id = recordingWith(assistant)
        hear(id, 5, "We will meet again tomorrow at ten.")
        hear(id, 9, "We said Tuesday for the launch, right?")
        runCurrent()
        assertEquals(0, client.calls.size)
        assertTrue(assistant.state.value.cards.isEmpty())
    }

    @Test
    fun noneAndEmptyAnswersShowNothing() = runTest(dispatcher) {
        val client = answering("NONE.")
        val assistant = assistant(client)
        val id = recordingWith(assistant)
        hear(id, 5, "What do you think about the new logo")
        runCurrent()
        client.reply = { flow { emit("<think>maybe</think>\n") } }
        hear(id, 30, "Who is presenting at the town hall")
        runCurrent()
        assertEquals(2, client.calls.size)
        assertTrue(assistant.state.value.cards.isEmpty())
        assertEquals(AssistStatus.IDLE, assistant.state.value.status)
    }

    @Test
    fun oneQuestionAtATime() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val client = ScriptedChatClient({
            flow {
                gate.await()
                emit("March 3.")
            }
        })
        val assistant = assistant(client)
        val id = recordingWith(assistant)
        hear(id, 5, "When is the launch date again")
        runCurrent()
        hear(id, 8, "Who signs off on the budget this year")
        runCurrent()
        assertEquals(1, client.calls.size)

        gate.complete(Unit)
        runCurrent()
        assertEquals(1, assistant.state.value.cards.size)
        assertEquals(1, client.calls.size)
    }

    @Test
    fun atMostOneCardEveryTwentySeconds() = runTest(dispatcher) {
        val client = answering("March 3.")
        val assistant = assistant(client)
        val id = recordingWith(assistant)
        hear(id, 5, "When is the launch date again")
        runCurrent()
        assertEquals(1, assistant.state.value.cards.size)

        advanceTimeBy(LiveAssistant.CARD_SPACING_MS - 1_000)
        hear(id, 20, "Who signs off on the budget this year")
        runCurrent()
        assertEquals(1, client.calls.size)

        advanceTimeBy(2_000)
        hear(id, 25, "Who signs off on the hiring plan this year")
        runCurrent()
        assertEquals(2, client.calls.size)
        assertEquals(2, assistant.state.value.cards.size)
        assertEquals("Who signs off on the hiring plan this year", assistant.state.value.cards.first().question)
    }

    @Test
    fun aNoneAnswerDoesNotHoldBackTheNextQuestion() = runTest(dispatcher) {
        val client = answering("NONE")
        val assistant = assistant(client)
        val id = recordingWith(assistant)
        hear(id, 5, "When is the launch date again")
        runCurrent()
        client.reply = { flow { emit("March 3.") } }
        hear(id, 7, "Who signs off on the budget this year")
        runCurrent()
        assertEquals(2, client.calls.size)
        assertEquals(1, assistant.state.value.cards.size)
    }

    @Test
    fun atMostThreeCardsNewestFirst() = runTest(dispatcher) {
        val client = answering("Yes.")
        val assistant = assistant(client)
        val id = recordingWith(assistant)
        for (i in 1..4) {
            hear(id, i * 30L, "Did we book room number $i for Friday")
            runCurrent()
            advanceTimeBy(LiveAssistant.CARD_SPACING_MS)
        }
        val cards = assistant.state.value.cards
        assertEquals(LiveAssistant.MAX_CARDS, cards.size)
        assertEquals("Did we book room number 4 for Friday", cards.first().question)
    }

    @Test
    fun aQuestionGivesUpAfterFifteenSecondsIncludingTheWaitForTheLock() = runTest(dispatcher) {
        val calls = Mutex()
        calls.lock()
        val client = answering("March 3.")
        val assistant = assistant(client, calls = calls)
        val id = recordingWith(assistant)
        hear(id, 5, "When is the launch date again")
        runCurrent()
        advanceTimeBy(LiveAssistant.ANSWER_TIMEOUT_MS + 1)
        runCurrent()
        calls.unlock()
        runCurrent()
        assertEquals(0, client.calls.size)
        assertTrue(assistant.state.value.cards.isEmpty())
        // The endpoint still answers: nothing to tell.
        assertEquals(AssistStatus.IDLE, assistant.state.value.status)

        client.reply = { flow { awaitCancellation() } }
        hear(id, 10, "Who signs off on the budget this year")
        runCurrent()
        advanceTimeBy(LiveAssistant.ANSWER_TIMEOUT_MS + 1)
        runCurrent()
        assertEquals(1, client.calls.size)
        assertTrue(assistant.state.value.cards.isEmpty())
    }

    @Test
    fun aLostEndpointPausesUntilTheNextQuestionFindsItAgain() = runTest(dispatcher) {
        val client = answering("March 3.")
        val assistant = assistant(client)
        val id = recordingWith(assistant)
        client.reply = { flow { throw LlmException("Network error") } }
        client.models = { throw LlmException("Network error") }
        hear(id, 5, "When is the launch date again")
        runCurrent()
        assertEquals(AssistStatus.PAUSED, assistant.state.value.status)
        assertTrue(assistant.state.value.enabled)

        // Still gone: the next question only probes.
        hear(id, 10, "Who signs off on the budget this year")
        runCurrent()
        assertEquals(1, client.calls.size)
        assertEquals(AssistStatus.PAUSED, assistant.state.value.status)

        client.models = { listOf("m") }
        client.reply = { flow { emit("Marie does.") } }
        hear(id, 15, "Who signs off on the budget this year")
        runCurrent()
        assertEquals(AssistStatus.IDLE, assistant.state.value.status)
        assertEquals("Marie does.", assistant.state.value.cards.single().answer)
    }

    @Test
    fun pinningMarksTheQuestionWithItsAnswer() = runTest(dispatcher) {
        val client = answering("March 3 (Planning, 2026-10-01, 00:12).")
        val assistant = assistant(client)
        val id = recordingWith(assistant)
        hear(id, 42, "When is the launch date again")
        runCurrent()
        val card = assistant.state.value.cards.single()

        assistant.pin(card.id)
        runCurrent()
        assertEquals(listOf("Q: When is the launch date again\nA: March 3." to 42_000L), marks)
        assertTrue(assistant.state.value.cards.isEmpty())
    }

    @Test
    fun dismissingDropsTheCard() = runTest(dispatcher) {
        val assistant = assistant(answering("March 3."))
        val id = recordingWith(assistant)
        hear(id, 5, "When is the launch date again")
        runCurrent()
        assistant.dismiss(assistant.state.value.cards.single().id)
        assertTrue(assistant.state.value.cards.isEmpty())
        assertTrue(marks.isEmpty())
    }

    @Test
    fun stopDropsUnpinnedCardsAndReleasesTheSources() = runTest(dispatcher) {
        val sources = FakeSources()
        val assistant = assistant(answering("March 3."), sources)
        val id = recordingWith(assistant)
        hear(id, 5, "When is the launch date again")
        runCurrent()
        assertEquals(1, assistant.state.value.cards.size)

        stop(id)
        runCurrent()
        assertNull(assistant.state.value.sessionId)
        assertTrue(assistant.state.value.cards.isEmpty())
        assertFalse(assistant.state.value.enabled)
        assertEquals(1, sources.released)
        assertTrue(marks.isEmpty())
    }

    @Test
    fun turningItOffDropsAnAnswerOnItsWay() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val client = ScriptedChatClient({
            flow {
                gate.await()
                emit("March 3.")
            }
        })
        val assistant = assistant(client)
        val id = recordingWith(assistant)
        hear(id, 5, "When is the launch date again")
        runCurrent()
        assistant.setEnabled(false)
        gate.complete(Unit)
        runCurrent()
        assertTrue(assistant.state.value.cards.isEmpty())

        hear(id, 10, "Who signs off on the budget this year")
        runCurrent()
        assertEquals("off: nothing is sent", 1, client.calls.size)
    }

    @Test
    fun meaningIsUsedOnlyWhenSwitchedOn() = runTest(dispatcher) {
        val sources = FakeSources()
        val assistant = assistant(answering("NONE"), sources)
        val id = recordingWith(assistant)
        hear(id, 5, "When is the launch date again")
        runCurrent()
        settings.update { it.copy(liveAssistSemantic = true) }
        hear(id, 10, "Who signs off on the budget this year")
        runCurrent()
        assertEquals(listOf(false, true), sources.asked.map { it.third })
    }

    @Test
    fun splitKeepsTheLastTwoMinutesAsRecent() {
        val lines = listOf(
            TranscriptLine(0L, null, "a", endMs = 3_000L),
            TranscriptLine(60_000L, null, "b", endMs = 63_000L),
            TranscriptLine(200_000L, null, "c", endMs = 203_000L)
        )
        val (earlier, recent) = LiveAssistant.split(lines)
        assertEquals(listOf("a", "b"), earlier.map { it.text })
        assertEquals(listOf("c"), recent.map { it.text })
        assertEquals(emptyList<TranscriptLine>() to emptyList<TranscriptLine>(), LiveAssistant.split(emptyList()))
    }

    private companion object {
        val PLANNING = SessionSource(
            id = 900L,
            title = "Planning",
            createdAt = java.time.LocalDate.of(2026, 10, 1).atTime(12, 0)
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            language = "en",
            lines = listOf(TranscriptLine(12_000L, "Marie", "We set the launch to March 3.", id = 77L, endMs = 15_000L))
        )
    }
}
