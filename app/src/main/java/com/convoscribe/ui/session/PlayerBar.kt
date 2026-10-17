package com.convoscribe.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.convoscribe.R
import com.convoscribe.audio.PlayerState
import com.convoscribe.data.TranscriptFormatter
import com.convoscribe.data.TranscriptLine
import java.util.Locale

/**
 * What the transcript and the player bar can do to the player. Kept
 * together so [SessionBody] takes one parameter for them;
 * [SessionScreen] fills it from the ViewModel.
 */
internal class PlaybackActions(
    val onTogglePlay: () -> Unit = {},
    val onSeek: (Long) -> Unit = {},
    val onSkip: (Long) -> Unit = {},
    val onCycleSpeed: () -> Unit = {},
    val onLineTapped: (TranscriptLine) -> Unit = {},
    val onUserScrolled: () -> Unit = {},
    val onResumeFollow: () -> Unit = {}
)

/**
 * Player controls above the transcript: a seek bar with the elapsed
 * and total time, then back 10 s, play / pause, forward 10 s and the
 * speed button (1x, 1.5x, 2x). While the user drags the seek bar it
 * shows the dragged position and seeks once on release, so the
 * player is not asked to seek on every pixel.
 */
@Composable
internal fun PlayerBar(state: PlayerState, actions: PlaybackActions, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: state.positionMs.toFloat()
    val total = state.durationMs.coerceAtLeast(1L).toFloat()
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 2.dp) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = TranscriptFormatter.timestamp(shown.toLong()),
                    style = MaterialTheme.typography.labelMedium
                )
                val positionDescription = stringResource(R.string.player_position)
                Slider(
                    value = shown.coerceIn(0f, total),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { actions.onSeek(it.toLong()) }
                        dragging = null
                    },
                    valueRange = 0f..total,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .semantics { contentDescription = positionDescription }
                )
                Text(
                    text = TranscriptFormatter.timestamp(state.durationMs),
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val back = stringResource(R.string.player_skip_back)
                TextButton(
                    onClick = { actions.onSkip(-PlaybackSync.SKIP_MS) },
                    modifier = Modifier.semantics { contentDescription = back }
                ) {
                    Text(text = stringResource(R.string.player_skip_back_label))
                }
                IconButton(onClick = actions.onTogglePlay) {
                    if (state.isPlaying) {
                        Icon(
                            painter = painterResource(R.drawable.ic_pause),
                            contentDescription = stringResource(R.string.player_pause)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = stringResource(R.string.player_play)
                        )
                    }
                }
                val forward = stringResource(R.string.player_skip_forward)
                TextButton(
                    onClick = { actions.onSkip(PlaybackSync.SKIP_MS) },
                    modifier = Modifier.semantics { contentDescription = forward }
                ) {
                    Text(text = stringResource(R.string.player_skip_forward_label))
                }
                val speedText = speedLabel(state.speed)
                val speedDescription = stringResource(R.string.player_speed, speedText)
                TextButton(
                    onClick = actions.onCycleSpeed,
                    modifier = Modifier.semantics { contentDescription = speedDescription }
                ) {
                    Text(text = stringResource(R.string.player_speed_label, speedText))
                }
            }
        }
    }
}

/** `1`, `1.5` or `2`: the speed without a trailing `.0`. */
private fun speedLabel(speed: Float): String =
    if (speed % 1f == 0f) speed.toInt().toString() else String.format(Locale.US, "%.1f", speed)
