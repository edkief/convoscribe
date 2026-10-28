package com.convoscribe.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.convoscribe.ConvoApp
import com.convoscribe.R
import com.convoscribe.data.Highlighted
import com.convoscribe.data.SearchLine
import com.convoscribe.data.SearchOptions
import com.convoscribe.data.SearchResult
import com.convoscribe.data.TranscriptFormatter
import com.convoscribe.ui.components.AppCard
import com.convoscribe.ui.components.EmptyState
import com.convoscribe.ui.components.ScreenScaffold
import com.convoscribe.ui.components.SpeakerDot
import com.convoscribe.ui.home.formatSessionDate
import com.convoscribe.ui.home.languageTag
import com.convoscribe.ui.session.languageName
import com.convoscribe.ui.sessionRoute
import com.convoscribe.ui.theme.AppTheme

/** Hit lines shown per session before "Show N more". */
private const val LINES_SHOWN = 3

private const val TAB_TRANSCRIPT = 0
private const val TAB_SUMMARY = 1

/**
 * Cross-session search (issue #24), reached from Home; its top bar
 * leads on to *Ask across sessions* (issue #49). The field
 * searches as the user types (after a pause, longer on e-ink) or on
 * the keyboard's Search key; filter chips narrow by language, date and
 * person. A transcript hit opens its session on the Transcript tab at
 * that line with the player cued there; a summary hit opens the
 * Summary tab; anything else opens the session.
 */
@Composable
fun SearchScreen(navController: NavHostController) {
    val container = (LocalContext.current.applicationContext as ConvoApp).container
    val debounceMs = if (AppTheme.isEink) SearchViewModel.EINK_DEBOUNCE_MS else SearchViewModel.DEBOUNCE_MS
    val vm: SearchViewModel = viewModel(
        factory = viewModelFactory {
            initializer { SearchViewModel(search = container.sessionSearch, debounceMs = debounceMs) }
        }
    )
    val query by vm.query.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()
    SearchBody(
        query = query,
        filters = filters,
        options = options,
        results = results,
        onQueryChange = vm::onQueryChange,
        onSubmit = vm::submit,
        onLanguage = vm::setLanguage,
        onDateRange = vm::setDateRange,
        onSpeaker = vm::setSpeaker,
        onTag = vm::setTag,
        onOpen = { id, tab, line ->
            navController.navigate(sessionRoute(id, tab = tab, lineId = line?.segmentId, atMs = line?.startMs))
        },
        onBack = { navController.popBackStack() },
        onAskAcross = { navController.navigate("ask") }
    )
}

/**
 * Render-only body of [SearchScreen], so screenshot tests can draw it
 * with fake [results]. [onOpen] gets the session, the tab to open and
 * the transcript line, if a line was tapped.
 */
@Composable
internal fun SearchBody(
    query: String,
    filters: FilterState,
    options: SearchOptions,
    results: SearchResults,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onLanguage: (String?) -> Unit,
    onDateRange: (DateRange) -> Unit,
    onSpeaker: (String?) -> Unit,
    onOpen: (sessionId: Long, tab: Int, line: SearchLine?) -> Unit,
    onBack: () -> Unit,
    autoFocus: Boolean = true,
    onTag: (Long?) -> Unit = {},
    onAskAcross: () -> Unit = {}
) {
    val spacing = AppTheme.spacing
    ScreenScaffold(
        title = stringResource(R.string.search_title),
        onBack = onBack,
        actions = {
            // Ask a question about the sessions rather than find words in them (issue #49).
            IconButton(onClick = onAskAcross) {
                Icon(
                    painter = painterResource(R.drawable.ic_conversation),
                    contentDescription = stringResource(R.string.ask_across_open)
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = AppTheme.sizes.contentMaxWidth)
                    .padding(horizontal = spacing.screen, vertical = spacing.s),
                verticalArrangement = Arrangement.spacedBy(spacing.s)
            ) {
                QueryField(query = query, onQueryChange = onQueryChange, onSubmit = onSubmit, autoFocus = autoFocus)
                FilterRow(
                    filters = filters,
                    options = options,
                    onLanguage = onLanguage,
                    onDateRange = onDateRange,
                    onSpeaker = onSpeaker,
                    onTag = onTag
                )
            }
            when {
                results.query.isEmpty() -> EmptyState(
                    icon = painterResource(R.drawable.ic_conversation),
                    title = stringResource(R.string.search_start_title),
                    body = stringResource(R.string.search_start_body)
                )
                results.items.isEmpty() -> EmptyState(
                    icon = painterResource(R.drawable.ic_conversation),
                    title = stringResource(R.string.search_no_results_title),
                    body = if (filters.isActive) {
                        stringResource(R.string.search_no_results_filtered, results.query)
                    } else {
                        stringResource(R.string.search_no_results_body, results.query)
                    }
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = spacing.screen, end = spacing.screen, bottom = spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(spacing.m),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    items(items = results.items, key = { it.session.id }) { result ->
                        ResultCard(
                            result = result,
                            onOpen = onOpen,
                            modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueryField(query: String, onQueryChange: (String) -> Unit, onSubmit: () -> Unit, autoFocus: Boolean) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    if (autoFocus) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        placeholder = { Text(text = stringResource(R.string.search_hint)) },
        leadingIcon = { Icon(imageVector = Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = stringResource(R.string.search_clear))
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            onSubmit()
            keyboard?.hide()
        })
    )
}

/**
 * Language, date, person and tag chips, each opening a menu of choices;
 * also above *Ask across sessions* (issue #49).
 */
@Composable
internal fun FilterRow(
    filters: FilterState,
    options: SearchOptions,
    onLanguage: (String?) -> Unit,
    onDateRange: (DateRange) -> Unit,
    onSpeaker: (String?) -> Unit,
    onTag: (Long?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s)
    ) {
        if (options.languages.size > 1 || filters.language != null) {
            val any = stringResource(R.string.search_filter_language_any)
            FilterMenuChip(
                label = filters.language?.let { languageName(it) } ?: any,
                selected = filters.language != null,
                choices = listOf<Pair<String?, String>>(null to any) +
                    options.languages.map { it to languageName(it) },
                onChoose = onLanguage
            )
        }
        FilterMenuChip(
            label = dateRangeLabel(filters.dateRange),
            selected = filters.dateRange != DateRange.ANY,
            choices = DateRange.entries.map { it to dateRangeLabel(it) },
            onChoose = onDateRange
        )
        if (options.speakers.isNotEmpty() || filters.speaker != null) {
            val anyone = stringResource(R.string.search_filter_speaker_any)
            FilterMenuChip(
                label = filters.speaker ?: anyone,
                selected = filters.speaker != null,
                choices = listOf<Pair<String?, String>>(null to anyone) + options.speakers.map { it to it },
                onChoose = onSpeaker
            )
        }
        if (options.tags.isNotEmpty() || filters.tagId != null) {
            val anyTag = stringResource(R.string.search_filter_tag_any)
            FilterMenuChip(
                label = options.tags.firstOrNull { it.id == filters.tagId }?.name ?: anyTag,
                selected = filters.tagId != null,
                choices = listOf<Pair<Long?, String>>(null to anyTag) + options.tags.map { it.id to it.name },
                onChoose = onTag
            )
        }
    }
}

@Composable
private fun <T> FilterMenuChip(
    label: String,
    selected: Boolean,
    choices: List<Pair<T, String>>,
    onChoose: (T) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { open = true },
            label = { Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Icon(imageVector = Icons.Filled.ArrowDropDown, contentDescription = null) }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text = text) },
                    onClick = {
                        open = false
                        onChoose(value)
                    }
                )
            }
        }
    }
}

@Composable
private fun dateRangeLabel(range: DateRange): String = stringResource(
    when (range) {
        DateRange.ANY -> R.string.search_filter_date_any
        DateRange.WEEK -> R.string.search_filter_date_week
        DateRange.MONTH -> R.string.search_filter_date_month
        DateRange.YEAR -> R.string.search_filter_date_year
    }
)

/**
 * One session's matches: the title (opens the session), then the
 * matching speaker names, a summary excerpt (opens the Summary tab)
 * and the matching lines (each opens the transcript at that line).
 */
@Composable
private fun ResultCard(
    result: SearchResult,
    onOpen: (sessionId: Long, tab: Int, line: SearchLine?) -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = AppTheme.spacing
    val session = result.session
    val highlight = highlightStyle()
    var expanded by rememberSaveable(session.id) { mutableStateOf(false) }
    val openTab = if (result.lines.isEmpty() && result.summary != null) TAB_SUMMARY else TAB_TRANSCRIPT
    AppCard(modifier = modifier.fillMaxWidth(), onClick = { onOpen(session.id, openTab, null) }) {
        Column(
            modifier = Modifier.padding(vertical = spacing.m),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            Column(modifier = Modifier.padding(horizontal = spacing.l)) {
                Text(
                    text = result.title.annotated(highlight),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOfNotNull(formatSessionDate(session.createdAt), languageTag(session.language))
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (result.speakers.isNotEmpty()) {
                LabelledHit(
                    label = stringResource(R.string.search_speakers_label),
                    text = joinHighlighted(result.speakers, ", ").annotated(highlight)
                )
            }
            result.summary?.let { summary ->
                LabelledHit(
                    label = stringResource(R.string.search_summary_label),
                    text = summary.annotated(highlight),
                    onClick = { onOpen(session.id, TAB_SUMMARY, null) }
                )
            }
            val shown = if (expanded) result.lines else result.lines.take(LINES_SHOWN)
            shown.forEach { line ->
                LineHit(line = line, highlight = highlight, onClick = { onOpen(session.id, TAB_TRANSCRIPT, line) })
            }
            val hidden = result.lines.size - shown.size
            if (hidden > 0) {
                TextButton(onClick = { expanded = true }, modifier = Modifier.padding(horizontal = spacing.s)) {
                    Text(text = pluralStringResource(R.plurals.search_more_lines, hidden, hidden))
                }
            }
        }
    }
}

@Composable
private fun LabelledHit(label: String, text: AnnotatedString, onClick: (() -> Unit)? = null) {
    val spacing = AppTheme.spacing
    var modifier = Modifier.fillMaxWidth()
    if (onClick != null) modifier = modifier.clickable(role = Role.Button, onClick = onClick)
    Column(modifier = modifier.padding(horizontal = spacing.l, vertical = spacing.xs)) {
        Text(
            text = label,
            style = AppTheme.typography.transcriptMeta,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

/** A matching transcript line: speaker and timestamp, then the snippet. */
@Composable
private fun LineHit(line: SearchLine, highlight: SpanStyle, onClick: () -> Unit) {
    val spacing = AppTheme.spacing
    val speakerIndex = line.speakerIndex
    val timestamp = TranscriptFormatter.timestamp(line.startMs)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.search_open_at, timestamp),
                onClick = onClick
            )
            .padding(horizontal = spacing.l, vertical = spacing.xs),
        verticalArrangement = Arrangement.spacedBy(spacing.xxs)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s)
        ) {
            if (speakerIndex != null && line.speaker != null) {
                SpeakerDot(speakerIndex = speakerIndex)
                Text(
                    text = line.speaker,
                    style = AppTheme.typography.transcriptMeta,
                    color = AppTheme.speakerColors[speakerIndex]
                )
            }
            Text(
                text = timestamp,
                style = AppTheme.typography.figures,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(text = line.text.annotated(highlight), style = AppTheme.typography.transcript)
    }
}

/** Matched words: bold in the primary colour, also underlined on e-ink where colour does not show. */
@Composable
private fun highlightStyle(): SpanStyle = if (AppTheme.isEink) {
    SpanStyle(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)
} else {
    SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
}

private fun Highlighted.annotated(style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    ranges.forEach { addStyle(style, it.first, it.last + 1) }
}

private fun joinHighlighted(parts: List<Highlighted>, separator: String): Highlighted {
    val text = StringBuilder()
    val ranges = mutableListOf<IntRange>()
    parts.forEachIndexed { index, part ->
        if (index > 0) text.append(separator)
        val offset = text.length
        text.append(part.text)
        part.ranges.forEach { ranges += (it.first + offset)..(it.last + offset) }
    }
    return Highlighted(text.toString(), ranges)
}
