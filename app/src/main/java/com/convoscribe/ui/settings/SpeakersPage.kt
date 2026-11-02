package com.convoscribe.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.convoscribe.R
import com.convoscribe.models.ModelCatalog
import com.convoscribe.settings.DEFAULT_DIARIZATION_EMBEDDING
import com.convoscribe.settings.DEFAULT_DIARIZATION_STEP_SECONDS
import com.convoscribe.settings.DIARIZATION_STEPS_SECONDS
import com.convoscribe.settings.SpeakerIdMode
import com.convoscribe.ui.components.ChoiceGroup
import com.convoscribe.ui.components.Disclosure
import com.convoscribe.ui.components.FieldLabel
import com.convoscribe.ui.components.InlineProgress
import com.convoscribe.ui.components.NavRow
import com.convoscribe.ui.components.NoticeText
import com.convoscribe.ui.components.PickerField
import com.convoscribe.ui.components.SectionCard
import com.convoscribe.ui.components.SupportingText
import com.convoscribe.ui.session.voiceModelName
import com.convoscribe.ui.theme.AppTheme
import java.util.Locale

private val SPEAKER_ID_MODES = listOf(
    SpeakerIdMode.AUTO to R.string.settings_speakers_id_mode_auto,
    SpeakerIdMode.CHARGING to R.string.settings_speakers_id_mode_charging,
    SpeakerIdMode.MANUAL to R.string.settings_speakers_id_mode_manual
)

/**
 * Speaker identification's voice model ([selected], a key of
 * [ModelCatalog.diarizationEmbeddings]) and segmentation step
 * ([stepSeconds]), both written immediately, to compare speed and
 * accuracy. [installed] is whether the selected model is downloaded;
 * until it is, the default one is used.
 */
internal data class VoiceModelUi(
    val selected: String = DEFAULT_DIARIZATION_EMBEDDING,
    val installed: Boolean = true,
    val downloading: Boolean = false,
    val stepSeconds: Float = DEFAULT_DIARIZATION_STEP_SECONDS,
    val onSelect: (String) -> Unit = {},
    val onDownload: () -> Unit = {},
    val onStepChange: (Float) -> Unit = {}
)

/** The *Voice model* and *Analysis step* choices of the Speakers section, from [ui]. */
@Composable
private fun VoiceModelFields(ui: VoiceModelUi) {
    FieldLabel(text = stringResource(R.string.settings_speakers_voice_model_label))
    ChoiceGroup(
        options = ModelCatalog.diarizationEmbeddings.keys.map { key -> key to voiceModelName(key) },
        selected = ui.selected,
        onSelect = ui.onSelect
    )
    SupportingText(text = stringResource(R.string.settings_speakers_voice_model_help))
    val name = voiceModelName(ui.selected)
    when {
        ui.installed -> Unit
        ui.downloading -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)
        ) {
            InlineProgress()
            NoticeText(text = stringResource(R.string.settings_speakers_voice_model_downloading, name))
        }
        else -> {
            NoticeText(text = stringResource(R.string.settings_speakers_voice_model_missing))
            OutlinedButton(onClick = ui.onDownload) {
                Text(
                    text = stringResource(
                        R.string.settings_speakers_voice_model_download,
                        name,
                        ModelCatalog.embeddingFor(ui.selected).totalBytes.toFloat() / 1_000_000f
                    )
                )
            }
        }
    }
    FieldLabel(text = stringResource(R.string.settings_speakers_step_label))
    // A step set outside the offered ones (none today) is still shown selected.
    val steps = (DIARIZATION_STEPS_SECONDS + ui.stepSeconds).distinct().sorted()
    ChoiceGroup(
        options = steps.map { step ->
            step to stringResource(R.string.settings_speakers_step_value, stepLabel(step))
        },
        selected = ui.stepSeconds,
        onSelect = ui.onStepChange
    )
    SupportingText(text = stringResource(R.string.settings_speakers_step_help))
}

/** `2` for 2 s, `2.5` for 2.5 s. */
private fun stepLabel(seconds: Float): String = String.format(Locale.ROOT, "%.1f", seconds).removeSuffix(".0")

/**
 * "Speakers" section: when speakers are identified ([speakerIdMode],
 * issue #11, written immediately), number-of-speakers dropdown (Automatic,
 * 2..6 — `-1` is the "automatic" sentinel persisted by the
 * repository) and a clustering threshold slider with the value
 * to two decimals and the "Lower = more speakers" helper text.
 * The speaker count and [defaultParticipants] are only the defaults
 * of a new conversation, which the Record screen can change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeakersSection(
    speakerIdMode: String,
    onSpeakerIdModeChange: (String) -> Unit,
    numSpeakers: Int,
    defaultParticipants: List<String>?,
    onResetDefaultParticipants: () -> Unit,
    threshold: Float,
    matchThreshold: Float,
    onNumSpeakersChange: (Int) -> Unit,
    onThresholdChange: (Float) -> Unit,
    onMatchThresholdChange: (Float) -> Unit,
    onOpenKnownSpeakers: () -> Unit,
    voiceModel: VoiceModelUi = VoiceModelUi()
) {
    var speakersExpanded by remember { mutableStateOf(false) }
    SectionCard(modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth)) {
        FieldLabel(text = stringResource(R.string.settings_speakers_id_mode_label))
        ChoiceGroup(
            options = SPEAKER_ID_MODES.map { (mode, label) -> mode.key to stringResource(label) },
            selected = SpeakerIdMode.of(speakerIdMode).key,
            onSelect = onSpeakerIdModeChange
        )
        SupportingText(text = stringResource(R.string.settings_speakers_id_mode_help))
    }
    SectionCard(
        modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth),
        title = stringResource(R.string.settings_section_new_conversations)
    ) {
        val speakersLabel = if (numSpeakers <= 0) {
            stringResource(R.string.settings_speakers_automatic)
        } else {
            stringResource(R.string.settings_speakers_count, numSpeakers)
        }
        PickerField(
            label = stringResource(R.string.settings_speakers_number_label),
            value = speakersLabel,
            onClick = { speakersExpanded = true }
        ) {
            DropdownMenu(
                expanded = speakersExpanded,
                onDismissRequest = { speakersExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.settings_speakers_automatic)) },
                    onClick = {
                        onNumSpeakersChange(-1)
                        speakersExpanded = false
                    }
                )
                (2..6).forEach { count ->
                    DropdownMenuItem(
                        text = { Text(text = count.toString()) },
                        onClick = {
                            onNumSpeakersChange(count)
                            speakersExpanded = false
                        }
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    R.string.settings_speakers_default_participants,
                    defaultParticipants?.joinToString(", ")
                        ?: stringResource(R.string.settings_speakers_default_participants_anyone)
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            if (defaultParticipants != null) {
                TextButton(onClick = onResetDefaultParticipants) {
                    Text(text = stringResource(R.string.settings_speakers_default_participants_reset))
                }
            }
        }
    }
    SectionCard(modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth)) {
        // Enrolled voices feed the diarization pipeline too: their cosine
        // match labels each cluster's centroid with a real name.
        NavRow(
            label = stringResource(R.string.settings_speakers_known_row),
            help = stringResource(R.string.settings_speakers_known_help),
            onClick = onOpenKnownSpeakers
        )
    }
    Disclosure(
        title = stringResource(R.string.settings_advanced),
        summary = stringResource(
            R.string.settings_speakers_advanced_summary,
            threshold,
            matchThreshold,
            voiceModelName(voiceModel.selected),
            stepLabel(voiceModel.stepSeconds)
        ),
        modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth)
    ) {
        FieldLabel(text = stringResource(R.string.settings_speakers_threshold_label))
        Slider(
            value = threshold.coerceIn(0.4f, 0.95f),
            onValueChange = { onThresholdChange(it.coerceIn(0.4f, 0.95f)) },
            valueRange = 0.4f..0.95f,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = stringResource(R.string.settings_speakers_threshold_value, threshold),
            style = MaterialTheme.typography.bodySmall
        )
        SupportingText(text = stringResource(R.string.settings_speakers_threshold_help))
        FieldLabel(text = stringResource(R.string.settings_speakers_match_threshold_label))
        Slider(
            value = matchThreshold.coerceIn(0.3f, 0.8f),
            onValueChange = { onMatchThresholdChange(it.coerceIn(0.3f, 0.8f)) },
            valueRange = 0.3f..0.8f,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = stringResource(R.string.settings_speakers_match_threshold_value, matchThreshold),
            style = MaterialTheme.typography.bodySmall
        )
        SupportingText(text = stringResource(R.string.settings_speakers_match_threshold_help))
        VoiceModelFields(voiceModel)
    }
}
