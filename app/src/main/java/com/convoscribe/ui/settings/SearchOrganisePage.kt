package com.convoscribe.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.convoscribe.R
import com.convoscribe.llm.embedding.IndexProgress
import com.convoscribe.models.ModelCatalog
import com.convoscribe.settings.SemanticIndexMode
import com.convoscribe.ui.components.ChoiceGroup
import com.convoscribe.ui.components.FieldLabel
import com.convoscribe.ui.components.InlineProgress
import com.convoscribe.ui.components.NoticeText
import com.convoscribe.ui.components.SectionCard
import com.convoscribe.ui.components.SupportingText
import com.convoscribe.ui.theme.AppTheme

/**
 * "Semantic search" section (issue #61): the optional EmbeddingGemma
 * model for *Ask across sessions*, downloaded or removed here, and how
 * many sessions the charging job has indexed so far.
 */
@Composable
internal fun SemanticSearchSection(
    installed: Boolean,
    downloading: Boolean,
    progress: IndexProgress,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    indexMode: String = SemanticIndexMode.CHARGING.key,
    onIndexModeChange: (String) -> Unit = {},
    onIndexNow: () -> Unit = {}
) {
    val megabytes = ModelCatalog.TEXT_EMBEDDING_GEMMA.totalBytes.toFloat() / 1_000_000f
    SectionCard(
        modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth),
        title = stringResource(R.string.settings_section_semantic)
    ) {
        SupportingText(text = stringResource(R.string.settings_semantic_help, megabytes))
        when {
            downloading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)
            ) {
                InlineProgress()
                NoticeText(text = stringResource(R.string.settings_semantic_downloading))
            }
            installed -> {
                FieldLabel(text = stringResource(R.string.settings_semantic_index_mode_label))
                ChoiceGroup(
                    options = SEMANTIC_INDEX_MODES.map { (mode, label) -> mode.key to stringResource(label) },
                    selected = SemanticIndexMode.of(indexMode).key,
                    onSelect = onIndexModeChange
                )
                SupportingText(
                    text = stringResource(R.string.settings_semantic_status, progress.indexed, progress.total) +
                        " " + stringResource(semanticIndexWhen(indexMode))
                )
                // Index the waiting sessions without the charger (issue #65).
                if (progress.indexed < progress.total) {
                    OutlinedButton(onClick = onIndexNow) {
                        Text(text = stringResource(R.string.settings_semantic_index_now))
                    }
                }
                TextButton(onClick = onDelete) {
                    Text(text = stringResource(R.string.settings_semantic_delete))
                }
            }
            else -> OutlinedButton(onClick = onDownload) {
                Text(text = stringResource(R.string.settings_semantic_download, megabytes))
            }
        }
        SupportingText(text = stringResource(R.string.settings_semantic_licence))
    }
}

/** The sentence saying when sessions get indexed for semantic search under [mode] (issue #65). */
@StringRes
internal fun semanticIndexWhen(mode: String): Int = when (SemanticIndexMode.of(mode)) {
    SemanticIndexMode.CHARGING -> R.string.semantic_index_when_charging
    SemanticIndexMode.ANY_TIME -> R.string.semantic_index_when_any_time
}

private val SEMANTIC_INDEX_MODES = listOf(
    SemanticIndexMode.CHARGING to R.string.settings_semantic_index_mode_charging,
    SemanticIndexMode.ANY_TIME to R.string.settings_semantic_index_mode_any_time
)

/** Tags (and presets) of conversations (issue #32), each managed on its own screen. */
@Composable
internal fun OrganiseSection(onOpenTags: () -> Unit, onOpenPresets: () -> Unit) {
    SectionCard(
        modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth),
        title = stringResource(R.string.settings_section_organise)
    ) {
        ManageRow(
            label = stringResource(R.string.settings_organise_tags_row),
            help = stringResource(R.string.settings_organise_tags_help),
            onClick = onOpenTags
        )
        ManageRow(
            label = stringResource(R.string.settings_organise_presets_row),
            help = stringResource(R.string.settings_organise_presets_help),
            onClick = onOpenPresets
        )
    }
}

/** A setting kept on its own screen: [label], [help] below it, and Manage. */
@Composable
private fun ManageRow(label: String, help: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.m),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            SupportingText(text = help)
        }
        OutlinedButton(onClick = onClick) {
            Text(text = stringResource(R.string.settings_summaries_templates_button))
        }
    }
}
