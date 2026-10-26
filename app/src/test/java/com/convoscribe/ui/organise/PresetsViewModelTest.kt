package com.convoscribe.ui.organise

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.data.EnrolledSpeakerEntity
import com.convoscribe.data.EnrolledSpeakerStore
import com.convoscribe.data.FakePresetStore
import com.convoscribe.data.FakeSummaryTemplateStore
import com.convoscribe.data.Preset
import com.convoscribe.diarization.SpeakerMatcher
import com.convoscribe.llm.SummaryTemplates
import com.convoscribe.recording.ConversationOptions
import com.convoscribe.settings.InMemorySecretStore
import com.convoscribe.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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

/** [PresetsViewModel]: drafts from the defaults or a preset, save and delete (issue #32). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PresetsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var settings: SettingsRepository
    private val store = FakePresetStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context: Context = ApplicationProvider.getApplicationContext()
        settings = SettingsRepository(
            context.getSharedPreferences("presets-vm-${System.nanoTime()}", Context.MODE_PRIVATE),
            InMemorySecretStore()
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel() = PresetsViewModel(
        store = store,
        settings = settings,
        enrolled = NoEnrolled,
        templates = FakeSummaryTemplateStore(),
        tagNames = flowOf(listOf("Acme"))
    )

    @Test
    fun newDraft_startsFromTheDefaults_andSavesOnlyWithAName() = runTest(dispatcher) {
        settings.update { it.copy(asrLanguage = "fr", numSpeakers = 3) }
        val vm = newViewModel()
        val draft = vm.newDraft()
        assertEquals(ConversationOptions.fromSettings(settings.settings.value), draft.options)

        vm.save(draft)
        assertEquals(emptyList<Preset>(), store.presets.value)

        vm.save(
            draft.copy(name = "Weekly", options = draft.options.copy(title = "Weekly {date}", tags = listOf("Acme")))
        )
        val saved = store.presets.value.single()
        assertEquals("Weekly", saved.name)
        assertEquals("Weekly {date}", saved.titlePattern)
        assertEquals("fr", saved.language)
        assertEquals(listOf("Acme"), saved.tags)
    }

    @Test
    fun draftOf_editsThePresetInPlace_andDeleteRemovesIt() = runTest(dispatcher) {
        val vm = newViewModel()
        val old = Preset(id = 3, name = "Old", language = "en", summaryTemplate = SummaryTemplates.INTERVIEW_KEY)
        store.presets.value = listOf(old)
        val draft = vm.draftOf(store.presets.value.single())
        assertEquals(3L, draft.id)
        assertEquals(SummaryTemplates.INTERVIEW_KEY, draft.options.summaryTemplate)

        vm.save(draft.copy(name = "New"))
        assertEquals(listOf(3L to "New"), store.presets.value.map { it.id to it.name })

        vm.delete(3)
        assertEquals(emptyList<Preset>(), store.presets.value)
    }

    private object NoEnrolled : EnrolledSpeakerStore {
        override fun observeAll(): Flow<List<EnrolledSpeakerEntity>> = flowOf(emptyList())
        override suspend fun getAll(): List<SpeakerMatcher.Enrolled> = emptyList()
        override suspend fun save(name: String, embedding: FloatArray) = Unit
        override suspend fun delete(id: Long) = Unit
    }
}
