package com.convoscribe

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
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
        // Whether any activity is visible, for the floating control.
        // Coming to the foreground also runs the update check when it
        // is due (issue #41), not only a fresh start.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { owner, event ->
                container.appInForeground.value = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                if (event == Lifecycle.Event.ON_START) container.updateManager.checkIfDue()
            }
        )
        container.updateCheckScheduler.sync()
        // A speaker run cancelled while charging holds the charging job
        // until the charger is unplugged (issue #64). Android 8+ only
        // delivers this to a receiver registered at run time.
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    container.pendingDiarizationScheduler.release()
                }
            },
            IntentFilter(Intent.ACTION_POWER_DISCONNECTED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Crash-recovery: walk the database for sessions that were
        // left mid-recording / mid-diarization by a previous
        // process and patch them so the user can see them in the
        // session list. We skip recovery while a recording is
        // already in flight (e.g. a foreground service restarted
        // the process); the active session already has the right
        // status and `recover()` would step on it.
        // Then the charging jobs are scheduled if a session waits for its
        // speakers (issue #11) or to be indexed for semantic search
        // (issue #61); they also wait for recovery to finish, as does sync (issue #68).
        if (container.recordingController.state.value.phase == RecordingPhase.IDLE) {
            val recovery = SessionRecovery(container.sessionRepository)
            container.applicationScope.launch {
                runCatching { recovery.recover() }
                container.recoveryDone.complete(Unit)
                // Files of an import the previous process died in; no
                // session was created for it.
                runCatching { container.importAudioUseCase.cleanUp() }
                // Recordings a restore had staged when the process died.
                runCatching { container.backupUseCase.cleanUp() }
                // Unplugged while the process was not running.
                container.pendingDiarizationScheduler.releaseIfUnplugged()
                container.pendingDiarizationScheduler.sync()
                container.semanticIndexScheduler.sync()
                // Sessions changed while the process was not running (issue #68).
                container.syncScheduler.sync()
            }
        } else {
            container.recoveryDone.complete(Unit)
        }
    }
}
