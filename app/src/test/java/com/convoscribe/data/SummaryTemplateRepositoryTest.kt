package com.convoscribe.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.llm.SummaryTemplates
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [SummaryTemplateRepository] over an in-memory database: built-ins
 * come first and cannot be changed, user templates round-trip, and a
 * deleted template's key resolves to Meeting.
 */
@RunWith(RobolectricTestRunner::class)
class SummaryTemplateRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: SummaryTemplateRepository
    private var now = 1_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = SummaryTemplateRepository(db.summaryTemplateDao(), clock = { now++ })
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun builtInsComeFirstThenUserTemplatesInOrderAdded() = runTest {
        repo.add("  Standup ", " Three bullets. \n")
        repo.add("Retro", "Went well / didn't.")

        val all = repo.observeAll().first()
        assertEquals(SummaryTemplates.BUILT_IN, all.take(SummaryTemplates.BUILT_IN.size))
        val mine = all.drop(SummaryTemplates.BUILT_IN.size)
        assertEquals(listOf("Standup", "Retro"), mine.map { it.name })
        assertEquals("Three bullets.", mine[0].body)
        mine.forEach { assertFalse(it.builtIn) }
        assertEquals(mine, repo.getUserTemplates())
    }

    @Test
    fun updateAndDeleteChangeUserTemplatesOnly() = runTest {
        val key = repo.add("Standup", "Three bullets.")
        repo.update(key, "Daily", "Two bullets.")
        repo.update(SummaryTemplates.MEETING_KEY, "Hacked", "Nothing.")
        repo.delete(SummaryTemplates.BRIEF_KEY)

        val all = repo.observeAll().first()
        assertEquals(SummaryTemplates.BUILT_IN, all.take(SummaryTemplates.BUILT_IN.size))
        assertEquals("Daily", all.last().name)
        assertEquals("Two bullets.", all.last().body)

        repo.delete(key)
        assertEquals(emptyList<Any>(), repo.getUserTemplates())
        assertEquals(SummaryTemplates.MEETING, SummaryTemplates.resolve(key, repo.getUserTemplates()))
    }

    @Test
    fun duplicateCopiesABuiltInAsAnEditableTemplate() = runTest {
        val interview = SummaryTemplates.resolve(SummaryTemplates.INTERVIEW_KEY, emptyList())
        val key = repo.duplicate(interview)

        val copy = SummaryTemplates.resolve(key, repo.getUserTemplates())
        assertEquals("Interview (copy)", copy.name)
        assertEquals(interview.body.trim(), copy.body)
        assertFalse(copy.builtIn)
    }
}
