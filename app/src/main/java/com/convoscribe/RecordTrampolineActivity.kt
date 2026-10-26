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
        val settings = (applicationContext as ConvoApp).container.settingsRepository.settings.value
        if (canStartNow(this, settings.asrLanguage)) {
            ContextCompat.startForegroundService(this, RecordingService.startIntent(this))
        } else {
            startActivity(MainActivity.openRecordIntent(this))
        }
        finish()
    }

    companion object {
        /**
         * Whether a new recording in [language] can start without the
         * Record screen: the microphone is allowed, the language's
         * models are on disk and nothing else holds the recorder.
         */
        fun canStartNow(activity: ComponentActivity, language: String): Boolean {
            val container = (activity.applicationContext as ConvoApp).container
            val settings = container.settingsRepository.settings.value
            val micGranted = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            val modelsReady = container.modelStore
                .missing(ModelCatalog.requiredFor(language, settings.asrEngine))
                .isEmpty()
            val free = container.recordingController.state.value.phase == RecordingPhase.IDLE &&
                !container.retranscribeUseCase.isRunning &&
                !container.importAudioUseCase.isRunning
            return micGranted && modelsReady && free
        }
    }
}
