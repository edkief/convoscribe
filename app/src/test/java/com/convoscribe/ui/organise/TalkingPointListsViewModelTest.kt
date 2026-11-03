package com.convoscribe.ui.organise

import com.convoscribe.data.FakeTalkingPointListStore
import com.convoscribe.data.TalkingPointList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
}
