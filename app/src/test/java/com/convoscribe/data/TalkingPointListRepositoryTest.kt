package com.convoscribe.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        assertEquals(listOf("a", "b"), TalkingPoints.split("a\n\n  b  \n"))
    }

    /** The same cases as the server's `talking-point-text.test.ts` (issue #114). */
    @Test
    fun aPastedBlockSplitsIntoPoints() {
        val pasted = "- Budget\r\n* Hiring\n\n+ Office move\r• Parking\n1. Q3 plan\n2) Holidays\n" +
            "- [ ] Open item\n[x] Done item\n-5 degrees\n3.5% growth\n  budget  \n· Réunion\nreunion"
        assertEquals(
            listOf(
                "Budget", "Hiring", "Office move", "Parking", "Q3 plan", "Holidays",
                "Open item", "Done item", "-5 degrees", "3.5% growth", "Réunion"
            ),
            TalkingPoints.fromPaste(pasted)
        )
        assertEquals(listOf("Hiring"), TalkingPoints.pasted(listOf("Budget", "Office"), "budget\n- Office\nHiring"))
        assertEquals(
            listOf(TalkingPoints.MAX_LEN, 1),
            TalkingPoints.fromPaste("x".repeat(TalkingPoints.MAX_LEN + 5) + "\nb").map { it.length }
        )
        assertEquals(emptyList<String>(), TalkingPoints.fromPaste("\n - \n\n"))
        assertTrue(TalkingPoints.isMultiLine("a\rb"))
        assertFalse(TalkingPoints.isMultiLine("a b"))
    }
}
