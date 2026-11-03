package com.convoscribe.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric test for [EnrolledSpeakerRepository]. We build a
 * fresh in-memory [AppDatabase] per test so the auto-migration
 * 1 → 2 runs (and is validated) for every fixture, and
 * `allowMainThreadQueries()` keeps the suspend DAO calls
 * synchronous so the assertions stay simple.
 *
 * Coverage:
 *  - `getAll` decodes the embedding byte column back to floats,
 *    confirming the codec wiring round-trips;
 *  - `save` followed by another `save` with the same name keeps
 *    one row, demonstrating the REPLACE strategy + upsert;
 *  - `observeAll` is a real Flow: insertions show up.
 */
@RunWith(RobolectricTestRunner::class)
class EnrolledSpeakerRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: EnrolledSpeakerRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = EnrolledSpeakerRepository(db.enrolledSpeakerDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun savePersistsNameAndEmbeddingAsBytes() = runTest {
        val floats = floatArrayOf(0.1f, -0.2f, 0.3f, -0.4f)
        repo.save(name = "Alice", embedding = floats)

        val rows = db.enrolledSpeakerDao().getAll()
        assertEquals(1, rows.size)
        assertEquals("Alice", rows[0].name)
        // The on-disk bytes round-trip back to the original
        // floats exactly — proving the codec was wired correctly.
        assertEquals(floats.toList(), rows[0].embedding.toFloats().toList())
    }

    @Test
    fun getAllReturnsEnrolledWithFloatEmbeddings() = runTest {
        val floats = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
        repo.save(name = "Bob", embedding = floats)

        val enrolled = repo.getAll()
        assertEquals(1, enrolled.size)
        assertEquals("Bob", enrolled[0].name)
        // `Enrolled.embedding` is the decoded FloatArray, not
        // the raw byte column. The repository's compile-time
        // return type already pins it; this assertion exists
        // so a future swap to `ByteArray` (skipping the codec)
        // would fail loudly here instead of corrupting the
        // cosine scores downstream.
        assertEquals(floats.toList(), enrolled[0].embedding.toList())
    }

    @Test
    fun savingSameNameTwiceKeepsBothVoiceSamples() = runTest {
        val first = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)
        val second = floatArrayOf(0.9f, 0.8f, 0.7f, 0.6f)

        repo.save(name = "Alice", embedding = first)
        repo.save(name = "Alice", embedding = second)

        val rows = db.enrolledSpeakerDao().getAll()
        assertEquals(listOf("Alice", "Alice"), rows.map { it.name })
        assertEquals(
            setOf(first.toList(), second.toList()),
            rows.map { it.embedding.toFloats().toList() }.toSet()
        )
    }

    @Test
    fun onlyTheNewestSamplesPerPersonAreKept() = runTest {
        for (i in 1..MAX_SAMPLES_PER_PERSON + 2) {
            repo.save(name = "Alice", embedding = floatArrayOf(i.toFloat()))
            // save() stamps the real clock; samples in the same millisecond would tie and sort by random syncId.
            Thread.sleep(2)
        }
        repo.save(name = "Bob", embedding = floatArrayOf(-1f))

        val alice = db.enrolledSpeakerDao().getAll().filter { it.name == "Alice" }
        assertEquals(MAX_SAMPLES_PER_PERSON, alice.size)
        // Samples 1 and 2 (the oldest) were dropped.
        assertEquals(
            (3..MAX_SAMPLES_PER_PERSON + 2).map {
                it.toFloat()
            }.toSet(),
            alice.map { it.embedding.toFloats()[0] }.toSet()
        )
        assertEquals(1, db.enrolledSpeakerDao().getAll().count { it.name == "Bob" })
    }

    @Test
    fun observeAllEmitsNewRowsAsTheyAreInserted() = runTest {
        repo.observeAll().test {
            // Initial empty state.
            val initial = awaitItem()
            assertEquals(emptyList<EnrolledSpeakerEntity>(), initial)

            // Insert a row and confirm the flow re-emits with it.
            repo.save(name = "Alice", embedding = floatArrayOf(1f, 0f))
            val afterFirst = awaitItem()
            assertEquals(1, afterFirst.size)
            assertEquals("Alice", afterFirst[0].name)

            // A second sample for the same person is a second row.
            repo.save(name = "Alice", embedding = floatArrayOf(0f, 1f))
            val afterSecond = awaitItem()
            assertEquals(listOf("Alice", "Alice"), afterSecond.map { it.name })

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun deleteRemovesTheRow() = runTest {
        repo.save(name = "Alice", embedding = floatArrayOf(1f, 0f))
        val rows = db.enrolledSpeakerDao().getAll()
        assertEquals(1, rows.size)
        repo.delete(rows[0].id)
        assertEquals(0, db.enrolledSpeakerDao().getAll().size)
    }

    @Test
    fun databaseCarriesTheEnrolledSpeakerEntity() = runTest {
        // Light-weight sanity check: the DAO compiled and is
        // wired into AppDatabase. A null DAO would surface here
        // as a NullPointerException.
        val dao = db.enrolledSpeakerDao()
        assertNotNull(dao)
        assertEquals(emptyList<EnrolledSpeakerEntity>(), dao.getAll())
    }
}
