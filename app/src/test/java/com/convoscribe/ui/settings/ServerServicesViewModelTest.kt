package com.convoscribe.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.data.FakeSummaryTemplateStore
import com.convoscribe.llm.ChatClient
import com.convoscribe.llm.LlmMessage
import com.convoscribe.models.ModelStore
import com.convoscribe.settings.InMemorySecretStore
import com.convoscribe.settings.OfferedService
import com.convoscribe.settings.ServerOffer
import com.convoscribe.settings.ServiceSource
import com.convoscribe.settings.SettingsRepository
import com.convoscribe.sync.ServerConfigResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * *Use this server's services* in Settings (issue #94): one
 * confirmation before anything is used, apart from *Sync sessions*,
 * and the user's own values left alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ServerServicesViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var repo: SettingsRepository
    private lateinit var modelStore: ModelStore
    private var answer: ServerConfigResult = ServerConfigResult.Offered(OFFER)
    private val asked = mutableListOf<Pair<String, String>>()
    private var refreshes = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("server-services-vm-${System.nanoTime()}", Context.MODE_PRIVATE)
        repo = SettingsRepository(prefs, InMemorySecretStore())
        modelStore = ModelStore(tmp.newFolder("models"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newVm() = SettingsViewModel(
        repo = repo,
        chatClient = NoChat,
        modelStore = modelStore,
        templates = FakeSummaryTemplateStore(),
        fetchServerOffer = { url, key ->
            asked += url to key
            answer
        },
        refreshServerOffer = { refreshes++ }
    )

    private fun SettingsViewModel.typeServer(url: String = SERVER, key: String = "sync-key") {
        update(state.value.form.copy(syncUrl = url, syncKey = key))
    }

    @Test
    fun turningOn_asksTheServer_andUsesNothingBeforeAccepting() = runTest(dispatcher) {
        val vm = newVm()
        vm.typeServer()

        vm.requestServerServices()
        advanceUntilIdle()

        assertEquals(listOf(SERVER to "sync-key"), asked)
        assertEquals(ServicesCheck.Confirm(OFFER), vm.state.value.servicesCheck)
        assertFalse(repo.settings.value.serverServicesEnabled)
        assertFalse(repo.settings.value.llmConfigured)
        assertNull(repo.settings.value.serverOffer)
    }

    @Test
    fun accepting_usesTheServersServices_withSyncOff_andImproveRecordingsOff() = runTest(dispatcher) {
        val vm = newVm()
        vm.typeServer()
        vm.requestServerServices()
        advanceUntilIdle()

        vm.acceptServerServices()
        advanceUntilIdle()

        val s = repo.settings.value
        assertTrue(s.serverServicesEnabled)
        assertEquals(OFFER, s.serverOffer)
        assertTrue(s.llmConfigured)
        assertTrue(s.asrServerConfigured)
        assertFalse(s.syncEnabled)
        assertFalse(s.asrServerAuto)
        assertNull(vm.state.value.servicesCheck)
        assertTrue(vm.state.value.serverServicesEnabled)
        assertEquals(ServiceSource.SERVER, vm.state.value.llmSource)
    }

    @Test
    fun improveRecordings_leftOnFromARemovedServer_doesNotCarryOverToTheSyncServer() = runTest(dispatcher) {
        repo.update { it.copy(asrServerUrl = "https://asr.example", asrServerAuto = true) }
        repo.update { it.copy(asrServerUrl = "") }
        val vm = newVm()
        vm.typeServer()
        vm.requestServerServices()
        advanceUntilIdle()

        vm.acceptServerServices()

        assertEquals(ServiceSource.SERVER, repo.settings.value.asrSource)
        assertFalse(repo.settings.value.asrServerAuto)
        assertFalse(repo.settings.value.asrServerAutoActive)
    }

    @Test
    fun improveRecordings_staysOn_forAUserKeepingTheirOwnServer() = runTest(dispatcher) {
        repo.update { it.copy(asrServerUrl = "https://asr.example", asrServerAuto = true) }
        val vm = newVm()
        vm.typeServer()
        vm.requestServerServices()
        advanceUntilIdle()

        vm.acceptServerServices()

        assertEquals(ServiceSource.OWN, repo.settings.value.asrSource)
        assertTrue(repo.settings.value.asrServerAutoActive)
        assertEquals("https://asr.example", repo.asrConfig().baseUrl)
    }

    @Test
    fun cancelling_changesNothing() = runTest(dispatcher) {
        val vm = newVm()
        vm.typeServer()
        vm.requestServerServices()
        advanceUntilIdle()

        vm.dismissServerServices()

        assertNull(vm.state.value.servicesCheck)
        assertFalse(repo.settings.value.serverServicesEnabled)
        assertNull(repo.settings.value.serverOffer)
    }

    @Test
    fun aRefusedKey_orAnUnreachableServer_isShown_andNothingIsTurnedOn() = runTest(dispatcher) {
        val vm = newVm()
        vm.typeServer()
        answer = ServerConfigResult.Refused(401)
        vm.requestServerServices()
        advanceUntilIdle()
        assertEquals(ServicesCheck.Refused, vm.state.value.servicesCheck)

        answer = ServerConfigResult.Failed("Network error")
        vm.requestServerServices()
        advanceUntilIdle()
        assertEquals(ServicesCheck.Failed("Network error"), vm.state.value.servicesCheck)
        assertFalse(repo.settings.value.serverServicesEnabled)
    }

    @Test
    fun aServerProvidingNothing_isNotTurnedOn() = runTest(dispatcher) {
        answer = ServerConfigResult.Offered(ServerOffer(fetchedAt = 5L))
        val vm = newVm()
        vm.typeServer()
        vm.requestServerServices()
        advanceUntilIdle()

        vm.acceptServerServices()

        assertNull(vm.state.value.servicesCheck)
        assertFalse(repo.settings.value.serverServicesEnabled)
    }

    @Test
    fun ownEndpoints_areKeptAfterAccepting_andAfterTurningOff() = runTest(dispatcher) {
        repo.update { it.copy(llmBaseUrl = "https://llm.example/v1", llmModel = "m") }
        val vm = newVm()
        vm.typeServer()
        vm.requestServerServices()
        advanceUntilIdle()
        vm.acceptServerServices()
        advanceUntilIdle()

        assertEquals(ServiceSource.OWN, vm.state.value.llmSource)
        assertEquals(ServiceSource.SERVER, vm.state.value.asrSource)
        assertEquals("https://llm.example/v1", repo.llmConfig().baseUrl)

        vm.setLlmSource(ServiceSource.SERVER)
        assertEquals("$SERVER/api/ai/v1/llm", repo.llmConfig().baseUrl)

        vm.turnOffServerServices()
        advanceUntilIdle()
        assertFalse(vm.state.value.serverServicesEnabled)
        assertEquals("https://llm.example/v1", repo.llmConfig().baseUrl)
        assertEquals("https://llm.example/v1", vm.state.value.form.baseUrl)
    }

    @Test
    fun turningOff_leavesSyncOn() = runTest(dispatcher) {
        val vm = newVm()
        vm.typeServer()
        vm.setSyncEnabled(true)
        vm.requestServerServices()
        advanceUntilIdle()
        vm.acceptServerServices()

        vm.turnOffServerServices()
        advanceUntilIdle()

        assertTrue(repo.settings.value.syncActive)
        assertFalse(repo.settings.value.serverServicesEnabled)
    }

    @Test
    fun savingANewKey_refreshesTheOffer_onlyWhileTheServicesAreOn() = runTest(dispatcher) {
        val vm = newVm()
        vm.typeServer()
        vm.save()
        vm.typeServer(key = "other")
        vm.save()
        advanceUntilIdle()
        assertEquals(0, refreshes)

        vm.requestServerServices()
        advanceUntilIdle()
        vm.acceptServerServices()
        vm.typeServer(key = "third")
        vm.save()
        advanceUntilIdle()
        assertEquals(1, refreshes)
    }

    private object NoChat : ChatClient {
        override fun stream(messages: List<LlmMessage>): Flow<String> = flowOf()

        override suspend fun listModels(): List<String> = emptyList()
    }

    private companion object {
        const val SERVER = "https://sync.example"
        val OFFER = ServerOffer(OfferedService("qwen3"), OfferedService("parakeet"), fetchedAt = 5L)
    }
}
