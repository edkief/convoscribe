package com.convoscribe.ui.search

import androidx.lifecycle.viewModelScope
import com.convoscribe.data.ChatMessageEntity
import com.convoscribe.data.SearchDao
import com.convoscribe.data.SegmentEntity
import com.convoscribe.data.SegmentHit
import com.convoscribe.data.SessionEntity
import com.convoscribe.data.SessionHits
import com.convoscribe.data.SessionSearch
import com.convoscribe.data.SessionStatus
import com.convoscribe.data.SessionTagEntity
import com.convoscribe.data.SpeakerEntity
import com.convoscribe.data.TagEntity
import java.time.ZoneOffset
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
        debounceMs: Long = SearchViewModel.DEBOUNCE_MS
    ): Pair<SearchViewModel, List<SearchResults>> {
        val vm = SearchViewModel(
            search = SessionSearch(dao),
            debounceMs = debounceMs,
            clock = { NOW },
            zone = ZoneOffset.UTC
        ).also { viewModels += it }
        val emissions = mutableListOf<SearchResults>()
        backgroundScope.launch { vm.results.collect { emissions += it } }
        runCurrent()
        return vm to emissions
    }

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

        override suspend fun segments(sessionId: Long): List<SegmentEntity> = emptyList()

        override suspend fun sessions(): List<SessionEntity> = listOf(session)

        override suspend fun speakers(): List<SpeakerEntity> = emptyList()

        override suspend fun summaries(): List<ChatMessageEntity> = emptyList()

        override suspend fun sessionTags(): List<SessionTagEntity> = listOf(SessionTagEntity(session.id, 5L))

        override suspend fun usedTags(): List<TagEntity> = listOf(TagEntity(5L, "Acme", "acme", 0L))
    }

    private companion object {
        const val DAY = 86_400_000L

        // 2026-10-19 12:00 UTC.
        const val NOW = 1_792_411_200_000L
    }
}
