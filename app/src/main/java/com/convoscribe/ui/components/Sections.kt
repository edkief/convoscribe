package com.convoscribe.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.convoscribe.R
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

/** A labelled switch with help text; the whole row toggles it. */
@Composable
fun SwitchRow(
    label: String,
    help: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            SupportingText(text = help)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * A row that opens another screen: [label], optional [help] below it,
 * and an arrow. The whole row is the target.
 */
@Composable
fun NavRow(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, help: String? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = AppTheme.sizes.touchTarget)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            if (help != null) SupportingText(text = help)
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Controls kept out of the way until asked for: a [title] row with what
 * they are set to now ([summary]) that opens them in place. Closed at
 * first; there is no animation, so it behaves the same on e-ink.
 * [outlined] makes it a card of its own, for use between sections.
 */
@Composable
fun Disclosure(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    outlined: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val state = stringResource(if (expanded) R.string.disclosure_expanded else R.string.disclosure_collapsed)
    val body: @Composable ColumnScope.() -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.sizes.touchTarget)
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = state }
                .padding(horizontal = if (outlined) AppTheme.spacing.l else AppTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)
        ) {
            Column(modifier = Modifier.weight(1f).padding(vertical = AppTheme.spacing.s)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                if (summary != null && !expanded) SupportingText(text = summary)
            }
            Icon(
                painter = painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(
                    start = if (outlined) AppTheme.spacing.l else 0.dp,
                    end = if (outlined) AppTheme.spacing.l else 0.dp,
                    bottom = if (outlined) AppTheme.spacing.l else 0.dp
                ),
                verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m),
                content = content
            )
        }
    }
    if (outlined) {
        OutlinedCard(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(
                AppTheme.sizes.outline,
                if (AppTheme.isEink) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant
            ),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
            content = body
        )
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m),
            content = body
        )
    }
}
