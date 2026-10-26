package com.convoscribe.ui.record

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.asr.TimedToken
import com.convoscribe.asr.TranscriberFactory
import com.convoscribe.audio.AudioSource
import com.convoscribe.data.EnrolledSpeakerEntity
import com.convoscribe.data.EnrolledSpeakerStore
import com.convoscribe.data.FakePresetStore
import com.convoscribe.data.FakeSummaryTemplateStore
import com.convoscribe.data.Preset
import com.convoscribe.diarization.SpeakerMatcher
import com.convoscribe.llm.SummaryTemplates
import com.convoscribe.recording.ConversationOptions
import com.convoscribe.recording.RecordingController
import com.convoscribe.recording.RecordingStore
import com.convoscribe.settings.InMemorySecretStore
import com.convoscribe.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The new-conversation options on the Record screen: pre-filled from
 * the Settings defaults, edited without touching Settings, and saved
 * back only through "Set as default".
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RecordViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var settings: SettingsRepository
    private val enrolled = FakeEnrolled()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context: Context = ApplicationProvider.getApplicationContext()
        settings = SettingsRepository(
            context.getSharedPreferences("record-vm-test-${System.nanoTime()}", Context.MODE_PRIVATE),
            InMemorySecretStore()
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Notes passed to the ViewModel's save hook, in order. */
    private val savedNotes = mutableListOf<Pair<Long, String?>>()

    private val presetStore = FakePresetStore(
        listOf(
            Preset(
                id = 7,
                name = "Weekly",
                titlePattern = "Weekly {date}",
                language = "fr",
                numSpeakers = 4,
                participants = listOf("Alice"),
                tags = listOf("Acme"),
                summaryTemplate = SummaryTemplates.INTERVIEW_KEY,
                cleanAudio = true
            ),
            Preset(id = 8, name = "Gone template", language = "en", summaryTemplate = "user:404")
        )
    )

    private fun newViewModel(missing: (String) -> Boolean = { false }) = RecordViewModel(
        controller = RecordingController(
            audioSource = object : AudioSource {
                override fun chunks() = emptyFlow<ShortArray>()
            },
            transcriberFactory = TranscriberFactory { error("not used") },
            store = UnusedStore,
            defaultOptions = { ConversationOptions(language = "en") },
            asrDispatcher = StandardTestDispatcher()
        ),
        settings = settings,
        enrolled = enrolled,
        templates = FakeSummaryTemplateStore(),
        missingModels = missing,
        saveMarkNote = { id, note -> savedNotes += id to note },
        tagNames = flowOf(listOf("Acme", "Weekly")),
        presetStore = presetStore
    )

    @Test
    fun applyPreset_fillsTheOptionsAndIsShownAsActiveUntilEdited() = runTest(dispatcher) {
        val vm = newViewModel()
        val job = launch { vm.activePreset.collect {} }
        val weekly = vm.presets.first { it.isNotEmpty() }.first()

        vm.applyPreset(weekly)
        val options = vm.options.value
        assertEquals("fr", options.language)
        assertEquals(4, options.numSpeakers)
        assertEquals(listOf("Alice"), options.participants)
        assertEquals("Weekly {date}", options.title)
        assertEquals(listOf("Acme"), options.tags)
        assertEquals(SummaryTemplates.INTERVIEW_KEY, options.summaryTemplate)
        assertTrue(options.cleanAudio)
        assertEquals(weekly, vm.activePreset.value)

        vm.setNumSpeakers(2)
        assertNull(vm.activePreset.value)

        vm.applyPreset(null)
        assertEquals(ConversationOptions.fromSettings(settings.settings.value), vm.options.value)
        job.cancel()
    }

    @Test
    fun applyPresetById_fallsBackForADeletedTemplate() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.applyPreset(8L)
        advanceUntilIdle()
        assertEquals(settings.settings.value.defaultSummaryTemplate, vm.options.value.summaryTemplate)

        // Unknown ids leave the options alone.
        vm.setTitle("Mine")
        vm.applyPreset(99L)
        advanceUntilIdle()
        assertEquals("Mine", vm.options.value.title)
    }

    @Test
    fun saveAsPreset_keepsTheCurrentOptions() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.setTitle("Standup {time}")
        vm.setTags(listOf("Acme"))
        vm.saveAsPreset("  ")
        vm.saveAsPreset("Standup")
        advanceUntilIdle()

        val saved = presetStore.presets.value.last()
        assertEquals("Standup", saved.name)
        assertEquals("Standup {time}", saved.titlePattern)
        assertEquals(listOf("Acme"), saved.tags)
        assertEquals(3, presetStore.presets.value.size)
    }

    @Test
    fun setTags_cleansNamesAndOffersExistingTags() = runTest(dispatcher) {
        val vm = newViewModel()
        val job = launch { vm.allTags.collect {} }
        advanceUntilIdle()
        assertEquals(listOf("Acme", "Weekly"), vm.allTags.value)

        vm.setTags(listOf(" Acme ", "ACME", "New"))
        assertEquals(listOf("Acme", "New"), vm.options.value.tags)
        job.cancel()
    }

    @Test
    fun mark_withoutAnOpenSession_reportsNothing() = runTest(dispatcher) {
        val vm = newViewModel()
        val reported = mutableListOf<MarkAdded>()
        val job = launch { vm.marks.collect { reported += it } }
        vm.mark()
        assertTrue(reported.isEmpty())
        job.cancel()
    }

    @Test
    fun setMarkNote_savesTheNote() {
        newViewModel().setMarkNote(5L, "decision")
        assertEquals(listOf(5L to "decision"), savedNotes)
    }

    @Test
    fun shouldAskForOverlay_onlyOnce_andNotWhenGrantedOrSwitchedOff() {
        val vm = newViewModel()
        assertFalse(vm.shouldAskForOverlay(granted = true))
        assertTrue(vm.shouldAskForOverlay(granted = false))
        assertTrue(settings.settings.value.overlayPromptShown)
        // Asked once: never again.
        assertFalse(vm.shouldAskForOverlay(granted = false))

        settings.update { it.copy(overlayPromptShown = false, floatingControl = false) }
        assertFalse(vm.shouldAskForOverlay(granted = false))
    }

    @Test
    fun options_arePreFilledFromTheSettingsDefaults() {
        settings.update { it.copy(asrLanguage = "fr", numSpeakers = 3, defaultParticipants = listOf("Alice")) }
        val options = newViewModel().options.value
        assertEquals(ConversationOptions(language = "fr", numSpeakers = 3, participants = listOf("Alice")), options)
    }

    @Test
    fun editingOptions_doesNotChangeSettings() {
        val vm = newViewModel()
        vm.setLanguage("fr")
        vm.setNumSpeakers(2)
        vm.setParticipants(listOf("Bob"))
        vm.setTitle("Interview")

        assertEquals(ConversationOptions("fr", 2, listOf("Bob"), "Interview"), vm.options.value)
        val s = settings.settings.value
        assertEquals("en", s.asrLanguage)
        assertEquals(-1, s.numSpeakers)
        assertNull(s.defaultParticipants)
    }

    @Test
    fun saveAsDefault_updatesSettingsAndTheNextConversationStartsFromThem() {
        val vm = newViewModel()
        vm.setLanguage("fr")
        vm.setNumSpeakers(4)
        vm.setParticipants(listOf("Bob"))
        vm.setTitle("Not a default")
        vm.setSummaryTemplate("lecture")
        vm.setCleanAudio(true)
        vm.saveAsDefault()

        val s = settings.settings.value
        assertEquals("fr", s.asrLanguage)
        assertEquals(4, s.numSpeakers)
        assertEquals(listOf("Bob"), s.defaultParticipants)
        assertEquals("lecture", s.defaultSummaryTemplate)
        assertTrue(s.cleanAudioDefault)
        // The title is per conversation only.
        assertEquals(
            ConversationOptions("fr", 4, listOf("Bob"), summaryTemplate = "lecture", cleanAudio = true),
            newViewModel().options.value
        )
    }

    @Test
    fun setParticipants_emptyMeansAnyone_andInvalidValuesAreIgnored() {
        val vm = newViewModel()
        vm.setParticipants(emptyList())
        assertNull(vm.options.value.participants)
        vm.setLanguage("de")
        assertEquals("en", vm.options.value.language)
        vm.setNumSpeakers(0)
        assertEquals(-1, vm.options.value.numSpeakers)
    }

    @Test
    fun modelsMissing_checksTheChosenLanguage() {
        val vm = newViewModel(missing = { it == "fr" })
        assertFalse(vm.modelsMissing())
        vm.setLanguage("fr")
        assertTrue(vm.modelsMissing())
    }

    @Test
    fun enrolledNames_areDistinct() = runTest(dispatcher) {
        enrolled.rows.value = listOf(row(1, "Alice"), row(2, "Alice"), row(3, "Bob"))
        val vm = newViewModel()
        assertEquals(listOf("Alice", "Bob"), vm.enrolledNames.first { it.isNotEmpty() })
    }

    private fun row(id: Long, name: String) =
        EnrolledSpeakerEntity(id = id, name = name, embedding = ByteArray(0), createdAt = id)

    private class FakeEnrolled : EnrolledSpeakerStore {
        val rows = MutableStateFlow<List<EnrolledSpeakerEntity>>(emptyList())
        override fun observeAll() = rows
        override suspend fun getAll(): List<SpeakerMatcher.Enrolled> = emptyList()
        override suspend fun save(name: String, embedding: FloatArray) = Unit
        override suspend fun delete(id: Long) = Unit
    }

    private object UnusedStore : RecordingStore {
        override suspend fun createSession(
            title: String,
            language: String,
            numSpeakers: Int?,
            participants: List<String>?,
            summaryTemplate: String?,
            cleanedWith: String?,
            tags: List<String>,
            titleSource: String
        ): Long = error("not used")
        override suspend fun cleanedWith(sessionId: Long): String? = null
        override suspend fun recordEngine(sessionId: Long, engine: String, model: String, continued: Boolean) = Unit
        override suspend fun sessionLanguage(sessionId: Long): String = error("not used")
        override suspend fun reopenSession(sessionId: Long) = Unit
        override suspend fun audioPath(sessionId: Long): String = error("not used")
        override suspend fun addMark(sessionId: Long, atMs: Long, note: String?): Long = error("not used")
        override suspend fun countMarks(sessionId: Long): Int = 0
        override suspend fun addSegment(
            sessionId: Long,
            startMs: Long,
            endMs: Long,
            text: String,
            tokens: List<TimedToken>
        ): Long = error("not used")
        override suspend fun finishRecording(sessionId: Long, durationMs: Long) = Unit
    }
}
