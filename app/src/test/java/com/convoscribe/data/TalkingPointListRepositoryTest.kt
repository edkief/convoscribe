package com.convoscribe.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Talking-point lists (issue #102). */
@RunWith(RobolectricTestRunner::class)
class TalkingPointListRepositoryTest {
    private lateinit var db: AppDatabase
    private var now = 100L
    private lateinit var store: TalkingPointListRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = TalkingPointListRepository(db.talkingPointListDao()) { now }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun listsAreCleanedKeptByNameAndStampedWhenChanged() = runTest {
        val weekly = store.add("  Weekly 1:1 ", listOf(" Budget ", "", "Hiring\nplan"))
        store.add("annual review", listOf("Goals"))
        assertEquals(
            listOf(
                TalkingPointList(2, "annual review", listOf("Goals")),
                TalkingPointList(weekly, "Weekly 1:1", listOf("Budget", "Hiring plan"))
            ),
            store.observeAll().first()
        )
        now = 500
        store.update(weekly, "Weekly", listOf("Office"))
        val row = db.talkingPointListDao().get(weekly)!!
        assertEquals(
            listOf("Weekly", "Office", "100", "500"),
            listOf(row.name, row.points, "${row.createdAt}", "${row.updatedAt}")
        )
        store.delete(weekly)
        assertEquals(listOf("annual review"), store.observeAll().first().map { it.name })
    }

    @Test
    fun appendLeavesOutPointsAlreadyThere() {
        assertEquals(
            listOf("Budget", "Réunion", "Hiring"),
            TalkingPoints.append(listOf("Budget", "Réunion"), listOf("budget", " reunion ", "Hiring", ""))
        )
        assertEquals(listOf("a", "b"), TalkingPoints.fromLines("a\n\n  b  \n"))
    }
}
