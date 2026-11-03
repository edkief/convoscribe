package com.convoscribe.ui.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.convoscribe.R
import com.convoscribe.backup.BackupKind
import com.convoscribe.backup.BackupState
import com.convoscribe.models.ModelCatalog
import com.convoscribe.settings.DisplayMode
import com.convoscribe.settings.ServiceSource
import com.convoscribe.settings.SpeakerIdMode
import com.convoscribe.ui.UiText
import com.convoscribe.ui.asString
import com.convoscribe.ui.components.AppCard
import com.convoscribe.ui.theme.AppTheme
import com.convoscribe.update.UpdateState

/** One page of Settings (issue #98); [key] is its value in the `settings?page=` route. */
internal enum class SettingsPage(val key: String, @StringRes val title: Int, @DrawableRes val icon: Int) {
    SERVER("server", R.string.settings_page_server, R.drawable.ic_server),
    RECORDING("recording", R.string.settings_page_recording, R.drawable.ic_mic),
    SPEAKERS("speakers", R.string.settings_page_speakers, R.drawable.ic_people),
    AI("ai", R.string.settings_page_ai, R.drawable.ic_sparkle),
    SEARCH("search", R.string.settings_page_search, R.drawable.ic_search),
    BACKUP("backup", R.string.settings_page_backup, R.drawable.ic_archive),
    APPEARANCE("appearance", R.string.settings_page_appearance, R.drawable.ic_palette),
    ABOUT("about", R.string.settings_page_about, R.drawable.ic_info);

    companion object {
        fun of(key: String?): SettingsPage? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The state a page's overview row shows: [parts] joined in one line.
 * [problem] when something is wrong, [notice] when something good waits
 * for the user, such as an update ready to install.
 */
internal data class OverviewStatus(val parts: List<UiText>, val problem: Boolean = false, val notice: Boolean = false)

/** What the overview rows read, apart from [SettingsUiState]. */
internal data class OverviewInputs(
    val overlayGranted: Boolean = true,
    val update: UpdateState = UpdateState.Idle,
    val installedVersion: String = "",
    val backup: BackupState = BackupState.Idle,
    val formatTime: (Long) -> String = { it.toString() }
)

/** `sync.example.com:8000` from `https://sync.example.com:8000/v1`. */
internal fun serverHost(url: String): String = url.trim().substringAfter("://").substringBefore('/')

/** The overview line of [page]. A problem replaces the usual state, in words. */
internal fun overviewStatus(page: SettingsPage, state: SettingsUiState, inputs: OverviewInputs): OverviewStatus =
    when (page) {
        SettingsPage.SERVER -> serverStatus(state, inputs)
        SettingsPage.RECORDING -> recordingStatus(state, inputs)
        SettingsPage.SPEAKERS -> speakersStatus(state)
        SettingsPage.AI -> aiStatus(state)
        SettingsPage.SEARCH -> searchStatus(state)
        SettingsPage.BACKUP -> backupStatus(inputs.backup)
        SettingsPage.APPEARANCE -> OverviewStatus(listOf(UiText.res(displayLabel(state.displayMode))))
        SettingsPage.ABOUT -> aboutStatus(inputs)
    }

private fun serverStatus(state: SettingsUiState, inputs: OverviewInputs): OverviewStatus {
    val url = state.form.syncUrl
    if (url.isBlank()) return OverviewStatus(listOf(UiText.res(R.string.settings_overview_server_none)))
    val host = UiText.Plain(serverHost(url))
    val status = state.syncStatus
    return when {
        state.syncEnabled && status.lastError != null ->
            OverviewStatus(listOf(host, UiText.res(R.string.settings_overview_sync_failed)), problem = true)
        state.syncEnabled -> {
            val synced = status.lastSyncedAt?.let { UiText.res(R.string.sync_last, inputs.formatTime(it)) }
                ?: UiText.res(R.string.sync_never)
            OverviewStatus(listOf(host, synced))
        }
        state.serverServicesEnabled -> OverviewStatus(listOf(host, UiText.res(R.string.settings_overview_services_on)))
        else -> OverviewStatus(listOf(host, UiText.res(R.string.settings_overview_sync_off)))
    }
}

private fun recordingStatus(state: SettingsUiState, inputs: OverviewInputs): OverviewStatus {
    if (state.floatingControl && !inputs.overlayGranted) {
        return OverviewStatus(listOf(UiText.res(R.string.settings_overview_floating_permission)), problem = true)
    }
    val language = if (state.asrLanguage == "en") {
        R.string.settings_transcription_language_en
    } else {
        R.string.settings_transcription_language_fr
    }
    val engine = if (ModelCatalog.effectiveEngine(state.asrLanguage, state.asrEngine) == "npu") {
        R.string.settings_overview_engine_npu
    } else {
        R.string.settings_overview_engine_cpu
    }
    return OverviewStatus(
        listOfNotNull(
            UiText.res(language),
            UiText.res(engine),
            UiText.res(R.string.settings_overview_floating_on).takeIf { state.floatingControl }
        )
    )
}

private fun speakersStatus(state: SettingsUiState): OverviewStatus {
    val mode = when (SpeakerIdMode.of(state.speakerIdMode)) {
        SpeakerIdMode.AUTO -> R.string.settings_overview_speakers_auto
        SpeakerIdMode.CHARGING -> R.string.settings_overview_speakers_charging
        SpeakerIdMode.MANUAL -> R.string.settings_overview_speakers_manual
    }
    return OverviewStatus(
        listOfNotNull(
            UiText.res(mode),
            state.defaultParticipants?.let {
                UiText.res(R.string.settings_overview_speakers_default, it.joinToString(", "))
            }
        )
    )
}

private fun aiStatus(state: SettingsUiState): OverviewStatus {
    val useServer = state.serverServicesEnabled && state.llmSource == ServiceSource.SERVER
    val offered = state.serverOffer?.llm
    if (useServer && offered == null) {
        return OverviewStatus(listOf(UiText.res(R.string.settings_overview_llm_unavailable)), problem = true)
    }
    val model = when {
        useServer && offered!!.model.isNotBlank() -> UiText.res(R.string.settings_overview_llm_server, offered.model)
        useServer -> UiText.res(R.string.settings_overview_llm_server_default)
        state.form.baseUrl.isBlank() -> UiText.res(R.string.settings_overview_llm_none)
        state.form.model.isNotBlank() -> UiText.Plain(state.form.model)
        else -> UiText.res(R.string.settings_overview_llm_own)
    }
    val template = state.summaryTemplates.firstOrNull { it.key == state.defaultSummaryTemplate }?.name
    return OverviewStatus(listOfNotNull(model, template?.let { UiText.Plain(it) }))
}

private fun searchStatus(state: SettingsUiState): OverviewStatus {
    val semantic = if (state.semanticInstalled) {
        UiText.res(R.string.settings_overview_indexed, state.semanticProgress.indexed, state.semanticProgress.total)
    } else {
        UiText.res(R.string.settings_overview_semantic_off)
    }
    return OverviewStatus(listOf(semantic, UiText.res(R.string.settings_overview_tags_presets)))
}

private fun backupStatus(backup: BackupState): OverviewStatus = when (backup) {
    is BackupState.Running -> OverviewStatus(
        listOf(
            UiText.res(
                if (backup.kind == BackupKind.RESTORE) {
                    R.string.settings_overview_restore_running
                } else {
                    R.string.settings_overview_backup_running
                }
            )
        )
    )
    is BackupState.Failed -> OverviewStatus(
        listOf(
            UiText.res(
                if (backup.kind == BackupKind.RESTORE) {
                    R.string.settings_overview_restore_failed
                } else {
                    R.string.settings_overview_backup_failed
                }
            )
        ),
        problem = true
    )
    else -> OverviewStatus(listOf(UiText.res(R.string.settings_overview_backup_idle)))
}

@StringRes
private fun displayLabel(mode: String): Int = when (mode) {
    DisplayMode.EINK.key -> R.string.settings_overview_display_eink
    DisplayMode.STANDARD.key -> R.string.settings_overview_display_standard
    else -> R.string.settings_overview_display_auto
}

private fun aboutStatus(inputs: OverviewInputs): OverviewStatus = when (val update = inputs.update) {
    is UpdateState.ReadyToInstall -> OverviewStatus(
        listOf(UiText.res(R.string.settings_overview_update_ready, update.offer.versionName)),
        notice = true
    )
    is UpdateState.Available -> OverviewStatus(
        listOf(UiText.res(R.string.settings_overview_update_available, update.offer.versionName)),
        notice = true
    )
    is UpdateState.Downloading -> OverviewStatus(listOf(UiText.res(R.string.settings_overview_update_downloading)))
    is UpdateState.Failed -> OverviewStatus(
        listOf(UiText.res(R.string.settings_overview_update_failed)),
        problem = true
    )
    else -> OverviewStatus(listOf(UiText.res(R.string.settings_overview_version, inputs.installedVersion)))
}

private val OVERVIEW_GROUPS = listOf(
    R.string.settings_group_capture to listOf(SettingsPage.RECORDING, SettingsPage.SPEAKERS),
    R.string.settings_group_intelligence to listOf(SettingsPage.AI, SettingsPage.SEARCH),
    R.string.settings_group_app to listOf(SettingsPage.BACKUP, SettingsPage.APPEARANCE, SettingsPage.ABOUT)
)

/**
 * The first screen of Settings (issue #98): the server connection on
 * top, then every other page by what people do with it, each row with
 * its state or a problem in one line. [selected] marks the open page
 * when the pages show beside the list; rows then have no arrow.
 */
@Composable
internal fun SettingsOverview(
    state: SettingsUiState,
    inputs: OverviewInputs,
    onOpen: (SettingsPage) -> Unit,
    modifier: Modifier = Modifier,
    selected: SettingsPage? = null,
    showArrows: Boolean = true
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)) {
        ServerRow(
            status = overviewStatus(SettingsPage.SERVER, state, inputs),
            selected = selected == SettingsPage.SERVER,
            showArrow = showArrows,
            onClick = { onOpen(SettingsPage.SERVER) }
        )
        OVERVIEW_GROUPS.forEach { (label, pages) ->
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = AppTheme.spacing.xs, top = AppTheme.spacing.m)
                    .semantics { heading() }
            )
            AppCard(modifier = Modifier.fillMaxWidth()) {
                pages.forEachIndexed { index, page ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    OverviewRow(
                        page = page,
                        status = overviewStatus(page, state, inputs),
                        selected = selected == page,
                        showArrow = showArrows,
                        onClick = { onOpen(page) }
                    )
                }
            }
        }
    }
}

/** The prominent row of [SettingsPage.SERVER]: outlined more heavily on e-ink, tinted otherwise. */
@Composable
private fun ServerRow(status: OverviewStatus, selected: Boolean, showArrow: Boolean, onClick: () -> Unit) {
    val eink = AppTheme.isEink
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (eink) colors.surface else colors.secondaryContainer,
        contentColor = if (eink) colors.onSurface else colors.onSecondaryContainer,
        border = if (eink) BorderStroke(AppTheme.spacing.xxs, colors.outline) else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        OverviewRow(
            page = SettingsPage.SERVER,
            status = status,
            selected = selected,
            showArrow = showArrow,
            emphasised = true,
            onClick = onClick
        )
    }
}

@Composable
private fun OverviewRow(
    page: SettingsPage,
    status: OverviewStatus,
    selected: Boolean,
    showArrow: Boolean,
    onClick: () -> Unit,
    emphasised: Boolean = false
) {
    val eink = AppTheme.isEink
    val selectedColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (selected && !eink) Modifier.background(selectedColor) else Modifier)
            .semantics { this.selected = selected }
            .heightIn(min = AppTheme.sizes.touchTarget)
            .padding(horizontal = AppTheme.spacing.l, vertical = AppTheme.spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.l)
    ) {
        Icon(painter = painterResource(page.icon), contentDescription = null)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xxs)) {
            Text(
                text = stringResource(page.title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (emphasised || selected) FontWeight.Medium else null
            )
            StatusLine(status = status, tinted = emphasised)
        }
        if (showArrow) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** The row's state; a problem also gets a warning icon and heavier text, a notice heavier text, so neither rests on colour. */
@Composable
private fun StatusLine(status: OverviewStatus, tinted: Boolean) {
    val parts = status.parts.map { it.asString() }
    val color = when {
        status.problem && !tinted -> MaterialTheme.colorScheme.error
        status.notice && !tinted -> MaterialTheme.colorScheme.primary
        tinted -> LocalContentColor.current
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)
    ) {
        if (status.problem) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(AppTheme.sizes.iconSmall)
            )
        }
        Text(
            text = parts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = color,
            fontWeight = if (status.problem || status.notice) FontWeight.Medium else null
        )
    }
}
