package com.convoscribe.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Which language model and transcription server the app uses once the
 * sync server provides them (issue #94), and that the user's own values
 * and sync are left alone.
 */
@RunWith(RobolectricTestRunner::class)
class ServerServicesSettingsTest {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var secrets: InMemorySecretStore

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences("server-services-${System.nanoTime()}", Context.MODE_PRIVATE)
        secrets = InMemorySecretStore().apply {
            setApiKey("own-llm-key")
            setAsrServerKey("own-asr-key")
            setSyncKey("sync-key")
        }
    }

    private fun newRepository() = SettingsRepository(prefs, secrets)

    private val bothOffered = ServerOffer(
        llm = OfferedService("qwen3"),
        transcription = OfferedService("parakeet"),
        fetchedAt = 1L
    )

    private fun withOwnEndpoints(s: AppSettings) =
        s.copy(llmBaseUrl = "https://llm.example/v1", llmModel = "m", asrServerUrl = "https://asr.example/v1")

    private fun connected(repo: SettingsRepository, offer: ServerOffer? = bothOffered) {
        repo.update { it.copy(syncServerUrl = SERVER) }
        repo.update { it.copy(serverOffer = offer).withServerServicesOn() }
    }

    @Test
    fun noServer_ownValuesAreUsedAsToday() {
        val repo = newRepository()
        repo.update { withOwnEndpoints(it) }
        assertEquals("https://llm.example/v1", repo.llmConfig().baseUrl)
        assertEquals("own-llm-key", repo.llmConfig().apiKey)
        assertEquals("https://asr.example/v1", repo.asrConfig().baseUrl)
        assertEquals("own-asr-key", repo.asrConfig().apiKey)
        assertTrue(repo.settings.value.llmConfigured)
    }

    @Test
    fun nothingTypedIn_servicesOn_usesTheServersWithTheSyncKey() {
        val repo = newRepository()
        connected(repo)
        val s = repo.settings.value
        assertEquals(ServiceSource.SERVER, s.llmSource)
        assertEquals(ServiceSource.SERVER, s.asrSource)
        assertEquals("$SERVER/api/ai/v1/llm", repo.llmConfig().baseUrl)
        assertEquals("qwen3", repo.llmConfig().model)
        assertEquals("sync-key", repo.llmConfig().apiKey)
        assertEquals("$SERVER/api/ai/v1/asr", repo.asrConfig().baseUrl)
        assertEquals("parakeet", repo.asrConfig().model)
        assertEquals("sync-key", repo.asrConfig().apiKey)
        assertTrue(s.llmConfigured)
        assertTrue(s.asrServerConfigured)
    }

    @Test
    fun offerKnown_butServicesNotAccepted_nothingGoesToTheServer() {
        val repo = newRepository()
        repo.update { it.copy(syncServerUrl = SERVER) }
        repo.update { it.copy(serverOffer = bothOffered) }
        val s = repo.settings.value
        assertFalse(s.llmConfigured)
        assertFalse(s.asrServerConfigured)
        assertEquals("", repo.llmConfig().baseUrl)
    }

    @Test
    fun ownEndpoints_keptAfterTurningServicesOn() {
        val repo = newRepository()
        repo.update { withOwnEndpoints(it) }
        connected(repo)
        val s = repo.settings.value
        assertEquals(ServiceSource.OWN, s.llmSource)
        assertEquals(ServiceSource.OWN, s.asrSource)
        assertEquals("https://llm.example/v1", repo.llmConfig().baseUrl)
        assertEquals("own-llm-key", repo.llmConfig().apiKey)
        assertEquals("https://asr.example/v1", repo.asrConfig().baseUrl)
    }

    @Test
    fun switchingToTheServer_keepsTheOwnValues_andTurningOffRestoresThem() {
        val repo = newRepository()
        repo.update { it.copy(llmBaseUrl = "https://llm.example/v1", llmModel = "m") }
        connected(repo)
        repo.update { it.copy(llmSource = ServiceSource.SERVER) }
        assertEquals("$SERVER/api/ai/v1/llm", repo.llmConfig().baseUrl)
        assertEquals("https://llm.example/v1", repo.settings.value.llmBaseUrl)

        repo.update { it.copy(serverServicesEnabled = false) }
        assertEquals("https://llm.example/v1", repo.llmConfig().baseUrl)
        assertEquals("m", repo.llmConfig().model)
        assertEquals("own-llm-key", repo.llmConfig().apiKey)
    }

    @Test
    fun aChosenSource_isKeptWhenServicesAreTurnedBackOn() {
        val repo = newRepository()
        connected(repo)
        repo.update { it.copy(asrSource = ServiceSource.OWN) }
        repo.update { it.copy(serverServicesEnabled = false) }
        repo.update { it.withServerServicesOn() }
        assertEquals(ServiceSource.OWN, repo.settings.value.asrSource)
    }

    @Test
    fun serviceNotOffered_isNotAvailable() {
        val repo = newRepository()
        connected(repo, ServerOffer(llm = OfferedService("qwen3"), transcription = null))
        val s = repo.settings.value
        assertTrue(s.llmConfigured)
        assertTrue(s.asrFromServer)
        assertFalse(s.asrServerConfigured)
        assertFalse(s.asrServerAutoActive)
    }

    @Test
    fun olderServerOfferingNothing_behavesAsToday() {
        val repo = newRepository()
        repo.update { it.copy(asrServerUrl = "https://asr.example/v1") }
        connected(repo, ServerOffer())
        val s = repo.settings.value
        assertFalse(s.llmConfigured)
        assertEquals("https://asr.example/v1", repo.asrConfig().baseUrl)
    }

    @Test
    fun improveRecordingsOnTheServer_staysOff() {
        val repo = newRepository()
        connected(repo)
        assertFalse(repo.settings.value.asrServerAuto)
        assertFalse(repo.settings.value.asrServerAutoActive)
    }

    @Test
    fun servicesWorkWithSyncOff() {
        val repo = newRepository()
        connected(repo)
        assertFalse(repo.settings.value.syncEnabled)
        assertTrue(repo.settings.value.llmConfigured)
    }

    @Test
    fun anotherServerAddress_turnsServicesOff_andLeavesSyncAlone() {
        val repo = newRepository()
        connected(repo)
        repo.update { it.copy(syncEnabled = true) }
        repo.update { it.copy(syncServerUrl = "https://other.example") }
        val s = repo.settings.value
        assertFalse(s.serverServicesEnabled)
        assertNull(s.serverOffer)
        assertTrue(s.syncEnabled)
    }

    @Test
    fun theSameAddressSavedAgain_keepsServicesOn() {
        val repo = newRepository()
        connected(repo)
        repo.update { it.copy(syncServerUrl = "$SERVER/ ") }
        assertTrue(repo.settings.value.serverServicesEnabled)
        assertEquals(bothOffered, repo.settings.value.serverOffer)
    }

    @Test
    fun servicesSourcesAndOffer_persist() {
        val repo = newRepository()
        connected(repo, ServerOffer(llm = OfferedService("qwen3"), transcription = OfferedService(""), fetchedAt = 42L))
        repo.update { it.copy(asrSource = ServiceSource.OWN) }
        val reloaded = newRepository().settings.value
        assertTrue(reloaded.serverServicesEnabled)
        assertEquals(ServiceSource.SERVER, reloaded.llmSource)
        assertEquals(ServiceSource.OWN, reloaded.asrSource)
        assertEquals(
            ServerOffer(llm = OfferedService("qwen3"), transcription = OfferedService(""), fetchedAt = 42L),
            reloaded.serverOffer
        )
    }

    /** A device set up before issue #94 keeps syncing exactly as it did. */
    @Test
    fun existingSyncSetup_loadsUnchanged_withServicesOff() {
        prefs.edit()
            .putString(SettingsRepository.KEY_SYNC_SERVER_URL, SERVER)
            .putBoolean(SettingsRepository.KEY_SYNC_ENABLED, true)
            .putString(SettingsRepository.KEY_LLM_BASE_URL, "https://llm.example/v1")
            .putString(SettingsRepository.KEY_LLM_MODEL, "m")
            .apply()
        val s = newRepository().settings.value
        assertTrue(s.syncActive)
        assertEquals(SERVER, s.syncServerUrl)
        assertFalse(s.serverServicesEnabled)
        assertNull(s.serverOffer)
        assertNull(s.llmSource)
        assertEquals("https://llm.example/v1", s.llmBaseUrlInUse)
    }

    private companion object {
        const val SERVER = "https://sync.example"
    }
}
