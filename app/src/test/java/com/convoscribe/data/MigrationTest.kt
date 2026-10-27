package com.convoscribe.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Upgrades a database written at an old schema version and checks
 * the rows survive. The schemas come from `app/schemas/`, exposed as
 * test assets in `app/build.gradle.kts`.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate3To4_keepsLanguageAndSpeakerCount() = runTest {
        helper.createDatabase(DB_NAME, 3).use { db ->
            db.execSQL(
                "INSERT INTO sessions (id, title, createdAt, durationMs, language, audioPath, status, numSpeakers) " +
                    "VALUES (1, 'French chat', 1000, 5000, 'fr', '/r/1.wav', 'DIARIZED', 3)"
            )
            db.execSQL(
                "INSERT INTO sessions (id, title, createdAt, durationMs, language, audioPath, status, numSpeakers) " +
                    "VALUES (2, 'Old default', 2000, 5000, 'en', '/r/2.wav', 'DIARIZED', NULL)"
            )
        }
        helper.runMigrationsAndValidate(DB_NAME, 4, true).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        try {
            val french = db.sessionDao().get(1)!!
            assertEquals("fr", french.language)
            assertEquals(3, french.numSpeakers)
            assertNull(french.participants)
            assertNull(french.asrEngine)
            assertNull(french.asrModel)
            val old = db.sessionDao().get(2)!!
            assertEquals("en", old.language)
            assertNull(old.numSpeakers)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate4To5_indexesExistingSegments() = runTest {
        helper.createDatabase(DB_NAME, 4).use { db ->
            db.execSQL(
                "INSERT INTO sessions (id, title, createdAt, durationMs, language, audioPath, status) " +
                    "VALUES (1, 'Lundi', 1000, 5000, 'fr', '/r/1.wav', 'DIARIZED')"
            )
            db.execSQL(
                "INSERT INTO segments (id, sessionId, startMs, endMs, text) " +
                    "VALUES (7, 1, 1200, 2400, 'La réunion sur le budget')"
            )
        }
        helper.runMigrationsAndValidate(DB_NAME, 5, true).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        try {
            val hits = db.searchDao().findSegments(
                match = "reunion*",
                language = null,
                since = 0L,
                open = "[",
                close = "]",
                limit = 10
            )
            assertEquals(listOf(7L), hits.map { it.segmentId })
            assertEquals(1200L, hits.single().startMs)
            assertEquals("La [réunion] sur le budget", hits.single().snippet)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate5To6_existingTranscriptsReadAsUnedited() = runTest {
        helper.createDatabase(DB_NAME, 5).use { db ->
            db.execSQL(
                "INSERT INTO sessions (id, title, createdAt, durationMs, language, audioPath, status) " +
                    "VALUES (1, 'Lundi', 1000, 5000, 'fr', '/r/1.wav', 'DIARIZED')"
            )
            db.execSQL(
                "INSERT INTO segments (id, sessionId, startMs, endMs, text, speakerIndex) " +
                    "VALUES (7, 1, 1200, 2400, 'Bonjour', 0)"
            )
        }
        helper.runMigrationsAndValidate(DB_NAME, 6, true).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        try {
            val segment = db.segmentDao().get(7)!!
            assertEquals("Bonjour", segment.text)
            assertFalse(segment.textEdited)
            assertFalse(segment.speakerEdited)
            assertFalse(db.sessionDao().get(1)!!.transcriptEdited)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate6To7_existingSessionsFollowTheDefaultTemplate() = runTest {
        helper.createDatabase(DB_NAME, 6).use { db ->
            db.execSQL(
                "INSERT INTO sessions (id, title, createdAt, durationMs, language, audioPath, status) " +
                    "VALUES (1, 'Standup', 1000, 5000, 'en', '/r/1.wav', 'DIARIZED')"
            )
            db.execSQL(
                "INSERT INTO chat_messages (id, sessionId, kind, role, content, createdAt) " +
                    "VALUES (3, 1, 'SUMMARY', 'ASSISTANT', 'Overview: short.', 2000)"
            )
        }
        helper.runMigrationsAndValidate(DB_NAME, 7, true).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        try {
            assertNull(db.sessionDao().get(1)!!.summaryTemplate)
            val summary = db.chatMessageDao().observe(1, MessageKind.SUMMARY).first().single()
            assertEquals("Overview: short.", summary.content)
            assertNull(summary.templateName)
            assertEquals(emptyList<SummaryTemplateEntity>(), db.summaryTemplateDao().getAll())
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate7To8_existingSessionsHaveNoMarksAndMarksGoWithTheirSession() = runTest {
        helper.createDatabase(DB_NAME, 7).use { db ->
            db.execSQL(
                "INSERT INTO sessions (id, title, createdAt, durationMs, language, audioPath, status) " +
                    "VALUES (1, 'Planning', 1000, 5000, 'en', '/r/1.wav', 'DIARIZED')"
            )
        }
        helper.runMigrationsAndValidate(DB_NAME, 8, true).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals("Planning", db.sessionDao().get(1)!!.title)
            assertEquals(emptyList<MarkEntity>(), db.markDao().getForSession(1))
            db.markDao().insert(MarkEntity(sessionId = 1, atMs = 2_500L, note = "budget", createdAt = 3000))
            assertEquals(1, db.markDao().count(1))
            db.sessionDao().delete(1)
            assertEquals(0, db.markDao().count(1))
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate8To9_existingSessionsAreNotCleaned() = runTest {
        helper.createDatabase(DB_NAME, 8).use { db ->
            db.execSQL(
                "INSERT INTO sessions (id, title, createdAt, durationMs, language, audioPath, status) " +
                    "VALUES (1, 'Cafe', 1000, 5000, 'en', '/r/1.wav', 'DIARIZED')"
            )
        }
        helper.runMigrationsAndValidate(DB_NAME, 9, true).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        try {
            val session = db.sessionDao().get(1)!!
            assertEquals("Cafe", session.title)
            assertNull(session.cleanedWith)
            db.sessionDao().setCleanedWith(1, "enh-gtcrn-simple")
            assertEquals("enh-gtcrn-simple", db.sessionDao().get(1)!!.cleanedWith)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate9To10_existingAnswersHaveNoCoverage() = runTest {
        helper.createDatabase(DB_NAME, 9).use { db ->
            db.execSQL(
                "INSERT INTO sessions (id, title, createdAt, durationMs, language, audioPath, status) " +
                    "VALUES (1, 'Cafe', 1000, 5000, 'en', '/r/1.wav', 'DIARIZED')"
            )
            db.execSQL(
                "INSERT INTO chat_messages (id, sessionId, kind, role, content, createdAt) " +
                    "VALUES (1, 1, 'QA', 'ASSISTANT', 'an answer', 2000)"
            )
        }
        helper.runMigrationsAndValidate(DB_NAME, 10, true).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        try {
            val answer = db.chatMessageDao().observe(1, MessageKind.QA).first().single()
            assertEquals("an answer", answer.content)
            assertNull(answer.coveredMs)
            assertNull(answer.totalMs)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate10To11_existingMarksHaveNoFullNote() = runTest {
        helper.createDatabase(DB_NAME, 10).use { db ->
            db.execSQL(
                "INSERT INTO sessions (id, title, createdAt, durationMs, language, audioPath, status) " +
                    "VALUES (1, 'Planning', 1000, 5000, 'en', '/r/1.wav', 'DIARIZED')"
            )
            db.execSQL("INSERT INTO marks (id, sessionId, atMs, note, createdAt) VALUES (1, 1, 2500, 'budget', 3000)")
        }
        helper.runMigrationsAndValidate(DB_NAME, 11, true).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        try {
            val mark = db.markDao().getForSession(1).single()
            assertEquals("budget", mark.note)
            assertNull(mark.expandedNote)
            assertNull(mark.expandedAt)
            assertFalse(mark.expandedStale)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate11To12_keepsOnlyDateTitlesAsDefault() = runTest {
        helper.createDatabase(DB_NAME, 11).use { db ->
            db.execSQL(
                "INSERT INTO sessions (id, title, createdAt, durationMs, language, audioPath, status) " +
                    "VALUES (1, 'Recording 2026-03-04 09:15', 1000, 5000, 'en', '/r/1.wav', 'DIARIZED')"
            )
            db.execSQL(
                "INSERT INTO sessions (id, title, createdAt, durationMs, language, audioPath, status) " +
                    "VALUES (2, 'Budget review', 2000, 5000, 'en', '/r/2.wav', 'DIARIZED')"
            )
            db.execSQL(
                "INSERT INTO sessions (id, title, createdAt, durationMs, language, audioPath, status) " +
                    "VALUES (3, 'Recording 2026-03-04 09:15 (call)', 3000, 5000, 'en', '/r/3.wav', 'DIARIZED')"
            )
        }
        helper.runMigrationsAndValidate(DB_NAME, 12, true).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(TitleSource.DEFAULT, db.sessionDao().get(1)!!.titleSource)
            assertEquals(TitleSource.USER, db.sessionDao().get(2)!!.titleSource)
            assertEquals(TitleSource.USER, db.sessionDao().get(3)!!.titleSource)
            assertNull(db.sessionDao().get(1)!!.previousTitle)
            assertEquals(emptyList<TagEntity>(), db.tagDao().getAll())
            assertEquals(emptyList<Long>(), db.tagDao().tagIdsFor(1))
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate11To12_tagLinksCascadeOnDelete() = runTest {
        helper.createDatabase(DB_NAME, 11).use { db ->
            db.execSQL(
                "INSERT INTO sessions (id, title, createdAt, durationMs, language, audioPath, status) " +
                    "VALUES (1, 'One', 1000, 5000, 'en', '', 'DIARIZED')"
            )
            db.execSQL(
                "INSERT INTO sessions (id, title, createdAt, durationMs, language, audioPath, status) " +
                    "VALUES (2, 'Two', 2000, 5000, 'en', '', 'DIARIZED')"
            )
        }
        helper.runMigrationsAndValidate(DB_NAME, 12, true).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        try {
            val filesDir = ApplicationProvider.getApplicationContext<android.content.Context>().filesDir
            val repo = SessionRepository(db, filesDir)
            repo.setSessionTags(1, listOf("Acme", "Weekly"))
            repo.setSessionTags(2, listOf("Acme"))
            val acme = db.tagDao().findByKey("acme")!!.id
            repo.deleteSession(1)
            assertEquals(listOf(2L), db.tagDao().sessionIdsFor(acme))
            repo.deleteTag(acme)
            assertEquals(emptyList<Long>(), db.tagDao().tagIdsFor(2))
            assertEquals(listOf("Weekly"), db.tagDao().getAll().map { it.name })
        } finally {
            db.close()
        }
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }

    @Test
    fun migrate12To13_existingSessionsHaveNoDiarizationRun() = runTest {
        helper.createDatabase(DB_NAME, 12).use { db ->
            db.execSQL(
                "INSERT INTO sessions (id, title, createdAt, durationMs, language, audioPath, status) " +
                    "VALUES (1, 'Standup', 1000, 60000, 'en', '/r/1.wav', 'DIARIZED')"
            )
        }
        helper.runMigrationsAndValidate(DB_NAME, 13, true).close()

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .build()
        try {
            val session = db.sessionDao().get(1)!!
            assertEquals("Standup", session.title)
            assertNull(session.diarizedWith)
            assertNull(session.diarizationRun())
            db.sessionDao().setDiarizationRun(1, "emb-wespeaker-campplus-lm", 2000, 4200L)
            assertEquals(
                DiarizationRun("emb-wespeaker-campplus-lm", 2000, 4200L),
                db.sessionDao().get(1)!!.diarizationRun()
            )
        } finally {
            db.close()
        }
    }
}
