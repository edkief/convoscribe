package com.convoscribe.ui.settings

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.SignInReturnActivity
import com.convoscribe.data.FakeSummaryTemplateStore
import com.convoscribe.llm.ChatClient
import com.convoscribe.llm.LlmMessage
import com.convoscribe.models.ModelStore
import com.convoscribe.settings.InMemorySecretStore
import com.convoscribe.settings.PendingSignIn
import com.convoscribe.settings.SettingsRepository
import com.convoscribe.sync.DeviceSignInApi
import com.convoscribe.sync.KeyRequestResult
import com.convoscribe.sync.SignInOffer
import com.convoscribe.sync.StartResult
import com.convoscribe.sync.StartedKeyRequest
import com.convoscribe.sync.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
 * Device sign-in in Settings (issue #95): asking the server whether it
 * offers sign-in, starting a key request, surviving the app's end while
 * the browser is in front, and collecting the key once approved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DeviceSignInViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var secrets: InMemorySecretStore
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var repo: SettingsRepository
    private lateinit var modelStore: ModelStore
    private val api = FakeSignInApi()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context: Context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences("sign-in-vm-${System.nanoTime()}", Context.MODE_PRIVATE)
        secrets = InMemorySecretStore()
        repo = SettingsRepository(prefs, secrets)
        modelStore = ModelStore(tmp.newFolder("models"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.newVm(): SettingsViewModel = SettingsViewModel(
        // A fresh repository over the same stores, as after Android ends the app.
        repo = SettingsRepository(prefs, secrets).also { repo = it },
        chatClient = NoChat,
        modelStore = modelStore,
        templates = FakeSummaryTemplateStore(),
        signInApi = api,
        deviceName = "Google Pixel 9",
        now = { testScheduler.currentTime },
        newSignInState = { "state-1" }
    )

    /** Type [SERVER] and let the screen's check run. */
    private fun TestScope.signInReady(): SettingsViewModel {
        val vm = newVm()
        vm.update(vm.state.value.form.copy(syncUrl = SERVER))
        vm.checkSignIn()
        advanceUntilIdle()
        return vm
    }

    @Test
    fun checkSignIn_takesTheServersOffer_forTheAddressTyped() = runTest(dispatcher) {
        val vm = signInReady()
        assertEquals(listOf(SERVER), api.offersAsked)
        assertEquals(OFFER, vm.state.value.signIn.offer)
        assertEquals(SERVER, vm.state.value.signIn.checkedUrl)

        api.offer = null
        vm.update(vm.state.value.form.copy(syncUrl = "https://other.example.com"))
        vm.checkSignIn()
        advanceUntilIdle()
        assertNull(vm.state.value.signIn.offer)
    }

    @Test
    fun startSignIn_savesTheAddress_rememberTheRequest_andOpensTheApprovalPage() = runTest(dispatcher) {
        api.statuses += List(3) { KeyRequestResult.Pending }
        val vm = signInReady()

        vm.startSignIn()
        runCurrent()

        assertEquals(SERVER, repo.settings.value.syncServerUrl)
        val started = api.started.single()
        assertEquals("ConvoScribe on Google Pixel 9", started.purpose)
        assertEquals("convoscribe://api-key", started.returnUrl)
        assertEquals("state-1", started.state)
        val pending = repo.pendingSignIn()
        assertNotNull(pending)
        assertEquals("secret-1", pending!!.requestSecret)
        assertEquals(OFFER.proxyUrl, pending.proxyUrl)
        assertEquals(APPROVAL, vm.state.value.signIn.approvalUrl)
        assertEquals(SignInPhase.Waiting, vm.state.value.signIn.phase)

        vm.signInBrowserOpened()
        assertNull(vm.state.value.signIn.approvalUrl)
        vm.cancelSignIn()
    }

    @Test
    fun approved_collectsTheKey_savesIt_andForgetsTheRequest() = runTest(dispatcher) {
        api.statuses += listOf(KeyRequestResult.Pending, KeyRequestResult.Approved)
        api.finalized += KeyRequestResult.Issued("sk_authz_abc_def")
        val vm = signInReady()

        vm.startSignIn()
        advanceUntilIdle()

        assertEquals("sk_authz_abc_def", repo.syncKey())
        assertEquals("sk_authz_abc_def", vm.state.value.form.syncKey)
        assertNull(repo.pendingSignIn())
        assertEquals(SignInPhase.SignedIn, vm.state.value.signIn.phase)
        assertEquals(2, api.statusChecks)
    }

    @Test
    fun aSignInStartedBeforeTheAppEnded_isCollectedOnReturn() = runTest(dispatcher) {
        api.statuses += List(2) { KeyRequestResult.Pending }
        val first = signInReady()
        first.startSignIn()
        runCurrent()
        first.cancelPollingForTest()

        // Android ended the app while the browser was in front; a new screen resumes.
        api.statuses.clear()
        api.statuses += KeyRequestResult.Approved
        api.finalized += KeyRequestResult.Issued("sk_authz_key")
        val second = newVm()
        second.resumeSignIn()
        advanceUntilIdle()

        assertEquals("sk_authz_key", repo.syncKey())
        assertNull(repo.pendingSignIn())
    }

    @Test
    fun declined_forgetsTheRequest_andSaysSo() = runTest(dispatcher) {
        api.statuses += KeyRequestResult.Declined
        val vm = signInReady()
        vm.startSignIn()
        advanceUntilIdle()
        assertEquals(SignInPhase.Declined, vm.state.value.signIn.phase)
        assertNull(repo.pendingSignIn())
        assertEquals("", repo.syncKey())
    }

    @Test
    fun unanswered_expiresAfterFifteenMinutes() = runTest(dispatcher) {
        // Always pending: the fake answers Pending when it runs out.
        val vm = signInReady()
        vm.startSignIn()
        advanceUntilIdle()
        assertEquals(SignInPhase.Expired, vm.state.value.signIn.phase)
        assertNull(repo.pendingSignIn())
        assertTrue(testScheduler.currentTime > PendingSignIn.LIFETIME_MS)
    }

    @Test
    fun slowDown_waitsLongerBeforeTheNextCheck() = runTest(dispatcher) {
        api.statuses += listOf(KeyRequestResult.SlowDown, KeyRequestResult.Pending)
        val vm = signInReady()
        vm.startSignIn()
        runCurrent()
        assertEquals(1, api.statusChecks)
        advanceTimeBy(5_001)
        assertEquals(1, api.statusChecks)
        advanceTimeBy(5_000)
        assertEquals(2, api.statusChecks)
        vm.cancelSignIn()
    }

    @Test
    fun aFailedStart_saysWhy_andRemembersNothing() = runTest(dispatcher) {
        api.startResult = StartResult.Failed("unknown client")
        val vm = signInReady()
        vm.startSignIn()
        advanceUntilIdle()
        assertEquals(SignInPhase.Failed("unknown client"), vm.state.value.signIn.phase)
        assertNull(repo.pendingSignIn())
    }

    @Test
    fun signOut_forgetsTheKey() = runTest(dispatcher) {
        repo.setSyncKey("sk_authz_old")
        val vm = signInReady()
        vm.signOut()
        assertEquals("", repo.syncKey())
        assertEquals("", vm.state.value.form.syncKey)
    }

    @Test
    fun keyRefused_isA401FromTheSyncServer() {
        assertTrue(SyncStatus(lastError = "HTTP 401 from the sync server (check the sync API key): ").keyRefused)
        assertFalse(SyncStatus(lastError = "HTTP 403 from the sync server").keyRefused)
        assertFalse(SyncStatus().keyRefused)
    }

    @Test
    fun theBrowsersReturn_isOursOnlyWithTheWaitingState() {
        val pending = PendingSignIn(OFFER.proxyUrl, OFFER.clientId, "secret", "state-1", 0L)
        val ours = Uri.parse("convoscribe://api-key?api_key_request=approved&state=state-1")
        assertTrue(SignInReturnActivity.isOurReturn(ours, pending))
        assertFalse(SignInReturnActivity.isOurReturn(ours, null))
        assertFalse(SignInReturnActivity.isOurReturn(Uri.parse("convoscribe://api-key?state=other"), pending))
        assertFalse(SignInReturnActivity.isOurReturn(Uri.parse("convoscribe://api-key"), pending))
        assertFalse(
            SignInReturnActivity.isOurReturn(Uri.parse("https://evil.example.com/api-key?state=state-1"), pending)
        )
    }

    /** Stop the first screen's checks, as the app's end would. */
    private fun SettingsViewModel.cancelPollingForTest() {
        // Keep the request: only the in-memory polling ends with the process.
        val saved = repo.pendingSignIn()
        cancelSignIn()
        repo.setPendingSignIn(saved)
    }

    private data class Started(val purpose: String, val returnUrl: String, val state: String)

    private class FakeSignInApi : DeviceSignInApi {
        var offer: SignInOffer? = OFFER
        var startResult: StartResult = StartResult.Started(StartedKeyRequest("secret-1", APPROVAL, 5))
        val statuses = ArrayDeque<KeyRequestResult>()
        val finalized = ArrayDeque<KeyRequestResult>()
        val offersAsked = mutableListOf<String>()
        val started = mutableListOf<Started>()
        var statusChecks = 0

        override suspend fun fetchOffer(serverUrl: String): SignInOffer? {
            offersAsked += serverUrl
            return offer
        }

        override suspend fun start(
            offer: SignInOffer,
            purpose: String,
            description: String,
            returnUrl: String,
            state: String
        ): StartResult {
            started += Started(purpose, returnUrl, state)
            return startResult
        }

        override suspend fun status(proxyUrl: String, clientId: String, requestSecret: String): KeyRequestResult {
            statusChecks++
            return statuses.removeFirstOrNull() ?: KeyRequestResult.Pending
        }

        override suspend fun finalize(proxyUrl: String, clientId: String, requestSecret: String): KeyRequestResult =
            finalized.removeFirstOrNull() ?: KeyRequestResult.Pending
    }

    private object NoChat : ChatClient {
        override fun stream(messages: List<LlmMessage>): Flow<String> = flowOf()

        override suspend fun listModels(): List<String> = emptyList()
    }

    private companion object {
        const val SERVER = "https://sync.example.com"
        const val APPROVAL = "https://auth.example.com/api-keys/request?token=t"
        val OFFER = SignInOffer("https://auth.example.com", "convoscribe", null)
    }
}
