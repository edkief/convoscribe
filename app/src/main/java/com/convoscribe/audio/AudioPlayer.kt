package com.convoscribe.audio

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.util.Log
import com.convoscribe.TAG
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the player bar renders. [ready] is `false` until the file has
 * been opened (and again after an error, see [failed]); [positionMs]
 * and [durationMs] are milliseconds into the recording.
 */
data class PlayerState(
    val ready: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val failed: Boolean = false
)

/**
 * Plays one session recording. The interface exists so the session
 * ViewModel can be tested with a fake; the real one is
 * [MediaPlayerAudioPlayer]. All calls come from the main thread.
 */
interface AudioPlayer {
    val state: StateFlow<PlayerState>

    /** Open [file]; calling it again for the same file is a no-op. */
    fun prepare(file: File)

    fun play()

    fun pause()

    /** Move to [positionMs] (clamped to the recording); keeps playing if it was. */
    fun seekTo(positionMs: Long)

    /** Move by [deltaMs] relative to the current position. */
    fun skip(deltaMs: Long)

    fun setSpeed(speed: Float)

    /** Pause if playing; the position is kept. */
    fun stop()

    fun release()
}

/**
 * [AudioPlayer] on the platform [MediaPlayer], which decodes the 16 kHz
 * mono WAV the app records and time-stretches for the speed control
 * without a new dependency. [scope] must run on the main dispatcher; it
 * drives the position polling while playing.
 *
 * A recording whose header was never closed (zero sizes after a crash)
 * is repaired first with [WavWriter.repairHeader]; the player is only
 * offered for finished sessions, so nothing is appending to the file.
 */
class MediaPlayerAudioPlayer(private val scope: CoroutineScope) : AudioPlayer {

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var openedPath: String? = null
    private var preparing: Job? = null
    private var ticker: Job? = null
    private var released = false

    override fun prepare(file: File) {
        if (released || file.absolutePath == openedPath) return
        openedPath = file.absolutePath
        preparing?.cancel()
        preparing = scope.launch {
            val usable = withContext(Dispatchers.IO) { repairIfNeeded(file) }
            if (!usable) {
                _state.update { it.copy(failed = true) }
                return@launch
            }
            open(file)
        }
    }

    private fun repairIfNeeded(file: File): Boolean = try {
        if (!file.isFile || file.length() <= WavWriter.HEADER_SIZE) {
            false
        } else {
            WavWriter.repairHeader(file)
            true
        }
    } catch (e: java.io.IOException) {
        Log.w(TAG, "cannot open ${file.name}: ${e.message}")
        false
    }

    private fun open(file: File) {
        player?.release()
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener {
                _state.update { s -> s.copy(ready = true, failed = false, durationMs = it.duration.toLong()) }
                applySpeed(_state.value.speed, keepPaused = true)
            }
            mp.setOnCompletionListener {
                stopTicker()
                _state.update { s -> s.copy(isPlaying = false, positionMs = s.durationMs) }
            }
            mp.setOnSeekCompleteListener { _state.update { s -> s.copy(positionMs = it.currentPosition.toLong()) } }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                stopTicker()
                _state.update { s -> s.copy(ready = false, isPlaying = false, failed = true) }
                true
            }
            mp.prepareAsync()
        } catch (e: java.io.IOException) {
            Log.w(TAG, "cannot open ${file.name}: ${e.message}")
            _state.update { it.copy(failed = true) }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "cannot open ${file.name}: ${e.message}")
            _state.update { it.copy(failed = true) }
        }
    }

    override fun play() {
        val mp = readyPlayer() ?: return
        // Playing a finished recording starts it again.
        if (_state.value.positionMs >= _state.value.durationMs) mp.seekTo(0, MediaPlayer.SEEK_CLOSEST)
        mp.start()
        _state.update { it.copy(isPlaying = true) }
        startTicker()
    }

    override fun pause() {
        val mp = readyPlayer() ?: return
        mp.pause()
        stopTicker()
        _state.update { it.copy(isPlaying = false, positionMs = mp.currentPosition.toLong()) }
    }

    override fun seekTo(positionMs: Long) {
        val mp = readyPlayer() ?: return
        val target = positionMs.coerceIn(0L, _state.value.durationMs)
        mp.seekTo(target, MediaPlayer.SEEK_CLOSEST)
        // currentPosition lags until the seek completes; show the target now.
        _state.update { it.copy(positionMs = target) }
    }

    override fun skip(deltaMs: Long) = seekTo(_state.value.positionMs + deltaMs)

    override fun setSpeed(speed: Float) {
        _state.update { it.copy(speed = speed) }
        applySpeed(speed, keepPaused = !_state.value.isPlaying)
    }

    override fun stop() {
        if (_state.value.isPlaying) pause()
    }

    override fun release() {
        released = true
        preparing?.cancel()
        stopTicker()
        player?.release()
        player = null
        _state.value = PlayerState()
    }

    private fun readyPlayer(): MediaPlayer? = if (_state.value.ready) player else null

    /** `setPlaybackParams` can start a paused player on some builds, so re-pause. */
    private fun applySpeed(speed: Float, keepPaused: Boolean) {
        val mp = player ?: return
        if (!_state.value.ready) return
        try {
            mp.playbackParams = PlaybackParams().setSpeed(speed)
            if (keepPaused && mp.isPlaying) mp.pause()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "cannot set speed $speed: ${e.message}")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "cannot set speed $speed: ${e.message}")
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val mp = player ?: break
                _state.update { it.copy(positionMs = mp.currentPosition.toLong()) }
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private companion object {
        const val TICK_MS = 200L
    }
}
