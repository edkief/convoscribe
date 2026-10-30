package com.convoscribe.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.LibraryManifests
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.VectorsManifest
import com.convoscribe.llm.embedding.FakeTextEmbedder
import com.convoscribe.llm.embedding.FakeTextEmbedderFactory
import com.convoscribe.llm.embedding.SemanticIndexRunner
import com.convoscribe.settings.InMemorySecretStore
import com.convoscribe.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A session's semantic vectors syncing with it (issue #70): kept only
 * when the installed model made them from the same text, otherwise the
 * session is indexed locally as before.
 */
@RunWith(RobolectricTestRunner::class)
class VectorSyncTest {
    private lateinit var context: Context
    private lateinit var fake: FakeSyncServer
    private lateinit var root: File
    private val devices = mutableListOf<Device>()
    private var now = 1_000L

    inner class Device(name: String, modelId: String = "fake/1") {
        val db: AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val dir = File(root, name).apply { mkdirs() }
        val repo = SessionRepository(db, File(dir, "recordings").apply { mkdirs() }) { now }
        val embedder = FakeTextEmbedder(modelId = modelId)
        val index =
            SemanticIndexRunner(
                db.searchDao(),
                db.semanticIndexDao(),
                repo::getSegments,
                FakeTextEmbedderFactory(embedder)
            )
        val settings = SettingsRepository(
            context.getSharedPreferences("vectors-$name-${System.nanoTime()}", 0),
            InMemorySecretStore()
        )
        val engine = SyncEngine(
            db = db,
            repository = repo,
            settings = settings,
            http = OkHttpClient(),
            status = SyncStatusStore(context.getSharedPreferences("vectors-status-$name-${System.nanoTime()}", 0)),
            isRecording = { false },
            downloadDir = File(dir, "downloads"),
            vectors = index,
            clock = { now }
        )

        init {
            settings.update { it.copy(syncServerUrl = fake.url, syncEnabled = true) }
            settings.setSyncKey(fake.apiKey)
            devices += this
        }

        suspend fun sync(): SyncOutcome = engine.run().also { now += 10 }

        suspend fun id(syncId: String): Long = db.sessionDao().getBySyncId(syncId)!!.id
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.filesDir, "vectors-${System.nanoTime()}").apply { mkdirs() }
        fake = FakeSyncServer()
        fake.server.start()
    }

    @After
    fun tearDown() {
        fake.shutdown()
        devices.forEach { it.db.close() }
        root.deleteRecursively()
    }

    private suspend fun session(device: Device): String {
        val id = device.repo.createSession(title = "Planning", language = "en")
        device.repo.addSegment(id, startMs = 0, endMs = 500, text = "we discussed the budget")
        device.repo.addSegment(id, startMs = 500, endMs = 900, text = "and the beta release")
        device.repo.finishRecording(id, durationMs = 1_000)
        return device.repo.getSession(id)!!.syncId
    }

    private fun serverVectors(syncId: String): VectorsManifest =
        LibraryManifests.decode(VectorsManifest.serializer(), fake.docs("vectors").getValue(syncId).manifest!!)

    @Test
    fun anIndexedSessionNeedsNoEmbeddingOnTheOtherDevice() = runTest {
        val a = Device("a")
        val b = Device("b")
        val syncId = session(a)
        a.sync()
        a.index.run()
        a.sync()
        assertEquals("fake/1", serverVectors(syncId).modelId)

        b.sync()

        assertFalse(b.index.hasPending())
        b.index.run()
        assertEquals(0, b.embedder.passages)
        assertEquals(
            a.db.semanticIndexDao().vectors("fake/1").map { it.vector.toList() },
            b.db.semanticIndexDao().vectors("fake/1").map { it.vector.toList() }
        )
        // B already has what the server has: nothing goes back up.
        fake.requests.clear()
        b.sync()
        assertTrue(fake.requests.none { it.startsWith("PUT documents/vectors") })
    }

    @Test
    fun anotherModelsVectorsAreNeitherUsedNorOverwritten() = runTest {
        val a = Device("a")
        val b = Device("b", modelId = "other/1")
        val syncId = session(a)
        a.sync()
        a.index.run()
        a.sync()

        b.sync()
        assertTrue(b.index.hasPending())
        b.index.run()
        assertTrue(b.embedder.passages > 0)
        b.sync()

        assertEquals("fake/1", serverVectors(syncId).modelId)
        assertEquals(1L, fake.docs("vectors").getValue(syncId).revision)
    }

    @Test
    fun vectorsOfOlderTextAreReplacedOnceTheNewTextIsIndexed() = runTest {
        val a = Device("a")
        val b = Device("b")
        val syncId = session(a)
        a.sync()
        b.sync()
        val line = b.repo.getSegments(b.id(syncId)).first()
        b.repo.editLineText(line.id, "we discussed the holiday trip")
        a.index.run()
        a.sync()

        b.sync()
        assertTrue("A's vectors are of the old text", b.index.hasPending())
        b.index.run()
        b.sync()

        assertEquals(b.index.currentHash(b.id(syncId)), serverVectors(syncId).textHash)
        a.sync()
        assertFalse("A takes B's vectors with B's text", a.index.hasPending())
    }

    @Test
    fun deletingASessionDeletesItsVectorsButRemovingItFromADeviceDoesNot() = runTest {
        val a = Device("a")
        val kept = session(a)
        val gone = session(a)
        a.sync()
        a.index.run()
        a.sync()

        a.repo.deleteSession(a.id(kept), everywhere = false)
        a.repo.deleteSession(a.id(gone))
        a.sync()
        a.sync()

        assertTrue(fake.docs("vectors").getValue(kept).manifest != null)
        assertTrue(fake.docs("vectors").getValue(gone).manifest == null)
    }
}
