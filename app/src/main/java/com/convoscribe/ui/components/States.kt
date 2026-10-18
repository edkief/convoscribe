package com.convoscribe.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.convoscribe.ui.theme.AppTheme

/*
 * Loading, empty and error states. Every screen shows them the same
 * way: an empty list is an EmptyState, work in progress is a
 * ProgressBlock, and a failure is an ErrorBanner with its retry action.
 */

/**
 * Centred placeholder for an empty list or tab: an icon, a title, a
 * line of explanation and an optional primary action.
 */
@Composable
fun EmptyState(
    icon: Painter,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(modifier = modifier.fillMaxSize().padding(AppTheme.spacing.xl), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)
        ) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(AppTheme.sizes.emptyStateIcon)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            if (body != null) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction, modifier = Modifier.padding(top = AppTheme.spacing.s)) {
                    Text(text = actionLabel)
                }
            }
        }
    }
}

/**
 * Work in progress: [message] with a bar. [progress] in `0f..1f` draws a
 * determinate bar; `null` is indeterminate, which on e-ink is shown as
 * the message alone (an endless animation would smear the panel).
 * Announced politely to screen readers.
 */
@Composable
fun ProgressBlock(message: String, modifier: Modifier = Modifier, progress: Float? = null, detail: String? = null) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyMedium)
        when {
            progress != null -> LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
            !AppTheme.reduceMotion -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (detail != null) SupportingText(detail)
    }
}

/** Small inline spinner next to a label, e.g. while a summary streams. Static on e-ink. */
@Composable
fun InlineProgress(modifier: Modifier = Modifier) {
    if (AppTheme.reduceMotion) return
    CircularProgressIndicator(
        modifier = modifier.size(AppTheme.sizes.progressSmall),
        strokeWidth = AppTheme.sizes.progressStroke
    )
}

/**
 * An error the user can act on: a warning icon, [message], and an
 * optional [actionLabel] button (usually Retry). Tinted with the error
 * container colour; outlined in red on e-ink.
 */
@Composable
fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val eink = AppTheme.isEink
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        border = if (eink) BorderStroke(AppTheme.sizes.outline, MaterialTheme.colorScheme.error) else null
    ) {
        Row(
            modifier = Modifier.padding(start = AppTheme.spacing.m, end = AppTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(AppTheme.sizes.iconSmall)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = AppTheme.spacing.m)
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(text = actionLabel, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}

/** A one-line informational note (not an error), e.g. "French uses the CPU engine". */
@Composable
fun NoticeText(text: String, modifier: Modifier = Modifier, isError: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}
