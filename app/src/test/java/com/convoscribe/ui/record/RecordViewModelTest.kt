package com.convoscribe.ui.record

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.asr.TimedToken
import com.convoscribe.asr.TranscriberFactory
import com.convoscribe.audio.AudioSource
import com.convoscribe.data.EnrolledSpeakerEntity
import com.convoscribe.data.EnrolledSpeakerStore
import com.convoscribe.diarization.SpeakerMatcher
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
        missingModels = missing
    )

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
        vm.saveAsDefault()

        val s = settings.settings.value
        assertEquals("fr", s.asrLanguage)
        assertEquals(4, s.numSpeakers)
        assertEquals(listOf("Bob"), s.defaultParticipants)
        // The title is per conversation only.
        assertEquals(ConversationOptions("fr", 4, listOf("Bob")), newViewModel().options.value)
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
            participants: List<String>?
        ): Long = error("not used")
        override suspend fun recordEngine(sessionId: Long, engine: String, model: String, continued: Boolean) = Unit
        override suspend fun sessionLanguage(sessionId: Long): String = error("not used")
        override suspend fun reopenSession(sessionId: Long) = Unit
        override suspend fun audioPath(sessionId: Long): String = error("not used")
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
