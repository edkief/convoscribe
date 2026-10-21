package com.convoscribe.update

import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * What the update check remembers between runs (issue #15): when it
 * last heard from GitHub, the version the user chose to skip, and the
 * offer it found (issue #41), so an update found by the background job
 * is still shown after the process is gone.
 * Kept out of [com.convoscribe.settings.AppSettings] because the user
 * never edits these directly.
 */
interface UpdateStore {
    /** Wall-clock millis of the last successful check, 0 if never. */
    var lastCheckAtMs: Long

    /** Version the user skipped ([SemVer.toString]), or `null`. */
    var skippedVersion: String?

    /** The last offer a check found, or `null`. */
    var offer: UpdateOffer?
}

/** [UpdateStore] in its own `updates` SharedPreferences, like [com.convoscribe.enginecheck.NpuAvailability]. */
class PrefsUpdateStore(private val prefs: SharedPreferences) : UpdateStore {
    override var lastCheckAtMs: Long
        get() = prefs.getLong(KEY_LAST_CHECK_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECK_AT, value).apply()

    override var skippedVersion: String?
        get() = prefs.getString(KEY_SKIPPED_VERSION, null)
        set(value) = prefs.edit().putString(KEY_SKIPPED_VERSION, value).apply()

    override var offer: UpdateOffer?
        get() = prefs.getString(KEY_OFFER, null)?.let(OfferJson::decode)
        set(value) = prefs.edit().putString(KEY_OFFER, value?.let(OfferJson::encode)).apply()

    companion object {
        const val PREFS_NAME = "updates"
        private const val KEY_LAST_CHECK_AT = "lastCheckAt"
        private const val KEY_SKIPPED_VERSION = "skippedVersion"
        private const val KEY_OFFER = "offer"
    }
}

/** An [UpdateOffer] as JSON; a value that does not read back is `null`. */
internal object OfferJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(offer: UpdateOffer): String = json.encodeToString(
        StoredOffer(
            version = offer.versionName,
            tag = offer.release.tag,
            name = offer.release.name,
            notes = offer.release.notes,
            htmlUrl = offer.release.htmlUrl,
            prerelease = offer.release.prerelease,
            apk = offer.apk.stored(),
            sha256 = offer.sha256?.stored()
        )
    )

    fun decode(text: String): UpdateOffer? {
        val stored = try {
            json.decodeFromString<StoredOffer>(text)
        } catch (e: SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        val version = SemVer.parse(stored.version) ?: return null
        val apk = stored.apk.asset()
        val sha256 = stored.sha256?.asset()
        val release = Release(
            tag = stored.tag,
            name = stored.name,
            notes = stored.notes,
            htmlUrl = stored.htmlUrl,
            prerelease = stored.prerelease,
            draft = false,
            assets = listOfNotNull(apk, sha256)
        )
        return UpdateOffer(version, release, apk, sha256)
    }

    private fun ReleaseAsset.stored() = StoredAsset(name, url, sizeBytes)

    private fun StoredAsset.asset() = ReleaseAsset(name, url, sizeBytes)

    @Serializable
    private data class StoredOffer(
        val version: String,
        val tag: String,
        val name: String,
        val notes: String,
        val htmlUrl: String,
        val prerelease: Boolean,
        val apk: StoredAsset,
        val sha256: StoredAsset? = null
    )

    @Serializable
    private data class StoredAsset(val name: String, val url: String, val sizeBytes: Long)
}
