package com.convoscribe.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.settings.InMemorySecretStore
import com.convoscribe.settings.OfferedService
import com.convoscribe.settings.ServerOffer
import com.convoscribe.settings.SettingsRepository
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [ServerConfigClient] and [ServerOfferRefresher] against a fake
 * server (issue #94): what each answer of `GET /api/sync/v1/config`
 * becomes, and that only a real answer replaces the stored offer.
 */
@RunWith(RobolectricTestRunner::class)
class ServerConfigClientTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: SettingsRepository
    private val client = ServerConfigClient(OkHttpClient()) { 1000L }

    private val base: String get() = server.url("/").toString().trimEnd('/')

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("server-config-${System.nanoTime()}", Context.MODE_PRIVATE)
        repo = SettingsRepository(prefs, InMemorySecretStore().apply { setSyncKey("sync-key") })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun bothServices_areOfferedWithTheirModels_askedWithTheSyncKey() = runBlocking {
        server.enqueue(json("""{"llm":{"model":"qwen3"},"transcription":{"model":"parakeet"}}"""))

        val result = client.fetch(base, "sync-key")

        assertEquals(
            ServerConfigResult.Offered(
                ServerOffer(OfferedService("qwen3"), OfferedService("parakeet"), fetchedAt = 1000L)
            ),
            result
        )
        val recorded = server.takeRequest()
        assertEquals("/api/sync/v1/config", recorded.path)
        assertEquals("Bearer sync-key", recorded.getHeader("Authorization"))
    }

    @Test
    fun aDecisionService_isOffered_withABlankModelWhenUnnamed() = runBlocking {
        server.enqueue(json("""{"llm":null,"transcription":null,"decision":{"model":null}}"""))

        val result = client.fetch(base, "sync-key") as ServerConfigResult.Offered

        assertEquals(OfferedService(""), result.offer.decision)
    }

    @Test
    fun anOlderServer_withoutDecision_offersNone() = runBlocking {
        server.enqueue(json("""{"llm":null,"transcription":null}"""))

        val result = client.fetch(base, "sync-key") as ServerConfigResult.Offered

        assertNull(result.offer.decision)
    }

    @Test
    fun aServiceNotOffered_isNull_andAnUnnamedTranscriptionModelIsBlank() = runBlocking {
        server.enqueue(json("""{"llm":null,"transcription":{"model":null}}"""))

        val result = client.fetch(base, "sync-key") as ServerConfigResult.Offered

        assertNull(result.offer.llm)
        assertEquals(OfferedService(""), result.offer.transcription)
    }

    @Test
    fun anUnnamedLanguageModel_isTheServersFirstModel() = runBlocking {
        server.enqueue(json("""{"llm":{"model":null},"transcription":null}"""))
        server.enqueue(json("""{"object":"list","data":[{"id":"llama"},{"id":"other"}]}"""))

        val result = client.fetch(base, "sync-key") as ServerConfigResult.Offered

        assertEquals(OfferedService("llama"), result.offer.llm)
        server.takeRequest()
        assertEquals("/api/ai/v1/llm/models", server.takeRequest().path)
    }

    @Test
    fun anOlderServerWithoutTheEndpoint_offersNothing() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))

        assertEquals(ServerConfigResult.Offered(ServerOffer(fetchedAt = 1000L)), client.fetch(base, "sync-key"))
    }

    @Test
    fun aRefusedKey_isRefused() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(ServerConfigResult.Refused(401), client.fetch(base, "bad"))
    }

    @Test
    fun aServerError_orAnUnexpectedBody_fails() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(json("<html>sign in</html>"))

        assertTrue(client.fetch(base, "sync-key") is ServerConfigResult.Failed)
        assertTrue(client.fetch(base, "sync-key") is ServerConfigResult.Failed)
    }

    @Test
    fun refresher_storesAnAnswer_andKeepsTheLastOfferWhenAFetchFailsOrIsRefused() = runBlocking {
        val stored = ServerOffer(OfferedService("old"), null, fetchedAt = 1L)
        repo.update { it.copy(syncServerUrl = base) }
        repo.update { it.copy(serverOffer = stored).withServerServicesOn() }
        val refresher = ServerOfferRefresher(repo, client)

        server.enqueue(MockResponse().setResponseCode(401))
        refresher.refresh()
        assertEquals(stored, repo.settings.value.serverOffer)

        server.enqueue(MockResponse().setResponseCode(502))
        refresher.refresh()
        assertEquals(stored, repo.settings.value.serverOffer)

        server.enqueue(json("""{"llm":{"model":"new"},"transcription":null}"""))
        refresher.refresh()
        assertEquals(ServerOffer(OfferedService("new"), null, fetchedAt = 1000L), repo.settings.value.serverOffer)
    }

    @Test
    fun refresher_keepsTheLastModelWhenTheModelListFails() = runBlocking {
        val stored = ServerOffer(OfferedService("llama"), null, fetchedAt = 1L)
        repo.update { it.copy(syncServerUrl = base) }
        repo.update { it.copy(serverOffer = stored).withServerServicesOn() }
        val refresher = ServerOfferRefresher(repo, client)

        server.enqueue(json("""{"llm":{"model":null},"transcription":null}"""))
        server.enqueue(MockResponse().setResponseCode(502))
        assertTrue(refresher.refresh() is ServerConfigResult.Failed)
        assertEquals(stored, repo.settings.value.serverOffer)

        server.enqueue(json("""{"llm":{"model":null},"transcription":null}"""))
        server.enqueue(json("<html>sign in</html>"))
        assertTrue(refresher.refresh() is ServerConfigResult.Failed)
        assertEquals(stored, repo.settings.value.serverOffer)
    }

    @Test
    fun refresher_doesNothingWhileTheServicesAreOff() = runBlocking {
        repo.update { it.copy(syncServerUrl = base, syncEnabled = true) }

        assertNull(ServerOfferRefresher(repo, client).refresh())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun refresher_whenDue_asksAtMostHourly() = runBlocking {
        var clock = 10_000_000L
        repo.update { it.copy(syncServerUrl = base) }
        repo.update { it.withServerServicesOn() }
        val refresher = ServerOfferRefresher(repo, client) { clock }
        server.enqueue(json("""{"llm":null,"transcription":null}"""))
        server.enqueue(json("""{"llm":null,"transcription":null}"""))

        refresher.refreshIfDue()
        clock += 60_000L
        refresher.refreshIfDue()
        assertEquals(1, server.requestCount)
        clock += ServerOfferRefresher.INTERVAL_MS
        refresher.refreshIfDue()
        assertEquals(2, server.requestCount)
    }
}
