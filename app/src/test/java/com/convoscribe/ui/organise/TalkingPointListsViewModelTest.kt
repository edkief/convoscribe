package com.convoscribe.ui.organise

import com.convoscribe.data.FakeTalkingPointListStore
import com.convoscribe.data.TalkingPointList
import com.convoscribe.data.UncoveredPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/** [TalkingPointListsViewModel]: save new and edited lists, delete (issue #102). */
@OptIn(ExperimentalCoroutinesApi::class)
class TalkingPointListsViewModelTest {
    private val store = FakeTalkingPointListStore()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun savesOnePointPerLineAndEditsInPlace() {
        val vm = TalkingPointListsViewModel(store)
        vm.save(TalkingPointListDraft(name = "Weekly", points = "Budget\n\n  Hiring  \n"))
        assertEquals(listOf(TalkingPointList(1, "Weekly", listOf("Budget", "Hiring"))), store.lists.value)

        val draft = TalkingPointListDraft.of(store.lists.value.single())
        assertEquals("Budget\nHiring", draft.points)
        vm.save(draft.copy(points = "Office"))
        assertEquals(listOf(TalkingPointList(1, "Weekly", listOf("Office"))), store.lists.value)

        vm.delete(1)
        assertEquals(emptyList<TalkingPointList>(), store.lists.value)
    }

    @Test
    fun aListNeedsAName() {
        val draft = TalkingPointListDraft(name = "  ", points = "Budget")
        assertFalse(draft.canSave)
        TalkingPointListsViewModel(store).save(draft)
        assertEquals(emptyList<TalkingPointList>(), store.lists.value)
    }

    @Test
    fun notCoveredPassesTheRecentPointsThrough() = runTest {
        val point = UncoveredPoint(7, sessionId = 3, text = "Career goals", sessionTitle = "1:1", sessionCreatedAt = 10)
        val vm = TalkingPointListsViewModel(store, uncovered = flowOf(listOf(point)))
        assertEquals(listOf(point), vm.notCovered.first { it.isNotEmpty() })
    }
}
