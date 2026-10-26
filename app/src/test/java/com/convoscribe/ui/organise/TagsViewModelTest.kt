package com.convoscribe.ui.organise

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.SessionRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [TagsViewModel] over an in-memory database: tag usage, rename (and merge) and delete. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TagsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var db: AppDatabase
    private lateinit var repo: SessionRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = SessionRepository(db, File(context.filesDir, "tags-vm-${System.nanoTime()}"))
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun listsUsage_renamesAndDeletes() = runTest(dispatcher) {
        repo.createSession(title = "a", language = "en", tags = listOf("Acme", "Weekly"))
        repo.createSession(title = "b", language = "en", tags = listOf("Acme"))
        val vm = TagsViewModel(repo)

        val usage = vm.tags.first { it.size == 2 }
        assertEquals(listOf("Acme" to 2, "Weekly" to 1), usage.map { it.name to it.sessionCount })

        vm.rename(usage.last().id, "acme")
        assertEquals(listOf("Acme" to 2), vm.tags.first { it.size == 1 }.map { it.name to it.sessionCount })

        vm.delete(usage.first().id)
        assertEquals(emptyList<String>(), vm.tags.first { it.isEmpty() }.map { it.name })
    }
}
