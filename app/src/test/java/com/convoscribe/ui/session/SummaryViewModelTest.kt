package com.convoscribe.ui.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.R
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.MessageKind
import com.convoscribe.data.MessageRole
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SummaryTemplateRepository
import com.convoscribe.data.TranscriptMark
import com.convoscribe.llm.ChatClient
import com.convoscribe.llm.LlmException
import com.convoscribe.llm.LlmMessage
import com.convoscribe.llm.PromptBuilder
import com.convoscribe.llm.SummaryStep
import com.convoscribe.llm.SummaryTemplates
import com.convoscribe.settings.InMemorySecretStore
import com.convoscribe.settings.SettingsRepository
import com.convoscribe.ui.UiText
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
 * Unit tests for [SummaryViewModel]. The repository is real (the
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
class SummaryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var recordingsDir: File
    private lateinit var repo: SessionRepository
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var secrets: InMemorySecretStore
    private lateinit var templates: SummaryTemplateRepository

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
        recordingsDir = File(context.filesDir, "summary-vm-test-${System.nanoTime()}")
        recordingsDir.mkdirs()
        repo = SessionRepository(db, recordingsDir)
        templates = SummaryTemplateRepository(db.summaryTemplateDao())
        prefs = context.getSharedPreferences(
            "summary-vm-test-${System.nanoTime()}",
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
     * non-blank string. The text is fixed to `"hello world"` so the
     * assertions can compare against `PromptBuilder.summary` exactly.
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
    fun summarise_streamsTokensPersistsOneAssistantSummary() = runTest(dispatcher) {
        val id = repo.createSession(title = "demo", language = "en")
        seedTranscript(id)
        val settings = newSettings().also { configureSettings(it) }
        val client = FakeChatClient(tokens = listOf("A", "B", "C"))
        val vm = SummaryViewModel(
            sessionId = id,
            repository = repo,
            chatClient = client,
            settings = settings,
            templates = templates
        )

        vm.summarise()
        // The streaming flow is collected on viewModelScope; wait
        // for it to drain the token queue and persist the row.
        advanceUntilIdle()

        // Wait for the Room observer to mirror the persisted
        // SUMMARY row back into `state.saved`. The summarise
        // coroutine finishes first, then the observer picks up
        // the insert on the next dispatcher tick.
        val state = vm.state.first { it.saved == "ABC" && !it.running }
        assertEquals("ABC", state.saved)
        assertFalse(state.running)
        assertTrue(state.streaming.isEmpty())

        val rows = db.chatMessageDao().observe(id, MessageKind.SUMMARY).first()
        assertEquals(1, rows.size)
        assertEquals(MessageRole.ASSISTANT, rows[0].role)
        assertEquals(MessageKind.SUMMARY, rows[0].kind)
        assertEquals("ABC", rows[0].content)
        assertEquals(1, client.streamCalls.get())
    }

    @Test
    fun summarise_regenerateReplacesPreviousSummary() = runTest(dispatcher) {
        val id = repo.createSession(title = "demo", language = "en")
        seedTranscript(id)
        val settings = newSettings().also { configureSettings(it) }
        val client = FakeChatClient(tokens = listOf("first"))
        val vm = SummaryViewModel(
            sessionId = id,
            repository = repo,
            chatClient = client,
            settings = settings,
            templates = templates
        )

        vm.summarise()
        advanceUntilIdle()
        // Wait for the persisted row to be observed back into the
        // state — the same caveat as the streaming test.
        assertEquals("first", vm.state.first { it.saved == "first" && !it.running }.saved)

        // Re-run with a different token list. The previous
        // SUMMARY row must be cleared before the new one lands —
        // there is still exactly one SUMMARY row afterwards.
        client.replaceTokens(listOf("second"))
        vm.summarise()
        advanceUntilIdle()

        val state = vm.state.first { it.saved == "second" && !it.running }
        assertEquals("second", state.saved)
        val rows = db.chatMessageDao().observe(id, MessageKind.SUMMARY).first()
        assertEquals(1, rows.size)
        assertEquals("second", rows[0].content)
    }

    @Test
    fun summarise_llmExceptionKeepsPreviousSavedSummary() = runTest(dispatcher) {
        val id = repo.createSession(title = "demo", language = "en")
        seedTranscript(id)
        val settings = newSettings().also { configureSettings(it) }
        val client = FakeChatClient(tokens = listOf("first"))
        val vm = SummaryViewModel(
            sessionId = id,
            repository = repo,
            chatClient = client,
            settings = settings,
            templates = templates
        )

        vm.summarise()
        advanceUntilIdle()
        val previousSaved = vm.state.first { it.saved == "first" && !it.running }.saved
        assertEquals("first", previousSaved)

        // Second call throws — the previous saved summary must
        // survive intact in the DB, on the state, and the test
        // asserts on `error`.
        client.replaceError(LlmException("HTTP 500: x"))
        vm.summarise()
        advanceUntilIdle()

        val state = vm.state.first { it.error != null && !it.running }
        assertEquals(UiText.Plain("HTTP 500: x"), state.error)
        assertFalse(state.running)
        assertEquals("first", state.saved)

        val rows = db.chatMessageDao().observe(id, MessageKind.SUMMARY).first()
        assertEquals(1, rows.size)
        assertEquals("first", rows[0].content)
    }

    /**
     * Regression: non-LlmException failures were rethrown out of
     * viewModelScope (crashing the app) instead of being shown.
     */
    @Test
    fun summarise_unexpectedFailureIsShownNotThrown() = runTest(dispatcher) {
        val id = repo.createSession(title = "demo", language = "en")
        seedTranscript(id)
        val settings = newSettings().also { configureSettings(it) }
        val client = FakeChatClient(tokens = emptyList(), initialError = IllegalStateException("boom"))
        val vm = SummaryViewModel(
            sessionId = id,
            repository = repo,
            chatClient = client,
            settings = settings,
            templates = templates
        )

        vm.summarise()
        advanceUntilIdle()

        val state = vm.state.first { it.error != null && !it.running }
        assertEquals(UiText.Plain("IllegalStateException: boom"), state.error)
    }

    @Test
    fun summarise_emptyTranscriptSetsErrorAndNeverCallsClient() = runTest(dispatcher) {
        val id = repo.createSession(title = "demo", language = "en")
        // No segments inserted — the rendered transcript is blank.
        val settings = newSettings().also { configureSettings(it) }
        val client = FakeChatClient(tokens = listOf("should not be called"))
        val vm = SummaryViewModel(
            sessionId = id,
            repository = repo,
            chatClient = client,
            settings = settings,
            templates = templates
        )

        vm.summarise()
        advanceUntilIdle()

        val state = vm.state.first { it.error != null }
        assertEquals(UiText.res(R.string.session_summary_empty_error), state.error)
        assertFalse(state.running)
        assertNull(state.saved)
        assertEquals(0, client.streamCalls.get())

        val rows = db.chatMessageDao().observe(id, MessageKind.SUMMARY).first()
        assertTrue(rows.isEmpty())
    }

    @Test
    fun summarise_messagesEqualPromptBuilderSummary() = runTest(dispatcher) {
        val id = repo.createSession(title = "demo", language = "en")
        seedTranscript(id, text = "hello world")
        val settings = newSettings().also { configureSettings(it) }
        val client = FakeChatClient(tokens = listOf("OK"))
        val vm = SummaryViewModel(
            sessionId = id,
            repository = repo,
            chatClient = client,
            settings = settings,
            templates = templates
        )

        vm.summarise()
        advanceUntilIdle()

        assertNotNull(client.lastMessages)
        val expected = PromptBuilder.summary(
            transcript = expectedTranscriptFor(id),
            maxChars = settings.settings.value.maxTranscriptChars
        )
        assertEquals(expected, client.lastMessages)
    }

    /** Marks taken while recording reach the summary prompt (issue #30). */
    @Test
    fun summarise_passesTheMarks() = runTest(dispatcher) {
        val id = repo.createSession(title = "demo", language = "en")
        seedTranscript(id, text = "hello world")
        repo.addMark(id, atMs = 2_000L, note = "follow up")
        val settings = newSettings().also { configureSettings(it) }
        val client = FakeChatClient(tokens = listOf("OK"))
        val vm = SummaryViewModel(
            sessionId = id,
            repository = repo,
            chatClient = client,
            settings = settings,
            templates = templates
        )

        vm.summarise()
        advanceUntilIdle()

        val mark = repo.getMarks(id).single()
        val expected = PromptBuilder.summary(
            transcript = expectedTranscriptFor(id),
            maxChars = settings.settings.value.maxTranscriptChars,
            marks = listOf(TranscriptMark(mark.id, 2_000L, "follow up"))
        )
        assertEquals(expected, client.lastMessages)
    }

    /** A line corrected by hand reaches the summary prompt (issue #26). */
    @Test
    fun summarise_usesTheEditedTranscript() = runTest(dispatcher) {
        val id = repo.createSession(title = "demo", language = "en")
        seedTranscript(id, text = "hello world")
        repo.editLineText(repo.getSegments(id).first().id, "hello Kubernetes")
        val client = FakeChatClient(tokens = listOf("OK"))
        val vm = SummaryViewModel(
            sessionId = id,
            repository = repo,
            chatClient = client,
            settings = newSettings().also { configureSettings(it) },
            templates = templates
        )

        vm.summarise()
        advanceUntilIdle()

        val prompt = client.lastMessages!!.joinToString("\n") { it.content }
        assertTrue(prompt.contains("hello Kubernetes"))
        assertFalse(prompt.contains("hello world"))
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
     * Fake [ChatClient] for [SummaryViewModel] tests. Records the
     * most recent [stream] call (asserted on by the
     * `messagesEqualPromptBuilderSummary` test) and the number of
     * invocations. Each `stream()` emits the configured `tokens`
     * in order, or throws the configured `error` if one was set
     * after construction. Tests that need to vary the response
     * between calls use [replaceTokens] / [replaceError].
     */
    private fun templateVm(id: Long, client: FakeChatClient, settings: SettingsRepository) = SummaryViewModel(
        sessionId = id,
        repository = repo,
        chatClient = client,
        settings = settings,
        templates = templates
    )

    @Test
    fun summarise_usesTheSessionsTemplateAndRecordsItsName() = runTest(dispatcher) {
        val id = repo.createSession(title = "demo", language = "en", summaryTemplate = SummaryTemplates.INTERVIEW_KEY)
        seedTranscript(id)
        val client = FakeChatClient(tokens = listOf("Q&A"))
        val vm = templateVm(id, client, newSettings().also { configureSettings(it) })
        advanceUntilIdle()
        assertEquals(SummaryTemplates.INTERVIEW_KEY, vm.state.value.selectedKey)

        vm.summarise()
        advanceUntilIdle()

        val interview = SummaryTemplates.resolve(SummaryTemplates.INTERVIEW_KEY, emptyList())
        assertEquals(PromptBuilder.summarySystem(interview.body), client.lastMessages!![0].content)
        val row = db.chatMessageDao().observe(id, MessageKind.SUMMARY).first().single()
        assertEquals("Interview", row.templateName)
        assertEquals("Interview", vm.state.first { it.saved == "Q&A" }.savedTemplateName)
    }

    @Test
    fun selectTemplate_isStoredOnTheSessionAndUsedForTheNextSummary() = runTest(dispatcher) {
        val id = repo.createSession(title = "demo", language = "en")
        seedTranscript(id)
        val key = templates.add("Standup", "Three bullets: yesterday, today, blockers.")
        val client = FakeChatClient(tokens = listOf("ok"))
        val vm = templateVm(id, client, newSettings().also { configureSettings(it) })
        advanceUntilIdle()

        vm.selectTemplate(key)
        advanceUntilIdle()
        assertEquals(key, repo.getSession(id)!!.summaryTemplate)
        assertTrue(vm.state.value.templates.any { it.key == key })

        vm.summarise()
        advanceUntilIdle()
        assertEquals(
            PromptBuilder.summarySystem("Three bullets: yesterday, today, blockers."),
            client.lastMessages!![0].content
        )
        assertEquals("Standup", db.chatMessageDao().observe(id, MessageKind.SUMMARY).first().single().templateName)
    }

    @Test
    fun summarise_sessionWithoutATemplateFollowsTheDefault() = runTest(dispatcher) {
        val id = repo.createSession(title = "demo", language = "en")
        seedTranscript(id)
        val settings = newSettings().also { configureSettings(it) }
        settings.update { it.copy(defaultSummaryTemplate = SummaryTemplates.BRIEF_KEY) }
        val client = FakeChatClient(tokens = listOf("ok"))
        val vm = templateVm(id, client, settings)
        advanceUntilIdle()

        vm.summarise()
        advanceUntilIdle()
        val brief = SummaryTemplates.resolve(SummaryTemplates.BRIEF_KEY, emptyList())
        assertEquals(PromptBuilder.summarySystem(brief.body), client.lastMessages!![0].content)
    }

    @Test
    fun summarise_deletedTemplateFallsBackToMeeting() = runTest(dispatcher) {
        val key = templates.add("Gone", "Soon deleted.")
        val id = repo.createSession(title = "demo", language = "en", summaryTemplate = key)
        seedTranscript(id)
        templates.delete(key)
        val client = FakeChatClient(tokens = listOf("ok"))
        val vm = templateVm(id, client, newSettings().also { configureSettings(it) })
        advanceUntilIdle()

        vm.summarise()
        advanceUntilIdle()
        assertEquals(PromptBuilder.SUMMARY_SYSTEM, client.lastMessages!![0].content)
        assertEquals("Meeting", db.chatMessageDao().observe(id, MessageKind.SUMMARY).first().single().templateName)
    }

    /**
     * Seed [count] one-minute lines of about 80 characters, so a
     * 2 000-character budget needs several parts (issue #28).
     */
    private suspend fun seedLongTranscript(sessionId: Long, count: Int = 120) {
        repeat(count) { i ->
            repo.addSegment(
                sessionId = sessionId,
                startMs = i * 60_000L,
                endMs = i * 60_000L + 50_000L,
                text = "point$i is something we discussed at some length today."
            )
        }
    }

    private fun longSettings(): SettingsRepository = newSettings().also { s ->
        configureSettings(s)
        s.update { it.copy(maxTranscriptChars = 2_000) }
    }

    @Test
    fun summarise_longTranscriptIsSummarisedInPartsAndOnlyTheFinalTextIsSaved() = runTest(dispatcher) {
        val id = repo.createSession(title = "demo", language = "en")
        seedLongTranscript(id)
        lateinit var vm: SummaryViewModel
        val steps = ArrayList<SummaryStep?>()
        val client = ScriptedChatClient { messages ->
            steps += vm.state.value.step
            if (messages[0].content == PromptBuilder.PART_NOTES_SYSTEM) {
                flow { emit(Regex("point\\d+").findAll(messages[1].content).joinToString(" ") { it.value }) }
            } else {
                flow { listOf("final ", "summary").forEach { emit(it) } }
            }
        }
        vm = SummaryViewModel(id, repo, client, longSettings(), templates)
        advanceUntilIdle()

        vm.summarise()
        advanceUntilIdle()

        val parts = client.calls.count { it[0].content == PromptBuilder.PART_NOTES_SYSTEM }
        assertTrue("expected several parts, got $parts", parts >= 3)
        assertEquals(parts + 1, client.calls.size)
        val reduce = client.calls.last()
        assertEquals(PromptBuilder.SUMMARY_SYSTEM, reduce[0].content)
        (0 until 120).forEach {
            assertTrue(
                "point$it missing",
                Regex("\\bpoint$it\\b").containsMatchIn(reduce[1].content)
            )
        }
        assertEquals(
            (1..parts).map { SummaryStep.Part(it, parts) } + SummaryStep.Writing,
            steps
        )
        val rows = db.chatMessageDao().observe(id, MessageKind.SUMMARY).first()
        assertEquals(listOf("final summary"), rows.map { it.content })
        val state = vm.state.first { it.saved == "final summary" }
        assertNull(state.step)
        assertFalse(state.running)
    }

    @Test
    fun summarise_longTranscriptUnderTheBudgetIsStillOneCall() = runTest(dispatcher) {
        val id = repo.createSession(title = "demo", language = "en")
        seedLongTranscript(id, count = 10)
        val client = FakeChatClient(tokens = listOf("ok"))
        val vm = SummaryViewModel(id, repo, client, longSettings(), templates)
        advanceUntilIdle()

        vm.summarise()
        advanceUntilIdle()
        assertEquals(1, client.streamCalls.get())
        assertEquals(PromptBuilder.SUMMARY_SYSTEM, client.lastMessages!![0].content)
    }

    @Test
    fun summarise_cancelPartWayKeepsThePreviousSummary() = runTest(dispatcher) {
        val id = repo.createSession(title = "demo", language = "en")
        seedLongTranscript(id)
        repo.addMessage(id, MessageKind.SUMMARY, MessageRole.ASSISTANT, "old summary")
        val client = ScriptedChatClient { _ ->
            flow {
                emit("notes")
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val vm = SummaryViewModel(id, repo, client, longSettings(), templates)
        advanceUntilIdle()

        vm.summarise()
        advanceUntilIdle()
        assertTrue(vm.state.value.running)
        assertEquals(1, (vm.state.value.step as SummaryStep.Part).part)

        vm.cancel()
        advanceUntilIdle()
        val state = vm.state.value
        assertFalse(state.running)
        assertNull(state.step)
        assertNull(state.error)
        assertEquals(1, client.calls.size)
        assertEquals(
            listOf("old summary"),
            db.chatMessageDao().observe(id, MessageKind.SUMMARY).first().map { it.content }
        )
    }

    @Test
    fun summarise_failurePartWaySavesNothingAndShowsTheError() = runTest(dispatcher) {
        val id = repo.createSession(title = "demo", language = "en")
        seedLongTranscript(id)
        repo.addMessage(id, MessageKind.SUMMARY, MessageRole.ASSISTANT, "old summary")
        val calls = AtomicInteger(0)
        val client = ScriptedChatClient { _ ->
            if (calls.incrementAndGet() == 2) flow { throw LlmException("HTTP 500: boom") } else flow { emit("notes") }
        }
        val vm = SummaryViewModel(id, repo, client, longSettings(), templates)
        advanceUntilIdle()

        vm.summarise()
        advanceUntilIdle()
        val state = vm.state.value
        assertFalse(state.running)
        assertNull(state.step)
        assertNotNull(state.error)
        assertEquals(2, client.calls.size)
        assertEquals(
            listOf("old summary"),
            db.chatMessageDao().observe(id, MessageKind.SUMMARY).first().map { it.content }
        )
    }

    /** Answers each call with [reply] and records the messages of every call. */
    private class ScriptedChatClient(private val reply: (List<LlmMessage>) -> Flow<String>) : ChatClient {
        val calls = java.util.concurrent.CopyOnWriteArrayList<List<LlmMessage>>()

        override fun stream(messages: List<LlmMessage>): Flow<String> {
            calls += messages
            return reply(messages)
        }

        override suspend fun listModels(): List<String> = emptyList()
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

        fun replaceError(error: Throwable) {
            tokensRef.set(emptyList())
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
