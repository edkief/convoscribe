package com.convoscribe.update

import android.util.Log
import com.convoscribe.TAG
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** One downloadable file of a [Release]. */
data class ReleaseAsset(val name: String, val url: String, val sizeBytes: Long)

/** A GitHub release of the app, as far as the update check needs it. */
data class Release(
    val tag: String,
    val name: String,
    val notes: String,
    val htmlUrl: String,
    val prerelease: Boolean,
    val draft: Boolean,
    val assets: List<ReleaseAsset>
)

/** Where the app's releases come from; a fake in tests. */
fun interface ReleaseSource {
    /** The most recent releases, newest first, or `null` when they could not be read. */
    suspend fun releases(): List<Release>?
}

/**
 * Reads the app's releases from the GitHub REST API (issue #15):
 * `GET /repos/edkief/convoscribe/releases?per_page=10`. Not
 * `/releases/latest`, which leaves pre-releases out, so *Include
 * pre-releases* can work. The repository is public, so no token: 60
 * requests an hour per IP, far more than a check a day needs.
 *
 * Fails quietly: offline, rate limited (403/429), any other HTTP error
 * or a body that does not parse all give `null`, logged only.
 *
 * @param userAgent sent as `User-Agent`, which GitHub requires.
 */
class GitHubReleases(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val apiBase: String = API_BASE
) : ReleaseSource {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun releases(): List<Release>? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${apiBase.trimEnd('/')}/repos/$REPOSITORY/releases?per_page=$PER_PAGE")
            .header("User-Agent", userAgent)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.i(TAG, "update check: HTTP ${response.code} from GitHub")
                    return@withContext null
                }
                val text = response.body?.string() ?: return@withContext null
                json.decodeFromString<List<ReleaseJson>>(text).map { it.toRelease() }
            }
        } catch (e: IOException) {
            Log.i(TAG, "update check: ${e.message}")
            null
        } catch (e: SerializationException) {
            Log.w(TAG, "update check: unreadable releases", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "update check: unreadable releases", e)
            null
        }
    }

    @Serializable
    private data class ReleaseJson(
        @SerialName("tag_name") val tagName: String,
        val name: String? = null,
        val body: String? = null,
        @SerialName("html_url") val htmlUrl: String = "",
        val prerelease: Boolean = false,
        val draft: Boolean = false,
        val assets: List<AssetJson> = emptyList()
    ) {
        fun toRelease() = Release(
            tag = tagName,
            name = name.orEmpty().ifBlank { tagName },
            notes = body.orEmpty(),
            htmlUrl = htmlUrl,
            prerelease = prerelease,
            draft = draft,
            assets = assets.map { ReleaseAsset(it.name, it.browserDownloadUrl, it.size) }
        )
    }

    @Serializable
    private data class AssetJson(
        val name: String,
        @SerialName("browser_download_url") val browserDownloadUrl: String,
        val size: Long = 0L
    )

    companion object {
        const val API_BASE = "https://api.github.com"
        const val REPOSITORY = "edkief/convoscribe"
        const val RELEASES_PAGE = "https://github.com/$REPOSITORY/releases"
        private const val PER_PAGE = 10
    }
}
