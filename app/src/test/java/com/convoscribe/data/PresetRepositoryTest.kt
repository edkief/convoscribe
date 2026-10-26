package com.convoscribe.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [PresetRepository] over an in-memory database: presets round-trip,
 * their tags are created and follow renames, and a deleted tag drops
 * out of them.
 */
@RunWith(RobolectricTestRunner::class)
class PresetRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var presets: PresetRepository
    private lateinit var sessions: SessionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        presets = PresetRepository(db, clock = { 1_000L })
        sessions = SessionRepository(db, context.filesDir)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun saveAddsThenUpdates() = runTest {
        val id = presets.save(
            Preset(
                name = "  Weekly sync ",
                titlePattern = " Weekly sync {date} ",
                language = "fr",
                numSpeakers = 4,
                participants = listOf("Alice", " Bob ", "Alice"),
                tags = listOf("Acme", "acme", "Weekly"),
                summaryTemplate = "meeting",
                cleanAudio = true
            )
        )
        val saved = presets.get(id)!!
        assertEquals("Weekly sync", saved.name)
        assertEquals("Weekly sync {date}", saved.titlePattern)
        assertEquals("fr", saved.language)
        assertEquals(4, saved.numSpeakers)
        assertEquals(listOf("Alice", "Bob"), saved.participants)
        assertEquals(listOf("Acme", "Weekly"), saved.tags)
        assertEquals(true, saved.cleanAudio)
        assertEquals(listOf("Acme", "Weekly"), sessions.observeTags().first().map { it.name })

        presets.save(saved.copy(name = "Standup", tags = emptyList(), participants = null))
        val updated = presets.observeAll().first().single()
        assertEquals(id, updated.id)
        assertEquals("Standup", updated.name)
        assertEquals(emptyList<String>(), updated.tags)
        assertNull(updated.participants)
    }

    @Test
    fun tagsFollowRenamesAndDeletes() = runTest {
        val preset = Preset(name = "P", language = "en", tags = listOf("Acme", "Weekly"), summaryTemplate = "meeting")
        val id = presets.save(preset)
        val tags = sessions.observeTags().first().associateBy { it.name }
        sessions.renameTag(tags.getValue("Acme").id, "Acme Inc")
        sessions.deleteTag(tags.getValue("Weekly").id)
        assertEquals(listOf("Acme Inc"), presets.get(id)!!.tags)
    }

    @Test
    fun listIsAlphabeticalAndDeleteRemoves() = runTest {
        presets.save(Preset(name = "beta", language = "en", summaryTemplate = "meeting"))
        val alpha = presets.save(Preset(name = "Alpha", language = "en", summaryTemplate = "meeting"))
        assertEquals(listOf("Alpha", "beta"), presets.observeAll().first().map { it.name })
        presets.delete(alpha)
        assertEquals(listOf("beta"), presets.observeAll().first().map { it.name })
        assertNull(presets.get(alpha))
    }
}
