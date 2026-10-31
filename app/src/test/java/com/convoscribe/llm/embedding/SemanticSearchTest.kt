package com.convoscribe.llm.embedding

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.SessionEntity
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SessionStatus
import java.io.File
import kotlinx.coroutines.Dispatchers
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

@RunWith(RobolectricTestRunner::class)
class SemanticSearchTest {

    private lateinit var db: AppDatabase
    private lateinit var recordingsDir: File
    private lateinit var repo: SessionRepository
    private val factory = FakeTextEmbedderFactory()

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        recordingsDir = File(context.filesDir, "semantic-search-${System.nanoTime()}").apply { mkdirs() }
        repo = SessionRepository(db, recordingsDir)
    }

    @After
    fun tearDown() {
        db.close()
        recordingsDir.deleteRecursively()
    }

    private suspend fun session(title: String, createdAt: Long, lines: List<String>): Long {
        val id = db.sessionDao().insert(
            SessionEntity(title = title, createdAt = createdAt, language = "en", status = SessionStatus.DIARIZED)
        )
        lines.forEachIndexed { i, text ->
            repo.addSegment(
                id,
                startMs = i * 1_000L,
                endMs = (i + 1) * 1_000L,
                text = text
            )
        }
        return id
    }

    private suspend fun index() = SemanticIndexRunner(
        db.searchDao(),
        db.semanticIndexDao(),
        repo::getSegments,
        factory
    ).run()

    private fun search() = SemanticSearch(db.semanticIndexDao(), factory, Dispatchers.Unconfined)

    @Test
    fun ranksSessionsAndScoresOverlappingPassages() = runTest {
        val budget = session("Budget", 1_000, listOf("The budget is ten thousand euros"))
        val knee = session("Doctor", 2_000, listOf("My knee hurts"))
        val weather = session("Chat", 3_000, listOf("Nice weather"))
        index()

        val matches = search().match("How much can we spend?")!!

        assertEquals(listOf(budget), matches.sessions)
        assertTrue(matches.score(budget, 0, 0)!! > 0.9)
        assertEquals(0.0, matches.score(knee, 0, 0)!!, 1e-9)
        assertNull("no summary passage", matches.score(budget, -1, -1))
        assertNull("lines past the passage", matches.score(budget, 4, 9))
        assertTrue(matches.isIndexed(weather))
        assertFalse(matches.isIndexed(999))
        assertEquals(listOf(0 to 0), matches.passages(budget).map { it.firstLine to it.lastLine })
        assertEquals("below MIN_COSINE", emptyList<ScoredPassage>(), matches.passages(knee))
        assertEquals(emptyList<ScoredPassage>(), matches.passages(999))
    }

    @Test
    fun seesReindexedSessions_countsUnindexedOnes_andLoadsTheModelOnce() = runTest {
        val id = session("Budget", 1_000, listOf("The budget is ten thousand euros"))
        val other = session("Doctor", 2_000, listOf("My knee hurts"))
        // Its own factory, so the runner's loads and closes do not count.
        val own = FakeTextEmbedderFactory()
        val search = SemanticSearch(db.semanticIndexDao(), own, Dispatchers.Unconfined)
        assertEquals(2, search.unindexed(listOf(id, other)))
        index()
        assertEquals(listOf(id), search.match("money")!!.sessions)
        assertEquals(0, search.unindexed(listOf(id, other)))

        // Same number of passages, other text: the cached vectors must be replaced.
        repo.editLineText(repo.getSegments(id).single().id, "My knee hurts too")
        index()
        assertEquals(listOf(id, other).toSet(), search.match("knee")!!.sessions.toSet())
        assertEquals(1, own.created)

        search.close()
        assertTrue(own.embedder.closed)
        assertNull(search.match("knee"))
    }

    @Test
    fun noModelOrAFailureGivesNull() = runTest {
        session("Budget", 1_000, listOf("The budget"))
        index()
        factory.embedder.failQueries = true
        assertNull(search().match("money"))
        factory.available = false
        assertNull(search().match("money"))
        assertEquals(0, search().unindexed(listOf(1L)))
    }

    @Test
    fun fusionAddsReciprocalRanks() {
        assertEquals(listOf("b", "a", "c"), SemanticRanking.fuse(listOf("a", "b"), listOf("b", "c")))
        val fused = SemanticRanking.fusedScores(doubleArrayOf(2.0, 0.0, 1.0, 0.0), arrayOf(0.1, 0.9, null, 0.3))
        val k = SemanticRanking.RRF_K
        assertEquals(1.0 / (k + 1), fused[0], 1e-12)
        assertEquals(1.0 / (k + 1), fused[1], 1e-12) // meaning only
        assertEquals(1.0 / (k + 2), fused[2], 1e-12) // words only; its session is not indexed
        assertEquals(0.0, fused[3], 0.0) // below MIN_COSINE and no shared word
    }
}
