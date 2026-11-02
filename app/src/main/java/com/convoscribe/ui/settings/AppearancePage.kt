package com.convoscribe.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.convoscribe.R
import com.convoscribe.settings.DisplayMode
import com.convoscribe.ui.components.ChoiceGroup
import com.convoscribe.ui.components.FieldLabel
import com.convoscribe.ui.components.SectionCard
import com.convoscribe.ui.components.SupportingText
import com.convoscribe.ui.components.SwitchRow
import com.convoscribe.ui.theme.AppTheme

/**
 * "Appearance" section: the display mode (Auto / Standard / E-ink) and,
 * on Android 12+ outside e-ink mode, "Use device colours". Both are
 * written immediately and the app re-themes at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceSection(
    displayMode: String,
    dynamicColor: Boolean,
    onDisplayModeChange: (String) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit
) {
    SectionCard(
        modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth),
        title = stringResource(R.string.settings_section_appearance)
    ) {
        FieldLabel(text = stringResource(R.string.settings_display_mode_label))
        ChoiceGroup(
            options = DISPLAY_MODES.map { (mode, label) -> mode.key to stringResource(label) },
            selected = displayMode,
            onSelect = onDisplayModeChange
        )
        SupportingText(text = stringResource(R.string.settings_display_mode_help))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !AppTheme.isEink) {
            SwitchRow(
                label = stringResource(R.string.settings_dynamic_color_label),
                help = stringResource(R.string.settings_dynamic_color_help),
                checked = dynamicColor,
                onCheckedChange = onDynamicColorChange
            )
        }
    }
}

private val DISPLAY_MODES = listOf(
    DisplayMode.AUTO to R.string.settings_display_mode_auto,
    DisplayMode.STANDARD to R.string.settings_display_mode_standard,
    DisplayMode.EINK to R.string.settings_display_mode_eink
)
