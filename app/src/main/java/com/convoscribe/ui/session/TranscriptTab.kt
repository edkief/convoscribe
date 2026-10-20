package com.convoscribe.ui.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.convoscribe.R
import com.convoscribe.data.SessionStatus
import com.convoscribe.data.SpeakerEntity
import com.convoscribe.data.TranscriptFormatter
import com.convoscribe.data.TranscriptLine
import com.convoscribe.ui.components.EmptyState
import com.convoscribe.ui.components.ErrorBanner
import com.convoscribe.ui.components.ProgressBlock
import com.convoscribe.ui.components.SpeakerDot
import com.convoscribe.ui.theme.AppTheme

/**
 * Transcript tab body: the diarization status, the speaker chips
 * (tap one to rename that speaker), then either an empty state or the
 * transcript. Each line shows its speaker (colour dot and name, keyed
 * on [TranscriptLine.speakerIndex] like the chips) and timestamp above
 * the text. While playing, the current line is highlighted and kept in
 * view until the user scrolls by hand; *Follow* resumes.
 *
 * [targetLineId] is the line a search hit opened (issue #24): the list
 * scrolls to it once and it is highlighted until playback moves on.
 */
@Composable
internal fun TranscriptTab(
    status: SessionStatus?,
    lines: List<TranscriptLine>,
    speakers: List<SpeakerEntity>,
    canPlay: Boolean,
    isPlaying: Boolean,
    positionMs: () -> Long,
    activeLineIndex: Int?,
    following: Boolean,
    playback: PlaybackActions,
    onSpeakerChipTapped: (Int) -> Unit,
    waitingForCharger: Boolean = false,
    onIdentifySpeakersNow: (() -> Unit)? = null,
    targetLineId: Long? = null
) {
    val spacing = AppTheme.spacing
    Column(modifier = Modifier.fillMaxSize()) {
        StatusBanner(status = status, waitingForCharger = waitingForCharger, onIdentifyNow = onIdentifySpeakersNow)
        if (speakers.isNotEmpty()) {
            SpeakerChipRow(speakers = speakers, onChipTapped = onSpeakerChipTapped)
        }
        if (lines.isEmpty()) {
            EmptyState(
                icon = painterResource(R.drawable.ic_conversation),
                title = stringResource(R.string.session_transcript_empty)
            )
            return
        }
        val listState = rememberLazyListState()
        // A drag (or the fling it starts) is the user taking over the
        // scroll position; programmatic scrolls below do not emit one.
        LaunchedEffect(listState) {
            listState.interactionSource.interactions.collect {
                if (it is DragInteraction.Start) playback.onUserScrolled()
            }
        }
        val reduceMotion = AppTheme.reduceMotion
        // Opened from a search hit: bring its line into view once.
        val targetIndex = targetLineId?.let { id -> lines.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
        var scrolledToTarget by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(targetIndex) {
            if (targetIndex != null && !scrolledToTarget) {
                listState.scrollToItem(targetIndex)
                scrolledToTarget = true
            }
        }
        LaunchedEffect(activeLineIndex, following) {
            val index = activeLineIndex
            if (following && index != null && index < lines.size && !listState.isFullyVisible(index)) {
                if (reduceMotion) listState.scrollToItem(index) else listState.animateScrollToItem(index)
            }
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = AppTheme.sizes.contentMaxWidth),
                contentPadding = PaddingValues(horizontal = spacing.s, vertical = spacing.s),
                verticalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                itemsIndexed(items = lines, key = { _, line -> line.id }) { index, line ->
                    // Until something plays, the search hit stands in for the playing line.
                    val active = index == (activeLineIndex ?: targetIndex)
                    TranscriptRow(
                        line = line,
                        active = active,
                        positionMs = if (active) positionMs else NO_POSITION,
                        onSpeakerTapped = onSpeakerChipTapped,
                        onTapped = if (canPlay) ({ playback.onLineTapped(line) }) else null
                    )
                }
            }
            if (isPlaying && !following && activeLineIndex != null) {
                FilledTonalButton(
                    onClick = playback.onResumeFollow,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(spacing.l)
                ) {
                    Text(text = stringResource(R.string.player_follow))
                }
            }
        }
    }
}

/**
 * Diarization status above the transcript: progress while speakers
 * are being identified, an error once it failed, a notice with
 * *Identify speakers now* while they wait (issue #11), nothing
 * otherwise. [onIdentifyNow] is `null` while the button cannot run.
 */
@Composable
private fun StatusBanner(status: SessionStatus?, waitingForCharger: Boolean, onIdentifyNow: (() -> Unit)?) {
    val spacing = AppTheme.spacing
    val modifier = Modifier.padding(horizontal = spacing.screen, vertical = spacing.s)
    when (status) {
        SessionStatus.DIARIZING -> ProgressBlock(
            message = stringResource(R.string.session_status_diarizing),
            modifier = modifier
        )
        SessionStatus.DIARIZATION_FAILED -> ErrorBanner(
            message = stringResource(R.string.session_status_diarization_failed),
            modifier = modifier
        )
        SessionStatus.DIARIZATION_PENDING -> PendingBanner(
            waitingForCharger = waitingForCharger,
            onIdentifyNow = onIdentifyNow,
            modifier = modifier
        )
        else -> Unit
    }
}

/**
 * "Speakers not identified yet" (and "Waiting for the charger"), with
 * *Identify speakers now* beside it, or below it when the text is large.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PendingBanner(waitingForCharger: Boolean, onIdentifyNow: (() -> Unit)?, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.align(Alignment.CenterVertically)) {
            Text(
                text = stringResource(R.string.session_status_pending),
                style = MaterialTheme.typography.bodyMedium
            )
            if (waitingForCharger) {
                Text(
                    text = stringResource(R.string.session_status_pending_charger),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        TextButton(
            onClick = { onIdentifyNow?.invoke() },
            enabled = onIdentifyNow != null,
            modifier = Modifier.align(Alignment.CenterVertically)
        ) {
            Text(text = stringResource(R.string.session_identify_speakers_now))
        }
    }
}

/**
 * Horizontally scrollable speaker chips, each with the same colour dot
 * as the speaker's lines. Tapping one opens the rename dialog.
 */
@Composable
private fun SpeakerChipRow(speakers: List<SpeakerEntity>, onChipTapped: (Int) -> Unit) {
    val spacing = AppTheme.spacing
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = spacing.screen, vertical = spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(spacing.s)
    ) {
        items(items = speakers, key = { it.speakerIndex }) { speaker ->
            AssistChip(
                onClick = { onChipTapped(speaker.speakerIndex) },
                label = { Text(text = speaker.label) },
                leadingIcon = { SpeakerDot(speakerIndex = speaker.speakerIndex) }
            )
        }
    }
}

/**
 * One transcript line: `● Alice · 00:04` above the text. Tapping the
 * speaker opens the rename dialog for them; tapping the rest of the
 * line ([onTapped], only when the recording can be played) plays from
 * its start. The [active] line is highlighted (outlined on e-ink) and,
 * when it has word timings, the word at [positionMs] is emphasised.
 */
@Composable
private fun TranscriptRow(
    line: TranscriptLine,
    active: Boolean,
    positionMs: () -> Long,
    onSpeakerTapped: (Int) -> Unit,
    onTapped: (() -> Unit)?
) {
    val spacing = AppTheme.spacing
    val speakerIndex = line.speakerIndex
    val speakerColor = if (speakerIndex == null) Color.Unspecified else AppTheme.speakerColors[speakerIndex]
    val timestamp = remember(line.startMs) { TranscriptFormatter.timestamp(line.startMs) }
    val words = remember(line) { PlaybackSync.words(line) }
    // Only the playing line reads the position, so the other rows do not
    // recompose on every playback tick.
    val wordIndex = if (active && words != null) PlaybackSync.activeWordIndex(line, positionMs()) else null
    val wordStyle = SpanStyle(
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline
    )
    val text = remember(line.text, words, wordIndex, wordStyle) {
        buildAnnotatedString {
            append(line.text)
            val word = if (wordIndex != null && words != null) words[wordIndex] else null
            if (word != null) addStyle(wordStyle, word.start, word.end)
        }
    }
    val shape = MaterialTheme.shapes.small
    var rowModifier = Modifier.fillMaxWidth()
    if (active) {
        rowModifier = rowModifier.background(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = shape)
        if (AppTheme.isEink) {
            rowModifier = rowModifier.border(
                BorderStroke(AppTheme.sizes.activeOutline, MaterialTheme.colorScheme.onSurface),
                shape
            )
        }
    }
    if (onTapped != null) rowModifier = rowModifier.clickable(onClick = onTapped)
    Column(
        modifier = rowModifier.padding(horizontal = spacing.s, vertical = spacing.s),
        verticalArrangement = Arrangement.spacedBy(spacing.xs)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s)
        ) {
            if (speakerIndex != null && line.speaker != null) {
                Row(
                    modifier = Modifier.clickable(role = Role.Button) { onSpeakerTapped(speakerIndex) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    SpeakerDot(speakerIndex = speakerIndex)
                    Text(
                        text = line.speaker,
                        style = AppTheme.typography.transcriptMeta,
                        color = speakerColor
                    )
                }
            }
            Text(
                text = timestamp,
                style = AppTheme.typography.figures,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(text = text, style = AppTheme.typography.transcript)
    }
}

private val NO_POSITION: () -> Long = { 0L }

/** `true` when the item at [index] is on screen and not cut off at either edge. */
private fun LazyListState.isFullyVisible(index: Int): Boolean {
    val info = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
    return info.offset >= layoutInfo.viewportStartOffset &&
        info.offset + info.size <= layoutInfo.viewportEndOffset
}
