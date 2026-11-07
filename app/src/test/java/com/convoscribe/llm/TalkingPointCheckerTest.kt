package com.convoscribe.llm

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.TalkingPointEntity
import com.convoscribe.data.TitleSource
import com.convoscribe.decision.DecisionAnswer
import com.convoscribe.decision.DecisionClient
import com.convoscribe.decision.DecisionQuestion
import com.convoscribe.recording.RecordingPhase
import com.convoscribe.recording.RecordingState
import com.convoscribe.settings.InMemorySecretStore
import com.convoscribe.settings.SettingsRepository
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TalkingPointCheckerTest {

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
        repo = SessionRepository(db, File(context.filesDir, "points-${System.nanoTime()}").apply { mkdirs() })
        val prefs = context.getSharedPreferences("settings-${System.nanoTime()}", Context.MODE_PRIVATE)
        settings = SettingsRepository(prefs, InMemorySecretStore())
        settings.update { it.copy(llmBaseUrl = "http://llm.test/v1", llmModel = "m", talkingPointCheckSeconds = 15) }
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Answers each call with [reply]; records what each call was asked. */
    private class ScriptedChatClient(var reply: () -> Flow<String>) : ChatClient {
        val calls = CopyOnWriteArrayList<List<LlmMessage>>()
        val inFlight = AtomicInteger(0)
        var mostInFlight = 0

        override fun stream(messages: List<LlmMessage>): Flow<String> {
            calls += messages
            val answer = reply()
            return flow {
                mostInFlight = maxOf(mostInFlight, inFlight.incrementAndGet())
                try {
                    answer.collect { emit(it) }
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }

        override suspend fun listModels(): List<String> = emptyList()
    }

    /**
     * A decision service that, for each line, answers point [byWord]'s
     * number where the line contains that word (with [probability]), and
     * "none" otherwise; records what each call was asked.
     */
    private class ScriptedDecisionClient(
        var byWord: Map<String, Int> = emptyMap(),
        var probability: Double = 0.9,
        var fail: Throwable? = null
    ) : DecisionClient {
        val calls = CopyOnWriteArrayList<Pair<List<String>, Map<String, DecisionQuestion>>>()

        override suspend fun decide(
            states: List<String>,
            questions: Map<String, DecisionQuestion>
        ): List<Map<String, DecisionAnswer>> {
            calls += states to questions
            fail?.let { throw it }
            return states.map { state ->
                questions.mapValues { (_, q) ->
                    val number = byWord.entries
                        .firstOrNull { (word, n) -> state.contains(word) && "$n" in q.options }
                        ?.value
                    val choice = number?.toString() ?: DecisionTalkingPoints.NONE_KEY
                    DecisionAnswer(choice, q.options.keys.associateWith { if (it == choice) probability else 0.01 })
                }
            }
        }

        override suspend fun ping(): String = "english"
    }

    private fun answering(text: String) = ScriptedChatClient { flow { emit(text) } }

    private fun failing() = ScriptedChatClient { flow { throw LlmException("HTTP 502 from llm") } }

    private fun TestScope.checker(
        client: ChatClient,
        calls: Mutex = Mutex(),
        decision: DecisionClient = ScriptedDecisionClient()
    ): TalkingPointChecker {
        val checker = TalkingPointChecker(repo, client, decision, settings, backgroundScope, calls, timeoutMs = 60_000L)
        checker.watch(state)
        runCurrent()
        return checker
    }

    private suspend fun session(points: List<String> = listOf("Budget", "Hiring", "Office move")): Long =
        repo.createSession(
            title = "Weekly",
            language = "en",
            numSpeakers = null,
            participants = null,
            summaryTemplate = null,
            cleanedWith = null,
            tags = emptyList(),
            titleSource = TitleSource.DEFAULT,
            talkingPoints = points
        )

    private suspend fun say(id: Long, startS: Long, text: String) {
        val startMs = startS * 1_000L
        repo.addSegment(id, startMs = startMs, endMs = startMs + 4_000L, text = text, tokens = emptyList())
    }

    private fun record(id: Long, elapsedMs: Long = 0L) {
        state.value = RecordingState(phase = RecordingPhase.RECORDING, sessionId = id, elapsedMs = elapsedMs)
    }

    private fun pause(id: Long) {
        state.value = RecordingState(phase = RecordingPhase.PAUSED, sessionId = id)
    }

    private fun stop(id: Long) {
        state.value = RecordingState(phase = RecordingPhase.STOPPING, sessionId = id)
        state.value = RecordingState(phase = RecordingPhase.IDLE, lastCompletedSessionId = id)
    }

    private suspend fun points(id: Long): List<TalkingPointEntity> = repo.getTalkingPoints(id)

    private fun userTurn(messages: List<LlmMessage>) = messages.last().content

    @Test
    fun coveredPointsTickThemselvesAfterAnInterval() = runTest(dispatcher) {
        val client = answering("""[{"point": 2, "at": "00:10"}]""")
        checker(client)
        val id = session()
        say(id, 5, "Let's talk money.")
        say(id, 10, "We will hire two people.")
        record(id)
        runCurrent()

        advanceTimeBy(14_000L)
        runCurrent()
        assertEquals(0, client.calls.size)

        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(1, client.calls.size)
        assertTrue(userTurn(client.calls[0]).contains("1. Budget\n2. Hiring\n3. Office move"))
        assertTrue(userTurn(client.calls[0]).contains("[00:10] We will hire two people."))
        val hiring = points(id)[1]
        assertTrue(hiring.covered)
        assertEquals(10_000L, hiring.coveredAtMs)
        assertFalse(hiring.byUser)
        assertFalse(points(id)[0].covered)
    }

    @Test
    fun theNextCheckSendsOnlyTheOpenPointsAndTheNewSpeech() = runTest(dispatcher) {
        val client = answering("""[{"point": 1}]""")
        checker(client)
        val id = session(listOf("Budget", "Hiring"))
        say(id, 5, "Money first.")
        record(id)
        advanceTimeBy(15_500L)
        runCurrent()
        assertTrue(points(id)[0].covered)

        // Nothing new said: no call.
        advanceTimeBy(15_000L)
        runCurrent()
        assertEquals(1, client.calls.size)

        say(id, 60, "Now hiring.")
        client.reply = { flow { emit("[]") } }
        advanceTimeBy(15_000L)
        runCurrent()
        assertEquals(2, client.calls.size)
        val second = userTurn(client.calls[1])
        assertTrue(second.contains("1. Hiring"))
        assertFalse(second.contains("Budget"))
        assertTrue(second.contains("Now hiring."))
    }

    @Test
    fun aPointSetByHandIsNeitherSentNorChanged() = runTest(dispatcher) {
        val client = answering("[1, 2]")
        checker(client)
        val id = session(listOf("Budget", "Hiring"))
        val budget = points(id)[0]
        repo.setTalkingPointCovered(budget.id, covered = true, atMs = 1_000L)
        repo.setTalkingPointCovered(budget.id, covered = false)
        say(id, 5, "Budget and hiring.")
        record(id)
        advanceTimeBy(15_500L)
        runCurrent()

        assertTrue(userTurn(client.calls.single()).contains("1. Hiring"))
        assertFalse(userTurn(client.calls.single()).contains("Budget\n"))
        assertFalse(points(id)[0].covered)
        assertTrue(points(id)[1].covered)
    }

    @Test
    fun aHandTickDuringTheRequestWins() = runTest(dispatcher) {
        val id = session(listOf("Budget"))
        val budget = points(id)[0]
        val client = ScriptedChatClient {
            flow {
                // The user unticks it by hand while the model is answering.
                repo.setTalkingPointCovered(budget.id, covered = false)
                emit("[1]")
            }
        }
        checker(client)
        say(id, 5, "Budget.")
        record(id)
        advanceTimeBy(15_500L)
        runCurrent()

        assertEquals(1, client.calls.size)
        assertFalse(points(id)[0].covered)
        assertTrue(points(id)[0].byUser)
    }

    @Test
    fun nothingIsSentWithoutALanguageModelWithoutPointsOrWhenOff() = runTest(dispatcher) {
        val client = answering("[1]")
        checker(client)
        val noPoints = session(emptyList())
        say(noPoints, 5, "Hello.")
        record(noPoints)
        advanceTimeBy(60_000L)
        runCurrent()
        stop(noPoints)
        runCurrent()
        assertEquals(0, client.calls.size)

        settings.update { it.copy(talkingPointCheckSeconds = 0) }
        val off = session()
        say(off, 5, "Budget.")
        record(off)
        advanceTimeBy(60_000L)
        runCurrent()
        stop(off)
        runCurrent()
        assertEquals(0, client.calls.size)

        settings.update { it.copy(talkingPointCheckSeconds = 15, llmBaseUrl = "") }
        val noLlm = session()
        say(noLlm, 5, "Budget.")
        record(noLlm)
        advanceTimeBy(60_000L)
        runCurrent()
        stop(noLlm)
        runCurrent()
        assertEquals(0, client.calls.size)
        assertTrue(points(noLlm).none { it.covered })
    }

    @Test
    fun turningTheIntervalOnWhileRecordingStartsChecking() = runTest(dispatcher) {
        settings.update { it.copy(talkingPointCheckSeconds = 0) }
        val client = answering("[]")
        checker(client)
        val id = session()
        say(id, 5, "Budget.")
        record(id)
        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(0, client.calls.size)

        settings.update { it.copy(talkingPointCheckSeconds = 10) }
        advanceTimeBy(5_000L + 10_000L + 500L)
        runCurrent()
        assertEquals(1, client.calls.size)
    }

    @Test
    fun pauseStopsCheckingAndResumeContinues() = runTest(dispatcher) {
        val client = answering("[]")
        checker(client)
        val id = session()
        say(id, 5, "Budget.")
        record(id)
        advanceTimeBy(10_000L)
        runCurrent()
        pause(id)
        runCurrent()
        advanceTimeBy(120_000L)
        runCurrent()
        assertEquals(0, client.calls.size)

        record(id, elapsedMs = 10_000L)
        runCurrent()
        advanceTimeBy(15_500L)
        runCurrent()
        assertEquals(1, client.calls.size)
    }

    @Test
    fun afterStopOneMoreCheckCoversTheSpeechSinceTheLastOne() = runTest(dispatcher) {
        val client = answering("[]")
        checker(client)
        val id = session()
        say(id, 5, "First words.")
        record(id)
        advanceTimeBy(15_500L)
        runCurrent()
        assertEquals(1, client.calls.size)

        say(id, 100, "Last words.")
        stop(id)
        runCurrent()
        runCurrent()
        assertEquals(2, client.calls.size)
        assertTrue(userTurn(client.calls[1]).contains("[01:40] Last words."))

        // No more checks once the session is over.
        advanceTimeBy(120_000L)
        runCurrent()
        assertEquals(2, client.calls.size)
    }

    @Test
    fun aContinuedSessionChecksOnlyItsNewSpeech() = runTest(dispatcher) {
        val client = answering("[]")
        checker(client)
        val id = session()
        say(id, 5, "Said last time.")
        say(id, 70, "Said now.")
        record(id, elapsedMs = 60_000L)
        advanceTimeBy(15_500L)
        runCurrent()

        val user = userTurn(client.calls.single())
        assertTrue(user.contains("Said now."))
        assertFalse(user.contains("Said last time."))
    }

    @Test
    fun neverMoreThanOneRequestInFlight() = runTest(dispatcher) {
        val lock = Mutex()
        val client = ScriptedChatClient {
            flow {
                kotlinx.coroutines.delay(40_000L)
                emit("[]")
            }
        }
        checker(client, lock)
        val id = session()
        say(id, 5, "Budget.")
        record(id)
        advanceTimeBy(15_500L)
        runCurrent()
        say(id, 30, "More.")
        stop(id)
        // The final check runs in the background: advance through its answer.
        advanceTimeBy(60_000L)
        runCurrent()

        assertEquals(1, client.mostInFlight)
        assertEquals(2, client.calls.size)
    }

    @Test
    fun theLockIsSharedWithOtherLlmWork() = runTest(dispatcher) {
        val lock = Mutex()
        val client = answering("[]")
        checker(client, lock)
        val id = session()
        say(id, 5, "Budget.")
        lock.lock()
        record(id)
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(0, client.calls.size)
        lock.unlock()
        runCurrent()
        assertEquals(1, client.calls.size)
    }

    @Test
    fun oneFailureIsQuietTwoInARowShowTheNoteAndSuccessClearsIt() = runTest(dispatcher) {
        val client = failing()
        val checker = checker(client)
        val id = session()
        say(id, 5, "Budget.")
        record(id)
        advanceTimeBy(15_500L)
        runCurrent()
        assertEquals(1, client.calls.size)
        assertEquals(emptySet<Long>(), checker.failing.value)

        // Backoff: the next check waits 30 s, not 15.
        advanceTimeBy(15_000L)
        runCurrent()
        assertEquals(1, client.calls.size)
        advanceTimeBy(15_000L)
        runCurrent()
        assertEquals(2, client.calls.size)
        assertEquals(setOf(id), checker.failing.value)
        // The same speech is checked again.
        assertTrue(userTurn(client.calls[1]).contains("Budget."))

        client.reply = { flow { emit("[1]") } }
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(3, client.calls.size)
        assertEquals(emptySet<Long>(), checker.failing.value)
        assertTrue(points(id)[0].covered)

        // Back to the interval after a success.
        say(id, 100, "Hiring.")
        client.reply = { flow { emit("[]") } }
        advanceTimeBy(15_500L)
        runCurrent()
        assertEquals(4, client.calls.size)
    }

    @Test
    fun anUnreadableAnswerOrATimeoutIsAFailure() = runTest(dispatcher) {
        val client = answering("Points 1 and 2, I think.")
        val checker = checker(client)
        val id = session()
        say(id, 5, "Budget.")
        record(id)
        advanceTimeBy(15_500L)
        runCurrent()
        assertTrue(points(id).none { it.covered })

        client.reply = { flow { awaitCancellation() } }
        advanceTimeBy(30_000L + 60_000L)
        runCurrent()
        assertEquals(2, client.calls.size)
        assertEquals(setOf(id), checker.failing.value)
    }

    @Test
    fun aFailedCheckAtStopShowsNothingAndRecordingNeverSeesIt() = runTest(dispatcher) {
        val client = ScriptedChatClient { flow { throw IOException("connection reset") } }
        val checker = checker(client)
        val id = session()
        say(id, 5, "Budget.")
        record(id)
        runCurrent()
        stop(id)
        runCurrent()
        assertEquals(1, client.calls.size)
        assertEquals(emptySet<Long>(), checker.failing.value)
        assertEquals(RecordingPhase.IDLE, state.value.phase)
    }

    private fun useDecisionService() {
        settings.update { it.copy(decisionBaseUrl = "http://laya.test:8080") }
    }

    @Test
    fun withADecisionService_pointsTickAtTheLineWhereTheyCameUp() = runTest(dispatcher) {
        useDecisionService()
        val chat = answering("[1, 2, 3]")
        val decision = ScriptedDecisionClient(byWord = mapOf("hire" to 2))
        checker(chat, decision = decision)
        val id = session()
        say(id, 5, "Let's talk money.")
        say(id, 10, "We will hire two people.")
        record(id)
        advanceTimeBy(15_500L)
        runCurrent()

        assertEquals(0, chat.calls.size)
        val (states, questions) = decision.calls.single()
        assertEquals(listOf("[00:05] Let's talk money.", "[00:10] We will hire two people."), states)
        val options = questions.values.single().options
        assertEquals(listOf("1", "2", "3", "none"), options.keys.toList())
        assertEquals("Hiring", options["2"])
        val hiring = points(id)[1]
        assertTrue(hiring.covered)
        assertEquals(10_000L, hiring.coveredAtMs)
        assertFalse(points(id)[0].covered)
        assertFalse(points(id)[2].covered)
    }

    @Test
    fun withADecisionService_theTickedPointsFillTheQuestion_butAreNotTickedAgain() = runTest(dispatcher) {
        useDecisionService()
        // Option 1 is the open point (Hiring), option 2 the ticked one (Budget).
        val decision = ScriptedDecisionClient(byWord = mapOf("hire" to 1, "money" to 2))
        checker(answering("[]"), decision = decision)
        val id = session(listOf("Budget", "Hiring"))
        repo.setTalkingPointCovered(points(id)[0].id, covered = true, atMs = 1_000L)
        say(id, 5, "Let's talk money.")
        say(id, 10, "We will hire two people.")
        record(id)
        advanceTimeBy(15_500L)
        runCurrent()

        val options = decision.calls.single().second.values.single().options
        assertEquals(mapOf("1" to "Hiring", "2" to "Budget", "none" to DecisionTalkingPoints.NONE_TEXT), options)
        val (budget, hiring) = points(id)
        assertEquals(1_000L, budget.coveredAtMs)
        assertEquals(10_000L, hiring.coveredAtMs)
    }

    @Test
    fun withADecisionService_anUnsureAnswerTicksNothing() = runTest(dispatcher) {
        useDecisionService()
        val decision = ScriptedDecisionClient(byWord = mapOf("hire" to 2), probability = 0.4)
        checker(answering("[]"), decision = decision)
        val id = session()
        say(id, 10, "We will hire two people.")
        record(id)
        advanceTimeBy(15_500L)
        runCurrent()

        assertEquals(1, decision.calls.size)
        assertTrue(points(id).none { it.covered })
    }

    @Test
    fun withADecisionService_noLanguageModelIsNeeded() = runTest(dispatcher) {
        settings.update { it.copy(llmBaseUrl = "", decisionBaseUrl = "http://laya.test:8080") }
        val decision = ScriptedDecisionClient(byWord = mapOf("Budget" to 1))
        checker(answering("[]"), decision = decision)
        val id = session()
        say(id, 5, "Budget first.")
        record(id)
        advanceTimeBy(15_500L)
        runCurrent()

        assertTrue(points(id)[0].covered)
    }

    @Test
    fun optedOutOfTheDecisionService_theLanguageModelChecks() = runTest(dispatcher) {
        settings.update { it.copy(decisionBaseUrl = "http://laya.test:8080", decisionForTalkingPoints = false) }
        val chat = answering("[1]")
        val decision = ScriptedDecisionClient()
        checker(chat, decision = decision)
        val id = session()
        say(id, 5, "Budget first.")
        record(id)
        advanceTimeBy(15_500L)
        runCurrent()

        assertEquals(1, chat.calls.size)
        assertEquals(0, decision.calls.size)
        assertTrue(points(id)[0].covered)
    }

    @Test
    fun withADecisionService_theLanguageModelLockIsNotTaken() = runTest(dispatcher) {
        useDecisionService()
        val lock = Mutex()
        val decision = ScriptedDecisionClient()
        checker(answering("[]"), lock, decision)
        val id = session()
        say(id, 5, "Budget.")
        lock.lock()
        record(id)
        advanceTimeBy(15_500L)
        runCurrent()
        assertEquals(1, decision.calls.size)
        lock.unlock()
    }

    @Test
    fun withADecisionService_failuresBackOffAndShowTheNote_likeTheLanguageModel() = runTest(dispatcher) {
        useDecisionService()
        val chat = answering("[1]")
        val decision = ScriptedDecisionClient(byWord = mapOf("Budget" to 1), fail = LlmException("HTTP 502 from laya"))
        val checker = checker(chat, decision = decision)
        val id = session()
        say(id, 5, "Budget.")
        record(id)
        advanceTimeBy(15_500L)
        runCurrent()
        assertEquals(1, decision.calls.size)
        assertEquals(emptySet<Long>(), checker.failing.value)

        advanceTimeBy(30_000L)
        runCurrent()
        assertEquals(2, decision.calls.size)
        assertEquals(setOf(id), checker.failing.value)
        // Never handed to the language model.
        assertEquals(0, chat.calls.size)

        decision.fail = null
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(emptySet<Long>(), checker.failing.value)
        assertTrue(points(id)[0].covered)
    }

    @Test
    fun waitDoublesPerFailureUpToFiveMinutes() {
        assertEquals(15_000L, TalkingPointChecker.waitMs(15_000L, 0))
        assertEquals(30_000L, TalkingPointChecker.waitMs(15_000L, 1))
        assertEquals(60_000L, TalkingPointChecker.waitMs(15_000L, 2))
        assertEquals(300_000L, TalkingPointChecker.waitMs(15_000L, 10))
        assertEquals(300_000L, TalkingPointChecker.waitMs(300_000L, 3))
    }
}
