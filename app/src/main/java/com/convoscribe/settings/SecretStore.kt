package com.convoscribe.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme
import androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme
import androidx.security.crypto.MasterKey

/**
 * Pluggable backing store for the LLM API key and the transcription
 * server's (issue #58). The production
 * implementation encrypts the value at rest; the in-memory variant
 * exists for tests and Compose previews so we never construct the
 * Android Keystore on the JVM.
 */
interface SecretStore {
    fun getApiKey(): String

    fun setApiKey(value: String)

    fun getAsrServerKey(): String

    fun setAsrServerKey(value: String)

    /** The sync server's API key (issue #68). */
    fun getSyncKey(): String

    fun setSyncKey(value: String)
}

/**
 * Stores the API key in an [EncryptedSharedPreferences] file. Uses
 * AES256-GCM for the master key (the strongest scheme shipped by
 * `androidx.security:security-crypto`).
 *
 * Robolectric cannot talk to the Android Keystore, so unit tests
 * must use [InMemorySecretStore] instead.
 */
class EncryptedSecretStore(context: Context) : SecretStore {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            PrefKeyEncryptionScheme.AES256_SIV,
            PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun getApiKey(): String = prefs.getString(KEY_API_KEY, "") ?: ""

    override fun setApiKey(value: String) {
        prefs.edit().putString(KEY_API_KEY, value).apply()
    }

    override fun getAsrServerKey(): String = prefs.getString(KEY_ASR_SERVER_KEY, "") ?: ""

    override fun setAsrServerKey(value: String) {
        prefs.edit().putString(KEY_ASR_SERVER_KEY, value).apply()
    }

    override fun getSyncKey(): String = prefs.getString(KEY_SYNC_KEY, "") ?: ""

    override fun setSyncKey(value: String) {
        prefs.edit().putString(KEY_SYNC_KEY, value).apply()
    }

    private companion object {
        const val FILE_NAME = "secrets"
        const val KEY_API_KEY = "api_key"
        const val KEY_ASR_SERVER_KEY = "asr_server_key"
        const val KEY_SYNC_KEY = "sync_key"
    }
}

/**
 * Trivially in-memory implementation of [SecretStore]. The API key
 * is lost when the process is killed; that is fine for tests and
 * Compose previews and it avoids ever constructing the Android
 * Keystore on the JVM.
 */
class InMemorySecretStore : SecretStore {
    private var value: String = ""
    private var asrServerKey: String = ""
    private var syncKey: String = ""

    override fun getApiKey(): String = value

    override fun setApiKey(value: String) {
        this.value = value
    }

    override fun getAsrServerKey(): String = asrServerKey

    override fun setAsrServerKey(value: String) {
        asrServerKey = value
    }

    override fun getSyncKey(): String = syncKey

    override fun setSyncKey(value: String) {
        syncKey = value
    }
}
