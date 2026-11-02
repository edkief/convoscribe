package com.convoscribe.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.convoscribe.R
import com.convoscribe.ui.components.InlineProgress
import com.convoscribe.ui.components.NoticeText
import com.convoscribe.ui.components.SectionCard
import com.convoscribe.ui.components.SupportingText
import com.convoscribe.ui.components.SwitchRow
import com.convoscribe.ui.theme.AppTheme
import com.convoscribe.ui.update.UpdateActions
import com.convoscribe.ui.update.UpdateBanner
import com.convoscribe.ui.update.hasBanner
import com.convoscribe.update.UpdateState

/**
 * "About" section: the installed version and updates (issue #15) —
 * *Check for updates* with its result (or the offer and its download,
 * as on the Home banner), *Check for updates automatically* and
 * *Include pre-releases* — then every model bundled by the on-device ASR and
 * diarization pipeline alongside its licence and a source link. Renders
 * after the three editable sections as a read-only credit roll so a
 * user who skipped the README can still see which upstream / licence
 * applies to a given model.
 *
 * Each [AboutModelRow] shows the model name, the licence in bodySmall,
 * and a tappable `Uri` link to the upstream source (`Intent.ACTION_VIEW`,
 * `chooser` skipped so a single browser is launched directly). The
 * Kroko rows in particular highlight the `CC-BY-SA 4.0` attribution
 * requirement (the LICENSE places copyleft on derivative works, so the
 * README mirrors this list with the same wording).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutSection(
    installedVersion: String,
    installedVersionCode: Long,
    update: UpdateState,
    checkAuto: Boolean,
    includePrereleases: Boolean,
    updateActions: UpdateActions,
    onCheckAutoChange: (Boolean) -> Unit,
    onIncludePrereleasesChange: (Boolean) -> Unit
) {
    SectionCard(
        modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth),
        title = stringResource(R.string.settings_section_about)
    ) {
        Text(
            text = stringResource(R.string.settings_about_version, installedVersion, installedVersionCode),
            style = MaterialTheme.typography.bodyLarge
        )
        val checking = update is UpdateState.Checking
        val busy = checking || update is UpdateState.Downloading || update is UpdateState.Installing ||
            update is UpdateState.ReadyToInstall
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)
        ) {
            OutlinedButton(onClick = updateActions.onCheck, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.settings_update_check))
            }
            if (checking) InlineProgress()
        }
        when {
            checking -> SupportingText(text = stringResource(R.string.settings_update_checking))
            update == UpdateState.UpToDate -> NoticeText(text = stringResource(R.string.settings_update_up_to_date))
            update == UpdateState.CheckFailed -> NoticeText(
                text = stringResource(R.string.settings_update_check_failed),
                isError = true
            )
            update.hasBanner() -> UpdateBanner(state = update, actions = updateActions, dismissible = false)
        }
        SwitchRow(
            label = stringResource(R.string.settings_update_auto_label),
            help = stringResource(R.string.settings_update_auto_help),
            checked = checkAuto,
            onCheckedChange = onCheckAutoChange
        )
        SwitchRow(
            label = stringResource(R.string.settings_update_prerelease_label),
            help = stringResource(R.string.settings_update_prerelease_help),
            checked = includePrereleases,
            onCheckedChange = onIncludePrereleasesChange
        )
        Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)) {
            AboutModelRow(
                name = stringResource(R.string.settings_about_nemotron_name),
                licence = stringResource(R.string.settings_about_nemotron_licence),
                url = "https://www.nvidia.com/en-us/agreements/enterprise-software/nvidia-open-model-license/"
            )
            AboutModelRow(
                name = stringResource(R.string.settings_about_kroko_name),
                licence = stringResource(R.string.settings_about_kroko_licence),
                url = "https://huggingface.co/Banafo/Kroko-ASR"
            )
            AboutModelRow(
                name = stringResource(R.string.settings_about_pyannote_name),
                licence = stringResource(R.string.settings_about_pyannote_licence),
                url = "https://github.com/pyannote/pyannote-segmentation"
            )
            AboutModelRow(
                name = stringResource(R.string.settings_about_wespeaker_name),
                licence = stringResource(R.string.settings_about_wespeaker_licence),
                url = "https://github.com/wenet-e2e/wespeaker"
            )
            AboutModelRow(
                name = stringResource(R.string.settings_about_gtcrn_name),
                licence = stringResource(R.string.settings_about_gtcrn_licence),
                url = "https://github.com/Xiaobin-Rong/gtcrn"
            )
            AboutModelRow(
                name = stringResource(R.string.settings_about_sherpa_name),
                licence = stringResource(R.string.settings_about_sherpa_licence),
                url = "https://github.com/k2-fsa/sherpa-onnx"
            )
            AboutModelRow(
                name = stringResource(R.string.settings_about_qnn_name),
                licence = stringResource(R.string.settings_about_qnn_licence),
                url = "https://developer.qualcomm.com/software/qualcomm-neural-processing-sdk"
            )
        }
    }
}

/**
 * One row in the [AboutSection]: the model name and licence, and a
 * "Source" button that opens the upstream URL via [Intent.ACTION_VIEW]. Using a plain `Text` clickable instead of a
 * link gives consistent focus / ripple behaviour inside the
 * scrollable settings column.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutModelRow(name: String, licence: String, url: String) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            SupportingText(text = licence)
        }
        TextButton(onClick = {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            runCatching { context.startActivity(intent) }
        }) {
            Text(text = stringResource(R.string.settings_about_source))
        }
    }
}
