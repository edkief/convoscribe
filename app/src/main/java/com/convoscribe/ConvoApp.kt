package com.convoscribe

import android.app.Application
import com.convoscribe.recording.RecordingPhase
import com.convoscribe.recording.SessionRecovery
import kotlinx.coroutines.launch

class ConvoApp : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Secondary processes (the `:npuprobe` engine-check probe)
        // only need the container's factories. Crash recovery must
        // not run there: it would see an idle controller and patch
        // the session the main process may be recording right now.
        if (Application.getProcessName()?.contains(':') == true) return
        // Crash-recovery: walk the database for sessions that were
        // left mid-recording / mid-diarization by a previous
        // process and patch them so the user can see them in the
        // session list. We skip recovery while a recording is
        // already in flight (e.g. a foreground service restarted
        // the process); the active session already has the right
        // status and `recover()` would step on it.
        if (container.recordingController.state.value.phase == RecordingPhase.IDLE) {
            val recovery = SessionRecovery(container.sessionRepository)
            container.applicationScope.launch {
                runCatching { recovery.recover() }
                // Files of an import the previous process died in; no
                // session was created for it.
                runCatching { container.importAudioUseCase.cleanUp() }
            }
        }
    }
}
