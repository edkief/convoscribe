package com.convoscribe.ui.search

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.convoscribe.data.ChatMessageEntity
import com.convoscribe.data.PassageVectorEntity
import com.convoscribe.data.SearchDao
import com.convoscribe.data.SegmentEntity
import com.convoscribe.data.SegmentHit
import com.convoscribe.data.SemanticIndexDao
import com.convoscribe.data.SessionEntity
import com.convoscribe.data.SessionHits
import com.convoscribe.data.SessionIndexStateEntity
import com.convoscribe.data.SessionSearch
import com.convoscribe.data.SessionStatus
import com.convoscribe.data.SessionTagEntity
import com.convoscribe.data.SpeakerEntity
import com.convoscribe.data.TagEntity
import com.convoscribe.data.toBytes
import com.convoscribe.llm.embedding.FakeTextEmbedderFactory
import com.convoscribe.llm.embedding.SemanticSearch
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [SearchViewModel] over a fake [SearchDao] that records each FTS
 * query, on virtual time, so the debounce can be checked exactly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val dao = FakeSearchDao()
    private val viewModels = mutableListOf<SearchViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        runTest(dispatcher) {
            val jobs = viewModels.map { it.viewModelScope.coroutineContext.job }
            jobs.forEach { it.cancel() }
            jobs.joinAll()
        }
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(
        debounceMs: Long = SearchViewModel.DEBOUNCE_MS,
        semantic: SemanticSearch? = null
    ): Pair<SearchViewModel, List<SearchResults>> {
        val vm = SearchViewModel(
            search = SessionSearch(dao),
            debounceMs = debounceMs,
            clock = { NOW },
            zone = ZoneOffset.UTC,
            semantic = semantic
        ).also { viewModels += it }
        val emissions = mutableListOf<SearchResults>()
        backgroundScope.launch { vm.results.collect { emissions += it } }
        runCurrent()
        return vm to emissions
    }

    private fun semantic(embedders: FakeTextEmbedderFactory, vectorsRead: CompletableDeferred<Unit>? = null) =
        SemanticSearch(FakeIndexDao(embedders, vectorsRead), embedders, Dispatchers.Unconfined)

    private fun TestScope.typeBurst(vm: SearchViewModel, vararg steps: String) {
        steps.forEach {
            vm.onQueryChange(it)
            advanceTimeBy(100)
        }
    }

    @Test
    fun typing_searchesOnceAfterThePause() = runTest(dispatcher) {
        val (vm, emissions) = viewModel()

        typeBurst(vm, "b", "bu", "bud")
        // 300 ms after "bud" was typed is 200 ms after the burst's last step.
        advanceTimeBy(199)
        runCurrent()
        assertEquals(emptyList<String>(), dao.queries)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("bud*"), dao.queries)
        assertEquals("bud", emissions.last().query)
        assertEquals(listOf(1L), emissions.last().items.map { it.session.id })
    }

    @Test
    fun eink_waitsLongerSoABurstRefreshesOnce() = runTest(dispatcher) {
        val (vm, _) = viewModel(debounceMs = SearchViewModel.EINK_DEBOUNCE_MS)

        typeBurst(vm, "b", "bu", "bud", "budg")
        advanceTimeBy(600)
        runCurrent()
        assertEquals(emptyList<String>(), dao.queries)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf("budg*"), dao.queries)
    }

    @Test
    fun submit_searchesAtOnce_andThePauseDoesNotRepeatIt() = runTest(dispatcher) {
        val (vm, _) = viewModel(debounceMs = SearchViewModel.EINK_DEBOUNCE_MS)

        vm.onQueryChange("budget")
        vm.submit()
        runCurrent()
        assertEquals(listOf("budget*"), dao.queries)

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf("budget*"), dao.queries)
    }

    @Test
    fun filters_applyAtOnce_andUnchangedResultsAreNotReEmitted() = runTest(dispatcher) {
        val (vm, emissions) = viewModel()
        vm.onQueryChange("budget")
        advanceTimeBy(400)
        runCurrent()
        val before = emissions.size

        // Same results under a filter that excludes nothing: searched, not re-emitted.
        vm.setLanguage("en")
        runCurrent()
        assertEquals(listOf("budget*", "budget*"), dao.queries)
        assertEquals(before, emissions.size)

        vm.setDateRange(DateRange.WEEK)
        runCurrent()
        assertEquals(emptyList<Long>(), emissions.last().items.map { it.session.id })
    }

    @Test
    fun tagFilter_keepsOnlySessionsWithTheTag() = runTest(dispatcher) {
        val (vm, emissions) = viewModel()
        vm.onQueryChange("budget")
        advanceTimeBy(400)
        runCurrent()
        val id = emissions.last().items.single().session.id

        vm.setTag(5L)
        runCurrent()
        assertEquals(listOf(id), emissions.last().items.map { it.session.id })
        assertTrue(vm.filters.value.isActive)

        vm.setTag(6L)
        runCurrent()
        assertEquals(emptyList<Long>(), emissions.last().items.map { it.session.id })
    }

    @Test
    fun blankQuery_clearsResultsWithoutSearching() = runTest(dispatcher) {
        val (vm, emissions) = viewModel()
        vm.onQueryChange("   ")
        advanceTimeBy(400)
        runCurrent()
        assertEquals(emptyList<String>(), dao.queries)
        assertEquals(SearchResults(), emissions.last())
    }

    @Test
    fun meaning_wordResultsFirst_thenWithWhatWasFoundByMeaning() = runTest(dispatcher) {
        val embedders = FakeTextEmbedderFactory()
        val vectorsRead = CompletableDeferred<Unit>()
        val (vm, emissions) = viewModel(semantic = semantic(embedders, vectorsRead))
        vm.onQueryChange("budget")
        advanceTimeBy(400)
        runCurrent()
        // The word results do not wait for the model.
        assertEquals(listOf(1L), emissions.last().items.map { it.session.id })

        vectorsRead.complete(Unit)
        runCurrent()
        assertEquals(listOf(1L, 2L), emissions.last().items.map { it.session.id })
        val line = emissions.last().items.last().lines.single()
        assertTrue(line.byMeaning)
        assertEquals(20L, line.segmentId)
        assertEquals(5_000L, line.startMs)
    }

    @Test
    fun leavingTheScreen_releasesTheModel() = runTest(dispatcher) {
        val embedders = FakeTextEmbedderFactory()
        val store = ViewModelStore()
        val factory = viewModelFactory {
            initializer { SearchViewModel(SessionSearch(dao), semantic = semantic(embedders)) }
        }
        val vm = ViewModelProvider.create(store, factory)[SearchViewModel::class]
        backgroundScope.launch { vm.results.collect {} }
        runCurrent()
        vm.onQueryChange("budget")
        advanceTimeBy(400)
        runCurrent()
        assertEquals(1, embedders.created)
        assertFalse(embedders.embedder.closed)

        store.clear()
        assertTrue(embedders.embedder.closed)
    }

    @Test
    fun meaning_failureOrNoModel_leavesTheWordResults() = runTest(dispatcher) {
        for (broken in listOf(true, false)) {
            val embedders = FakeTextEmbedderFactory()
            if (broken) embedders.embedder.failQueries = true else embedders.available = false
            val (vm, emissions) = viewModel(semantic = semantic(embedders))
            vm.onQueryChange("budget")
            advanceTimeBy(400)
            runCurrent()
            val searched = emissions.filter { it.query == "budget" }
            assertEquals(listOf(listOf(1L)), searched.map { results -> results.items.map { it.session.id } })
        }
    }

    @Test
    fun dateRanges_resolveAgainstTheClock() {
        val zone = ZoneOffset.UTC
        assertEquals(0L, DateRange.ANY.since(NOW, zone))
        assertEquals(NOW - 7 * DAY, DateRange.WEEK.since(NOW, zone))
        assertEquals(NOW - 30 * DAY, DateRange.MONTH.since(NOW, zone))
        // 1 Jan 2026 00:00 UTC.
        assertEquals(1_767_225_600_000L, DateRange.YEAR.since(NOW, zone))
    }

    private class FakeSearchDao : SearchDao {
        val queries = mutableListOf<String>()

        private val session = SessionEntity(
            id = 1,
            title = "Standup",
            createdAt = NOW - 10 * DAY,
            language = "en",
            status = SessionStatus.DIARIZED
        )

        override suspend fun findSegments(
            match: String,
            language: String?,
            since: Long,
            open: String,
            close: String,
            limit: Int
        ): List<SegmentHit> {
            queries += match
            return listOf(SegmentHit(1, 10, 0, null, "${open}budget$close"))
        }

        override suspend fun matchingSessions(
            match: String,
            language: String?,
            since: Long,
            limit: Int
        ): List<SessionHits> = listOf(SessionHits(1, 1))

        /** Shares no word with the queries; found by meaning only. */
        private val spending = SessionEntity(
            id = 2,
            title = "Planning",
            createdAt = NOW - 2 * DAY,
            language = "en",
            status = SessionStatus.DIARIZED
        )

        override suspend fun segments(sessionId: Long): List<SegmentEntity> = if (sessionId == spending.id) {
            listOf(SegmentEntity(id = 20, sessionId = spending.id, startMs = 5_000, endMs = 6_000, text = SPENDING))
        } else {
            emptyList()
        }

        override suspend fun sessions(): List<SessionEntity> = listOf(spending, session)

        override suspend fun speakers(): List<SpeakerEntity> = emptyList()

        override suspend fun summaries(): List<ChatMessageEntity> = emptyList()

        override suspend fun sessionTags(): List<SessionTagEntity> = listOf(SessionTagEntity(session.id, 5L))

        override suspend fun usedTags(): List<TagEntity> = listOf(TagEntity(5L, "Acme", "acme", 0L))
    }

    /**
     * The vectors [embedders] would store for session 2's one passage;
     * reading them waits for [vectorsRead] when given.
     */
    private class FakeIndexDao(
        embedders: FakeTextEmbedderFactory,
        private val vectorsRead: CompletableDeferred<Unit>?
    ) : SemanticIndexDao {
        private val modelId = embedders.modelId()
        private val vector = embedders.embedder.embedPassages(listOf(SPENDING)).single().toBytes()

        override suspend fun vectors(modelId: String): List<PassageVectorEntity> {
            vectorsRead?.await()
            return vectorsOf(2).filter { it.modelId == modelId }
        }

        override suspend fun vectorsOf(sessionId: Long): List<PassageVectorEntity> =
            if (sessionId == 2L) listOf(PassageVectorEntity(2, 0, 0, 0, modelId, vector)) else emptyList()

        override suspend fun vectorCount(modelId: String): Int = vectorsOf(2).count { it.modelId == modelId }

        override suspend fun states(): List<SessionIndexStateEntity> = listOf(SessionIndexStateEntity(2, modelId, "h"))

        override suspend fun insertVectors(vectors: List<PassageVectorEntity>) = Unit

        override suspend fun upsertState(state: SessionIndexStateEntity) = Unit

        override suspend fun deleteVectors(sessionId: Long) = Unit

        override suspend fun deleteVectorsNotOf(modelId: String) = Unit

        override suspend fun deleteStatesNotOf(modelId: String) = Unit

        override suspend fun clearVectors() = Unit

        override suspend fun clearStates() = Unit
    }

    private companion object {
        const val DAY = 86_400_000L

        const val SPENDING = "we can spend ten euros"

        // 2026-10-19 12:00 UTC.
        const val NOW = 1_792_411_200_000L
    }
}
