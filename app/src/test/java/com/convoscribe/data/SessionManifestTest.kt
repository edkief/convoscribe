package com.convoscribe.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.asr.TimedToken
import com.convoscribe.llm.SummaryTemplates
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The portable session format and its content hash (issue #25). */
@RunWith(RobolectricTestRunner::class)
class SessionManifestTest {

    private lateinit var db: AppDatabase
    private lateinit var recordingsDir: File
    private lateinit var repo: SessionRepository

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        recordingsDir = File(context.filesDir, "manifest-test-${System.nanoTime()}").apply { mkdirs() }
        repo = SessionRepository(db, recordingsDir)
    }

    @After
    fun tearDown() {
        db.close()
        recordingsDir.deleteRecursively()
    }

    private suspend fun session(): Long {
        val templateId = db.summaryTemplateDao().insert(
            SummaryTemplateEntity(name = "Standup", body = "b", createdAt = 1)
        )
        val id = repo.createSession(
            title = "Weekly",
            language = "en",
            summaryTemplate = SummaryTemplates.userKey(templateId),
            tags = listOf("Work", "acme")
        )
        repo.addSegment(id, 0, 900, "hello there", listOf(TimedToken("hello", 0), TimedToken(" there", 400)))
        repo.addSegment(id, 1000, 1900, "bye", emptyList())
        repo.addMark(id, 500, "note")
        repo.finishRecording(id, 2000)
        File(repo.audioPath(id)).writeBytes(ByteArray(64) { it.toByte() })
        return id
    }

    @Test
    fun hash_isStableForAnUnchangedSession() = runTest {
        val id = session()
        val first = repo.snapshot(id)!!.manifest
        val second = repo.snapshot(id)!!.manifest
        assertEquals(SessionManifests.hash(first), SessionManifests.hash(second))
    }

    @Test
    fun hash_ignoresASpeakerRunInProgress() = runTest {
        val id = session()
        repo.setStatus(id, SessionStatus.DIARIZATION_PENDING)
        val pending = repo.snapshot(id)!!.manifest
        repo.setStatus(id, SessionStatus.DIARIZING)
        val running = repo.snapshot(id)!!.manifest
        assertEquals(SessionStatus.DIARIZATION_PENDING.name, running.status)
        assertEquals(SessionManifests.hash(pending), SessionManifests.hash(running))
    }

    @Test
    fun hash_changesWithTheSession() = runTest {
        val id = session()
        val before = SessionManifests.hash(repo.snapshot(id)!!.manifest)
        repo.editLineText(repo.getSegments(id).first().id, "hello world")
        assertNotEquals(before, SessionManifests.hash(repo.snapshot(id)!!.manifest))
    }

    @Test
    fun manifest_leavesOutLocalIdsAndNamesTemplatesAndTags() = runTest {
        val id = session()
        val snapshot = repo.snapshot(id)!!
        val manifest = snapshot.manifest
        val text = SessionManifests.encode(manifest)
        assertFalse(text.contains("audioPath"))
        assertFalse(text.contains(recordingsDir.path))
        assertEquals(SessionManifest.TemplateRef(user = "Standup"), manifest.summaryTemplate)
        assertEquals(listOf("acme", "Work"), manifest.tags)
        assertEquals(
            listOf(SessionManifest.Token(0, "hello"), SessionManifest.Token(400, " there")),
            manifest.lines[0].tokens
        )
        assertEquals(listOf("note"), manifest.marks.map { it.note })
        assertEquals(64L, manifest.audio!!.size)
        assertEquals(File(repo.audioPath(id)), snapshot.files[manifest.audio!!.sha256])
        assertNull(manifest.cleanAudio)
        assertEquals(manifest, SessionManifests.decode(text))
    }
}
