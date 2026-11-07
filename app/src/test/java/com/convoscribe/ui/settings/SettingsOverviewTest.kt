package com.convoscribe.ui.settings

import com.convoscribe.R
import com.convoscribe.backup.BackupFailure
import com.convoscribe.backup.BackupKind
import com.convoscribe.backup.BackupState
import com.convoscribe.llm.embedding.IndexProgress
import com.convoscribe.settings.OfferedService
import com.convoscribe.settings.ServerOffer
import com.convoscribe.settings.ServiceSource
import com.convoscribe.sync.SyncStatus
import com.convoscribe.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsOverviewTest {

    private val connected = SettingsUiState(form = SettingsForm(syncUrl = "https://sync.example.com:8000/v1"))

    private fun status(page: SettingsPage, state: SettingsUiState, inputs: OverviewInputs = OverviewInputs()) =
        overviewStatus(page, state, inputs)

    @Test
    fun `server host drops the scheme and the path`() {
        assertEquals("sync.example.com:8000", serverHost(" https://sync.example.com:8000/v1 "))
        assertEquals("192.168.1.10", serverHost("192.168.1.10/"))
    }

    @Test
    fun `no address is not connected and no problem`() {
        val status = status(SettingsPage.SERVER, SettingsUiState())
        assertEquals(listOf(UiText.res(R.string.settings_overview_server_none)), status.parts)
        assertFalse(status.problem)
    }

    @Test
    fun `an address alone never claims a working sync`() {
        val status = status(SettingsPage.SERVER, connected)
        assertEquals(
            listOf(UiText.Plain("sync.example.com:8000"), UiText.res(R.string.settings_overview_sync_off)),
            status.parts
        )
    }

    @Test
    fun `sync on shows when it last synced`() {
        val state = connected.copy(syncEnabled = true, syncStatus = SyncStatus(lastSyncedAt = 5L))
        val status = status(SettingsPage.SERVER, state, OverviewInputs(formatTime = { "t$it" }))
        assertEquals(UiText.res(R.string.sync_last, "t5"), status.parts.last())
    }

    @Test
    fun `a sync error is a problem while sync is on, and not once it is off`() {
        val failed = connected.copy(syncEnabled = true, syncStatus = SyncStatus(lastError = "HTTP 500"))
        assertTrue(status(SettingsPage.SERVER, failed).problem)
        assertFalse(status(SettingsPage.SERVER, failed.copy(syncEnabled = false)).problem)
    }

    @Test
    fun `missing overlay permission replaces the recording state`() {
        val state = SettingsUiState(floatingControl = true)
        val status = status(SettingsPage.RECORDING, state, OverviewInputs(overlayGranted = false))
        assertTrue(status.problem)
        assertEquals(listOf(UiText.res(R.string.settings_overview_floating_permission)), status.parts)
    }

    @Test
    fun `missing overlay permission does not matter with the control off`() {
        val state = SettingsUiState(floatingControl = false)
        assertFalse(status(SettingsPage.RECORDING, state, OverviewInputs(overlayGranted = false)).problem)
    }

    @Test
    fun `french shows the CPU engine it really uses`() {
        val state = SettingsUiState(asrEngine = "npu", asrLanguage = "fr", floatingControl = false)
        assertEquals(
            listOf(
                UiText.res(R.string.settings_transcription_language_fr),
                UiText.res(R.string.settings_overview_engine_cpu)
            ),
            status(SettingsPage.RECORDING, state).parts
        )
    }

    @Test
    fun `language model shows the source in use`() {
        val server = SettingsUiState(
            form = SettingsForm(baseUrl = "http://own/v1", model = "own-model"),
            serverServicesEnabled = true,
            llmSource = ServiceSource.SERVER,
            serverOffer = ServerOffer(llm = OfferedService("qwen3"), transcription = null)
        )
        assertEquals(
            UiText.res(R.string.settings_overview_llm_server, "qwen3"),
            status(SettingsPage.AI, server).parts.first()
        )
        assertEquals(
            UiText.Plain("own-model"),
            status(SettingsPage.AI, server.copy(llmSource = ServiceSource.OWN)).parts.first()
        )
    }

    @Test
    fun `the server not offering the chosen language model is a problem`() {
        val state = SettingsUiState(serverServicesEnabled = true, llmSource = ServiceSource.SERVER, serverOffer = null)
        assertTrue(status(SettingsPage.AI, state).problem)
    }

    @Test
    fun `talking points checked by a decision model are named, unless left to the language model`() {
        val decision = SettingsUiState(decisionConfigured = true)
        assertEquals(UiText.res(R.string.settings_overview_decision), status(SettingsPage.AI, decision).parts.last())
        val optedOut = status(SettingsPage.AI, decision.copy(decisionForTalkingPoints = false))
        assertFalse(optedOut.parts.contains(UiText.res(R.string.settings_overview_decision)))
        assertFalse(
            status(SettingsPage.AI, SettingsUiState()).parts.contains(UiText.res(R.string.settings_overview_decision))
        )
    }

    @Test
    fun `no language model is stated without being a problem`() {
        val status = status(SettingsPage.AI, SettingsUiState())
        assertEquals(UiText.res(R.string.settings_overview_llm_none), status.parts.first())
        assertFalse(status.problem)
    }

    @Test
    fun `search shows indexing progress once the model is installed`() {
        val installed = SettingsUiState(semanticInstalled = true, semanticProgress = IndexProgress(42, 58))
        assertEquals(
            UiText.res(R.string.settings_overview_indexed, 42, 58),
            status(SettingsPage.SEARCH, installed).parts.first()
        )
        assertEquals(
            UiText.res(R.string.settings_overview_semantic_off),
            status(SettingsPage.SEARCH, SettingsUiState()).parts.first()
        )
    }

    @Test
    fun `a failed backup is a problem`() {
        val failed = BackupState.Failed(BackupKind.BACKUP, BackupFailure.entries.first())
        val status = status(SettingsPage.BACKUP, SettingsUiState(), OverviewInputs(backup = failed))
        assertTrue(status.problem)
        assertEquals(listOf(UiText.res(R.string.settings_overview_backup_failed)), status.parts)
    }

    @Test
    fun `about shows the version when there is nothing to do`() {
        val status = status(SettingsPage.ABOUT, SettingsUiState(), OverviewInputs(installedVersion = "0.2.1"))
        assertEquals(listOf(UiText.res(R.string.settings_overview_version, "0.2.1")), status.parts)
        assertFalse(status.notice)
    }

    @Test
    fun `every page key resolves back to its page`() {
        SettingsPage.entries.forEach { assertEquals(it, SettingsPage.of(it.key)) }
        assertEquals(null, SettingsPage.of(null))
        assertEquals(null, SettingsPage.of("gone"))
    }
}
