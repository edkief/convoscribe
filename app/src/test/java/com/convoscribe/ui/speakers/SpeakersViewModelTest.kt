package com.convoscribe.ui.speakers

import com.convoscribe.R
import com.convoscribe.audio.AudioSource
import com.convoscribe.data.EnrolledSpeakerEntity
import com.convoscribe.data.EnrolledSpeakerStore
import com.convoscribe.diarization.EnrollSpeakerUseCase
import com.convoscribe.diarization.SpeakerEmbedder
import com.convoscribe.diarization.SpeakerEmbedderFactory
import com.convoscribe.diarization.SpeakerMatcher
import com.convoscribe.ui.UiText
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pure-JVM unit tests for [SpeakersViewModel]. Uses a fake
 * [EnrolledSpeakerStore] backed by an in-memory list and a fake
 * [EnrollSpeakerUseCase] whose `record` returns a deterministic
 * mute buffer immediately and whose `enroll` writes through the
 * same fake store. No sherpa classes are referenced — the
 * production wiring is replaced end-to-end with fakes that
 * exercise the same coroutine machinery (`viewModelScope`,
 * `kotlinx.coroutines.test.runTest`) without needing the native
 * engine.
 *
 * Coverage maps onto the task's acceptance bullets:
 *  * `startEnrollment` with a non-blank name drives the state
 *    machine through `RECORDING → PROCESSING → IDLE` and ends
 *    with the new name in the list and `enrollName` cleared;
 *  * `startEnrollment` with a blank name short-circuits to
 *    `IDLE` with the "Speaker name must not be blank" error and
 *    never touches the audio source;
 *  * `delete(id)` removes the row from the store, and the flow
 *    the view model collects from re-emits without it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SpeakersViewModelTest {

    /**
     * Standard test dispatcher wired to `Dispatchers.Main`. We
     * override the fake use case's `enroll` so the production
     * `withContext(Dispatchers.Default)` switch is bypassed;
     * with the fake in place `runTest`'s `runCurrent()` ticks
     * the entire `record` → `enroll` pipeline without crossing
     * to a real dispatcher.
     *
     * Standard over Unconfined: `StandardTestDispatcher` queues
     * tasks so the `init` view-model collector's indefinite
     * `repo.observeAll().collect { }` is parked as a
     * `state.value` subscription rather than running eagerly
     * on the test thread. The test JVM keeps that subscriber
     * alive; paired with `Dispatchers.resetMain()` in
     * [tearDown] the cleanup produces no concurrent
     * `setMain` / `resetMain` races (the same pattern
     * `SessionViewModelTest` uses).
     */
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        // The view model collects the repository flow through
        // `viewModelScope`, which defaults to
        // `Dispatchers.Main.immediate`. Robolectric gives us a
        // real main looper, but `Dispatchers.Main` only resolves
        // it once `setMain` has wired the test dispatcher —
        // otherwise the MainCoroutineDispatcher initialiser
        // throws `Module with the Main dispatcher had failed to
        // initialize`.
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Placeholder [AudioSource] that returns nothing. The fake
     * use case overrides `record` and never touches it, so the
     * placeholder suffices as a constructor argument. Kept
     * concrete here only because [EnrollSpeakerUseCase]'s primary
     * constructor still demands one.
     */
    private class FakeAudioSource : AudioSource {
        override fun chunks(): Flow<ShortArray> = flow { /* never emits */ }
    }

    /**
     * Trivial [SpeakerEmbedder] used only to satisfy the
     * [EnrollSpeakerUseCase] primary constructor. The fake use
     * case overrides [EnrollSpeakerUseCase.enroll] and never
     * calls `embed`, so this implementation is dead code at
     * test time.
     */
    private class FakeSpeakerEmbedder : SpeakerEmbedder {
        override fun embed(samples: FloatArray): FloatArray = FloatArray(0)
        override fun close() {}
    }

    private class FakeSpeakerEmbedderFactory(
        private val embedder: SpeakerEmbedder
    ) : SpeakerEmbedderFactory {
        override fun create(): SpeakerEmbedder = embedder
    }

    /**
     * In-memory fake [EnrolledSpeakerStore]. Exposes the rows as
     * a [MutableStateFlow] mirroring what Room's `observeAll`
     * would emit, and supports `save` / `delete` so the view
     * model's repository collector reactively reflects inserts
     * and deletes. `save` reflects the upsert semantics
     * (`ON CONFLICT REPLACE` on the unique name index): saving
     * the same name twice replaces the row.
     */
    private class FakeEnrolledSpeakerStore : EnrolledSpeakerStore {
        data class Row(val id: Long, val name: String, val embedding: FloatArray)

        private val rows: MutableList<Row> = mutableListOf()
        private val nextId: Long get() = (rows.maxOfOrNull { it.id } ?: 0L) + 1L
        private val flow: MutableStateFlow<List<EnrolledSpeakerEntity>> =
            MutableStateFlow(emptyList())

        override fun observeAll(): Flow<List<EnrolledSpeakerEntity>> = flow

        override suspend fun getAll(): List<SpeakerMatcher.Enrolled> = rows.map {
            SpeakerMatcher.Enrolled(it.name, it.embedding)
        }

        override suspend fun save(name: String, embedding: FloatArray) {
            val existing = rows.indexOfFirst { it.name == name }
            if (existing >= 0) {
                rows[existing] = rows[existing].copy(embedding = embedding.copyOf())
            } else {
                rows.add(Row(nextId, name, embedding.copyOf()))
            }
            publish()
        }

        override suspend fun delete(id: Long) {
            rows.removeAll { it.id == id }
            publish()
        }

        fun namesSnapshot(): List<String> = rows.map { it.name }

        private fun publish() {
            flow.value = rows.map {
                EnrolledSpeakerEntity(
                    id = it.id,
                    name = it.name,
                    embedding = it.embedding.toBytes(),
                    createdAt = 0L
                )
            }
        }
    }

    /**
     * Minimal stub of [EnrollSpeakerUseCase] that lets each test
     * script exactly what `record` and `enroll` do. The real use
     * case is exercised by [com.convoscribe.diarization.EnrollSpeakerUseCaseTest];
     * this test focuses on the view-model state machine, so
     * driving it through a stub avoids the multi-dispatcher
     * hopping (`viewModelScope` ⇒ `Dispatchers.Default` ⇒ back)
     * that the real use case introduces and that is awkward to
     * script under a single test scheduler.
     */
    private class FakeEnrollUseCase(
        private val store: EnrolledSpeakerStore
    ) : EnrollSpeakerUseCase(
        audioSource = FakeAudioSource(),
        embedderFactory = FakeSpeakerEmbedderFactory(FakeSpeakerEmbedder()),
        repo = store
    ) {
        var recordSamplesToReturn: FloatArray = FloatArray(240_000) { 0f }
        var recordProgress: FloatArray = floatArrayOf(0.25f, 0.5f, 0.75f, 1f)
        var recordShouldThrow: Throwable? = null
        var enrollShouldThrow: Throwable? = null
        var lastEnrollName: String? = null
            private set
        var lastEnrollSamples: FloatArray? = null
            private set
        var recordCalls: Int = 0
            private set
        var enrollCalls: Int = 0
            private set

        override suspend fun record(durationMs: Long, onProgress: (Float) -> Unit): FloatArray {
            recordCalls += 1
            recordShouldThrow?.let { throw it }
            for (p in recordProgress) onProgress(p)
            return recordSamplesToReturn
        }

        override suspend fun enroll(name: String, samples: FloatArray) {
            enrollCalls += 1
            enrollShouldThrow?.let { throw it }
            lastEnrollName = name
            lastEnrollSamples = samples
            store.save(name, samples)
        }
    }

    /**
     * Test fixture: wire the fakes together. Returns the view
     * model, the underlying store and the embedder so each test
     * can assert on whichever side it cares about.
     */
    private class Fixture(
        val viewModel: SpeakersViewModel,
        val store: FakeEnrolledSpeakerStore,
        val useCase: FakeEnrollUseCase
    )

    private fun newFixture(useCase: FakeEnrollUseCase? = null): Fixture {
        val store = FakeEnrolledSpeakerStore()
        val fakeUseCase = useCase ?: FakeEnrollUseCase(store)
        val vm = SpeakersViewModel(repo = store, enroll = fakeUseCase)
        return Fixture(vm, store, fakeUseCase)
    }

    /**
     * Acceptance test 1: a successful enrollment drives the
     * state machine through `RECORDING → PROCESSING → IDLE`,
     * clears the name and lets the speaker list contain the new
     * name. We wait on `advanceUntilIdle` so the suspending
     * chain (`record` → `enroll`) gets to complete.
     */
    @Test
    fun startEnrollment_completesReturnsToIdle_clearsName_andAddsSpeakerToList() = runTest(dispatcher) {
        val fx = newFixture()
        // The state machine is IDLE, but the names list is empty
        // until the simulated insert lands through the flow.
        fx.viewModel.setEnrollName("Alice")

        fx.viewModel.startEnrollment()
        // `Dispatchers.Main = StandardTestDispatcher` parks
        // every continuation on the test scheduler; a single
        // `runCurrent()` ticks through the IDLE → RECORDING →
        // PROCESSING → IDLE transitions and the post-enrollment
        // save through the `observeAll` flow.
        runCurrent()

        val state = fx.viewModel.state.value
        assertEquals(EnrollmentPhase.IDLE, state.phase)
        assertEquals(0f, state.progress, 0.0001f)
        assertEquals("", state.enrollName)
        assertNull(state.error)

        // The flow collector saw the upsert: the list contains
        // "Alice", and the fake use case forwards the embedder
        // step into the same store the view model collects
        // from.
        assertEquals(listOf("Alice"), fx.store.namesSnapshot())
        assertEquals(1, fx.useCase.recordCalls)
        assertEquals(1, fx.useCase.enrollCalls)
        assertEquals("Alice", fx.useCase.lastEnrollName)
        assertEquals(240_000, fx.useCase.lastEnrollSamples?.size)
    }

    /**
     * Acceptance test 2: a blank name short-circuits
     * `startEnrollment` to `IDLE` with the documentation error
     * string, and the audio source is never collected. The store
     * stays empty and the embedder is never touched.
     */
    @Test
    fun startEnrollment_withBlankName_setsError_doesNotStartRecording_andDoesNotSave() = runTest(dispatcher) {
        val fx = newFixture()
        fx.viewModel.setEnrollName("   ")

        fx.viewModel.startEnrollment()
        runCurrent()

        val state = fx.viewModel.state.value
        assertEquals(EnrollmentPhase.IDLE, state.phase)
        assertEquals(UiText.res(R.string.speakers_name_blank), state.error)
        assertTrue(
            "no row should have been saved",
            fx.store.namesSnapshot().isEmpty()
        )
        assertEquals(0, fx.useCase.recordCalls)
        assertEquals(0, fx.useCase.enrollCalls)
    }

    /**
     * Acceptance test 2 (empty-string variant): an empty name
     * hits the same short-circuit. Kept separate so a future
     * change that special-cases whitespace-only does not
     * regress the truly-empty case.
     */
    @Test
    fun startEnrollment_withEmptyName_setsError_andDoesNotSave() = runTest(dispatcher) {
        val fx = newFixture()
        fx.viewModel.setEnrollName("")

        fx.viewModel.startEnrollment()
        runCurrent()

        val state = fx.viewModel.state.value
        assertEquals(EnrollmentPhase.IDLE, state.phase)
        assertEquals(UiText.res(R.string.speakers_name_blank), state.error)
        assertTrue(fx.store.namesSnapshot().isEmpty())
    }

    /**
     * Acceptance test 3: `delete(id)` removes the row from the
     * underlying store and the flow the view model collects
     * from re-emits the updated list. Two enrollments are run
     * first so the test exercises an actual deletion rather
     * than the trivial "delete the only row" case.
     */
    @Test
    fun delete_removesRowAndFlowEmitsUpdatedList() = runTest(dispatcher) {
        val fx = newFixture()
        // Insert Alice → Bob so deletion has something to act
        // on, mirroring what the production screen does.
        fx.viewModel.setEnrollName("Alice")
        fx.viewModel.startEnrollment()
        runCurrent()
        assertEquals(EnrollmentPhase.IDLE, fx.viewModel.state.value.phase)

        fx.viewModel.setEnrollName("Bob")
        fx.viewModel.startEnrollment()
        runCurrent()
        assertEquals(EnrollmentPhase.IDLE, fx.viewModel.state.value.phase)

        assertEquals(listOf("Alice", "Bob"), fx.store.namesSnapshot())
        val aliceId = fx.viewModel.state.value.speakers
            .single { it.name == "Alice" }.id

        fx.viewModel.delete(aliceId)
        runCurrent()

        assertEquals(listOf("Bob"), fx.store.namesSnapshot())
        val state = fx.viewModel.state.value
        assertEquals(listOf("Bob"), state.speakers.map { it.name })
    }

    /**
     * Sanity check on the fake store: `save` followed by a
     * `delete` works the way the production view model relies on
     * (the flow re-emits after every write). This guards
     * against a future refactor that accidentally replaces the
     * `publish()` call with a no-op.
     */
    @Test
    fun fakeStore_saveThenDelete_roundtripsThroughFlow() = runBlocking {
        val store = FakeEnrolledSpeakerStore()
        store.save(name = "Solo", embedding = floatArrayOf(1f, 2f))
        assertEquals(listOf("Solo"), store.namesSnapshot())

        val id = store.observeAll().first().single().id
        store.delete(id)
        assertTrue(store.namesSnapshot().isEmpty())
    }

    /**
     * Pack a `FloatArray` as little-endian float32 bytes. Mirrors
     * `com.convoscribe.data.toBytes` so the fake store's
     * [EnrolledSpeakerStore.observeAll] emitter returns rows that
     * look the same way to a consumer as Room would produce.
     */
    private companion object {
        fun FloatArray.toBytes(): ByteArray {
            val buf = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
            buf.asFloatBuffer().put(this)
            return buf.array()
        }
    }
}
