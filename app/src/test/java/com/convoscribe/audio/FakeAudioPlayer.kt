package com.convoscribe.audio

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** In-memory [AudioPlayer] that records what it was asked to do. */
class FakeAudioPlayer(durationMs: Long = 60_000L) : AudioPlayer {
    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state

    private val duration = durationMs
    val prepared = mutableListOf<File>()
    var released = false

    override fun prepare(file: File) {
        prepared.add(file)
        _state.update { it.copy(ready = true, durationMs = duration) }
    }

    override fun play() = _state.update { it.copy(isPlaying = true) }

    override fun pause() = _state.update { it.copy(isPlaying = false) }

    override fun seekTo(positionMs: Long) = _state.update { it.copy(positionMs = positionMs.coerceIn(0L, duration)) }

    override fun skip(deltaMs: Long) = seekTo(_state.value.positionMs + deltaMs)

    override fun setSpeed(speed: Float) = _state.update { it.copy(speed = speed) }

    override fun stop() {
        if (_state.value.isPlaying) pause()
    }

    override fun release() {
        released = true
    }
}
