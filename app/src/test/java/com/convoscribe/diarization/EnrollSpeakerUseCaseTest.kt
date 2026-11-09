package com.convoscribe.diarization

import com.convoscribe.audio.AudioSource
import com.convoscribe.data.EnrolledSpeakerEntity
import com.convoscribe.data.EnrolledSpeakerStore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for [EnrollSpeakerUseCase]. The sherpa
 * embedder only loads on a device (its `loadLibrary("sherpa-onnx-jni")`
 * call has no JVM analogue), so the suite injects fakes for both
 * the [AudioSource] (scripted "always emit 1 600 samples" chunks
 * of mute audio) and the [SpeakerEmbedder] (returns a tiny
 * vector derived from the input size). The
 * [EnrolledSpeakerStore] is also an in-memory fake so we can
 * assert on what got saved without spinning up Room.
 *
 * Coverage mirrors the task's three acceptance bullets:
 *  * `record(1000)` returns exactly 16 000 samples and the
 *    progress callback reaches 1.0;
 *  * `enroll(" Alice ", 64 000 samples)` trims the name and
 *    saves the embedding under "Alice";
 *  * `enroll` with 16 000 samples or a blank name throws
 *    [IllegalArgumentException] and saves nothing.
 */
class EnrollSpeakerUseCaseTest {

    /**
     * Fake [AudioSource] that emits 1 600-sample (100 ms at 16 kHz)
     * chunks of mute audio. The [stopAfter] hook lets a test end
     * the flow after a fixed number of chunks so we can assert
     * on a deterministic recording; without it the flow emits
     * chunks forever (matching the real mic's behaviour).
     *
     * [chunksEmitted] counts a chunk before it is handed on, so
     * the chunk whose `emit` ends the collection is counted too.
     * [released] is set in a `finally`, the way the real mic
     * stops and releases its `AudioRecord`.
     */
    private class FakeAudioSource(private val stopAfter: Int? = null) : AudioSource {
        var chunksEmitted: Int = 0
            private set
        var released: Boolean = false
            private set
        override fun chunks(): Flow<ShortArray> = flow {
            try {
                while (stopAfter == null || chunksEmitted < stopAfter) {
                    chunksEmitted += 1
                    emit(ShortArray(1600))
                }
            } finally {
                released = true
            }
        }
    }

    /**
     * Fake [SpeakerEmbedder] that returns a tiny embedding
     * derived from the input size (the first slot holds the
     * sample count, the second slot is a constant marker). The
     * factory records every call so we can assert the use case
     * created one and disposed it.
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
            return floatArrayOf(samples.size.toFloat(), 1f)
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
     * In-memory fake [EnrolledSpeakerStore]. Only [save] and
     * [getAll] are exercised by the use case today; [observeAll]
     * stays in place so a future caller can compose enrollment
     * with a Flow-driven UI without spinning up Room.
     */
    private class FakeEnrolledSpeakerStore : EnrolledSpeakerStore {
        data class Row(val name: String, val embedding: FloatArray)
        val rows: MutableList<Row> = mutableListOf()

        override fun observeAll(): Flow<List<EnrolledSpeakerEntity>> = flow {
            emit(
                rows.map { row ->
                    EnrolledSpeakerEntity(
                        id = 0L,
                        name = row.name,
                        embedding = floatArrayToBytes(row.embedding),
                        createdAt = 0L
                    )
                }
            )
        }

        override suspend fun getAll(): List<SpeakerMatcher.Enrolled> = rows.map {
            SpeakerMatcher.Enrolled(it.name, it.embedding)
        }

        override suspend fun save(name: String, embedding: FloatArray) {
            rows.add(Row(name, embedding.copyOf()))
        }

        @Suppress("unused")
        override suspend fun delete(id: Long) {
            rows.clear()
        }
    }

    /**
     * Acceptance test 1: `record(1000)` collects exactly 16 000
     * mono samples (`1 s * 16 kHz`), truncates the final chunk so
     * the returned array is the requested length, and emits the
     * progress callback up to 1.0.
     */
    @Test
    fun record_truncatesFinalChunkToExactRequestedLength_andReachesOneProgress() = runTest {
        val source = FakeAudioSource(stopAfter = 100)
        val useCase = EnrollSpeakerUseCase(
            audioSource = source,
            embedderFactory = FakeSpeakerEmbedderFactory(FakeSpeakerEmbedder()),
            repo = FakeEnrolledSpeakerStore()
        )

        val progress = mutableListOf<Float>()
        val samples = useCase.record(durationMs = 1000L) { p -> progress.add(p) }

        assertEquals(16_000, samples.size)
        // The last progress emission is exactly 1.0; earlier
        // emissions may also be 1.0 if a chunk landed exactly on
        // the target.
        assertTrue(
            "progress should have reached 1.0; got ${progress.toList()}",
            progress.last() == 1.0f
        )
        assertTrue(progress.isNotEmpty())
        assertTrue(
            "progress should be monotonically non-decreasing; got ${progress.toList()}",
            progress.zipWithNext().all { (a, b) -> b >= a }
        )
    }

    /**
     * The real mic never completes, so `record` must end the
     * collection itself once it has the requested length (#146).
     * 1 s at 16 kHz is ten 1 600-sample chunks: no eleventh chunk
     * is read, and the source's `finally` runs (the mic is
     * released). The timeout turns a regression into a failure
     * instead of a hung test run.
     */
    @Test(timeout = 10_000L)
    fun record_fromSourceThatNeverCompletes_returnsRequestedLength_andReleasesSource() = runTest {
        val source = FakeAudioSource()
        val useCase = EnrollSpeakerUseCase(
            audioSource = source,
            embedderFactory = FakeSpeakerEmbedderFactory(FakeSpeakerEmbedder()),
            repo = FakeEnrolledSpeakerStore()
        )

        val progress = mutableListOf<Float>()
        val samples = useCase.record(durationMs = 1000L) { p -> progress.add(p) }

        assertEquals(16_000, samples.size)
        assertEquals(10, source.chunksEmitted)
        assertTrue("the source should have been released", source.released)
        assertEquals(1.0f, progress.last(), 0f)
    }

    /**
     * A source that ends before the requested length still makes
     * `record` return what it got; `enroll` is what rejects a
     * sample that is too short.
     */
    @Test(timeout = 10_000L)
    fun record_fromSourceThatEndsEarly_returnsWhatWasCollected() = runTest {
        val source = FakeAudioSource(stopAfter = 5)
        val useCase = EnrollSpeakerUseCase(
            audioSource = source,
            embedderFactory = FakeSpeakerEmbedderFactory(FakeSpeakerEmbedder()),
            repo = FakeEnrolledSpeakerStore()
        )

        val progress = mutableListOf<Float>()
        val samples = useCase.record(durationMs = 1000L) { p -> progress.add(p) }

        assertEquals(8_000, samples.size)
        assertEquals(5, source.chunksEmitted)
        assertEquals(0.5f, progress.last(), 0.0001f)
    }

    /**
     * Acceptance test 2: `enroll(" Alice ", 64 000 samples)` saves
     * the speaker under the trimmed name "Alice" with the embedder's
     * returned fake vector.
     *
     * 64 000 samples = 4 s of 16 kHz audio, well over the
     * `MIN_SAMPLES` floor of 48 000.
     */
    @Test
    fun enroll_trimsName_andSavesFakeEmbedding() = runBlocking {
        val store = FakeEnrolledSpeakerStore()
        val embedder = FakeSpeakerEmbedder()
        val factory = FakeSpeakerEmbedderFactory(embedder)
        val useCase = EnrollSpeakerUseCase(
            audioSource = FakeAudioSource(),
            embedderFactory = factory,
            repo = store
        )

        useCase.enroll(name = " Alice ", samples = FloatArray(64_000) { 0f })

        assertEquals("Exactly one enrollment should have been saved", 1, store.rows.size)
        val row = store.rows.single()
        assertEquals("Alice", row.name)
        assertEquals(64_000f, row.embedding[0], 0f)
        assertEquals(1f, row.embedding[1], 0f)
        // Factory created a single embedder and the use case
        // closed it (the embedding is short-lived).
        assertEquals(1, factory.createCalls)
        assertEquals(1, embedder.embedCalls)
        assertEquals(64_000, embedder.lastSamplesSize)
        assertTrue(embedder.closed)
    }

    /**
     * Acceptance test 3, case A: too-short audio throws
     * `IllegalArgumentException` and nothing is saved. The
     * threshold is 48 000 samples (3 s at 16 kHz), so 16 000 is
     * firmly below.
     */
    @Test
    fun enroll_withTooFewSamples_throws_andDoesNotSave() = runBlocking {
        val store = FakeEnrolledSpeakerStore()
        val embedder = FakeSpeakerEmbedder()
        val useCase = EnrollSpeakerUseCase(
            audioSource = FakeAudioSource(),
            embedderFactory = FakeSpeakerEmbedderFactory(embedder),
            repo = store
        )

        val thrown = runCatching {
            useCase.enroll(name = "Alice", samples = FloatArray(16_000))
        }.exceptionOrNull()
        assertNotNull("Expected IllegalArgumentException, but enroll returned normally", thrown)
        assertTrue(
            "expected IllegalArgumentException, got ${thrown!!::class.java.simpleName}",
            thrown is IllegalArgumentException
        )

        assertTrue("no enrollment should have been saved", store.rows.isEmpty())
        // The use case must not have built an embedder or called
        // into it: the validation runs before the engine is
        // touched, so the sherpa model is never loaded for
        // bad calls.
        assertEquals(0, embedder.embedCalls)
    }

    /**
     * Acceptance test 3, case B: a blank name (whitespace only)
     * throws `IllegalArgumentException` and saves nothing.
     */
    @Test
    fun enroll_withBlankName_throws_andDoesNotSave() = runBlocking {
        val store = FakeEnrolledSpeakerStore()
        val useCase = EnrollSpeakerUseCase(
            audioSource = FakeAudioSource(),
            embedderFactory = FakeSpeakerEmbedderFactory(FakeSpeakerEmbedder()),
            repo = store
        )

        val thrown = runCatching {
            useCase.enroll(name = "   ", samples = FloatArray(64_000))
        }.exceptionOrNull()
        assertNotNull("Expected IllegalArgumentException, but enroll returned normally", thrown)
        assertTrue(
            "expected IllegalArgumentException, got ${thrown!!::class.java.simpleName}",
            thrown is IllegalArgumentException
        )

        assertTrue(store.rows.isEmpty())
    }

    /**
     * An empty-string name should also be rejected (the
     * `requireNotBlank` path covers both whitespace and empty).
     */
    @Test
    fun enroll_withEmptyName_throws_andDoesNotSave() = runBlocking {
        val store = FakeEnrolledSpeakerStore()
        val useCase = EnrollSpeakerUseCase(
            audioSource = FakeAudioSource(),
            embedderFactory = FakeSpeakerEmbedderFactory(FakeSpeakerEmbedder()),
            repo = store
        )

        val thrown = runCatching {
            useCase.enroll(name = "", samples = FloatArray(64_000))
        }.exceptionOrNull()
        assertNotNull("Expected IllegalArgumentException, but enroll returned normally", thrown)
        assertTrue(
            "expected IllegalArgumentException, got ${thrown!!::class.java.simpleName}",
            thrown is IllegalArgumentException
        )

        assertTrue(store.rows.isEmpty())
    }

    /**
     * Sanity check the fake `EnrolledSpeakerStore`: it's wired to
     * behave like the real one for `save`. A direct save followed
     * by a list confirms the fake can stand in for Room in a
     * future test that composes enrollment with matching.
     */
    @Test
    fun fakeStore_saveAndRetrieveWorks() = runBlocking {
        val store = FakeEnrolledSpeakerStore()
        store.save(name = "Bob", embedding = floatArrayOf(0.5f))
        val all = store.getAll()
        assertEquals(1, all.size)
        assertEquals("Bob", all[0].name)
        assertEquals(0.5f, all[0].embedding[0], 0f)
    }

    /**
     * Pack a `FloatArray` as little-endian float32 bytes.
     * Mirrors [com.convoscribe.data.toBytes] so the fake store's
     * `observeAll` returns the same encoding Room would store
     * on disk.
     */
    private companion object {
        fun floatArrayToBytes(floats: FloatArray): ByteArray {
            val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            buf.asFloatBuffer().put(floats)
            return buf.array()
        }
    }
}
