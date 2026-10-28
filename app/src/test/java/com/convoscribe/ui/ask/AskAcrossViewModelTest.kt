package com.convoscribe.ui.ask

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.GlobalChatCodec
import com.convoscribe.data.MessageRole
import com.convoscribe.data.SearchFilters
import com.convoscribe.data.SessionEntity
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SessionSearch
import com.convoscribe.data.SessionStatus
import com.convoscribe.llm.ChatClient
import com.convoscribe.llm.Citation
import com.convoscribe.llm.LlmException
import com.convoscribe.llm.LlmMessage
import com.convoscribe.settings.InMemorySecretStore
import com.convoscribe.settings.SettingsRepository
import java.io.File
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [AskAcrossViewModel] (issue #49) against an in-memory Room database
 * with synthetic sessions, so the FTS index, the candidate sessions and
 * the excerpts are the real ones; the LLM is a [FakeChatClient].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AskAcrossViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var db: AppDatabase
    private lateinit var recordingsDir: File
    private lateinit var repo: SessionRepository
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context: Context = ApplicationProvider.getApplicationContext()
        val syncExecutor = java.util.concurrent.Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(syncExecutor)
            .setTransactionExecutor(syncExecutor)
            .build()
        recordingsDir = File(context.filesDir, "ask-across-test-${System.nanoTime()}").apply { mkdirs() }
        repo = SessionRepository(db, recordingsDir)
        val prefs = context.getSharedPreferences("ask-across-test-${System.nanoTime()}", Context.MODE_PRIVATE)
        settings = SettingsRepository(prefs, InMemorySecretStore())
        settings.update { it.copy(llmBaseUrl = "http://example.test/v1", llmModel = "m", maxTranscriptChars = 2_000) }
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
        recordingsDir.deleteRecursively()
    }

    private suspend fun session(title: String, createdAt: Long, lines: List<String>, language: String = "en"): Long {
        val id = db.sessionDao().insert(
            SessionEntity(title = title, createdAt = createdAt, language = language, status = SessionStatus.DIARIZED)
        )
        lines.forEachIndexed { i, text ->
            repo.addSegment(sessionId = id, startMs = i * 10_000L, endMs = (i + 1) * 10_000L, text = text)
        }
        return id
    }

    private fun vm(client: ChatClient) = AskAcrossViewModel(
        repository = repo,
        search = SessionSearch(db.searchDao()),
        chatClient = client,
        settings = settings,
        clock = { NOW },
        zone = ZoneOffset.UTC
    )

    @Test
    fun aFactOnlyInAnOlderSessionIsSentAndCited() = runTest(dispatcher) {
        val filler = List(30) { "we talked about the weather and the traffic again" }
        session("Recent chat", NOW - DAY, filler)
        val older = session("Planning", JAN_1, filler.take(5) + "the Kowalski contract is signed for 40k" + filler)
        val client = FakeChatClient(listOf("Signed for 40k ", "(Planning, 2026-01-01, 00:50)."))
        val vm = vm(client)

        vm.ask("What happened with the Kowalski contract?")
        advanceUntilIdle()

        val system = client.lastMessages!!.first().content
        assertTrue(system.contains("## Planning — 2026-01-01 (en)"))
        assertTrue(system.contains("Kowalski contract is signed"))
        val state = vm.state.first { it.messages.size == 2 && !it.running }
        val answer = state.messages.last()
        assertEquals(MessageRole.ASSISTANT, answer.role)
        val segment = repo.getSegments(older)[5]
        assertEquals(listOf(Citation(older, "Planning", "2026-01-01", segment.id, 50_000L)), answer.citations)
        assertEquals(1, answer.sessionCount)
        assertNotNull(answer.excerptCount)
    }

    @Test
    fun filtersNarrowTheSessionsAndAreSavedWithTheQuestion() = runTest(dispatcher) {
        session("English", NOW - DAY, listOf("budget review"))
        session("Français", NOW - DAY, listOf("budget du trimestre"), language = "fr")
        val client = FakeChatClient(listOf("ok"))
        val vm = vm(client)

        vm.setLanguage("fr")
        vm.ask("budget")
        advanceUntilIdle()

        val system = client.lastMessages!!.first().content
        assertTrue(system.contains("## Français"))
        assertFalse(system.contains("## English"))
        val question = db.globalChatMessageDao().observe().first().first()
        assertEquals(SearchFilters(language = "fr"), GlobalChatCodec.decodeFilters(question.filters))
    }

    @Test
    fun failureKeepsTheQuestion_retryResendsItWithItsFilters_clearEmptiesTheHistory() = runTest(dispatcher) {
        session("Français", NOW - DAY, listOf("budget du trimestre"), language = "fr")
        session("English", NOW - DAY, listOf("budget review"))
        val client = FakeChatClient(listOf("Le budget."), LlmException("HTTP 500: x"))
        val vm = vm(client)

        vm.setLanguage("fr")
        vm.ask("budget")
        advanceUntilIdle()
        assertNotNull(vm.state.value.error)
        assertEquals(listOf(MessageRole.USER), vm.state.value.messages.map { it.role })

        vm.setLanguage(null)
        client.error = null
        vm.retryLast()
        advanceUntilIdle()
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), vm.state.value.messages.map { it.role })
        assertFalse(client.lastMessages!!.first().content.contains("## English"))

        vm.clearHistory()
        advanceUntilIdle()
        assertEquals(emptyList<AskAcrossMessage>(), vm.state.value.messages)
    }

    @Test
    fun cancelLeavesTheQuestionAndSavesNoAnswer() = runTest(dispatcher) {
        session("Chat", NOW - DAY, listOf("budget review"))
        val gate = CompletableDeferred<Unit>()
        val client = object : ChatClient {
            override fun stream(messages: List<LlmMessage>): Flow<String> = flow {
                emit("partial")
                gate.await()
            }

            override suspend fun listModels(): List<String> = emptyList()
        }
        val vm = vm(client)

        vm.ask("budget")
        advanceUntilIdle()
        assertEquals("partial", vm.state.value.streaming)
        vm.cancel()
        advanceUntilIdle()

        assertFalse(vm.state.value.running)
        assertEquals(listOf(MessageRole.USER), vm.state.value.messages.map { it.role })
    }

    @Test
    fun aDeletedSessionDropsOutOfTheKnownSessions_butItsCitationStays() = runTest(dispatcher) {
        val id = session("Planning", JAN_1, listOf("Kowalski contract signed"))
        val vm = vm(FakeChatClient(listOf("Yes (Planning, 2026-01-01, 00:00).")))

        vm.ask("Kowalski")
        advanceUntilIdle()
        assertTrue(id in vm.state.value.sessionIds!!)

        repo.deleteSession(id)
        advanceUntilIdle()
        val state = vm.state.value
        assertFalse(id in state.sessionIds!!)
        assertEquals(id, state.messages.last().citations.single().sessionId)
    }

    private class FakeChatClient(private val tokens: List<String>, var error: Throwable? = null) : ChatClient {
        @Volatile
        var lastMessages: List<LlmMessage>? = null
            private set

        override fun stream(messages: List<LlmMessage>): Flow<String> {
            lastMessages = messages
            val failure = error
            return flow {
                failure?.let { throw it }
                tokens.forEach { emit(it) }
            }
        }

        override suspend fun listModels(): List<String> = emptyList()
    }

    private companion object {
        const val DAY = 86_400_000L

        /** 1 Jan 2026 00:00 UTC. */
        const val JAN_1 = 1_767_225_600_000L

        /** 15 Mar 2026 00:00 UTC. */
        const val NOW = JAN_1 + 73 * DAY
    }
}
