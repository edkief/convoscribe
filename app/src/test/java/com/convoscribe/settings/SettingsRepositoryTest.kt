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
 * Unit tests for [SettingsRepository]. Robolectric only because we need
 * a working [android.content.SharedPreferences]; the API key path uses
 * [InMemorySecretStore] so we never touch the Android Keystore (which
 * Robolectric cannot provide).
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {

    private lateinit var context: Context
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var secrets: InMemorySecretStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // A unique prefs file per test so values don't bleed across tests.
        prefs = context.getSharedPreferences(
            "settings-test-${System.nanoTime()}",
            Context.MODE_PRIVATE
        )
        secrets = InMemorySecretStore()
    }

    private fun newRepository(): SettingsRepository = SettingsRepository(prefs, secrets)

    @Test
    fun defaults_areReturnedOnEmptyPrefs() {
        val repo = newRepository()
        val s = repo.settings.value
        assertEquals("", s.llmBaseUrl)
        assertEquals("", s.llmModel)
        assertEquals(24000, s.maxTranscriptChars)
        assertEquals("en", s.asrLanguage)
        // CPU until the engine check has verified the NPU on the device.
        assertEquals("cpu", s.asrEngine)
        assertFalse(s.asrEngineChosen)
        assertEquals(4, s.numThreads)
        assertEquals(-1, s.numSpeakers)
        assertEquals(SettingsRepository.DEFAULT_CLUSTER_THRESHOLD, s.clusterThreshold, 0.0001f)
        assertEquals(0.5f, s.speakerMatchThreshold, 0.0001f)
    }

    @Test
    fun update_valuesArePersistedAndReadByANewRepository() {
        val repo = newRepository()
        repo.update {
            it.copy(
                llmBaseUrl = "https://api.example.com/v1",
                llmModel = "gpt-4o-mini",
                maxTranscriptChars = 12345,
                asrLanguage = "fr",
                asrEngine = "cpu",
                numThreads = 6,
                numSpeakers = 3,
                clusterThreshold = 0.7f,
                speakerMatchThreshold = 0.8f
            )
        }

        val fresh = newRepository()
        val s = fresh.settings.value
        assertEquals("https://api.example.com/v1", s.llmBaseUrl)
        assertEquals("gpt-4o-mini", s.llmModel)
        assertEquals(12345, s.maxTranscriptChars)
        assertEquals("fr", s.asrLanguage)
        assertEquals("cpu", s.asrEngine)
        assertEquals(6, s.numThreads)
        assertEquals(3, s.numSpeakers)
        assertEquals(0.7f, s.clusterThreshold, 0.0001f)
        assertEquals(0.8f, s.speakerMatchThreshold, 0.0001f)
    }

    @Test
    fun update_coercesNumericFields() {
        val repo = newRepository()
        repo.update {
            it.copy(
                maxTranscriptChars = 10,
                numThreads = 20,
                numSpeakers = 0,
                clusterThreshold = 1.5f,
                speakerMatchThreshold = 0.001f
            )
        }
        val s = repo.settings.value
        assertEquals(2000, s.maxTranscriptChars)
        assertEquals(8, s.numThreads)
        assertEquals(-1, s.numSpeakers)
        assertEquals(0.95f, s.clusterThreshold, 0.0001f)
        assertEquals(0.05f, s.speakerMatchThreshold, 0.0001f)
    }

    @Test
    fun update_trimsBaseUrlAndStripsTrailingSlash() {
        val repo = newRepository()
        repo.update { it.copy(llmBaseUrl = " http://x:8000/v1/ ") }
        assertEquals("http://x:8000/v1", repo.settings.value.llmBaseUrl)
    }

    @Test
    fun update_unsupportedLanguageKeepsPreviousValue() {
        val repo = newRepository()
        // First, switch to French so we have a known previous value.
        repo.update { it.copy(asrLanguage = "fr") }
        assertEquals("fr", repo.settings.value.asrLanguage)

        // Now try to set German — must keep "fr".
        repo.update { it.copy(asrLanguage = "de") }
        assertEquals("fr", repo.settings.value.asrLanguage)
    }

    @Test
    fun update_unsupportedAsrEngineKeepsPreviousValue() {
        val repo = newRepository()
        // Default is "cpu". Try to set an unsupported engine.
        repo.update { it.copy(asrEngine = "gpu") }
        assertEquals("cpu", repo.settings.value.asrEngine)

        // Switch to "npu" so we have a different known previous value.
        repo.update { it.copy(asrEngine = "npu") }
        assertEquals("npu", repo.settings.value.asrEngine)

        // Now try to set "gpu" again — must keep "npu".
        repo.update { it.copy(asrEngine = "gpu") }
        assertEquals("npu", repo.settings.value.asrEngine)
    }

    @Test
    fun update_asrEngineRoundTripsSupportedValues() {
        val repo = newRepository()
        repo.update { it.copy(asrEngine = "cpu") }
        assertEquals("cpu", repo.settings.value.asrEngine)

        val fresh = newRepository()
        assertEquals("cpu", fresh.settings.value.asrEngine)

        repo.update { it.copy(asrEngine = "npu") }
        assertEquals("npu", repo.settings.value.asrEngine)

        val freshAgain = newRepository()
        assertEquals("npu", freshAgain.settings.value.asrEngine)
    }

    @Test
    fun update_stripsMultipleTrailingSlashesFromBaseUrl() {
        val repo = newRepository()
        repo.update { it.copy(llmBaseUrl = "https://example.com/v1///") }
        // trimEnd removes every trailing '/' in turn.
        assertEquals("https://example.com/v1", repo.settings.value.llmBaseUrl)
    }

    @Test
    fun apiKey_goesThroughSecretStoreAndNotPlainPrefs() {
        val repo = newRepository()
        repo.setApiKey("sk-test-12345")

        // The in-memory store has it.
        assertEquals("sk-test-12345", secrets.getApiKey())
        assertEquals("sk-test-12345", repo.apiKey())

        // The plain prefs file (used by the rest of the settings)
        // must not contain the key in any form.
        val all = prefs.all
        assertFalse("api key leaked into plain prefs: $all", all.keys.any { it.contains("api", ignoreCase = true) })
        assertFalse(
            "api key value leaked into plain prefs: $all",
            all.values.any { it?.toString()?.contains("sk-test-12345") == true }
        )

        // A fresh repository built on the same prefs but with a
        // brand new SecretStore must NOT see the API key — proving
        // the key is stored exclusively in the SecretStore.
        val freshSecrets = InMemorySecretStore()
        val fresh = SettingsRepository(prefs, freshSecrets)
        assertEquals("", fresh.apiKey())
    }

    @Test
    fun apiKey_defaultIsEmptyString() {
        val repo = newRepository()
        assertEquals("", repo.apiKey())
    }

    @Test
    fun update_clampsLowerBoundsForThresholds() {
        val repo = newRepository()
        repo.update {
            it.copy(
                clusterThreshold = 0.0f,
                speakerMatchThreshold = -1.0f
            )
        }
        val s = repo.settings.value
        assertEquals(0.05f, s.clusterThreshold, 0.0001f)
        assertEquals(0.05f, s.speakerMatchThreshold, 0.0001f)
    }

    @Test
    fun update_numSpeakersNegativeLessThanMinusOneBecomesMinusOne() {
        val repo = newRepository()
        repo.update { it.copy(numSpeakers = -42) }
        assertEquals(-1, repo.settings.value.numSpeakers)
    }

    @Test
    fun update_numSpeakersAboveTenClampsToTen() {
        val repo = newRepository()
        repo.update { it.copy(numSpeakers = 999) }
        assertEquals(10, repo.settings.value.numSpeakers)
    }

    @Test
    fun settingsFlow_emitsNewValueOnUpdate() {
        val repo = newRepository()
        // StateFlow.value reads the latest cached emission without a
        // collector; no Turbine needed here.
        repo.update { it.copy(numThreads = 7) }
        assertEquals(7, repo.settings.value.numThreads)
    }

    @Test
    fun engine_followsDeviceDefaultUntilChosen_evenAcrossOtherSaves() {
        var npuVerified = false
        val repo = SettingsRepository(prefs, secrets) { if (npuVerified) "npu" else "cpu" }
        repo.update { it.copy(llmModel = "m") } // an unrelated save
        assertEquals("cpu", repo.settings.value.asrEngine)
        assertFalse(prefs.contains(SettingsRepository.KEY_ASR_ENGINE))

        // The engine check verifies the NPU: a user who never chose follows it.
        npuVerified = true
        repo.refreshDefaults()
        assertEquals("npu", repo.settings.value.asrEngine)
        assertEquals("npu", SettingsRepository(prefs, secrets) { "npu" }.settings.value.asrEngine)
    }

    @Test
    fun engine_explicitChoiceIsKeptOverDeviceDefault() {
        val repo = SettingsRepository(prefs, secrets) { "npu" }
        repo.update { it.copy(asrEngine = "cpu") }
        assertTrue(repo.settings.value.asrEngineChosen)

        repo.refreshDefaults()
        assertEquals("cpu", repo.settings.value.asrEngine)
        assertEquals("cpu", SettingsRepository(prefs, secrets) { "npu" }.settings.value.asrEngine)
    }

    @Test
    fun engine_legacyStoredEngineWithoutChosenFlagCountsAsChosen() {
        // Written by builds from before the chosen flag existed.
        prefs.edit().putString(SettingsRepository.KEY_ASR_ENGINE, "npu").commit()
        val repo = SettingsRepository(prefs, secrets) { "cpu" }
        assertEquals("npu", repo.settings.value.asrEngine)
        assertTrue(repo.settings.value.asrEngineChosen)
    }

    @Test
    fun clusterThreshold_legacyStoredDefaultMovesToTheTunedDefault() {
        // Saved by a build from before the threshold was tuned.
        prefs.edit().putFloat(SettingsRepository.KEY_CLUSTER_THRESHOLD, 0.5f).commit()
        assertEquals(
            SettingsRepository.DEFAULT_CLUSTER_THRESHOLD,
            newRepository().settings.value.clusterThreshold,
            0.0001f
        )
    }

    @Test
    fun clusterThreshold_legacyStoredCustomValueIsKept() {
        prefs.edit().putFloat(SettingsRepository.KEY_CLUSTER_THRESHOLD, 0.6f).commit()
        assertEquals(0.6f, newRepository().settings.value.clusterThreshold, 0.0001f)
    }

    @Test
    fun clusterThreshold_halfChosenAfterTheChangeIsKept() {
        newRepository().update { it.copy(clusterThreshold = 0.5f) }
        assertEquals(0.5f, newRepository().settings.value.clusterThreshold, 0.0001f)
    }

    @Test
    fun defaultParticipants_persistAndEmptyMeansAnyone() {
        assertNull(newRepository().settings.value.defaultParticipants)
        newRepository().update { it.copy(defaultParticipants = listOf(" Alice ", "Bob", "Alice")) }
        assertEquals(listOf("Alice", "Bob"), newRepository().settings.value.defaultParticipants)
        newRepository().update { it.copy(defaultParticipants = emptyList()) }
        assertNull(newRepository().settings.value.defaultParticipants)
    }
}
