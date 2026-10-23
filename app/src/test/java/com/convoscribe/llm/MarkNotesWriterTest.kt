package com.convoscribe.llm

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.SessionRepository
import com.convoscribe.settings.InMemorySecretStore
import com.convoscribe.settings.SettingsRepository
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MarkNotesWriterTest {

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

    /** Answers each call with [reply] and records the messages of every call. */
    private class ScriptedChatClient(private val reply: (List<LlmMessage>) -> Flow<String>) : ChatClient {
        val calls = CopyOnWriteArrayList<List<LlmMessage>>()

        override fun stream(messages: List<LlmMessage>): Flow<String> {
            calls += messages
            return reply(messages)
        }

        override suspend fun listModels(): List<String> = emptyList()
    }

    private suspend fun session(): Long {
        val id = repo.createSession(title = "Pricing", language = "en")
        repo.addSegment(id, startMs = 0L, endMs = 4_000L, text = "Twelve euros a seat.", tokens = emptyList())
        repo.addSegment(id, startMs = 90_000L, endMs = 95_000L, text = "Ship on Friday.", tokens = emptyList())
        return id
    }

    private fun echo() = ScriptedChatClient { messages ->
        flowOf("Note ", messages.last().content.substringAfter("[").substringBefore("]"))
    }

    @Test
    fun start_writesTheNoteOfEveryMarkFromTheTranscriptAroundIt() = runTest {
        val id = session()
        val early = repo.addMark(id, atMs = 2_000L, note = "pricing")
        val late = repo.addMark(id, atMs = 92_000L)
        val client = echo()
        val writer = MarkNotesWriter(repo, client, settings, scope)

        writer.start(id)!!.join()

        assertEquals(
            mapOf(early to "Note 00:02", late to "Note 01:32"),
            repo.getMarks(id).associate { it.id to it.expandedNote }
        )
        assertEquals(2, client.calls.size)
        assertTrue(client.calls[0].last().content.contains("Twelve euros a seat."))
        assertTrue(!client.calls[0].last().content.contains("Ship on Friday."))
        assertEquals(emptyMap<Long, MarkNotesProgress>(), writer.progress.value)
    }

    @Test
    fun onDiarized_writesOnlyMissingNotesAndFollowsTheSetting() = runTest {
        val id = session()
        val written = repo.addMark(id, atMs = 2_000L)
        repo.setMarkExpansion(written, "kept")
        val missing = repo.addMark(id, atMs = 92_000L)
        val client = echo()
        val writer = MarkNotesWriter(repo, client, settings, scope)

        settings.update { it.copy(autoMarkNotes = false) }
        assertNull(writer.onDiarized(id))

        settings.update { it.copy(autoMarkNotes = true) }
        writer.onDiarized(id)!!.join()

        assertEquals(1, client.calls.size)
        assertEquals(
            mapOf(written to "kept", missing to "Note 01:32"),
            repo.getMarks(id).associate { it.id to it.expandedNote }
        )
    }

    @Test
    fun withoutAnEndpointNothingIsCalled() = runTest {
        val id = session()
        repo.addMark(id, atMs = 2_000L)
        settings.update { it.copy(llmBaseUrl = "") }
        val client = echo()
        val writer = MarkNotesWriter(repo, client, settings, scope)

        assertNull(writer.start(id))
        assertNull(writer.onDiarized(id))
        assertEquals(0, client.calls.size)
    }

    @Test
    fun aFailedManualRunKeepsTheNotesWrittenAndShowsTheError() = runTest {
        val id = session()
        val first = repo.addMark(id, atMs = 2_000L)
        val second = repo.addMark(id, atMs = 92_000L)
        var call = 0
        val client = ScriptedChatClient {
            call++
            if (call == 1) flowOf("first note") else flow { throw IOException("unreachable") }
        }
        val writer = MarkNotesWriter(repo, client, settings, scope)

        writer.start(id)!!.join()

        assertEquals(
            mapOf(first to "first note", second to null),
            repo.getMarks(id).associate { it.id to it.expandedNote }
        )
        val progress = writer.progress.value.getValue(id)
        assertEquals(1, progress.done)
        assertEquals(2, progress.total)
        assertNotNull(progress.error)

        writer.cancel(id)
        assertEquals(emptyMap<Long, MarkNotesProgress>(), writer.progress.value)
    }

    @Test
    fun aFailedAutomaticRunIsQuiet() = runTest {
        val id = session()
        repo.addMark(id, atMs = 2_000L)
        val writer = MarkNotesWriter(repo, ScriptedChatClient { flow { throw IOException("down") } }, settings, scope)

        writer.onDiarized(id)!!.join()

        assertEquals(emptyMap<Long, MarkNotesProgress>(), writer.progress.value)
    }

    @Test
    fun cancelSavesNothingForTheNoteInProgress() = runTest {
        val id = session()
        repo.addMark(id, atMs = 2_000L)
        val streaming = CompletableDeferred<Unit>()
        val client = ScriptedChatClient {
            flow {
                emit("half a ")
                streaming.complete(Unit)
                awaitCancellation()
            }
        }
        val writer = MarkNotesWriter(repo, client, settings, scope)

        val job = writer.start(id)!!
        assertNull(writer.start(id))
        streaming.await()
        writer.cancel(id)
        job.join()

        assertNull(repo.getMarks(id).single().expandedNote)
        assertEquals(emptyMap<Long, MarkNotesProgress>(), writer.progress.value)
    }

    @Test
    fun aNoteEditedWhileItsFullNoteIsWrittenIsLeftAlone() = runTest {
        val id = session()
        val mark = repo.addMark(id, atMs = 2_000L, note = "old")
        val client = ScriptedChatClient {
            flow {
                repo.setMarkNote(mark, "new")
                emit("written from the old note")
            }
        }

        MarkNotesWriter(repo, client, settings, scope).start(id)!!.join()

        assertNull(repo.getMarks(id).single().expandedNote)
    }
}
