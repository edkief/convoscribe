package com.convoscribe.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.MessageKind
import com.convoscribe.data.MessageRole
import com.convoscribe.data.SessionManifests
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SpeakerEntity
import com.convoscribe.data.SyncKinds
import com.convoscribe.data.cleanAudioFile
import com.convoscribe.settings.InMemorySecretStore
import com.convoscribe.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
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
 * Two devices syncing through one [FakeSyncServer] (issue #69):
 * downloads, edits both ways, the conflict rule, deletes everywhere and
 * *Remove from this device*.
 */
@RunWith(RobolectricTestRunner::class)
class TwoWaySyncTest {
    private lateinit var context: Context
    private lateinit var fake: FakeSyncServer
    private lateinit var root: File
    private lateinit var a: Device
    private lateinit var b: Device
    private var now = 1_000L

    /** One install: its own database, recordings, settings and sync engine. */
    inner class Device(name: String) {
        val db: AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val dir = File(root, name).apply { mkdirs() }
        val repo = SessionRepository(db, File(dir, "recordings").apply { mkdirs() }) { now }
        val settings = SettingsRepository(
            context.getSharedPreferences("two-way-$name-${System.nanoTime()}", 0),
            InMemorySecretStore()
        )
        val status = SyncStatusStore(context.getSharedPreferences("two-way-status-$name-${System.nanoTime()}", 0))
        var metered = false
        var attached = 0
        val engine = SyncEngine(
            db = db,
            repository = repo,
            settings = settings,
            http = OkHttpClient(),
            status = status,
            isRecording = { false },
            downloadDir = File(dir, "downloads"),
            isMetered = { metered },
            onRecordingAttached = { attached++ },
            clock = { now },
            partBytes = 4
        )

        init {
            settings.update { it.copy(syncServerUrl = fake.url, syncEnabled = true) }
            settings.setSyncKey(fake.apiKey)
        }

        suspend fun sync(): SyncOutcome = engine.run().also { now += 10 }

        suspend fun session(syncId: String) = db.sessionDao().getBySyncId(syncId)

        suspend fun hash(syncId: String): String {
            val row = session(syncId)!!
            return SessionManifests.hash(repo.snapshot(row.id)!!.manifest)
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.filesDir, "two-way-${System.nanoTime()}").apply { mkdirs() }
        fake = FakeSyncServer()
        fake.server.start()
        a = Device("a")
        b = Device("b")
    }

    @After
    fun tearDown() {
        fake.shutdown()
        a.db.close()
        b.db.close()
        root.deleteRecursively()
    }

    /**
     * A finished session on [device] with a recording, a cleaned copy,
     * a named speaker, a summary, a mark and a tag.
     */
    private suspend fun fullSession(device: Device, durationMs: Long = 1_000): String {
        val repo = device.repo
        val id = repo.createSession(title = "Standup", language = "en", tags = listOf("work"))
        val row = repo.getSession(id)!!
        File(row.audioPath).writeBytes(AUDIO)
        repo.addSegment(id, startMs = 0, endMs = 500, text = "hello")
        repo.addMark(id, atMs = 100, note = "decision")
        repo.finishRecording(id, durationMs = durationMs)
        device.db.sessionDao().setCleanedWith(id, "cleaner")
        row.cleanAudioFile().writeBytes(CLEAN)
        repo.applyDiarization(id, mapOf(repo.getSegments(id).single().id to 0), speakerCount = 1)
        repo.renameSpeaker(id, 0, "Ann")
        repo.addMessage(id, MessageKind.SUMMARY, MessageRole.ASSISTANT, "the summary")
        return row.syncId
    }

    @Test
    fun aSessionFromAnotherDeviceArrivesComplete() = runTest {
        val syncId = fullSession(a)
        assertEquals(SyncOutcome.DONE, a.sync())

        assertEquals(SyncOutcome.DONE, b.sync())

        val row = b.session(syncId)!!
        assertEquals(a.hash(syncId), b.hash(syncId))
        assertEquals(AUDIO.toList(), File(row.audioPath).readBytes().toList())
        assertEquals(CLEAN.toList(), row.cleanAudioFile().readBytes().toList())
        assertEquals(listOf(SpeakerEntity(row.id, 0, "Ann")), b.repo.getSpeakers(row.id))
        assertEquals(
            listOf("the summary"),
            b.db.chatMessageDao().getForSession(row.id).filter {
                it.kind == MessageKind.SUMMARY
            }.map { it.content }
        )
        assertEquals(listOf("decision"), b.repo.getMarks(row.id).map { it.note })
        assertEquals(1, b.attached)
        assertNull(b.db.syncDao().state(SyncKinds.SESSION, syncId)!!.audioSha)
        assertEquals(0, b.status.status.value.downloading)

        // Nothing changed on either side: no more pushes.
        fake.requests.clear()
        b.sync()
        a.sync()
        assertEquals(emptyList<String>(), fake.requests.filter { it.startsWith("PUT documents") })
    }

    @Test
    fun editsOnOneDeviceReachTheOther() = runTest {
        val syncId = fullSession(a)
        a.sync()
        b.sync()
        val id = b.session(syncId)!!.id
        b.repo.renameSession(id, "Renamed on B")
        b.repo.editLineText(b.repo.getSegments(id).single().id, "hello there")
        b.repo.renameSpeaker(id, 0, "Bob")
        b.repo.addMessage(id, MessageKind.SUMMARY, MessageRole.ASSISTANT, "a new summary")

        fake.requests.clear()
        b.sync()
        a.sync()

        val onA = a.session(syncId)!!
        assertEquals("Renamed on B", onA.title)
        assertEquals(listOf("hello there"), a.repo.getSegments(onA.id).map { it.text })
        assertEquals("Bob", a.repo.getSpeakers(onA.id).single().label)
        assertTrue(
            a.db.chatMessageDao().getForSession(onA.id).filter {
                it.kind == MessageKind.SUMMARY
            }.any { it.content == "a new summary" }
        )
        assertEquals(a.hash(syncId), b.hash(syncId))
        // The recordings were already there and were not fetched again.
        assertFalse(fake.requests.any { it.startsWith("GET blobs/") })
    }

    @Test
    fun theLongerRecordingWinsAConflict() = runTest {
        val syncId = fullSession(a)
        a.sync()
        b.sync()
        a.repo.setDuration(a.session(syncId)!!.id, 5_000)
        now += 100
        b.repo.renameSession(b.session(syncId)!!.id, "Renamed later on B")

        b.sync()
        a.sync()
        b.sync()

        assertEquals(5_000L, b.session(syncId)!!.durationMs)
        assertEquals("Standup", b.session(syncId)!!.title)
        assertEquals(a.hash(syncId), b.hash(syncId))
    }

    @Test
    fun withEqualRecordingsTheLaterChangeWinsWhicheverSyncsFirst() = runTest {
        val syncId = fullSession(a)
        a.sync()
        b.sync()
        b.repo.renameSession(b.session(syncId)!!.id, "Earlier on B")
        now += 100
        a.repo.renameSession(a.session(syncId)!!.id, "Later on A")

        // B goes first and is accepted; A's later change still wins.
        b.sync()
        a.sync()
        b.sync()
        assertEquals("Later on A", b.session(syncId)!!.title)
        assertEquals("Later on A", a.session(syncId)!!.title)

        // The losing device takes the winner in the same run as its refused push.
        b.repo.renameSession(b.session(syncId)!!.id, "Old news")
        now += 100
        a.repo.renameSession(a.session(syncId)!!.id, "Newest")
        a.sync()
        b.sync()
        assertEquals("Newest", b.session(syncId)!!.title)
        assertEquals(a.hash(syncId), b.hash(syncId))
    }

    @Test
    fun aDeleteReachesEveryDevice() = runTest {
        val syncId = fullSession(a)
        a.sync()
        b.sync()

        a.repo.deleteSession(a.session(syncId)!!.id)
        a.sync()
        b.sync()

        assertNull(b.session(syncId))
        assertNull(b.db.syncDao().state(SyncKinds.SESSION, syncId))
        assertEquals(emptyList<Any>(), a.db.syncDao().tombstones())
    }

    @Test
    fun aLongerRecordingSurvivesADeleteFromAnotherDevice() = runTest {
        val syncId = fullSession(a)
        a.sync()
        b.sync()
        b.repo.setDuration(b.session(syncId)!!.id, 9_000)
        b.sync()

        a.repo.deleteSession(a.session(syncId)!!.id)
        a.sync()

        assertEquals(9_000L, a.session(syncId)!!.durationMs)
        assertNotNull(fake.documents.getValue(syncId).manifest)
        assertEquals(a.hash(syncId), b.hash(syncId))
    }

    @Test
    fun anEditDoesNotBringBackASessionDeletedElsewhere() = runTest {
        val syncId = fullSession(a)
        a.sync()
        b.sync()
        a.repo.deleteSession(a.session(syncId)!!.id)
        a.sync()

        b.repo.renameSession(b.session(syncId)!!.id, "Too late")
        b.sync()

        assertNull(b.session(syncId))
        assertNull(fake.documents.getValue(syncId).manifest)
    }

    @Test
    fun removeFromThisDeviceKeepsItOnTheServerUntilAskedFor() = runTest {
        val syncId = fullSession(a)
        a.sync()
        b.sync()

        b.repo.deleteSession(b.session(syncId)!!.id, everywhere = false)
        b.sync()
        b.sync()
        a.sync()

        assertNull(b.session(syncId))
        assertNotNull(a.session(syncId))
        assertNotNull(fake.documents.getValue(syncId).manifest)
        assertTrue(b.db.syncDao().state(SyncKinds.SESSION, syncId)!!.excluded)

        b.repo.downloadAgain(syncId)
        b.sync()
        assertEquals(AUDIO.toList(), File(b.session(syncId)!!.audioPath).readBytes().toList())
        assertEquals(a.hash(syncId), b.hash(syncId))
    }

    @Test
    fun aRemovedSessionDeletedElsewhereIsForgotten() = runTest {
        val syncId = fullSession(a)
        a.sync()
        b.sync()
        b.repo.deleteSession(b.session(syncId)!!.id, everywhere = false)

        a.repo.deleteSession(a.session(syncId)!!.id)
        a.sync()
        b.sync()

        assertNull(b.db.syncDao().state(SyncKinds.SESSION, syncId))
    }

    @Test
    fun anInterruptedDownloadResumesAndNeverLeavesAHalfSession() = runTest {
        val syncId = fullSession(a)
        a.sync()
        val ranges = mutableListOf<String?>()
        var cut = true
        fake.intercept = { request ->
            if (request.method == "GET" && request.path!!.contains("/blobs/")) ranges += request.getHeader("Range")
            if (cut && request.method == "GET" && request.path!!.endsWith("/blobs/${FakeSyncServer.sha256(AUDIO)}")) {
                cut = false
                MockResponse().setResponseCode(200).setHeader("Content-Length", AUDIO.size)
                    .setBody(Buffer().write(AUDIO)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            } else {
                null
            }
        }

        assertEquals(SyncOutcome.RETRY, b.sync())
        val row = b.session(syncId)!!
        assertEquals(listOf("hello"), b.repo.getSegments(row.id).map { it.text })
        assertFalse(File(row.audioPath).exists())
        assertNotNull(b.db.syncDao().state(SyncKinds.SESSION, syncId)!!.audioSha)
        assertEquals(1L, fake.documents.getValue(syncId).revision)

        assertEquals(SyncOutcome.DONE, b.sync())
        assertEquals(AUDIO.toList(), File(row.audioPath).readBytes().toList())
        assertTrue(ranges.any { it != null && it.startsWith("bytes=") })
    }

    @Test
    fun recordingsWaitForAnUnmeteredNetworkUnlessMobileDataIsAllowed() = runTest {
        val syncId = fullSession(a)
        a.sync()
        b.metered = true

        assertEquals(SyncOutcome.AUDIO_WAITING, b.sync())
        val row = b.session(syncId)!!
        assertFalse(File(row.audioPath).exists())
        assertEquals(2, b.status.status.value.downloading)
        // Waiting for its recording is not a change to upload.
        fake.requests.clear()
        b.sync()
        assertEquals(emptyList<String>(), fake.requests.filter { it.startsWith("PUT documents") })

        b.settings.update { it.copy(syncAudioOnMobileData = true) }
        assertEquals(SyncOutcome.DONE, b.sync())
        assertEquals(AUDIO.toList(), File(row.audioPath).readBytes().toList())
    }

    @Test
    fun withTheServerDownNothingLocalChanges() = runTest {
        val syncId = fullSession(a)
        a.sync()
        b.sync()
        val before = b.hash(syncId)
        fake.intercept = { MockResponse().setResponseCode(503) }

        assertEquals(SyncOutcome.RETRY, b.sync())

        assertEquals(before, b.hash(syncId))
        assertNotNull(b.status.status.value.lastError)
    }

    private companion object {
        val AUDIO = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val CLEAN = byteArrayOf(11, 12, 13, 14, 15, 16, 17, 18, 19, 20)
    }
}
