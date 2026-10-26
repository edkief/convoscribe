package com.convoscribe.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.convoscribe.ConvoApp
import com.convoscribe.R
import com.convoscribe.RecordTrampolineActivity
import com.convoscribe.recording.RecordingPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * The "ConvoScribe" Quick Settings tile (issue #7). While idle it
 * starts a new recording with the Settings defaults; while a session
 * is open it pauses or resumes it. Its state follows the session:
 * inactive while idle, active with "Recording" or "Paused" while a
 * session is open, unavailable while one is loading or finishing, or
 * while an import or re-transcription holds the recorder.
 *
 * A new recording starts through [RecordTrampolineActivity] rather
 * than straight from [onClick]: Android 14+ lets a microphone
 * foreground service start only while the app is visible, and an
 * activity launched from the tile is. Pause / resume go straight to
 * the recording service, which is already in the foreground then.
 *
 * A long-press opens [com.convoscribe.PresetPickerActivity] (issue
 * #32) through its `QS_TILE_PREFERENCES` filter, to start with a preset.
 */
class RecordTileService : TileService() {

    private var scope: CoroutineScope? = null
    private var observer: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val listening = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        observer = container().recordingController.state
            .onEach { refresh() }
            .launchIn(listening)
    }

    override fun onStopListening() {
        observer?.cancel()
        observer = null
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        when {
            container().recordingController.state.value.phase.active ->
                startService(RecordingService.togglePauseIntent(this))
            busy() -> Unit
            isLocked -> unlockAndRun { startNewRecording() }
            else -> startNewRecording()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun startNewRecording() {
        val intent = Intent(this, RecordTrampolineActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val phase = container().recordingController.state.value.phase
        tile.state = when {
            phase.active -> Tile.STATE_ACTIVE
            phase != RecordingPhase.IDLE || busy() -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.tile_label)
        tile.subtitle = getString(
            when (phase) {
                RecordingPhase.RECORDING -> R.string.status_recording
                RecordingPhase.PAUSED -> R.string.status_paused
                else -> R.string.tile_subtitle_idle
            }
        )
        tile.contentDescription = getString(
            when (phase) {
                RecordingPhase.RECORDING -> R.string.tile_description_recording
                RecordingPhase.PAUSED -> R.string.tile_description_paused
                else -> R.string.tile_description_idle
            }
        )
        tile.updateTile()
    }

    /** An import or re-transcription holds the recorder; a recording cannot start. */
    private fun busy(): Boolean = container().retranscribeUseCase.isRunning || container().importAudioUseCase.isRunning

    private fun container() = (application as ConvoApp).container
}
