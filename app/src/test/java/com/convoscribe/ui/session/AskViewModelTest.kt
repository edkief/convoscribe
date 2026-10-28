package com.convoscribe.ui.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.MessageKind
import com.convoscribe.data.MessageRole
import com.convoscribe.data.SessionRepository
import com.convoscribe.llm.ChatClient
import com.convoscribe.llm.LlmException
import com.convoscribe.llm.LlmMessage
import com.convoscribe.llm.PromptBuilder
import com.convoscribe.settings.InMemorySecretStore
import com.convoscribe.settings.SettingsRepository
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [AskViewModel]. The repository is real (the
 * ViewModel reads segments, speakers and chat messages out of
 * Room) but the database is an in-memory Room under Robolectric,
 * so nothing touches the device. The LLM is a [FakeChatClient]
 * that records every `stream()` call and replays a configured
 * token sequence (or throws a configured exception).
 *
 * `Dispatchers.setMain(...)` is mandatory because the ViewModel's
 * `viewModelScope` is hard-wired to `Main.immediate`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AskViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var recordingsDir: File
    private lateinit var repo: SessionRepository
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var secrets: InMemorySecretStore

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        // Both Room executors run synchronously on the calling
        // thread. Without this, `advanceUntilIdle()` never
        // observes the suspend DAO calls (`getSegments`,
        // `getSpeakers`, `addMessage`) because they would be
        // dispatched to Room's background executor pool, which
        // the test scheduler does not drive.
        val syncExecutor = java.util.concurrent.Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(syncExecutor)
            .setTransactionExecutor(syncExecutor)
            .build()
        recordingsDir = File(context.filesDir, "ask-vm-test-${System.nanoTime()}")
        recordingsDir.mkdirs()
        repo = SessionRepository(db, recordingsDir)
        prefs = context.getSharedPreferences(
            "ask-vm-test-${System.nanoTime()}",
            Context.MODE_PRIVATE
        )
        secrets = InMemorySecretStore()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
        if (recordingsDir.exists()) {
            recordingsDir.deleteRecursively()
        }
    }

    private fun newSettings(): SettingsRepository = SettingsRepository(prefs, secrets)

    private fun configureSettings(repo: SettingsRepository) {
        repo.update { previous ->
            previous.copy(
                llmBaseUrl = "http://example.test/v1",
                llmModel = "m",
                maxTranscriptChars = 24_000
            )
        }
    }

    /**
     * Populate [sessionId] with two segments and a single-speaker
     * diarization so the transcript-formatter produces a
     * non-blank string. The text is fixed so the assertions can
     * compare against `PromptBuilder.question` exactly.
     */
    private suspend fun seedTranscript(sessionId: Long, text: String = "hello world") {
        repo.addSegment(sessionId = sessionId, startMs = 0L, endMs = 1_000L, text = text)
        repo.addSegment(sessionId = sessionId, startMs = 1_000L, endMs = 2_000L, text = "and again")
        val segments = repo.getSegments(sessionId)
        repo.applyDiarization(
            sessionId = sessionId,
            assignments = mapOf(segments[0].id to 0, segments[1].id to 0),
            speakerCount = 1
        )
    }

    @Test
    fun ask_savesUserThenAssistantInOrder() = runTest(dispatcher) {
        val id = repo.createSession(title = "demo", language = "en")
        seedTranscript(id)
        val settings = newSettings().also { configureSettings(it) }
        val client = FakeChatClient(tokens = listOf("Bob"))
        val vm = AskViewModel(
            sessionId = id,
            repository = repo,
            chatClient = client,
            settings = settings
        )

        vm.ask("Who decided?")
        advanceUntilIdle()

        val state = vm.state.first { !it.running && it.streaming.isEmpty() && it.messages.size == 2 }
        assertFalse(state.running)
        assertEquals(2, state.messages.size)
        assertEquals(MessageRole.USER, state.messages[0].role)
        assertEquals("Who decided?", state.messages[0].content)
        assertEquals(MessageRole.ASSISTANT, state.messages[1].role)
        assertEquals("Bob", state.messages[1].content)

        // Persisted rows match the state ordering.
        val rows = db.chatMessageDao().observe(id, MessageKind.QA).first()
        assertEquals(2, rows.size)
        assertEquals(MessageRole.USER, rows[0].role)
        assertEquals("Who decided?", rows[0].content)
        assertEquals(MessageRole.ASSISTANT, rows[1].role)
        assertEquals("Bob", rows[1].content)
    }

    @Test
    fun secondQuestion_promptContainsFirstQAAsHistory() = runTest(dispatcher) {
        val id = repo.createSession(title = "demo", language = "en")
        seedTranscript(id)
        val settings = newSettings().also { configureSettings(it) }
        val client = FakeChatClient(tokens = listOf("first"))
        val vm = AskViewModel(
            sessionId = id,
            repository = repo,
            chatClient = client,
            settings = settings
        )

        // First question — no history yet.
        vm.ask("first Q")
        advanceUntilIdle()
        val stateAfterFirst = vm.state.first { it.messages.size == 2 && !it.running }
        assertEquals(1, client.streamCalls.get())
        val firstCallMessages = client.lastMessages
        assertNotNull(firstCallMessages)
        // The prompt for the first question is exactly what
        // PromptBuilder.question would build with an empty
        // history.
        val transcript = expectedTranscriptFor(id)
        val expectedFirst = PromptBuilder.question(
            transcript = transcript,
            maxChars = settings.settings.value.maxTranscriptChars,
            history = emptyList(),
            question = "first Q"
        )
        assertEquals(expectedFirst, firstCallMessages)

        // Swap the token list for the second round so the
        // assistant message content is "second" and the history
        // assertion is unambiguous.
        client.replaceTokens(listOf("second"))
        vm.ask("second Q")
        advanceUntilIdle()
        val stateAfterSecond = vm.state.first { it.messages.size == 4 && !it.running }
        assertEquals(2, client.streamCalls.get())
        val secondCallMessages = client.lastMessages
        assertNotNull(secondCallMessages)
        val expectedSecond = PromptBuilder.question(
            transcript = transcript,
            maxChars = settings.settings.value.maxTranscriptChars,
            history = listOf(
                LlmMessage(LlmMessage.ROLE_USER, "first Q"),
                LlmMessage(LlmMessage.ROLE_ASSISTANT, "first")
            ),
            question = "second Q"
        )
        assertEquals(expectedSecond, secondCallMessages)
    }

    @Test
    fun llmException_keepsUserMessageSetsErrorRetrySucceedsWithoutDup() = runTest(dispatcher) {
        val id = repo.createSession(title = "demo", language = "en")
        seedTranscript(id)
        val settings = newSettings().also { configureSettings(it) }
        val client = FakeChatClient(tokens = listOf("unused"))
        val vm = AskViewModel(
            sessionId = id,
            repository = repo,
            chatClient = client,
            settings = settings
        )

        // First call throws — the USER row must survive in the
        // DB and no ASSISTANT row is added.
        client.replaceError(LlmException("HTTP 500: x"))
        vm.ask("retry me")
        advanceUntilIdle()

        val state = vm.state.first { it.error != null && !it.running }
        assertEquals("HTTP 500: x", state.error)
        assertFalse(state.running)
        // The persisted USER row is mirrored into `messages` so
        // the user can see their question while the error is on
        // screen.
        assertEquals(1, state.messages.size)
        assertEquals(MessageRole.USER, state.messages[0].role)
        assertEquals("retry me", state.messages[0].content)

        val rowsAfterError = db.chatMessageDao().observe(id, MessageKind.QA).first()
        assertEquals(1, rowsAfterError.size)
        assertEquals(MessageRole.USER, rowsAfterError[0].role)

        // Retry succeeds: a fresh token list replays through the
        // fake, and the retry path must NOT save a second USER
        // row.
        client.replaceError(null)
        client.replaceTokens(listOf("OK"))
        vm.retryLast()
        advanceUntilIdle()

        val stateAfterRetry = vm.state.first { it.messages.size == 2 && !it.running }
        assertNull(stateAfterRetry.error)
        assertFalse(stateAfterRetry.running)
        assertEquals(2, stateAfterRetry.messages.size)
        assertEquals(MessageRole.USER, stateAfterRetry.messages[0].role)
        assertEquals("retry me", stateAfterRetry.messages[0].content)
        assertEquals(MessageRole.ASSISTANT, stateAfterRetry.messages[1].role)
        assertEquals("OK", stateAfterRetry.messages[1].content)

        // Still exactly two rows: USER + ASSISTANT, no duplicate
        // USER row.
        val rowsAfterRetry = db.chatMessageDao().observe(id, MessageKind.QA).first()
        assertEquals(2, rowsAfterRetry.size)
        assertEquals(MessageRole.USER, rowsAfterRetry[0].role)
        assertEquals(MessageRole.ASSISTANT, rowsAfterRetry[1].role)
        assertEquals("OK", rowsAfterRetry[1].content)
    }

    @Test
    fun blankQuestionIsIgnored() = runTest(dispatcher) {
        val id = repo.createSession(title = "demo", language = "en")
        seedTranscript(id)
        val settings = newSettings().also { configureSettings(it) }
        val client = FakeChatClient(tokens = listOf("unused"))
        val vm = AskViewModel(
            sessionId = id,
            repository = repo,
            chatClient = client,
            settings = settings
        )

        // Each of these must be a no-op: nothing saved, no
        // stream() call.
        listOf("", "   ", "\n\t ").forEach { blank ->
            vm.ask(blank)
            advanceUntilIdle()
            assertEquals(0, client.streamCalls.get())
            assertTrue(vm.state.value.messages.isEmpty())
        }
    }

    @Test
    fun clearHistory_emptiesMessages() = runTest(dispatcher) {
        val id = repo.createSession(title = "demo", language = "en")
        seedTranscript(id)
        val settings = newSettings().also { configureSettings(it) }
        val client = FakeChatClient(tokens = listOf("a"))
        val vm = AskViewModel(
            sessionId = id,
            repository = repo,
            chatClient = client,
            settings = settings
        )

        vm.ask("Q")
        advanceUntilIdle()
        val seeded = vm.state.first { it.messages.size == 2 && !it.running }
        assertEquals(2, seeded.messages.size)

        vm.clearHistory()
        advanceUntilIdle()

        val cleared = vm.state.first { it.messages.isEmpty() }
        assertTrue(cleared.messages.isEmpty())

        val rows = db.chatMessageDao().observe(id, MessageKind.QA).first()
        assertTrue(rows.isEmpty())
    }

    /**
     * Build the transcript the ViewModel sees so the test does not
     * have to mirror the formatter's behaviour.
     */
    private suspend fun expectedTranscriptFor(id: Long): String {
        val segments = repo.getSegments(id)
        val speakers = repo.getSpeakers(id)
        return com.convoscribe.data.TranscriptFormatter.toText(
            com.convoscribe.data.TranscriptFormatter.lines(segments, speakers)
        )
    }

    /**
     * Seed 120 one-minute lines of filler with one fact only in the
     * middle (minute 60), for a 2 000-character budget (issue #28).
     */
    private suspend fun seedLongTranscript(sessionId: Long) {
        repeat(120) { i ->
            val text = if (i == 60) {
                "the Kowalski invoice is due on the ninth of March."
            } else {
                "we went over the agenda item number $i once more."
            }
            repo.addSegment(sessionId = sessionId, startMs = i * 60_000L, endMs = i * 60_000L + 50_000L, text = text)
        }
    }

    private fun longSettings(): SettingsRepository = newSettings().also { s ->
        configureSettings(s)
        s.update { it.copy(maxTranscriptChars = 2_000) }
    }

    @Test
    fun ask_longTranscriptSendsTheRelevantExcerptAndSavesItsCoverage() = runTest(dispatcher) {
        val id = repo.createSession(title = "demo", language = "en")
        seedLongTranscript(id)
        val client = FakeChatClient(tokens = listOf("The ninth of March."))
        val vm = AskViewModel(id, repo, client, longSettings())
        advanceUntilIdle()

        vm.ask("When is the Kowalski invoice due?")
        advanceUntilIdle()

        val system = client.lastMessages!![0].content
        assertTrue(system.startsWith(PromptBuilder.QA_EXCERPTS_PREFIX))
        assertTrue(system.contains("[1:00:00] the Kowalski invoice is due on the ninth of March."))
        assertTrue(system.length <= PromptBuilder.QA_EXCERPTS_PREFIX.length + 2_000)
        val answer = db.chatMessageDao().observe(id, MessageKind.QA).first().last()
        assertEquals(MessageRole.ASSISTANT, answer.role)
        assertEquals(119L * 60_000L + 50_000L, answer.totalMs)
        assertTrue(answer.coveredMs!! in 1L until answer.totalMs!!)
    }

    @Test
    fun ask_followUpAboutALongTranscriptAlsoSearchesThePreviousQuestion() = runTest(dispatcher) {
        val id = repo.createSession(title = "demo", language = "en")
        seedLongTranscript(id)
        val client = FakeChatClient(tokens = listOf("ok"))
        val vm = AskViewModel(id, repo, client, longSettings())
        advanceUntilIdle()

        vm.ask("When is the Kowalski invoice due?")
        advanceUntilIdle()
        vm.ask("Who raised that?")
        advanceUntilIdle()
        assertTrue(client.lastMessages!![0].content.contains("Kowalski"))
    }

    @Test
    fun retryLast_longTranscriptUsesTheExcerptsToo() = runTest(dispatcher) {
        val id = repo.createSession(title = "demo", language = "en")
        seedLongTranscript(id)
        val client = FakeChatClient(tokens = emptyList(), initialError = LlmException("HTTP 500: x"))
        val vm = AskViewModel(id, repo, client, longSettings())
        advanceUntilIdle()
        vm.ask("When is the Kowalski invoice due?")
        advanceUntilIdle()

        client.replaceTokens(listOf("March"))
        vm.retryLast()
        advanceUntilIdle()
        assertTrue(client.lastMessages!![0].content.contains("Kowalski"))
        assertNotNull(db.chatMessageDao().observe(id, MessageKind.QA).first().last().coveredMs)
    }

    @Test
    fun ask_transcriptWithinTheBudgetSavesNoCoverage() = runTest(dispatcher) {
        val id = repo.createSession(title = "demo", language = "en")
        seedTranscript(id)
        val client = FakeChatClient(tokens = listOf("ok"))
        val vm = AskViewModel(id, repo, client, newSettings().also { configureSettings(it) })
        advanceUntilIdle()
        vm.ask("hi?")
        advanceUntilIdle()
        val answer = db.chatMessageDao().observe(id, MessageKind.QA).first().last()
        assertNull(answer.coveredMs)
        assertNull(answer.totalMs)
    }

    @Test
    fun coverageMinutes_roundsAndKeepsCoveredWithinTotal() {
        assertEquals(18 to 92, coverageMinutes(18L * 60_000L + 10_000L, 91L * 60_000L + 40_000L))
        assertEquals(1 to 90, coverageMinutes(5_000L, 90L * 60_000L))
        assertEquals(3 to 3, coverageMinutes(200_000L, 170_000L))
    }

    /**
     * Fake [ChatClient] for [AskViewModel] tests. Records every
     * `stream()` call (the `lastMessages` field is the most
     * recent; the test asserts on it for the prompt-shape
     * assertions). Each `stream()` emits the configured `tokens`
     * in order, or throws the configured `error` if one was set
     * after construction. Tests that need to vary the response
     * between calls use [replaceTokens] / [replaceError].
     */
    @Test
    fun ask_onEinkTheStreamingAnswerIsRedrawnAtMostOncePerInterval() = runTest(dispatcher) {
        val id = repo.createSession(title = "demo", language = "en")
        seedTranscript(id)
        val settings = newSettings().also { configureSettings(it) }
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        // Three tokens, then the answer waits so the streaming text can be read.
        val client = object : ChatClient {
            override fun stream(messages: List<LlmMessage>): Flow<String> = flow {
                listOf("a", "b", "c").forEach { emit(it) }
                gate.await()
            }

            override suspend fun listModels(): List<String> = emptyList()
        }
        // Time stands still, so only the first token is due.
        val vm = AskViewModel(id, repo, client, settings, streamIntervalMs = 1_000L, clock = { 0L })

        vm.ask("q")
        advanceUntilIdle()
        assertEquals("a", vm.state.value.streaming)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("abc", vm.state.value.messages.last().content)
        assertEquals("", vm.state.value.streaming)
    }

    private class FakeChatClient(
        tokens: List<String>,
        private val initialError: Throwable? = null
    ) : ChatClient {
        private val tokensRef = java.util.concurrent.atomic.AtomicReference(tokens)
        private val errorRef = java.util.concurrent.atomic.AtomicReference<Throwable?>(initialError)

        val streamCalls = AtomicInteger(0)

        @Volatile
        var lastMessages: List<LlmMessage>? = null
            private set

        fun replaceTokens(tokens: List<String>) {
            tokensRef.set(tokens)
            errorRef.set(null)
        }

        fun replaceError(error: Throwable?) {
            if (error != null) {
                tokensRef.set(emptyList())
            }
            errorRef.set(error)
        }

        override fun stream(messages: List<LlmMessage>): Flow<String> {
            streamCalls.incrementAndGet()
            lastMessages = messages
            val error = errorRef.get()
            val tokens = tokensRef.get()
            return flow {
                error?.let { throw it }
                tokens.forEach { emit(it) }
            }
        }

        override suspend fun listModels(): List<String> = emptyList()
    }
}
