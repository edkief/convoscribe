package com.convoscribe.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
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

/** What [SessionRepository] does for two-way sync (issue #69). */
@RunWith(RobolectricTestRunner::class)
class SessionSyncRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var recordingsDir: File
    private lateinit var repo: SessionRepository
    private var now = 1_000L

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        recordingsDir = File(context.filesDir, "sync-repo-test-${System.nanoTime()}").apply { mkdirs() }
        repo = SessionRepository(db, recordingsDir) { now }
    }

    @After
    fun tearDown() {
        db.close()
        recordingsDir.deleteRecursively()
    }

    private suspend fun session(): Long {
        val id = repo.createSession(title = "Weekly", language = "en")
        repo.addSegment(id, 0, 900, "hello there", emptyList())
        repo.addMark(id, 100, null)
        repo.finishRecording(id, 2000)
        File(repo.audioPath(id)).writeBytes(ByteArray(64) { it.toByte() })
        return id
    }

    private suspend fun synced(id: Long, revision: Long = 3) {
        val row = repo.getSession(id)!!
        db.syncDao().upsertState(SyncStateEntity(SyncKinds.SESSION, row.syncId, baseRevision = revision))
    }

    @Test
    fun writes_stampUpdatedAt() = runTest {
        val id = session()
        val line = repo.getSegments(id).single().id
        val mark = repo.getMarks(id).single().id
        val writes: List<suspend () -> Unit> = listOf(
            { repo.renameSession(id, "New") },
            { repo.editLineText(line, "hello you") },
            { repo.renameSpeaker(id, 0, "Ann") },
            { repo.setSessionTags(id, listOf("Work")) },
            { repo.setMarkNote(mark, "note") },
            { repo.addMessage(id, MessageKind.SUMMARY, MessageRole.ASSISTANT, "sum") },
            { repo.setParticipants(id, listOf("Ann")) }
        )
        for (write in writes) {
            now += 10
            write()
            assertEquals(now, repo.getSession(id)!!.updatedAt)
        }
        now += 10
        repo.setStatus(id, SessionStatus.DIARIZING)
        assertEquals(now - 10, repo.getSession(id)!!.updatedAt)
    }

    @Test
    fun applyManifest_withoutTheRecordingKeepsTheTranscript() = runTest {
        val id = session()
        val manifest = repo.snapshot(id)!!.manifest
        val other = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val otherRepo = SessionRepository(other, File(recordingsDir, "other").apply { mkdirs() })
            val newId = otherRepo.applyManifest(manifest, 5L, emptyMap(), allowMissing = true)
            assertEquals(1, otherRepo.getSegments(newId).size)
            assertFalse(File(otherRepo.audioPath(newId)).exists())
        } finally {
            other.close()
        }
    }

    @Test
    fun applyManifest_leavesARecordingInPlace() = runTest {
        val id = session()
        val snapshot = repo.snapshot(id)!!
        val audio = snapshot.manifest.audio!!
        repo.applyManifest(snapshot.manifest.copy(title = "Renamed"), 5L, emptyMap(), inPlace = setOf(audio.sha256))
        assertEquals("Renamed", repo.getSession(id)!!.title)
        assertEquals(64L, File(repo.audioPath(id)).length())
    }

    @Test
    fun attachRecording_putsTheAwaitedFileInPlaceOnly() = runTest {
        val id = session()
        val row = repo.getSession(id)!!
        File(row.audioPath).delete()
        val ref = SessionManifest.FileRef("a".repeat(64), 3)
        db.syncDao().upsertState(
            SyncStateEntity(SyncKinds.SESSION, row.syncId, baseRevision = 1, audioSha = ref.sha256, audioSize = 3)
        )
        val wrong = File(recordingsDir, "wrong").apply { writeBytes(byteArrayOf(9)) }
        assertFalse(repo.attachRecording(row.syncId, SessionManifest.FileRef("b".repeat(64), 1), wrong))
        val staged = File(recordingsDir, "staged").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertTrue(repo.attachRecording(row.syncId, ref, staged))
        assertEquals(3L, File(row.audioPath).length())
        assertNull(db.syncDao().state(SyncKinds.SESSION, row.syncId)!!.audioSha)
    }

    @Test
    fun delete_leavesATombstoneWithTheLength() = runTest {
        val id = session()
        synced(id)
        val syncId = repo.getSession(id)!!.syncId
        repo.deleteSession(id)
        val tombstone = db.syncDao().tombstones().single()
        assertEquals(syncId, tombstone.syncId)
        assertEquals(2000L, tombstone.durationMs)
        assertNull(db.syncDao().state(SyncKinds.SESSION, syncId))
    }

    @Test
    fun removeFromDevice_keepsItOnTheServerUntilDownloadedAgain() = runTest {
        val id = session()
        synced(id)
        val syncId = repo.getSession(id)!!.syncId
        repo.deleteSession(id, everywhere = false)
        assertNull(repo.getSession(id))
        assertFalse(File(recordingsDir, "$id.wav").exists())
        assertEquals(emptyList<SyncTombstoneEntity>(), db.syncDao().tombstones())
        val state = db.syncDao().state(SyncKinds.SESSION, syncId)!!
        assertTrue(state.excluded)
        assertEquals("Weekly", state.title)
        assertEquals(2000L, state.durationMs)

        repo.downloadAgain(syncId)
        val again = db.syncDao().state(SyncKinds.SESSION, syncId)!!
        assertFalse(again.excluded)
        assertEquals(0L, again.baseRevision)
        assertEquals(3L, again.remoteRevision)
        assertTrue(again.behind)
    }
}
