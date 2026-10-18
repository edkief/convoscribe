package com.convoscribe.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
