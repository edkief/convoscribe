package com.convoscribe

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.convoscribe.data.Preset
import com.convoscribe.llm.SummaryTemplates
import com.convoscribe.recording.toOptions
import com.convoscribe.service.RecordingService
import com.convoscribe.settings.DisplayMode
import com.convoscribe.ui.theme.AppTheme
import com.convoscribe.ui.theme.ConvoScribeTheme
import kotlinx.coroutines.launch

/**
 * What a long-press on the Quick Settings tile opens (issue #32,
 * `QS_TILE_PREFERENCES`): a small dialog to start a recording with the
 * Settings defaults or with one of the presets. Like
 * [RecordTrampolineActivity] it is an activity the user just opened,
 * so it may start the microphone service; when a recording cannot
 * start at once it opens the Record screen with the preset applied.
 *
 * While a session is open there is nothing to start, so it just opens
 * the app on the Record screen.
 */
class PresetPickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (applicationContext as ConvoApp).container
        if (container.recordingController.state.value.phase.active) {
            startActivity(MainActivity.openRecordIntent(this))
            finish()
            return
        }
        setContent {
            val settings by container.settingsRepository.settings.collectAsStateWithLifecycle()
            val presets by container.presets.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
            val mode = DisplayMode.fromKey(settings.displayMode) ?: DisplayMode.AUTO
            val eink = DisplayMode.resolvesToEink(mode, DisplayMode.isEinkDevice(Build.MANUFACTURER, Build.BRAND))
            ConvoScribeTheme(
                darkTheme = isSystemInDarkTheme() && !eink,
                eink = eink,
                dynamicColor = settings.dynamicColor
            ) {
                PresetPickerDialog(presets = presets, onPick = ::start, onDismiss = ::finish)
            }
        }
    }

    /** Start recording with [preset] (`null` the Settings defaults), or hand it to the Record screen. */
    private fun start(preset: Preset?) {
        val container = (applicationContext as ConvoApp).container
        lifecycleScope.launch {
            val settings = container.settingsRepository.settings.value
            val options = preset?.toOptions(
                (SummaryTemplates.BUILT_IN + container.summaryTemplates.getUserTemplates()).map { it.key },
                settings.defaultSummaryTemplate
            )
            if (RecordTrampolineActivity.canStartNow(
                    this@PresetPickerActivity,
                    options?.language ?: settings.asrLanguage
                )
            ) {
                ContextCompat.startForegroundService(
                    this@PresetPickerActivity,
                    RecordingService.startIntent(this@PresetPickerActivity, options = options)
                )
            } else {
                container.pendingRecordPreset.value = preset?.id
                startActivity(MainActivity.openRecordIntent(this@PresetPickerActivity))
            }
            finish()
        }
    }
}

/** "Start recording with": the Settings defaults, then each preset. */
@Composable
internal fun PresetPickerDialog(presets: List<Preset>, onPick: (Preset?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.preset_picker_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                PickerRow(text = stringResource(R.string.record_preset_defaults)) { onPick(null) }
                presets.forEach { preset ->
                    HorizontalDivider()
                    PickerRow(text = preset.name) { onPick(preset) }
                }
                if (presets.isEmpty()) {
                    Text(
                        text = stringResource(R.string.preset_picker_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = AppTheme.spacing.s)
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.conversation_cancel)) }
        }
    )
}

@Composable
private fun PickerRow(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppTheme.sizes.touchTarget)
            .clickable(onClick = onClick)
            .padding(vertical = AppTheme.spacing.m)
    )
}
