package com.convoscribe.llm

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.TitleSource
import com.convoscribe.settings.InMemorySecretStore
import com.convoscribe.settings.SettingsRepository
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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

/** [AutoTitler] over an in-memory database and a scripted LLM (issue #32). */
@RunWith(RobolectricTestRunner::class)
class AutoTitlerTest {

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
        settings.update { it.copy(llmBaseUrl = "http://llm.test/v1", llmModel = "m") }
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

    private fun answering(vararg tokens: String) = ScriptedChatClient { flowOf(*tokens) }

    @Test
    fun defaultTitle_isReplacedAndAnnounced() = runTest {
        val id = repo.createSession(title = "Recording 2026-03-04 09:05", language = "fr")
        val client = answering("\"Planification ", "du budget.\"")
        val titler = AutoTitler(repo, client, settings, scope)
        val announced = scope.async { titler.titled.first() }

        titler.onSummarySaved(id, "Résumé : le budget du T3.")!!.join()

        val session = repo.getSession(id)!!
        assertEquals("Planification du budget", session.title)
        assertEquals(TitleSource.AUTO, session.titleSource)
        assertEquals(AutoTitled(id, "Planification du budget", "Recording 2026-03-04 09:05"), announced.await())
        assertTrue(client.calls.single().first().content.endsWith("Write it in French."))
        assertTrue(client.calls.single().last().content.contains("le budget du T3"))
    }

    @Test
    fun userTitle_isNeverReplacedOrAsked() = runTest {
        val id = repo.createSession(title = "Standup", language = "en", titleSource = TitleSource.USER)
        val client = answering("Budget planning")
        AutoTitler(repo, client, settings, scope).onSummarySaved(id, "Summary")!!.join()

        assertEquals("Standup", repo.getSession(id)!!.title)
        assertEquals(0, client.calls.size)
    }

    @Test
    fun settingOffOrNoEndpoint_doesNothing() = runTest {
        val id = repo.createSession(title = "Recording 2026-03-04 09:05", language = "en")
        val client = answering("Budget planning")
        val titler = AutoTitler(repo, client, settings, scope)

        settings.update { it.copy(autoTitle = false) }
        assertNull(titler.onSummarySaved(id, "Summary"))
        settings.update { it.copy(autoTitle = true, llmModel = "") }
        assertNull(titler.onSummarySaved(id, "Summary"))

        assertEquals(TitleSource.DEFAULT, repo.getSession(id)!!.titleSource)
        assertEquals(0, client.calls.size)
    }

    @Test
    fun failureOrUnusableAnswer_keepsTheTitleQuietly() = runTest {
        val id = repo.createSession(title = "Recording 2026-03-04 09:05", language = "en")
        val failing = ScriptedChatClient { flow { throw IOException("unreachable") } }
        AutoTitler(repo, failing, settings, scope).onSummarySaved(id, "Summary")!!.join()
        AutoTitler(repo, answering("  ", "..."), settings, scope).onSummarySaved(id, "Summary")!!.join()

        val session = repo.getSession(id)!!
        assertEquals("Recording 2026-03-04 09:05", session.title)
        assertEquals(TitleSource.DEFAULT, session.titleSource)
    }
}
