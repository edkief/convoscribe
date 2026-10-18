package com.convoscribe.diarization

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.EnrolledSpeakerEntity
import com.convoscribe.data.EnrolledSpeakerStore
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SessionStatus
import com.convoscribe.settings.InMemorySecretStore
import com.convoscribe.settings.SettingsRepository
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [DiarizeSessionUseCase]. Robolectric gives us a
 * real [android.content.Context] (so [androidx.room.Room] can
 * wire SQLite), and we inject fakes for everything else:
 *
 *  * [ScriptedDiarizerFactory] returns a diarizer that emits a
 *    scripted list of turns so we can assert on the assignment
 *    without any native code.
 *  * [ThrowingDiarizer] raises on [diarize] to verify the
 *    failure path lands the session in `DIARIZATION_FAILED` and
 *    leaves the segments untouched.
 *  * [readSamples] is replaced with a lambda that returns
 *    `FloatArray(16_000)` so the test does not have to lay out
 *    a WAV header just to feed the engine.
 *
 * For the speaker-name embedding pass (TASK-33) we inject
 *  * [FakeEnrolledSpeakerStore] returning the configured list
 *    of [SpeakerMatcher.Enrolled] names;
 *  * [FakeSpeakerEmbedderFactory] / [FakeSpeakerEmbedder] that
 *    return a vector derived from the input (so a unit vector
 *    `unit(0°)` is cosine-identical to a `"Alice"` whose
 *    reference is `unit(0°)`, and `unit(60°)` is cosine 0.5
 *    away — convenient for asserting the threshold behaviour).
 *
 * Every test passes the same Robolectric-provided context to a
 * freshly built in-memory Room database, so the tests do not
 * share state.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DiarizeSessionUseCaseTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var recordingsDir: File
    private lateinit var repo: SessionRepository
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        recordingsDir = File(context.filesDir, "recordings-test-${System.nanoTime()}")
        recordingsDir.mkdirs()
        repo = SessionRepository(db, recordingsDir)
        val prefs = context.getSharedPreferences(
            "settings-test-${System.nanoTime()}",
            Context.MODE_PRIVATE
        )
        settings = SettingsRepository(prefs, InMemorySecretStore())
    }

    @After
    fun tearDown() {
        db.close()
        if (recordingsDir.exists()) {
            recordingsDir.deleteRecursively()
        }
    }

    /**
     * Simple [Diarizer] that returns the [turns] it was built with
     * on every [diarize] call. The factory controls whether the
     * diarizer has been "created" yet via its `created` counter.
     */
    private class ScriptedDiarizer(
        private val turns: List<SpeakerTurn>
    ) : Diarizer {
        var diarizeCount: Int = 0
            private set
        var lastNumSpeakers: Int = 0
            private set
        var lastThreshold: Float = 0f
            private set
        var lastSamplesSize: Int = 0
            private set
        override fun diarize(
            samples: FloatArray,
            numSpeakers: Int,
            threshold: Float,
            onProgress: (Float) -> Unit,
            isCancelled: () -> Boolean
        ): List<SpeakerTurn> {
            diarizeCount += 1
            lastNumSpeakers = numSpeakers
            lastThreshold = threshold
            lastSamplesSize = samples.size
            return turns
        }

        override fun close() {
            // No native resources to release in tests.
        }
    }

    private class ScriptedDiarizerFactory(
        private val diarizer: Diarizer
    ) : DiarizerFactory {
        var created: Int = 0
            private set
        override fun create(): Diarizer {
            created += 1
            return diarizer
        }
    }

    /**
     * [Diarizer] that always throws on [diarize]. Used by the
     * failure-path test to confirm the session lands in
     * `DIARIZATION_FAILED` without losing the existing segment
     * rows.
     */
    private class ThrowingDiarizer(val message: String = "engine exploded") : Diarizer {
        override fun diarize(
            samples: FloatArray,
            numSpeakers: Int,
            threshold: Float,
            onProgress: (Float) -> Unit,
            isCancelled: () -> Boolean
        ): List<SpeakerTurn> = throw IllegalStateException(message)

        override fun close() {
            // Nothing to release.
        }
    }

    /**
     * Small 2-D speaker-embedding fake: returns a unit vector at
     * `angle = degrees` so two embeddings have a predictable
     * cosine similarity (cos of the angular distance). The
     * factory records every call so we can assert the use case
     * did (or didn't) build one.
     */
    private class FakeSpeakerEmbedder : SpeakerEmbedder {
        var embedCalls: Int = 0
            private set
        var closed: Boolean = false
            private set
        var lastSamplesSize: Int = 0
            private set
        override fun embed(samples: FloatArray): FloatArray {
            embedCalls += 1
            lastSamplesSize = samples.size
            return unit(0.0)
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeSpeakerEmbedderFactory(private val embedder: SpeakerEmbedder) : SpeakerEmbedderFactory {
        var createCalls: Int = 0
            private set
        override fun create(): SpeakerEmbedder {
            createCalls += 1
            return embedder
        }
    }

    /**
     * [SpeakerEmbedder] that always throws on [embed]. Used by
     * the "embedder failure" test to verify the speaker-name
     * pass fails closed: the session is still `DIARIZED` and
     * the speakers table falls back to default labels.
     */
    private class ThrowingSpeakerEmbedder : SpeakerEmbedder {
        override fun embed(samples: FloatArray): FloatArray {
            throw IllegalStateException("embedding model exploded")
        }

        override fun close() = Unit
    }

    /**
     * In-memory [EnrolledSpeakerStore]. Mirrors the [FakeEnrolledSpeakerStore]
     * used in `EnrollSpeakerUseCaseTest` but kept private to
     * this file because the two test classes never look at the
     * same database state.
     */
    private class FakeEnrolledSpeakerStore(
        private val rows: List<SpeakerMatcher.Enrolled>
    ) : EnrolledSpeakerStore {
        constructor(vararg pairs: Pair<String, FloatArray>) : this(
            pairs.map { (name, vec) -> SpeakerMatcher.Enrolled(name, vec) }
        )

        override fun observeAll(): Flow<List<EnrolledSpeakerEntity>> = flow {
            emit(
                rows.map { row ->
                    EnrolledSpeakerEntity(
                        id = 0L,
                        name = row.name,
                        embedding = row.embedding.toBytes(),
                        createdAt = 0L
                    )
                }
            )
        }

        override suspend fun getAll(): List<SpeakerMatcher.Enrolled> = rows.map { row ->
            SpeakerMatcher.Enrolled(row.name, row.embedding.copyOf())
        }

        override suspend fun save(name: String, embedding: FloatArray) {
            error("Fake store is read-only")
        }

        @Suppress("unused")
        override suspend fun delete(id: Long) = Unit
    }

    /**
     * Acceptance test 1: a session with two segments and scripted
     * turns for two speakers ends up `DIARIZED`, the segments have
     * the correct speaker index, and the speakers table contains
     * "Speaker 1" / "Speaker 2".
     *
     * Setup: the segments are `[0, 1000)` and `[1000, 2000)` and
     * the engine returns a turn for speaker 0 on the first
     * segment and a turn for speaker 1 on the second. After
     * first-appearance renumbering the assignments are
     * `(segId1 -> 0, segId2 -> 1)` and the speaker count is `2`.
     */
    @Test
    fun run_withTwoSegmentsAndTwoSpeakers_resultsInDiarized() = runTest {
        val id = repo.createSession(title = "Test", language = "en")
        val seg1 = repo.addSegment(id, startMs = 0L, endMs = 1000L, text = "first")
        val seg2 = repo.addSegment(id, startMs = 1000L, endMs = 2000L, text = "second")
        val turns = listOf(
            SpeakerTurn(startMs = 0L, endMs = 1000L, speaker = 0),
            SpeakerTurn(startMs = 1000L, endMs = 2000L, speaker = 1)
        )
        val diarizer = ScriptedDiarizer(turns)
        val factory = ScriptedDiarizerFactory(diarizer)
        val useCase = DiarizeSessionUseCase(
            repository = repo,
            diarizerFactory = factory,
            settings = settings,
            readSamples = { FloatArray(16_000) }
        )

        useCase.run(id)

        val session = repo.getSession(id)!!
        assertEquals(SessionStatus.DIARIZED, session.status)

        val segments = repo.getSegments(id)
        assertEquals(listOf(0, 1), segments.map { it.speakerIndex })

        val speakers = repo.getSpeakers(id)
        assertEquals(2, speakers.size)
        assertEquals(
            listOf("Speaker 1" to 0, "Speaker 2" to 1),
            speakers.map { it.label to it.speakerIndex }
        )

        // The factory was called exactly once and the diarizer
        // got the user's numSpeakers / threshold settings.
        assertEquals(1, factory.created)
        assertEquals(1, diarizer.diarizeCount)
        assertEquals(settings.settings.value.numSpeakers, diarizer.lastNumSpeakers)
        assertEquals(settings.settings.value.clusterThreshold, diarizer.lastThreshold, 0.0001f)
        assertEquals(16_000, diarizer.lastSamplesSize)
    }

    /**
     * A line spanning a speaker change (quick exchange, no 0.8 s
     * pause) is split into one row per speaker.
     */
    @Test
    fun run_lineWithTokenTimesSpanningTwoSpeakers_isSplitIntoLabelledLines() = runTest {
        val id = repo.createSession(title = "Test", language = "en")
        repo.addSegment(
            id,
            startMs = 0L,
            endMs = 4_000L,
            text = "Are you coming yes I am",
            tokens = listOf(
                " are" to 100L,
                " you" to 300L,
                " coming" to 500L,
                " yes" to 1_500L,
                " I" to 1_800L,
                " am" to 2_000L
            )
                .map { (t, ms) -> com.convoscribe.asr.TimedToken(t, ms) }
        )
        val useCase = DiarizeSessionUseCase(
            repository = repo,
            diarizerFactory = ScriptedDiarizerFactory(
                ScriptedDiarizer(
                    listOf(
                        SpeakerTurn(startMs = 0L, endMs = 1_200L, speaker = 4),
                        SpeakerTurn(startMs = 1_300L, endMs = 3_000L, speaker = 2)
                    )
                )
            ),
            settings = settings,
            readSamples = { FloatArray(64_000) }
        )

        useCase.run(id)

        val segments = repo.getSegments(id)
        assertEquals(listOf("Are you coming", "Yes I am"), segments.map { it.text })
        assertEquals(listOf(0, 1), segments.map { it.speakerIndex })
        assertEquals(listOf(0L, 1_500L), segments.map { it.startMs })
        assertEquals(listOf("Speaker 1", "Speaker 2"), repo.getSpeakers(id).map { it.label })
        assertEquals(SessionStatus.DIARIZED, repo.getSession(id)!!.status)
    }

    /**
     * "Re-identify speakers" with a count saves it on the session; a
     * later run with no count (e.g. after continuing the recording)
     * reuses it instead of the Settings default.
     */
    @Test
    fun run_withSpeakerCount_savesItOnTheSessionAndLaterRunsReuseIt() = runTest {
        val id = repo.createSession(title = "Test", language = "en")
        repo.addSegment(id, startMs = 0L, endMs = 1000L, text = "first")
        val diarizer = ScriptedDiarizer(listOf(SpeakerTurn(startMs = 0L, endMs = 1000L, speaker = 0)))
        val useCase = DiarizeSessionUseCase(
            repository = repo,
            diarizerFactory = ScriptedDiarizerFactory(diarizer),
            settings = settings,
            readSamples = { FloatArray(16_000) }
        )
        assertEquals(-1, settings.settings.value.numSpeakers)

        useCase.run(id, numSpeakers = 3)
        assertEquals(3, diarizer.lastNumSpeakers)
        assertEquals(3, repo.getSession(id)!!.numSpeakers)

        useCase.run(id)
        assertEquals(3, diarizer.lastNumSpeakers)

        useCase.run(id, numSpeakers = -1)
        assertEquals(-1, diarizer.lastNumSpeakers)
        assertEquals(-1, repo.getSession(id)!!.numSpeakers)
    }

    /**
     * Re-diarizing (after continuing a recording) keeps a speaker
     * name the user typed instead of resetting it to "Speaker N".
     */
    @Test
    fun run_again_afterContinuingRecording_keepsCustomSpeakerNames() = runTest {
        val id = repo.createSession(title = "Test", language = "en")
        repo.addSegment(id, startMs = 0L, endMs = 1000L, text = "first")
        repo.addSegment(id, startMs = 1000L, endMs = 2000L, text = "second")
        val firstRun = DiarizeSessionUseCase(
            repository = repo,
            diarizerFactory = ScriptedDiarizerFactory(
                ScriptedDiarizer(
                    listOf(
                        SpeakerTurn(startMs = 0L, endMs = 1000L, speaker = 0),
                        SpeakerTurn(startMs = 1000L, endMs = 2000L, speaker = 1)
                    )
                )
            ),
            settings = settings,
            readSamples = { FloatArray(32_000) }
        )
        firstRun.run(id)
        repo.renameSpeaker(id, speakerIndex = 1, label = "Bob")

        // More audio was appended: a third segment, spoken by Bob.
        repo.addSegment(id, startMs = 2000L, endMs = 3000L, text = "third")
        val secondRun = DiarizeSessionUseCase(
            repository = repo,
            diarizerFactory = ScriptedDiarizerFactory(
                ScriptedDiarizer(
                    listOf(
                        SpeakerTurn(startMs = 0L, endMs = 1000L, speaker = 0),
                        SpeakerTurn(startMs = 1000L, endMs = 3000L, speaker = 1)
                    )
                )
            ),
            settings = settings,
            readSamples = { FloatArray(48_000) }
        )
        secondRun.run(id)

        val labels = repo.getSpeakers(id).associate { it.speakerIndex to it.label }
        val bobIndex = repo.getSegments(id).single { it.text == "third" }.speakerIndex
        assertEquals("Bob", labels[bobIndex])
        assertEquals(setOf("Speaker 1", "Bob"), labels.values.toSet())
    }

    /**
     * Acceptance test 2: a diarizer that throws leaves the session
     * in `DIARIZATION_FAILED` and the segment rows untouched (no
     * speaker index, no speaker rows created).
     */
    @Test
    fun run_withThrowingDiarizer_leavesSessionInDiarizationFailed() = runTest {
        val id = repo.createSession(title = "Test", language = "en")
        val seg1 = repo.addSegment(id, startMs = 0L, endMs = 1000L, text = "first")
        val seg2 = repo.addSegment(id, startMs = 1000L, endMs = 2000L, text = "second")
        val factory = ScriptedDiarizerFactory(ThrowingDiarizer("engine exploded"))
        val useCase = DiarizeSessionUseCase(
            repository = repo,
            diarizerFactory = factory,
            settings = settings,
            readSamples = { FloatArray(16_000) }
        )

        useCase.run(id)

        val session = repo.getSession(id)!!
        assertEquals(SessionStatus.DIARIZATION_FAILED, session.status)

        // Segments are still untouched: speaker index is null
        // because no assignment ever landed.
        val segments = repo.getSegments(id)
        assertEquals(listOf(null, null), segments.map { it.speakerIndex })
        // No speakers were ever written.
        assertTrue(repo.getSpeakers(id).isEmpty())
        // The factory was invoked exactly once; the use case
        // did not retry.
        assertEquals(1, factory.created)
    }

    /**
     * Acceptance test 3: a session with zero segments goes
     * straight to `DIARIZED` without the diarizer factory ever
     * being called.
     */
    @Test
    fun run_withZeroSegments_skipsDiarizer_andGoesStraightToDiarized() = runTest {
        val id = repo.createSession(title = "Test", language = "en")
        val factory = ScriptedDiarizerFactory(
            ScriptedDiarizer(turns = emptyList())
        )
        val useCase = DiarizeSessionUseCase(
            repository = repo,
            diarizerFactory = factory,
            settings = settings,
            readSamples = { FloatArray(16_000) }
        )

        useCase.run(id)

        val session = repo.getSession(id)!!
        assertEquals(SessionStatus.DIARIZED, session.status)
        // Factory was never created — the zero-segment shortcut
        // bypasses the engine entirely.
        assertEquals(0, factory.created)
        // No speakers written.
        assertTrue(repo.getSpeakers(id).isEmpty())
    }

    /**
     * Acceptance test 4: with an enrolled "Alice" whose
     * reference embedding equals the fake embedder's output for
     * every speaker window (we use a constant `unit(0°)`), the
     * post-diarization naming pass labels speaker 0 with
     * "Alice", and speaker 1 falls back to the default
     * "Speaker 2" label (no second enrollment to match
     * against). Confirms the wiring end-to-end and that
     * [SpeakerAssigner.Assignment.rawToIndex] is actually
     * consulted by the embedder's `speakerMap`.
     */
    @Test
    fun run_withEnrolledSpeaker_labelsMatchedSpeakerByName() = runTest {
        val id = repo.createSession(title = "Test", language = "en")
        repo.addSegment(id, startMs = 0L, endMs = 5_000L, text = "first")
        repo.addSegment(id, startMs = 5_000L, endMs = 10_000L, text = "second")
        // Two raw engine speakers, both renumbered 0 and 1 by
        // SpeakerAssigner. The fake embedder returns
        // `unit(0°)`, so whichever speaker window we feed it
        // has cosine 1.0 against "Alice"'s reference.
        val turns = listOf(
            SpeakerTurn(startMs = 0L, endMs = 5_000L, speaker = 7),
            SpeakerTurn(startMs = 5_000L, endMs = 10_000L, speaker = 3)
        )
        val diarizer = ScriptedDiarizer(turns)
        val diarizerFactory = ScriptedDiarizerFactory(diarizer)
        val embedderFactory = FakeSpeakerEmbedderFactory(FakeSpeakerEmbedder())
        val enrolledStore = FakeEnrolledSpeakerStore("Alice" to unit(0.0))
        // Force a very loose threshold so the cosine-1.0 match
        // clears it without contention from any other enrolled
        // vector.
        settings.update { it.copy(speakerMatchThreshold = 0.3f) }
        // 12 s of audio so the collector's MIN_COLLECTED_MS
        // (2 000 ms) per speaker is comfortably cleared; both
        // turns land inside the recording window.
        val useCase = DiarizeSessionUseCase(
            repository = repo,
            diarizerFactory = diarizerFactory,
            settings = settings,
            embedderFactory = embedderFactory,
            enrolled = enrolledStore,
            readSamples = { FloatArray(12 * 16_000) }
        )

        useCase.run(id)
        advanceUntilIdle()

        val session = repo.getSession(id)!!
        assertEquals(SessionStatus.DIARIZED, session.status)
        val speakers = repo.getSpeakers(id).sortedBy { it.speakerIndex }
        assertEquals(2, speakers.size)
        // First renumbered index (0) was matched to "Alice" — the
        // SpeakerAssigner assigns raw speaker 7 → index 0 first
        // because segment 0 starts at 0 ms.
        assertEquals("Alice", speakers[0].label)
        assertEquals(0, speakers[0].speakerIndex)
        // The second renumbered index (1) has no match — default
        // label applies.
        assertEquals("Speaker 2", speakers[1].label)
        assertEquals(1, speakers[1].speakerIndex)

        // The embedder factory was used (the pass runs because
        // the user has at least one enrollment) and the embedder
        // was closed via `use { }`.
        assertEquals(1, embedderFactory.createCalls)
    }

    /**
     * Acceptance test 5: a session that finds *zero*
     * enrolled speakers must short-circuit the embedding pass
     * entirely. The use case must not call `create()` on the
     * embedder factory (no native model load) and must land
     * with default labels.
     */
    @Test
    fun run_withNoEnrolledSpeakers_neverCreatesEmbedder() = runTest {
        val id = repo.createSession(title = "Test", language = "en")
        repo.addSegment(id, startMs = 0L, endMs = 5_000L, text = "first")
        repo.addSegment(id, startMs = 5_000L, endMs = 10_000L, text = "second")
        val turns = listOf(
            SpeakerTurn(startMs = 0L, endMs = 5_000L, speaker = 0),
            SpeakerTurn(startMs = 5_000L, endMs = 10_000L, speaker = 1)
        )
        val diarizerFactory = ScriptedDiarizerFactory(ScriptedDiarizer(turns))
        val embedderFactory = FakeSpeakerEmbedderFactory(FakeSpeakerEmbedder())
        val useCase = DiarizeSessionUseCase(
            repository = repo,
            diarizerFactory = diarizerFactory,
            settings = settings,
            embedderFactory = embedderFactory,
            enrolled = FakeEnrolledSpeakerStore(emptyList()),
            readSamples = { FloatArray(16_000) }
        )

        useCase.run(id)

        val session = repo.getSession(id)!!
        assertEquals(SessionStatus.DIARIZED, session.status)
        // Embedder factory was never asked to build anything:
        // the empty-enrolled short-circuit runs first.
        assertEquals(0, embedderFactory.createCalls)
        // Default labels are preserved.
        val speakers = repo.getSpeakers(id).sortedBy { it.speakerIndex }
        assertEquals(listOf("Speaker 1", "Speaker 2"), speakers.map { it.label })
    }

    /**
     * Acceptance test 6: a throw in the embedder does NOT
     * defeat the diarization. The session still lands in
     * `DIARIZED`, no exception leaks out, and the speakers
     * table falls back to default labels. This is the v2
     * "embedding failures must not fail diarization" guarantee.
     */
    @Test
    fun run_withThrowingEmbedder_stillDiarizesWithDefaultLabels() = runTest {
        val id = repo.createSession(title = "Test", language = "en")
        repo.addSegment(id, startMs = 0L, endMs = 5_000L, text = "first")
        repo.addSegment(id, startMs = 5_000L, endMs = 10_000L, text = "second")
        val turns = listOf(
            SpeakerTurn(startMs = 0L, endMs = 5_000L, speaker = 0),
            SpeakerTurn(startMs = 5_000L, endMs = 10_000L, speaker = 1)
        )
        val diarizerFactory = ScriptedDiarizerFactory(ScriptedDiarizer(turns))
        val embedderFactory = FakeSpeakerEmbedderFactory(ThrowingSpeakerEmbedder())
        val enrolledStore = FakeEnrolledSpeakerStore("Alice" to unit(0.0))
        val useCase = DiarizeSessionUseCase(
            repository = repo,
            diarizerFactory = diarizerFactory,
            settings = settings,
            embedderFactory = embedderFactory,
            enrolled = enrolledStore,
            readSamples = { FloatArray(16_000) }
        )

        useCase.run(id)

        val session = repo.getSession(id)!!
        assertEquals(SessionStatus.DIARIZED, session.status)
        val speakers = repo.getSpeakers(id).sortedBy { it.speakerIndex }
        assertEquals(2, speakers.size)
        // No name survived — the embedder threw, the fallback
        // labels take over.
        assertEquals("Speaker 1", speakers[0].label)
        assertEquals("Speaker 2", speakers[1].label)
    }

    /**
     * Expected participants limit the matching: both speakers sound
     * exactly like Bob, but only Alice was expected, so Bob's name
     * never appears and the closer Alice match wins. With anyone
     * expected, Bob is matched as before.
     */
    @Test
    fun run_withParticipants_neverAssignsANameOutsideThem() = runTest {
        suspend fun labelsFor(participants: List<String>?): List<String> {
            val id = repo.createSession(title = "Test", language = "en", participants = participants)
            repo.addSegment(id, startMs = 0L, endMs = 5_000L, text = "first")
            repo.addSegment(id, startMs = 5_000L, endMs = 10_000L, text = "second")
            val turns = listOf(
                SpeakerTurn(startMs = 0L, endMs = 5_000L, speaker = 0),
                SpeakerTurn(startMs = 5_000L, endMs = 10_000L, speaker = 1)
            )
            settings.update { it.copy(speakerMatchThreshold = 0.3f) }
            DiarizeSessionUseCase(
                repository = repo,
                diarizerFactory = ScriptedDiarizerFactory(ScriptedDiarizer(turns)),
                settings = settings,
                embedderFactory = FakeSpeakerEmbedderFactory(FakeSpeakerEmbedder()),
                enrolled = FakeEnrolledSpeakerStore("Bob" to unit(0.0), "Alice" to unit(60.0)),
                readSamples = { FloatArray(12 * 16_000) }
            ).run(id)
            return repo.getSpeakers(id).sortedBy { it.speakerIndex }.map { it.label }
        }

        val onlyAlice = labelsFor(listOf("Alice"))
        assertFalse(onlyAlice.toString(), "Bob" in onlyAlice)
        assertEquals(listOf("Alice", "Speaker 2"), onlyAlice)

        assertEquals("Bob", labelsFor(null).first())

        // Nobody enrolled is expected: nothing to match.
        assertEquals(listOf("Speaker 1", "Speaker 2"), labelsFor(listOf("Carol")))
    }

    /** The speaker count chosen before Start drives the first diarization. */
    @Test
    fun run_usesTheSpeakerCountChosenAtStart() = runTest {
        settings.update { it.copy(numSpeakers = -1) }
        val id = repo.createSession(title = "Test", language = "en", numSpeakers = 2)
        repo.addSegment(id, startMs = 0L, endMs = 5_000L, text = "first")
        val diarizer = ScriptedDiarizer(listOf(SpeakerTurn(startMs = 0L, endMs = 5_000L, speaker = 0)))
        DiarizeSessionUseCase(
            repository = repo,
            diarizerFactory = ScriptedDiarizerFactory(diarizer),
            settings = settings,
            readSamples = { FloatArray(16_000) }
        ).run(id)
        assertEquals(2, diarizer.lastNumSpeakers)
    }

    private suspend fun twoLineSession(): Long {
        val id = repo.createSession(title = "Test", language = "en")
        repo.addSegment(id, startMs = 0L, endMs = 1000L, text = "first")
        repo.addSegment(id, startMs = 1000L, endMs = 2000L, text = "second")
        repo.finishRecording(id, 2_000L)
        return id
    }

    private fun twoSpeakerUseCase(
        factory: DiarizerFactory = ScriptedDiarizerFactory(
            ScriptedDiarizer(listOf(SpeakerTurn(0L, 1000L, 0), SpeakerTurn(1000L, 2000L, 1)))
        ),
        charging: Boolean = false,
        pendingCalls: AtomicInteger = AtomicInteger()
    ) = DiarizeSessionUseCase(
        repository = repo,
        diarizerFactory = factory,
        settings = settings,
        readSamples = { FloatArray(16_000) },
        isCharging = { charging },
        onPending = { pendingCalls.incrementAndGet() }
    )

    /** Automatically (the default) diarizes at once, charging or not (issue #11). */
    @Test
    fun runOrDefer_automatically_runsOnBattery() = runTest {
        val id = twoLineSession()
        val pending = AtomicInteger()

        assertTrue(twoSpeakerUseCase(charging = false, pendingCalls = pending).runOrDefer(id))

        assertEquals(SessionStatus.DIARIZED, repo.getSession(id)!!.status)
        assertEquals(0, pending.get())
    }

    @Test
    fun runOrDefer_whileCharging_defersOnBattery_andRunsWhenCharging() = runTest {
        settings.update { it.copy(speakerIdMode = "charging") }
        val onBattery = twoLineSession()
        val factory = ScriptedDiarizerFactory(ScriptedDiarizer(listOf(SpeakerTurn(0L, 2000L, 0))))
        val pending = AtomicInteger()

        assertFalse(twoSpeakerUseCase(factory, charging = false, pendingCalls = pending).runOrDefer(onBattery))

        assertEquals(SessionStatus.DIARIZATION_PENDING, repo.getSession(onBattery)!!.status)
        assertEquals(listOf(null, null), repo.getSegments(onBattery).map { it.speakerIndex })
        assertEquals(0, factory.created)
        assertEquals(1, pending.get())

        val charging = twoLineSession()
        assertTrue(twoSpeakerUseCase(factory, charging = true, pendingCalls = pending).runOrDefer(charging))
        assertEquals(SessionStatus.DIARIZED, repo.getSession(charging)!!.status)
        assertEquals(1, pending.get())
    }

    @Test
    fun runOrDefer_onDemand_defersEvenWhileCharging_butRunStillWorks() = runTest {
        settings.update { it.copy(speakerIdMode = "manual") }
        val id = twoLineSession()
        val useCase = twoSpeakerUseCase(charging = true)

        assertFalse(useCase.runOrDefer(id))
        assertEquals(SessionStatus.DIARIZATION_PENDING, repo.getSession(id)!!.status)

        // The manual action ("Identify speakers now") ignores the setting.
        useCase.run(id)
        assertEquals(SessionStatus.DIARIZED, repo.getSession(id)!!.status)
    }

    /**
     * Cancelling a run (the charger unplugged) stops the engine at its
     * next progress step and leaves the session pending, not failed or
     * stuck in DIARIZING.
     */
    @Test
    fun run_cancelled_stopsTheEngine_andLeavesTheSessionPending() = runBlocking {
        val id = twoLineSession()
        val started = CompletableDeferred<Unit>()
        val sawCancel = AtomicBoolean(false)
        val blocking = object : Diarizer {
            override fun diarize(
                samples: FloatArray,
                numSpeakers: Int,
                threshold: Float,
                onProgress: (Float) -> Unit,
                isCancelled: () -> Boolean
            ): List<SpeakerTurn> {
                started.complete(Unit)
                while (!isCancelled()) Thread.sleep(5)
                sawCancel.set(true)
                return emptyList()
            }

            override fun close() = Unit
        }
        val pending = AtomicInteger()
        val useCase = twoSpeakerUseCase(ScriptedDiarizerFactory(blocking), pendingCalls = pending)

        val job = launch(Dispatchers.Default) { useCase.run(id) }
        started.await()
        assertEquals(SessionStatus.DIARIZING, repo.getSession(id)!!.status)
        job.cancelAndJoin()

        assertTrue(sawCancel.get())
        assertEquals(SessionStatus.DIARIZATION_PENDING, repo.getSession(id)!!.status)
        assertEquals(1, pending.get())
    }

    /** The charging job's entry point leaves alone a session that is no longer pending. */
    @Test
    fun runIfPending_runsOnlyPendingSessions() = runTest {
        val diarized = twoLineSession()
        val factory = ScriptedDiarizerFactory(ScriptedDiarizer(listOf(SpeakerTurn(0L, 2000L, 0))))
        val useCase = twoSpeakerUseCase(factory)
        useCase.run(diarized)
        assertEquals(1, factory.created)

        assertFalse(useCase.runIfPending(diarized))
        assertEquals(1, factory.created)

        val pending = twoLineSession()
        useCase.defer(pending)
        assertTrue(useCase.runIfPending(pending))
        assertEquals(SessionStatus.DIARIZED, repo.getSession(pending)!!.status)
        assertEquals(2, factory.created)
    }
}

/** 2-D unit vector at [degrees] from the x-axis. */
private fun unit(degrees: Double): FloatArray {
    val rad = Math.toRadians(degrees)
    return floatArrayOf(Math.cos(rad).toFloat(), Math.sin(rad).toFloat())
}

/**
 * Little-endian float32 packing. Mirrors
 * [com.convoscribe.data.toBytes] so the fake store's observeAll
 * hands back the same encoding Room would store on disk.
 */
private fun FloatArray.toBytes(): ByteArray {
    val buf = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
    buf.asFloatBuffer().put(this)
    return buf.array()
}
