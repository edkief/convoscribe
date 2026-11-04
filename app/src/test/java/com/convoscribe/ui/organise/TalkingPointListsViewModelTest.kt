package com.convoscribe.ui.organise

import com.convoscribe.data.FakeTalkingPointListStore
import com.convoscribe.data.TalkingPointList
import com.convoscribe.data.TalkingPoints
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
    fun savesThePointsInOrderCleanedAndEditsInPlace() {
        val vm = TalkingPointListsViewModel(store)
        vm.save(TalkingPointListDraft(name = "Weekly", points = listOf("Hiring", "", "  Budget  ", " ")))
        assertEquals(listOf(TalkingPointList(1, "Weekly", listOf("Hiring", "Budget"))), store.lists.value)

        val draft = TalkingPointListDraft.of(store.lists.value.single())
        assertEquals(listOf("Hiring", "Budget"), draft.points)
        vm.save(draft.movePoint(1, -1).addPoint().withPoint(2, "Office"))
        assertEquals(listOf(TalkingPointList(1, "Weekly", listOf("Budget", "Hiring", "Office"))), store.lists.value)

        vm.delete(1)
        assertEquals(emptyList<TalkingPointList>(), store.lists.value)
    }

    @Test
    fun aDraftAlwaysHasARow() {
        assertEquals(listOf(""), TalkingPointListDraft().points)
        assertEquals(listOf(""), TalkingPointListDraft.of(TalkingPointList(4, "Empty", emptyList())).points)
        val one = TalkingPointListDraft(points = listOf("Budget"))
        assertEquals(listOf(""), one.removePoint(0).points)
        assertEquals(emptyList<String>(), one.removePoint(0).savedPoints)
    }

    @Test
    fun withPointKeepsOneLineAndTheCap() {
        val draft = TalkingPointListDraft(points = listOf("a", "b"))
        assertEquals(listOf("a", "x y"), draft.withPoint(1, "x\ny").points)
        assertEquals(TalkingPoints.MAX_LEN, draft.withPoint(0, "z".repeat(TalkingPoints.MAX_LEN + 5)).points[0].length)
        assertEquals(draft, draft.withPoint(2, "out of range"))
    }

    @Test
    fun addPointGoesAfterTheRowOrAtTheEnd() {
        val draft = TalkingPointListDraft(points = listOf("a", "b"))
        assertEquals(listOf("a", "b", ""), draft.addPoint().points)
        assertEquals(listOf("a", "", "b"), draft.addPoint(after = 0).points)
    }

    @Test
    fun movePointSwapsAndStopsAtTheEdges() {
        val draft = TalkingPointListDraft(points = listOf("a", "b", "c"))
        assertEquals(listOf("b", "a", "c"), draft.movePoint(1, -1).points)
        assertEquals(listOf("a", "c", "b"), draft.movePoint(1, 1).points)
        assertEquals(draft, draft.movePoint(0, -1))
        assertEquals(draft, draft.movePoint(2, 1))
    }

    @Test
    fun removePointDropsThatRow() {
        val draft = TalkingPointListDraft(points = listOf("a", "b", "c"))
        assertEquals(listOf("a", "c"), draft.removePoint(1).points)
        assertEquals(draft, draft.removePoint(3))
    }

    @Test
    fun aListNeedsAName() {
        val draft = TalkingPointListDraft(name = "  ", points = listOf("Budget"))
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
