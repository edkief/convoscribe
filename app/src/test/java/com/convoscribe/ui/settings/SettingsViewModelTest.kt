package com.convoscribe.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.asr.server.ServerAsrException
import com.convoscribe.data.FakeSummaryTemplateStore
import com.convoscribe.llm.ChatClient
import com.convoscribe.llm.LlmException
import com.convoscribe.llm.LlmMessage
import com.convoscribe.models.ModelStore
import com.convoscribe.settings.InMemorySecretStore
import com.convoscribe.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [SettingsViewModel]. Robolectric only because
 * [SettingsRepository] needs a working [android.content.SharedPreferences];
 * the API key path uses [InMemorySecretStore] so we never touch the
 * Android Keystore. `ChatClient` is a fake that returns a
 * caller-supplied model list or throws a caller-supplied
 * [LlmException].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var secrets: InMemorySecretStore
    private lateinit var modelStore: ModelStore

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences(
            "settings-vm-test-${System.nanoTime()}",
            Context.MODE_PRIVATE
        )
        secrets = InMemorySecretStore()
        val modelsRoot: File = tmp.newFolder("models")
        modelStore = ModelStore(modelsRoot)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newRepo(): SettingsRepository = SettingsRepository(prefs, secrets)

    private fun newVm(
        repo: SettingsRepository = newRepo(),
        chatClient: ChatClient = FakeChatClient(models = emptyList()),
        templates: FakeSummaryTemplateStore = FakeSummaryTemplateStore(),
        listServerModels: suspend () -> List<String> = { emptyList() }
    ): SettingsViewModel = SettingsViewModel(repo, chatClient, modelStore, templates, listServerModels)

    @Test
    fun save_persistsFormAndApiKeyAndReloadReadsThemBack() {
        val repo = newRepo()
        val vm = newVm(repo = repo)
        val form = SettingsForm(
            baseUrl = "http://192.168.1.10:8000/v1",
            apiKey = "sk-secret",
            model = "m1",
            maxChars = "12345",
            numThreads = 6,
            numSpeakers = 4,
            clusterThreshold = 0.4f
        )
        vm.update(form)
        vm.save()

        // A second repository sharing the same prefs file but a
        // brand-new SecretStore reads the plain fields but NOT
        // the API key — proving the key lives only in the
        // SecretStore and that save() did write it through the
        // existing SecretStore instance.
        val s = repo.settings.value
        assertEquals("http://192.168.1.10:8000/v1", s.llmBaseUrl)
        assertEquals("m1", s.llmModel)
        assertEquals(12345, s.maxTranscriptChars)
        assertEquals(6, s.numThreads)
        assertEquals(4, s.numSpeakers)
        assertEquals(0.4f, s.clusterThreshold, 0.0001f)
        assertEquals("sk-secret", repo.apiKey())

        val fresh = SettingsRepository(prefs, InMemorySecretStore())
        val s2 = fresh.settings.value
        assertEquals("http://192.168.1.10:8000/v1", s2.llmBaseUrl)
        assertEquals("m1", s2.llmModel)
        assertEquals(12345, s2.maxTranscriptChars)
        assertEquals("", fresh.apiKey())
    }

    @Test
    fun save_persistsEveryFieldIncludingApiKey() = runTest(dispatcher) {
        val repo = newRepo()
        val vm = newVm(repo = repo)
        vm.update(
            SettingsForm(
                baseUrl = "http://example.test/v1",
                apiKey = "sk-roundtrip",
                model = "gpt-test",
                maxChars = "9999",
                numThreads = 7,
                numSpeakers = -1,
                clusterThreshold = 0.7f
            )
        )
        vm.save()

        val s = repo.settings.value
        assertEquals("http://example.test/v1", s.llmBaseUrl)
        assertEquals("gpt-test", s.llmModel)
        assertEquals(9999, s.maxTranscriptChars)
        assertEquals(7, s.numThreads)
        assertEquals(-1, s.numSpeakers)
        assertEquals(0.7f, s.clusterThreshold, 0.0001f)
        assertEquals("sk-roundtrip", repo.apiKey())

        assertTrue(vm.state.value.saved)
    }

    @Test
    fun save_invalidMaxCharsKeepsPreviousValue() {
        val repo = newRepo()
        // Pre-seed maxTranscriptChars to a known value.
        repo.update { it.copy(maxTranscriptChars = 8800) }
        val vm = newVm(repo = repo)
        vm.update(SettingsForm(maxChars = "")) // empty → toIntOrNull == null
        vm.save()
        assertEquals(8800, repo.settings.value.maxTranscriptChars)

        vm.update(SettingsForm(maxChars = "not a number"))
        vm.save()
        assertEquals(8800, repo.settings.value.maxTranscriptChars)
    }

    @Test
    fun testConnection_withFakeModelsListsPopulatesTestResultAndAvailableModels() = runTest(dispatcher) {
        val fake = FakeChatClient(models = listOf("m1", "m2"))
        val repo = newRepo()
        val vm = newVm(repo = repo, chatClient = fake)
        vm.update(SettingsForm(baseUrl = "http://example.test/v1", model = "m1"))
        vm.testConnection()
        advanceUntilIdle()
        val s = vm.state.value
        assertFalse(s.testing)
        assertEquals(ConnectionTestResult.Connected(count = 2, models = listOf("m1", "m2")), s.testResult)
        assertEquals(listOf("m1", "m2"), s.availableModels)
    }

    @Test
    fun save_persistsServerFieldsAndKey() {
        val repo = newRepo()
        val vm = newVm(repo = repo)
        vm.update(SettingsForm(serverUrl = "https://asr.example.com/v1/", serverKey = "sk-asr", serverModel = "p"))

        vm.save()

        assertEquals("https://asr.example.com/v1", repo.settings.value.asrServerUrl)
        assertEquals("p", repo.settings.value.asrServerModel)
        assertEquals("sk-asr", repo.asrServerKey())
        assertFalse(vm.state.value.serverUrlError)
        assertTrue(vm.state.value.serverConfigured)
    }

    @Test
    fun save_refusesAnHttpServerUrlAndKeepsItOnScreen() {
        val repo = newRepo()
        val vm = newVm(repo = repo)
        vm.update(SettingsForm(serverUrl = "http://parakeet-asr.asr:8000/v1"))

        vm.save()

        assertEquals("", repo.settings.value.asrServerUrl)
        assertTrue(vm.state.value.serverUrlError)
        assertEquals("http://parakeet-asr.asr:8000/v1", vm.state.value.form.serverUrl)
    }

    @Test
    fun testServer_listsModels() = runTest(dispatcher) {
        val vm = newVm(listServerModels = { listOf("parakeet-tdt-0.6b-v3") })
        vm.update(SettingsForm(serverUrl = "https://asr.example.com/v1"))

        vm.testServer()
        advanceUntilIdle()

        assertFalse(vm.state.value.serverTesting)
        assertEquals(
            ConnectionTestResult.Connected(count = 1, models = listOf("parakeet-tdt-0.6b-v3")),
            vm.state.value.serverTestResult
        )
        assertEquals(listOf("parakeet-tdt-0.6b-v3"), vm.state.value.serverModels)
    }

    @Test
    fun testServer_failureShowsTheServerMessage() = runTest(dispatcher) {
        val vm = newVm(listServerModels = { throw ServerAsrException("HTTP 401 from x: no") })
        vm.update(SettingsForm(serverUrl = "https://asr.example.com/v1"))

        vm.testServer()
        advanceUntilIdle()

        assertEquals(ConnectionTestResult.Failed("HTTP 401 from x: no"), vm.state.value.serverTestResult)
    }

    @Test
    fun testServer_withAnHttpUrlDoesNotCallTheServer() = runTest(dispatcher) {
        var called = false
        val vm = newVm(listServerModels = {
            called = true
            emptyList()
        })
        vm.update(SettingsForm(serverUrl = "http://asr.example.com/v1"))

        vm.testServer()
        advanceUntilIdle()

        assertFalse(called)
        assertTrue(vm.state.value.serverUrlError)
        assertEquals(null, vm.state.value.serverTestResult)
    }

    @Test
    fun setServerAuto_writesImmediately() {
        val repo = newRepo()
        val vm = newVm(repo = repo)

        vm.setServerAuto(true)

        assertTrue(repo.settings.value.asrServerAuto)
    }

    @Test
    fun testConnection_withThrowingFakeSetsFailureMessage() = runTest(dispatcher) {
        val fake = FakeChatClient(error = LlmException("HTTP 401: x", httpCode = 401))
        val vm = newVm(repo = newRepo(), chatClient = fake)
        vm.testConnection()
        advanceUntilIdle()
        val s = vm.state.value
        assertFalse(s.testing)
        assertNotNull(s.testResult)
        assertEquals(ConnectionTestResult.Failed("HTTP 401: x"), s.testResult)
        assertTrue(s.availableModels.isEmpty())
    }

    @Test
    fun testConnection_messagelessFailureShowsExceptionName() = runTest(dispatcher) {
        val fake = FakeChatClient(error = IllegalStateException())
        val vm = newVm(repo = newRepo(), chatClient = fake)
        vm.testConnection()
        advanceUntilIdle()
        assertEquals(ConnectionTestResult.Failed("IllegalStateException"), vm.state.value.testResult)
    }

    @Test
    fun testConnection_stripsTrailingSlashWhenSavingBeforeTest() = runTest(dispatcher) {
        val fake = FakeChatClient(models = listOf("m"))
        val repo = newRepo()
        val vm = newVm(repo = repo, chatClient = fake)
        vm.update(SettingsForm(baseUrl = "http://example.test/v1/", model = "m"))
        vm.testConnection()
        advanceUntilIdle()
        // The trailing slash was stripped before chatClient was
        // called; repo is the canonical source.
        assertEquals("http://example.test/v1", repo.settings.value.llmBaseUrl)
        assertEquals(ConnectionTestResult.Connected(count = 1, models = listOf("m")), vm.state.value.testResult)
    }

    @Test
    fun setEngine_persistsImmediatelyAndIgnoresUnknownValues() {
        val repo = newRepo()
        val vm = newVm(repo = repo)
        vm.setEngine("cpu")
        assertEquals("cpu", repo.settings.value.asrEngine)
        assertEquals("cpu", vm.state.value.asrEngine)
        // Unknown value must NOT touch the persistence layer or
        // the form state.
        vm.setEngine("hopper")
        assertEquals("cpu", repo.settings.value.asrEngine)
        assertEquals("cpu", vm.state.value.asrEngine)
    }

    @Test
    fun update_replacesForm() {
        val vm = newVm()
        vm.update(SettingsForm(model = "first"))
        assertEquals("first", vm.state.value.form.model)
        vm.update(SettingsForm(model = "second"))
        assertEquals("second", vm.state.value.form.model)
    }

    @Test
    fun acknowledgeSaved_resetsSavedFlag() {
        val vm = newVm()
        vm.update(SettingsForm(model = "x"))
        vm.save()
        assertTrue(vm.state.value.saved)
        vm.acknowledgeSaved()
        assertFalse(vm.state.value.saved)
    }

    @Test
    fun initialState_seedsFromRepository() = runTest(dispatcher) {
        val repo = newRepo()
        repo.update { it.copy(llmBaseUrl = "http://seed/v1", llmModel = "m-seed", numThreads = 3) }
        repo.setApiKey("sk-seed")
        // Build a fresh repository reading from the same prefs +
        // secrets so we exercise the init path explicitly.
        val newRepo = SettingsRepository(prefs, secrets)
        val vm = newVm(repo = newRepo)
        val state = vm.state.first { it.form.baseUrl.isNotEmpty() }
        assertEquals("http://seed/v1", state.form.baseUrl)
        assertEquals("m-seed", state.form.model)
        assertEquals(3, state.form.numThreads)
        assertEquals("sk-seed", state.form.apiKey)
    }

    @Test
    fun setLanguage_persistsImmediatelyAndIgnoresUnknownValues() {
        val repo = newRepo()
        val vm = newVm(repo = repo)
        vm.setLanguage("fr")
        assertEquals("fr", repo.settings.value.asrLanguage)
        assertEquals("fr", vm.state.value.asrLanguage)
        // Unknown value must NOT touch the persistence layer or
        // the form state.
        vm.setLanguage("de")
        assertEquals("fr", repo.settings.value.asrLanguage)
        assertEquals("fr", vm.state.value.asrLanguage)
    }

    @Test
    fun setLanguage_toFrench_marksModelAsNotInstalled() {
        // Nothing on disk: the language selector must report
        // the French bundle as missing and the missing-bytes
        // value at 71_052_319 (the ASR_FR bundle's totalBytes).
        val repo = newRepo()
        val vm = newVm(repo = repo)
        vm.setLanguage("fr")
        val s = vm.state.value
        assertEquals("fr", s.asrLanguage)
        assertFalse(s.selectedLanguageModelInstalled)
        assertEquals(71_052_319L, s.selectedLanguageModelMissingBytes)
    }

    @Test
    fun deleteFrenchModel_removesBundleFromDisk_andUpdatesState() = runTest(dispatcher) {
        // Pretend the French bundle is installed: write every
        // declared file at its declared size into the
        // ModelStore's bundle directory. We use a sparse
        // RandomAccessFile (length set to the declared size but
        // never actually written) so the test does not have to
        // allocate a multi-hundred-megabyte byte array.
        val repo = newRepo()
        for (file in com.convoscribe.models.ModelCatalog.ASR_FR.files) {
            val onDisk = modelStore.fileFor(com.convoscribe.models.ModelCatalog.ASR_FR, file.fileName)
            onDisk.parentFile?.mkdirs()
            java.io.RandomAccessFile(onDisk, "rw").use { it.setLength(file.sizeBytes) }
        }
        assertTrue(modelStore.isInstalled(com.convoscribe.models.ModelCatalog.ASR_FR))

        val vm = newVm(repo = repo)
        assertTrue(vm.state.value.frenchModelInstalled)

        vm.deleteFrenchModel()
        advanceUntilIdle()

        assertFalse(modelStore.isInstalled(com.convoscribe.models.ModelCatalog.ASR_FR))
        assertFalse(vm.state.value.frenchModelInstalled)
    }

    @Test
    fun autoMarkNotes_isOnAndTurningItOffPersists() = runTest(dispatcher) {
        val repo = newRepo()
        val vm = newVm(repo = repo)
        assertTrue(vm.state.value.autoMarkNotes)

        vm.setAutoMarkNotes(false)
        advanceUntilIdle()
        assertFalse(repo.settings.value.autoMarkNotes)
        assertFalse(vm.state.value.autoMarkNotes)
    }

    @Test
    fun autoTitle_isOnAndTurningItOffPersists() = runTest(dispatcher) {
        val repo = newRepo()
        val vm = newVm(repo = repo)
        assertTrue(vm.state.value.autoTitle)

        vm.setAutoTitle(false)
        advanceUntilIdle()
        assertFalse(repo.settings.value.autoTitle)
        assertFalse(vm.state.value.autoTitle)
    }

    @Test
    fun cleanupModel_showsAsInstalled_andCanBeDeleted_andCleanByDefaultPersists() = runTest(dispatcher) {
        val repo = newRepo()
        val vm = newVm(repo = repo)
        assertFalse(vm.state.value.cleanupInstalled)
        val bundle = com.convoscribe.models.ModelCatalog.CLEANUP
        val onDisk = modelStore.fileFor(bundle, bundle.files.single().fileName)
        onDisk.parentFile?.mkdirs()
        java.io.RandomAccessFile(onDisk, "rw").use { it.setLength(bundle.files.single().sizeBytes) }

        vm.refreshModels()
        assertTrue(vm.state.value.cleanupInstalled)
        vm.setCleanAudioDefault(true)
        advanceUntilIdle()
        assertTrue(repo.settings.value.cleanAudioDefault)
        assertTrue(vm.state.value.cleanAudioDefault)

        vm.deleteCleanupModel()
        advanceUntilIdle()
        assertFalse(modelStore.isInstalled(bundle))
        assertFalse(vm.state.value.cleanupInstalled)
    }

    @Test
    fun deletingFrenchModel_whileEnglishIsActive_keepsEnglishInstalled() = runTest(dispatcher) {
        // Pre-install both English and French so the French
        // bundle is removable without affecting the user's
        // chosen language (English). The default engine is
        // "cpu" (no verified NPU), so the English bundle that
        // satisfies the `selectedLanguageModelInstalled` check is
        // the CPU [ModelCatalog.ASR_EN].
        for (bundle in listOf(
            com.convoscribe.models.ModelCatalog.ASR_EN,
            com.convoscribe.models.ModelCatalog.ASR_FR
        )) {
            for (file in bundle.files) {
                val onDisk = modelStore.fileFor(bundle, file.fileName)
                onDisk.parentFile?.mkdirs()
                // A sparse file via RandomAccessFile.setLength
                // records the expected size on disk without
                // allocating the model bytes.
                java.io.RandomAccessFile(onDisk, "rw").use { it.setLength(file.sizeBytes) }
            }
        }
        val repo = newRepo()
        val vm = newVm(repo = repo)
        assertEquals("en", vm.state.value.asrLanguage)
        assertTrue(vm.state.value.selectedLanguageModelInstalled)
        assertTrue(vm.state.value.frenchModelInstalled)

        vm.deleteFrenchModel()
        advanceUntilIdle()

        // English (the CPU bundle, the default engine) is
        // untouched, French is gone.
        assertTrue(modelStore.isInstalled(com.convoscribe.models.ModelCatalog.ASR_EN))
        assertFalse(modelStore.isInstalled(com.convoscribe.models.ModelCatalog.ASR_FR))
        assertEquals("en", vm.state.value.asrLanguage)
        assertTrue(vm.state.value.selectedLanguageModelInstalled)
        assertFalse(vm.state.value.frenchModelInstalled)
    }

    @Test
    fun defaultParticipants_areShownKeptOnSaveAndReset() {
        val repo = newRepo()
        repo.update { it.copy(defaultParticipants = listOf("Alice")) }
        val vm = newVm(repo = repo)
        assertEquals(listOf("Alice"), vm.state.value.defaultParticipants)

        vm.save()
        assertEquals(listOf("Alice"), repo.settings.value.defaultParticipants)

        vm.resetDefaultParticipants()
        assertNull(repo.settings.value.defaultParticipants)
        assertNull(vm.state.value.defaultParticipants)
    }

    @Test
    fun displayMode_andDynamicColour_areWrittenImmediately() = runTest(dispatcher) {
        val repo = newRepo()
        val vm = newVm(repo = repo)
        assertEquals("auto", vm.state.value.displayMode)

        vm.setDisplayMode("eink")
        vm.setDynamicColor(true)
        advanceUntilIdle()

        assertEquals("eink", repo.settings.value.displayMode)
        assertTrue(repo.settings.value.dynamicColor)
        assertEquals("eink", vm.state.value.displayMode)
        assertTrue(vm.state.value.dynamicColor)

        vm.setDisplayMode("sepia")
        advanceUntilIdle()
        assertEquals("eink", repo.settings.value.displayMode)
    }

    @Test
    fun save_keepsTheDisplayMode() = runTest(dispatcher) {
        val repo = newRepo()
        val vm = newVm(repo = repo)
        vm.setDisplayMode("standard")
        vm.save()
        assertEquals("standard", repo.settings.value.displayMode)
    }

    @Test
    fun updateSwitches_areWrittenImmediately_andMirroredInTheState() = runTest(dispatcher) {
        val repo = newRepo()
        val vm = newVm(repo = repo)
        assertTrue(vm.state.value.updateCheckAuto)
        assertFalse(vm.state.value.updateIncludePrereleases)
        vm.setUpdateCheckAuto(false)
        vm.setUpdateIncludePrereleases(true)
        advanceUntilIdle()
        assertFalse(repo.settings.value.updateCheckAuto)
        assertTrue(repo.settings.value.updateIncludePrereleases)
        assertFalse(vm.state.value.updateCheckAuto)
        assertTrue(vm.state.value.updateIncludePrereleases)
        // Save does not undo them.
        vm.save()
        assertFalse(repo.settings.value.updateCheckAuto)
    }

    @Test
    fun speakerIdMode_isWrittenImmediately_andMirroredInTheState() = runTest(dispatcher) {
        val repo = newRepo()
        val vm = newVm(repo = repo)
        assertEquals("auto", vm.state.value.speakerIdMode)
        vm.setSpeakerIdMode("charging")
        advanceUntilIdle()
        assertEquals("charging", repo.settings.value.speakerIdMode)
        assertEquals("charging", vm.state.value.speakerIdMode)
        // Save does not undo it.
        vm.save()
        assertEquals("charging", repo.settings.value.speakerIdMode)
    }

    @Test
    fun defaultSummaryTemplate_isWrittenImmediately_andListsUserTemplates() = runTest(dispatcher) {
        val repo = newRepo()
        val templates = FakeSummaryTemplateStore()
        val key = templates.add("Standup", "Three bullets.")
        val vm = newVm(repo = repo, templates = templates)
        advanceUntilIdle()
        assertEquals("meeting", vm.state.value.defaultSummaryTemplate)
        assertEquals("Standup", vm.state.value.summaryTemplates.last().name)

        vm.setDefaultSummaryTemplate(key)
        advanceUntilIdle()
        assertEquals(key, repo.settings.value.defaultSummaryTemplate)
        assertEquals(key, vm.state.value.defaultSummaryTemplate)
    }

    @Test
    fun floatingShape_isWrittenImmediately_andMirroredInTheState() = runTest(dispatcher) {
        val repo = newRepo()
        val vm = newVm(repo = repo)
        assertNull(vm.state.value.floatingShape)
        vm.setFloatingShape("expanded")
        advanceUntilIdle()
        assertEquals("expanded", repo.settings.value.floatingShape)
        assertEquals("expanded", vm.state.value.floatingShape)
        vm.save()
        assertEquals("expanded", repo.settings.value.floatingShape)
    }

    /**
     * Trivial in-memory [ChatClient] for tests. Returns the
     * configured `models` list, or throws the configured `error`
     * if one was provided.
     */
    private class FakeChatClient(
        private val models: List<String> = emptyList(),
        private val error: Throwable? = null
    ) : ChatClient {
        override fun stream(messages: List<LlmMessage>): Flow<String> = flowOf()

        override suspend fun listModels(): List<String> {
            error?.let { throw it }
            return models
        }
    }
}
