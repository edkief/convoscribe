package com.convoscribe.ui.templates

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.data.FakeSummaryTemplateStore
import com.convoscribe.llm.SummaryTemplates
import com.convoscribe.settings.InMemorySecretStore
import com.convoscribe.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [SummaryTemplatesViewModel] over a [FakeSummaryTemplateStore]. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SummaryTemplatesViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var settings: SettingsRepository
    private val store = FakeSummaryTemplateStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("templates-vm-test-${System.nanoTime()}", Context.MODE_PRIVATE)
        settings = SettingsRepository(prefs, InMemorySecretStore())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun save_addsANewTemplateThenEditsIt() = runTest(dispatcher) {
        val vm = SummaryTemplatesViewModel(store, settings)
        vm.save(TemplateDraft(name = " Standup ", body = "Three bullets."))
        advanceUntilIdle()
        val added = store.user.value.single()
        assertEquals("Standup", added.name)

        vm.save(TemplateDraft(key = added.key, name = "Daily", body = "Two bullets."))
        advanceUntilIdle()
        assertEquals("Daily", store.user.value.single().name)
        assertEquals("Two bullets.", store.user.value.single().body)
    }

    @Test
    fun save_ignoresADraftWithoutNameOrInstructions() = runTest(dispatcher) {
        val vm = SummaryTemplatesViewModel(store, settings)
        assertFalse(TemplateDraft(name = "x", body = "  ").canSave)
        assertTrue(TemplateDraft(name = "x", body = "y").canSave)
        vm.save(TemplateDraft(name = "  ", body = "Something"))
        vm.save(TemplateDraft(name = "Name", body = ""))
        advanceUntilIdle()
        assertEquals(emptyList<Any>(), store.user.value)
    }

    @Test
    fun duplicate_copiesABuiltIn() = runTest(dispatcher) {
        val vm = SummaryTemplatesViewModel(store, settings)
        vm.duplicate(SummaryTemplates.MEETING)
        advanceUntilIdle()
        val copy = store.user.value.single()
        assertEquals("Meeting (copy)", copy.name)
        assertEquals(SummaryTemplates.MEETING_BODY.trim(), copy.body)
    }

    @Test
    fun delete_removesTheTemplateAndResetsTheDefaultWhenItWasIt() = runTest(dispatcher) {
        val vm = SummaryTemplatesViewModel(store, settings)
        val kept = store.add("Kept", "a")
        val gone = store.add("Gone", "b")
        settings.update { it.copy(defaultSummaryTemplate = gone) }

        vm.delete(gone)
        advanceUntilIdle()
        assertEquals(listOf(kept), store.user.value.map { it.key })
        assertEquals(SummaryTemplates.MEETING_KEY, settings.settings.value.defaultSummaryTemplate)

        settings.update { it.copy(defaultSummaryTemplate = kept) }
        vm.delete("user:999")
        advanceUntilIdle()
        assertEquals(kept, settings.settings.value.defaultSummaryTemplate)
    }
}
