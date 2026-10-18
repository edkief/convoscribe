package com.convoscribe.update

import android.content.SharedPreferences

/**
 * What the update check remembers between runs (issue #15): when it
 * last heard from GitHub, and the version the user chose to skip.
 * Kept out of [com.convoscribe.settings.AppSettings] because the user
 * never edits these directly.
 */
interface UpdateStore {
    /** Wall-clock millis of the last successful check, 0 if never. */
    var lastCheckAtMs: Long

    /** Version the user skipped ([SemVer.toString]), or `null`. */
    var skippedVersion: String?
}

/** [UpdateStore] in its own `updates` SharedPreferences, like [com.convoscribe.enginecheck.NpuAvailability]. */
class PrefsUpdateStore(private val prefs: SharedPreferences) : UpdateStore {
    override var lastCheckAtMs: Long
        get() = prefs.getLong(KEY_LAST_CHECK_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECK_AT, value).apply()

    override var skippedVersion: String?
        get() = prefs.getString(KEY_SKIPPED_VERSION, null)
        set(value) = prefs.edit().putString(KEY_SKIPPED_VERSION, value).apply()

    companion object {
        const val PREFS_NAME = "updates"
        private const val KEY_LAST_CHECK_AT = "lastCheckAt"
        private const val KEY_SKIPPED_VERSION = "skippedVersion"
    }
}
