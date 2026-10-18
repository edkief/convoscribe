package com.convoscribe

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.convoscribe.models.ModelCatalog
import com.convoscribe.recording.RecordingPhase
import com.convoscribe.service.RecordingService

/**
 * Invisible activity the Quick Settings tile opens to start a new
 * recording (issue #7). Being an activity the user just launched, it
 * makes the app visible, which Android 14+ requires before a
 * microphone foreground service may start. It starts the recording
 * with the Settings defaults and closes at once.
 *
 * When a recording cannot start straight away (no microphone
 * permission yet, the default language's models are missing, or
 * another job holds the recorder), it opens the Record screen
 * instead, which asks for the permission or explains.
 */
class RecordTrampolineActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (applicationContext as ConvoApp).container
        val settings = container.settingsRepository.settings.value
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val modelsReady = container.modelStore
            .missing(ModelCatalog.requiredFor(settings.asrLanguage, settings.asrEngine))
            .isEmpty()
        val free = container.recordingController.state.value.phase == RecordingPhase.IDLE &&
            !container.retranscribeUseCase.isRunning &&
            !container.importAudioUseCase.isRunning
        if (micGranted && modelsReady && free) {
            ContextCompat.startForegroundService(this, RecordingService.startIntent(this))
        } else {
            startActivity(MainActivity.openRecordIntent(this))
        }
        finish()
    }
}
