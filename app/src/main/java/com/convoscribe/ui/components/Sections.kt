package com.convoscribe.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.convoscribe.ui.theme.AppTheme

/**
 * A titled group of related controls. Filled with a quiet surface tone
 * in the standard theme and outlined (no fill, no shadow) on e-ink.
 * [title] is announced as a heading; [supportingText] explains the
 * group in one or two lines.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    supportingText: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    AppCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AppTheme.spacing.l),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)
        ) {
            if (title != null) SectionHeader(title = title, supportingText = supportingText)
            content()
        }
    }
}

/** Title and optional supporting text at the top of a section. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, supportingText: String? = null) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xxs)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() }
        )
        if (supportingText != null) SupportingText(supportingText)
    }
}

/** Secondary explanatory text (help lines under a control). */
@Composable
fun SupportingText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/** Small heading for one control inside a section ("Speech engine"). */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text = text, style = MaterialTheme.typography.titleSmall, modifier = modifier)
}

/**
 * The app's card surface: tonal and flat in the standard theme,
 * outlined on e-ink. Clickable variants pass [onClick].
 */
@Composable
fun AppCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.medium
    if (AppTheme.isEink) {
        val border = BorderStroke(AppTheme.sizes.outline, MaterialTheme.colorScheme.outline)
        val colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
        if (onClick != null) {
            OutlinedCard(
                onClick = onClick,
                modifier = modifier,
                shape = shape,
                border = border,
                colors = colors,
                content = content
            )
        } else {
            OutlinedCard(modifier = modifier, shape = shape, border = border, colors = colors, content = content)
        }
    } else {
        val colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
        if (onClick != null) {
            Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors, content = content)
        } else {
            Card(modifier = modifier, shape = shape, colors = colors, content = content)
        }
    }
}

/** A `label · value` line, the value right-aligned (device info, session info). */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.m),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}
