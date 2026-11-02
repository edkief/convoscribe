package com.convoscribe.llm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.settings.InMemorySecretStore
import com.convoscribe.settings.OfferedService
import com.convoscribe.settings.ServerOffer
import com.convoscribe.settings.SettingsRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The chat client wired as in AppContainer (issue #94): with the sync
 * server's language model in use, questions go to its fixed path with
 * the sync key and the server's model, never the user's own key.
 */
@RunWith(RobolectricTestRunner::class)
class ServerLlmRoutingTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("llm-routing-${System.nanoTime()}", Context.MODE_PRIVATE)
        val secrets = InMemorySecretStore().apply {
            setApiKey("own-key")
            setSyncKey("sync-key")
        }
        repo = SettingsRepository(prefs, secrets)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun serverLanguageModel_isAskedAtItsFixedPathWithTheSyncKey() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n")
        )
        val base = server.url("/").toString().trimEnd('/')
        repo.update { it.copy(syncServerUrl = base) }
        repo.update {
            it.copy(serverOffer = ServerOffer(llm = OfferedService("qwen3"))).withServerServicesOn()
        }
        val client = OpenAiChatClient(OkHttpClient()) { repo.llmConfig() }

        val tokens = client.stream(listOf(LlmMessage(role = LlmMessage.ROLE_USER, content = "hi"))).toList()

        assertEquals(listOf("ok"), tokens)
        val recorded = server.takeRequest()
        assertEquals("/api/ai/v1/llm/chat/completions", recorded.path)
        assertEquals("Bearer sync-key", recorded.getHeader("Authorization"))
        assertEquals(true, recorded.body.readUtf8().contains("\"model\":\"qwen3\""))
    }
}
