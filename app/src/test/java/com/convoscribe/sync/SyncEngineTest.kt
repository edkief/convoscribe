package com.convoscribe.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.ChatMessageEntity
import com.convoscribe.data.MessageKind
import com.convoscribe.data.MessageRole
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SessionStatus
import com.convoscribe.data.SyncKinds
import com.convoscribe.settings.InMemorySecretStore
import com.convoscribe.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
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

@RunWith(RobolectricTestRunner::class)
class SyncEngineTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var recordingsDir: File
    private lateinit var repo: SessionRepository
    private lateinit var settings: SettingsRepository
    private lateinit var secrets: InMemorySecretStore
    private lateinit var status: SyncStatusStore
    private lateinit var fake: FakeSyncServer
    private lateinit var engine: SyncEngine
    private var recording = false
    private var now = 1_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        recordingsDir = File(context.filesDir, "sync-test-${System.nanoTime()}").apply { mkdirs() }
        repo = SessionRepository(db, recordingsDir)
        secrets = InMemorySecretStore()
        settings = SettingsRepository(context.getSharedPreferences("sync-test-${System.nanoTime()}", 0), secrets)
        status = SyncStatusStore(context.getSharedPreferences("sync-status-${System.nanoTime()}", 0))
        fake = FakeSyncServer()
        fake.server.start()
        settings.update { it.copy(syncServerUrl = fake.url, syncEnabled = true) }
        settings.setSyncKey(fake.apiKey)
        engine = SyncEngine(
            db = db,
            repository = repo,
            settings = settings,
            http = OkHttpClient(),
            status = status,
            isRecording = { recording },
            clock = { now },
            partBytes = PART
        )
    }

    @After
    fun tearDown() {
        fake.shutdown()
        db.close()
        recordingsDir.deleteRecursively()
    }

    /** A finished session with a 10-byte recording (three parts of [PART]) and one line. */
    private suspend fun finishedSession(title: String = "Standup", audio: ByteArray = AUDIO): Long {
        val id = repo.createSession(title = title, language = "en")
        File(repo.getSession(id)!!.audioPath).writeBytes(audio)
        repo.addSegment(id, startMs = 0, endMs = 500, text = "hello")
        repo.finishRecording(id, durationMs = 1_000)
        return id
    }

    private suspend fun syncId(id: Long) = repo.getSession(id)!!.syncId

    private fun documentRequests() = fake.requests.filter { it.startsWith("PUT documents/") }

    @Test
    fun uploadsAFinishedSessionWithItsRecording() = runTest {
        val id = finishedSession()

        assertEquals(SyncOutcome.DONE, engine.run())

        val doc = fake.documents.getValue(syncId(id))
        assertEquals(1L, doc.revision)
        assertTrue(doc.manifest!!.contains("\"title\":\"Standup\""))
        assertTrue(doc.manifest!!.contains("hello"))
        assertEquals(AUDIO.toList(), fake.blobs.getValue(FakeSyncServer.sha256(AUDIO)).toList())
        assertEquals(3, fake.partsSent)
        val state = db.syncDao().state(SyncKinds.SESSION, syncId(id))!!
        assertEquals(1L, state.baseRevision)
        assertNull(state.pendingHash)
        assertEquals(SyncStatus(lastSyncedAt = now, waiting = 0), status.status.value)
    }

    @Test
    fun anUnchangedSessionIsNotSentAgain() = runTest {
        finishedSession()
        engine.run()
        fake.requests.clear()

        assertEquals(SyncOutcome.DONE, engine.run())

        assertEquals(emptyList<String>(), fake.requests)
    }

    @Test
    fun everyKindOfChangeIsPushedAsTheNextRevisionWithoutResendingTheRecording() = runTest {
        val id = finishedSession()
        engine.run()
        val line = repo.getSegments(id).single().id
        val changes: List<suspend () -> Unit> = listOf(
            { repo.renameSession(id, "Renamed") },
            { repo.editLineText(line, "hello there") },
            { repo.addMark(id, atMs = 100, note = "decision") },
            { repo.setSessionTags(id, listOf("work")) },
            {
                repo.addChatMessage(
                    ChatMessageEntity(
                        sessionId = id,
                        kind = MessageKind.SUMMARY,
                        role = MessageRole.ASSISTANT,
                        content = "summary",
                        createdAt = 5L
                    )
                )
            }
        )
        fake.requests.clear()

        changes.forEachIndexed { index, change ->
            change()
            assertEquals(SyncOutcome.DONE, engine.run())
            assertEquals(index + 2L, fake.documents.getValue(syncId(id)).revision)
        }

        assertEquals(changes.size, documentRequests().size)
        assertFalse(fake.requests.any { it.startsWith("POST blobs/") })
    }

    @Test
    fun identifyingSpeakersIsNotAChange() = runTest {
        val id = finishedSession()
        repo.setStatus(id, SessionStatus.DIARIZATION_PENDING)
        engine.run()
        fake.requests.clear()

        repo.setStatus(id, SessionStatus.DIARIZING)
        assertEquals(SyncOutcome.DONE, engine.run())
        assertEquals(1, status.status.value.waiting)
        repo.setStatus(id, SessionStatus.DIARIZATION_PENDING)
        assertEquals(SyncOutcome.DONE, engine.run())

        assertEquals(emptyList<String>(), documentRequests())
        assertEquals(0, status.status.value.waiting)
    }

    @Test
    fun aSessionStillRecordingIsNeverUploaded() = runTest {
        val id = repo.createSession(title = "Live", language = "en")

        assertEquals(SyncOutcome.DONE, engine.run())

        assertTrue(fake.documents.isEmpty())
        assertEquals(1, status.status.value.waiting)
        assertNull(db.syncDao().state(SyncKinds.SESSION, syncId(id)))
    }

    @Test
    fun syncWaitsWhileARecordingRuns() = runTest {
        finishedSession()
        recording = true

        assertEquals(SyncOutcome.DEFERRED, engine.run())

        assertEquals(emptyList<String>(), fake.requests)
    }

    @Test
    fun withSyncOffNothingIsSent() = runTest {
        finishedSession()
        settings.update { it.copy(syncEnabled = false) }

        assertEquals(SyncOutcome.DISABLED, engine.run())

        assertEquals(emptyList<String>(), fake.requests)
    }

    @Test
    fun withNoNetworkNothingLocalChangesAndTheErrorIsShown() = runTest {
        val id = finishedSession()
        val before = repo.getSession(id)
        fake.shutdown()

        assertEquals(SyncOutcome.RETRY, engine.run())

        assertEquals(before, repo.getSession(id))
        assertEquals(listOf("hello"), repo.getSegments(id).map { it.text })
        assertTrue(status.status.value.lastError!!.startsWith("Network error"))
        assertNull(status.status.value.lastSyncedAt)
    }

    @Test
    fun aServerErrorIsRecordedAndTheNextRunCatchesUp() = runTest {
        val id = finishedSession()
        fake.intercept = {
            if (it.method == "PUT" && it.path!!.contains("documents")) MockResponse().setResponseCode(500) else null
        }

        assertEquals(SyncOutcome.RETRY, engine.run())
        assertTrue(status.status.value.lastError!!.contains("HTTP 500"))
        assertEquals("HTTP 500 from the sync server: ", db.syncDao().state(SyncKinds.SESSION, syncId(id))!!.lastError)
        assertEquals(1, status.status.value.waiting)

        fake.intercept = { null }
        assertEquals(SyncOutcome.DONE, engine.run())
        assertNull(status.status.value.lastError)
        assertEquals(1L, fake.documents.getValue(syncId(id)).revision)
    }

    @Test
    fun anInterruptedUploadResumesWithoutResendingFinishedParts() = runTest {
        val id = finishedSession()
        fake.intercept = { if (it.path!!.endsWith("/parts/2")) MockResponse().setResponseCode(503) else null }

        assertEquals(SyncOutcome.RETRY, engine.run())
        assertTrue(fake.blobs.isEmpty())
        fake.intercept = { null }
        fake.requests.clear()

        assertEquals(SyncOutcome.DONE, engine.run())

        val parts = fake.requests.filter { "/parts/" in it }.map { it.substringAfterLast('/') }
        assertEquals(listOf("2", "3"), parts)
        assertFalse(fake.requests.any { it.matches(Regex("POST blobs/[0-9a-f]+/uploads")) })
        assertEquals(AUDIO.toList(), fake.blobs.getValue(FakeSyncServer.sha256(AUDIO)).toList())
        assertEquals(1L, fake.documents.getValue(syncId(id)).revision)
    }

    @Test
    fun aRecordingTheServerHasIsNotSentAgain() = runTest {
        finishedSession(title = "One")
        engine.run()
        fake.requests.clear()

        finishedSession(title = "Two (same audio)")
        assertEquals(SyncOutcome.DONE, engine.run())

        assertEquals(0, fake.partsSent)
        assertEquals(2, fake.documents.size)
    }

    @Test
    fun aNewerRevisionFromAnotherDeviceWaitsForTwoWaySync() = runTest {
        val id = finishedSession()
        engine.run()
        fake.documents.getValue(syncId(id)).apply {
            revision = 7
            hash = "other"
        }
        repo.renameSession(id, "Mine")

        assertEquals(SyncOutcome.DONE, engine.run())

        val state = db.syncDao().state(SyncKinds.SESSION, syncId(id))!!
        assertTrue(state.conflict)
        assertEquals(1, status.status.value.waiting)
        fake.requests.clear()
        engine.run()
        assertEquals(emptyList<String>(), documentRequests())
    }

    @Test
    fun deletingASyncedSessionDeletesItOnTheServer() = runTest {
        val id = finishedSession()
        engine.run()
        val syncId = syncId(id)

        repo.deleteSession(id)
        assertEquals(SyncOutcome.DONE, engine.run())

        assertNull(fake.documents.getValue(syncId).manifest)
        assertEquals(emptyList<Any>(), db.syncDao().tombstones())
    }

    @Test
    fun aSessionDeletedDuringItsUploadIsDeletedOnTheServerToo() = runTest {
        val id = finishedSession()
        val syncId = syncId(id)
        fake.intercept = { request ->
            if (request.method == "PUT" && request.path!!.contains("documents")) {
                kotlinx.coroutines.runBlocking { repo.deleteSession(id) }
            }
            null
        }

        engine.run()
        fake.intercept = { null }
        engine.run()

        assertNull(fake.documents.getValue(syncId).manifest)
    }

    @Test
    fun aRefusedKeyStopsTheRunAndSaysSo() = runTest {
        finishedSession(title = "One")
        finishedSession(title = "Two", audio = byteArrayOf(9, 9))
        settings.setSyncKey("wrong")

        assertEquals(SyncOutcome.RETRY, engine.run())

        assertEquals(1, fake.requests.size)
        assertTrue(status.status.value.lastError!!.contains("check the sync API key"))
    }

    @Test
    fun secretsAreNeverUploaded() = runTest {
        secrets.setApiKey("llm-secret-1234")
        secrets.setAsrServerKey("asr-secret-5678")
        val id = finishedSession()
        repo.renameSession(id, "Has a title")

        engine.run()

        val uploaded = fake.documents.values.mapNotNull { it.manifest } + fake.blobs.values.map { String(it) }
        assertNotNull(uploaded.firstOrNull())
        for (secret in listOf("llm-secret-1234", "asr-secret-5678", fake.apiKey)) {
            assertFalse(uploaded.any { secret in it })
        }
    }

    private companion object {
        const val PART = 4L
        val AUDIO = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
    }
}
