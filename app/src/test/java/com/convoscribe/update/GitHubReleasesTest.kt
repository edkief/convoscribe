package com.convoscribe.update

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** [GitHubReleases] against a [MockWebServer] standing in for api.github.com. */
class GitHubReleasesTest {

    private lateinit var server: MockWebServer
    private lateinit var releases: GitHubReleases

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        releases = GitHubReleases(OkHttpClient(), userAgent = "ConvoScribe/0.2.1", apiBase = server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun parsesReleasesAndSendsTheRequiredHeaders() = runBlocking {
        server.enqueue(MockResponse().setBody(SAMPLE))
        val list = releases.releases()!!

        val request = server.takeRequest()
        assertEquals("/repos/edkief/convoscribe/releases?per_page=10", request.path)
        assertEquals("ConvoScribe/0.2.1", request.getHeader("User-Agent"))
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))

        assertEquals(2, list.size)
        val latest = list[0]
        assertEquals("v0.2.1", latest.tag)
        assertEquals("ConvoScribe 0.2.1", latest.name)
        assertEquals("Notes body", latest.notes)
        assertEquals(false, latest.prerelease)
        assertEquals(listOf("convoscribe-0.2.1.apk", "convoscribe-0.2.1.apk.sha256"), latest.assets.map { it.name })
        assertEquals(89_400_330L, latest.assets[0].sizeBytes)
        assertEquals(
            "https://github.com/edkief/convoscribe/releases/download/v0.2.1/convoscribe-0.2.1.apk",
            latest.assets[0].url
        )
        val old = list[1]
        assertTrue(old.prerelease)
        // A null name and body fall back to the tag and empty notes.
        assertEquals("v0.1.0", old.name)
        assertEquals("", old.notes)
    }

    @Test
    fun rateLimited_givesNull() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"API rate limit exceeded"}"""))
        assertNull(releases.releases())
        server.enqueue(MockResponse().setResponseCode(429))
        assertNull(releases.releases())
    }

    @Test
    fun offline_givesNull() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertNull(releases.releases())
    }

    @Test
    fun unparseableBody_givesNull() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>not json</html>"))
        assertNull(releases.releases())
    }

    private companion object {
        val SAMPLE = """
            [
              {
                "tag_name": "v0.2.1",
                "name": "ConvoScribe 0.2.1",
                "body": "Notes body",
                "html_url": "https://github.com/edkief/convoscribe/releases/tag/v0.2.1",
                "draft": false,
                "prerelease": false,
                "author": {"login": "someone"},
                "assets": [
                  {"name": "convoscribe-0.2.1.apk", "size": 89400330,
                   "browser_download_url": "https://github.com/edkief/convoscribe/releases/download/v0.2.1/convoscribe-0.2.1.apk"},
                  {"name": "convoscribe-0.2.1.apk.sha256", "size": 88,
                   "browser_download_url": "https://github.com/edkief/convoscribe/releases/download/v0.2.1/convoscribe-0.2.1.apk.sha256"}
                ]
              },
              {
                "tag_name": "v0.1.0",
                "name": null,
                "body": null,
                "html_url": "https://github.com/edkief/convoscribe/releases/tag/v0.1.0",
                "draft": false,
                "prerelease": true,
                "assets": [
                  {"name": "convoscribe-0.1.0-debug.apk", "size": 102723138,
                   "browser_download_url": "https://github.com/edkief/convoscribe/releases/download/v0.1.0/convoscribe-0.1.0-debug.apk"}
                ]
              }
            ]
        """.trimIndent()
    }
}
