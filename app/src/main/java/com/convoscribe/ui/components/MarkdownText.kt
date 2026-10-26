package com.convoscribe.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.convoscribe.ui.theme.AppTheme
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding

/**
 * LLM output (issue #53): [text] rendered as Markdown (headings,
 * emphasis, lists, quotes, code, rules, tables) in the app's type and
 * colours. Body text uses [style]; headings step down from
 * `titleLarge`. Text takes the surrounding content colour, so it reads
 * on a card or in a chat bubble alike. Code has a quiet background,
 * none on e-ink, where a grey block would ghost. Size changes are not
 * animated, so streaming text grows in place.
 *
 * Wrap it in a `SelectionContainer` to make the text selectable.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, style: TextStyle = AppTheme.typography.transcript) {
    val scheme = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val spacing = AppTheme.spacing
    val eink = AppTheme.isEink
    val content = LocalContentColor.current
    val codeBackground = if (eink) Color.Transparent else scheme.surfaceContainerHighest
    val heading = { base: TextStyle -> base.copy(fontWeight = FontWeight.SemiBold) }
    val code = style.copy(fontFamily = FontFamily.Monospace)
    Markdown(
        content = text,
        modifier = modifier,
        colors = markdownColor(
            text = content,
            codeText = content,
            linkText = scheme.primary,
            codeBackground = codeBackground,
            dividerColor = if (eink) scheme.outline else scheme.outlineVariant,
            tableBackground = Color.Transparent
        ),
        typography = markdownTypography(
            h1 = heading(type.titleLarge),
            h2 = heading(type.titleMedium),
            h3 = heading(type.titleSmall),
            h4 = heading(type.titleSmall),
            h5 = heading(type.titleSmall),
            h6 = heading(type.titleSmall),
            text = style,
            code = code.copy(fontSize = type.bodyMedium.fontSize, lineHeight = type.bodyMedium.lineHeight),
            inlineCode = code,
            quote = style.copy(fontStyle = FontStyle.Italic),
            paragraph = style,
            ordered = style,
            bullet = style,
            list = style,
            link = style.copy(textDecoration = TextDecoration.Underline)
        ),
        padding = markdownPadding(
            block = spacing.xxs,
            list = spacing.xs,
            listItemBottom = spacing.xxs,
            indentList = spacing.s,
            codeBlock = PaddingValues(spacing.s),
            blockQuote = PaddingValues(horizontal = spacing.m)
        ),
        dimens = markdownDimens(
            dividerThickness = AppTheme.sizes.outline,
            codeBackgroundCornerSize = spacing.xs,
            tableCellPadding = spacing.s
        ),
        animations = markdownAnimations(animateTextSize = { this })
    )
}
