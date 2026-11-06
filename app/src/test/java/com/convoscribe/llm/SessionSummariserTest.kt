package com.convoscribe.llm

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.FakeSummaryTemplateStore
import com.convoscribe.data.MessageKind
import com.convoscribe.data.MessageRole
import com.convoscribe.data.SessionRepository
import com.convoscribe.settings.InMemorySecretStore
import com.convoscribe.settings.SettingsRepository
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The automatic summary of a finished session (issue #29). */
@RunWith(RobolectricTestRunner::class)
class SessionSummariserTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: SessionRepository
    private lateinit var settings: SettingsRepository
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = SessionRepository(db, File(context.filesDir, "recordings-${System.nanoTime()}").apply { mkdirs() })
        val prefs = context.getSharedPreferences("settings-${System.nanoTime()}", Context.MODE_PRIVATE)
        settings = SettingsRepository(prefs, InMemorySecretStore())
        settings.update { it.copy(llmBaseUrl = "http://llm.test/v1", llmModel = "m", autoSummary = true) }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private class ScriptedChatClient(private val reply: () -> Flow<String>) : ChatClient {
        val calls = CopyOnWriteArrayList<List<LlmMessage>>()

        override fun stream(messages: List<LlmMessage>): Flow<String> {
            calls += messages
            return reply()
        }

        override suspend fun listModels(): List<String> = emptyList()
    }

    private suspend fun session(): Long {
        val id = repo.createSession(title = "Budget", language = "en")
        repo.addSegment(id, startMs = 0L, endMs = 4_000L, text = "We cut travel by ten percent.", tokens = emptyList())
        return id
    }

    private fun summariser(client: ChatClient) =
        SessionSummariser(repo, client, settings, FakeSummaryTemplateStore(), scope = scope)

    private suspend fun summaries(id: Long) =
        repo.getMessages(id, MessageKind.SUMMARY).filter { it.role == MessageRole.ASSISTANT }

    @Test
    fun onFinished_summarisesWithTheSessionsTemplate() = runTest {
        val id = session()
        repo.setSummaryTemplate(id, SummaryTemplates.INTERVIEW_KEY)
        val client = ScriptedChatClient { flowOf("Travel ", "is cut.") }

        summariser(client).onFinished(id)!!.join()

        val saved = summaries(id).single()
        assertEquals("Travel is cut.", saved.content)
        assertEquals(SummaryTemplates.resolve(SummaryTemplates.INTERVIEW_KEY, emptyList()).name, saved.templateName)
        assertTrue(client.calls.single().last().content.contains("We cut travel by ten percent."))
    }

    @Test
    fun onFinished_followsTheSettingAndTheEndpoint() = runTest {
        val id = session()
        val client = ScriptedChatClient { flowOf("Summary.") }

        settings.update { it.copy(autoSummary = false) }
        assertNull(summariser(client).onFinished(id))
        settings.update { it.copy(autoSummary = true, llmBaseUrl = "") }
        assertNull(summariser(client).onFinished(id))

        assertEquals(0, client.calls.size)
        assertEquals(emptyList<Any>(), summaries(id))
    }

    @Test
    fun onFinished_keepsAnExistingSummary() = runTest {
        val id = session()
        repo.addMessage(id, MessageKind.SUMMARY, MessageRole.ASSISTANT, "Written by hand.")
        val client = ScriptedChatClient { flowOf("New.") }

        summariser(client).onFinished(id)!!.join()

        assertEquals(0, client.calls.size)
        assertEquals(listOf("Written by hand."), summaries(id).map { it.content })
    }

    @Test
    fun onFinished_failsQuietly() = runTest {
        val id = session()
        val client = ScriptedChatClient { flow { throw IOException("unreachable") } }

        summariser(client).onFinished(id)!!.join()

        assertEquals(1, client.calls.size)
        assertEquals(emptyList<Any>(), summaries(id))
    }
}
