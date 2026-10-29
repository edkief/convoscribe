package com.convoscribe.llm.embedding

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.MessageKind
import com.convoscribe.data.MessageRole
import com.convoscribe.data.SessionEntity
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SessionStatus
import com.convoscribe.data.TranscriptLine
import com.convoscribe.data.toFloats
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SemanticIndexRunnerTest {

    private lateinit var db: AppDatabase
    private lateinit var recordingsDir: File
    private lateinit var repo: SessionRepository
    private val factory = FakeTextEmbedderFactory()
    private var busy = false

    private val runner by lazy {
        SemanticIndexRunner(
            search = db.searchDao(),
            index = db.semanticIndexDao(),
            segments = repo::getSegments,
            embedders = factory,
            busy = { busy }
        )
    }

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        recordingsDir = File(context.filesDir, "recordings-index-${System.nanoTime()}").apply { mkdirs() }
        repo = SessionRepository(db, recordingsDir)
    }

    @After
    fun tearDown() {
        db.close()
        recordingsDir.deleteRecursively()
    }

    private suspend fun session(title: String, createdAt: Long, status: SessionStatus = SessionStatus.DIARIZED): Long =
        db.sessionDao().insert(SessionEntity(title = title, createdAt = createdAt, language = "en", status = status))

    @Test
    fun indexesEverySessionAndSkipsThemNextTime() = runTest {
        val budget = session("Budget", 1_000)
        repo.addSegment(budget, startMs = 0, endMs = 1_000, text = "The budget is ten thousand euros")
        val knee = session("Doctor", 2_000)
        repo.addSegment(knee, startMs = 0, endMs = 1_000, text = "My knee hurts")
        repo.addMessage(knee, MessageKind.SUMMARY, MessageRole.ASSISTANT, "A visit about the knee.")

        assertTrue(runner.hasPending())
        assertEquals(SemanticIndexRunner.Outcome.DONE, runner.run())

        val vectors = db.semanticIndexDao().vectors(factory.modelId()).sortedWith(
            compareBy({
                it.sessionId
            }, { it.passageIndex })
        )
        assertEquals(listOf(budget to 0, knee to 0, knee to 1), vectors.map { it.sessionId to it.passageIndex })
        // The summary passage comes first and has no lines.
        assertEquals(-1 to -1, vectors[1].firstLine to vectors[1].lastLine)
        assertEquals(0 to 0, vectors[2].firstLine to vectors[2].lastLine)
        assertEquals(factory.embedder.dimensions, vectors[0].vector.toFloats().size)
        assertTrue(factory.embedder.closed)

        val embedded = factory.embedder.passages
        assertFalse(runner.hasPending())
        assertEquals(SemanticIndexRunner.Outcome.DONE, runner.run())
        assertEquals(embedded, factory.embedder.passages)
    }

    @Test
    fun anEditedTranscriptIsIndexedAgain() = runTest {
        val id = session("Budget", 1_000)
        val line = repo.addSegment(id, startMs = 0, endMs = 1_000, text = "The budget is ten thousand euros")
        runner.run()
        val before = db.semanticIndexDao().states().single().textHash

        repo.editLineText(line, "My knee hurts")
        assertTrue(runner.hasPending())
        runner.run()

        assertNotEquals(before, db.semanticIndexDao().states().single().textHash)
        val vector = db.semanticIndexDao().vectors(factory.modelId()).single().vector.toFloats()
        assertEquals(1f, vector[1], 1e-6f) // the knee axis
    }

    @Test
    fun givesWayWhenBusyAndSkipsSessionsBeingRecorded() = runTest {
        session("Live", 1_000, status = SessionStatus.RECORDING)
        val done = session("Done", 2_000)
        repo.addSegment(done, startMs = 0, endMs = 1_000, text = "Hello")

        busy = true
        assertEquals(SemanticIndexRunner.Outcome.BUSY, runner.run())
        assertEquals(0, factory.created)

        busy = false
        assertEquals(SemanticIndexRunner.Outcome.DONE, runner.run())
        assertEquals(listOf(done), db.semanticIndexDao().states().map { it.sessionId })
    }

    /** *Index for semantic search* (issue #65) indexes that session only, and gives way like a run. */
    @Test
    fun indexIndexesOnlyThatSession() = runTest {
        val first = session("First", 1_000)
        repo.addSegment(first, startMs = 0, endMs = 1_000, text = "Hello")
        val second = session("Second", 2_000)
        repo.addSegment(second, startMs = 0, endMs = 1_000, text = "Goodbye")
        assertTrue(runner.isPending(second))

        busy = true
        assertEquals(SemanticIndexRunner.Outcome.BUSY, runner.index(second))
        assertTrue(runner.isPending(second))

        busy = false
        assertEquals(SemanticIndexRunner.Outcome.DONE, runner.index(second))
        assertEquals(listOf(second), db.semanticIndexDao().states().map { it.sessionId })
        assertFalse(runner.isPending(second))
        assertTrue(runner.isPending(first))
        assertTrue(runner.indexing.value.isEmpty())

        // Already indexed: nothing to embed.
        val created = factory.created
        assertEquals(SemanticIndexRunner.Outcome.DONE, runner.index(second))
        assertEquals(created, factory.created)
    }

    @Test
    fun withoutTheModelNothingRuns() = runTest {
        session("Done", 2_000)
        factory.available = false
        assertFalse(runner.hasPending())
        assertEquals(SemanticIndexRunner.Outcome.DISABLED, runner.run())
    }

    @Test
    fun anotherModelsVectorsAreReplaced() = runTest {
        val id = session("Budget", 1_000)
        repo.addSegment(id, startMs = 0, endMs = 1_000, text = "The budget")
        SemanticIndexRunner(
            db.searchDao(),
            db.semanticIndexDao(),
            repo::getSegments,
            FakeTextEmbedderFactory(FakeTextEmbedder(modelId = "old/1"))
        ).run()
        assertEquals(1, db.semanticIndexDao().vectorCount("old/1"))

        assertTrue(runner.hasPending())
        runner.run()

        assertEquals(0, db.semanticIndexDao().vectorCount("old/1"))
        assertEquals(1, db.semanticIndexDao().vectorCount(factory.modelId()))
        assertEquals(listOf(factory.modelId()), db.semanticIndexDao().states().map { it.modelId })
    }

    @Test
    fun passagesHaveAFixedSizeAndDropTimes() {
        val lines = (0 until 40).map { i ->
            TranscriptLine(
                startMs = i * 1_000L,
                speaker = if (i % 2 == 0) "Alice" else null,
                text = "sentence number $i " + "x".repeat(60)
            )
        }
        val passages = SemanticPassages.of(lines, summary = "  ")

        assertTrue(passages.size > 1)
        assertTrue(passages.all { it.text.length <= SemanticPassages.PASSAGE_CHARS })
        assertEquals((0 until 40).toList(), passages.flatMap { (it.firstLine..it.lastLine).toList() })
        assertTrue(passages.first().text.startsWith("Alice: sentence number 0"))
        assertFalse(passages.any { it.text.contains("[00:") })
        assertNotEquals(SemanticPassages.hash(passages), SemanticPassages.hash(passages.drop(1)))
    }
}
