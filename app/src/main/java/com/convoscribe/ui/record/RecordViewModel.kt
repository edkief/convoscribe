package com.convoscribe.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoscribe.data.EnrolledSpeakerStore
import com.convoscribe.data.ParticipantsCodec
import com.convoscribe.recording.ConversationOptions
import com.convoscribe.recording.RecordingController
import com.convoscribe.recording.RecordingState
import com.convoscribe.settings.SettingsRepository
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Thin ViewModel over [RecordingController] for the recording screen.
 * The controller already owns its own [RecordingController.state]
 * `StateFlow` and exposes every operation the UI needs; this class
 * adds:
 *
 *  - the [formatElapsed] formatter (top-level function so a
 *    no-Android unit test can cover it without instantiating the
 *    controller);
 *  - the [onNavigatedToSession] callback that clears
 *    [RecordingState.lastCompletedSessionId] once the navigation
 *    has fired;
 *  - the new conversation's [options] (issue #6), pre-filled from
 *    the Settings defaults. Editing them never touches Settings;
 *    [saveAsDefault] writes them back. [enrolledNames] feeds the
 *    participants picker, and [modelsMissing] tells the screen to
 *    send the user to setup before Start.
 * Constructed by
 * [com.convoscribe.ui.AppNavHost]'s `viewModelFactory` reading
 * `AppContainer.recordingController` so the singleton lives across
 * configuration changes (and is the same instance the service
 * drives).
 */
class RecordViewModel(
    controller: RecordingController,
    private val settings: SettingsRepository,
    enrolled: EnrolledSpeakerStore,
    private val missingModels: (language: String) -> Boolean = { false }
) : ViewModel() {

    private val recordingController = controller

    /** Latest state snapshot of the active recording session. */
    val state: StateFlow<RecordingState> = controller.state

    private val _options = MutableStateFlow(ConversationOptions.fromSettings(settings.settings.value))

    /** Options the next Start uses; seeded from the Settings defaults. */
    val options: StateFlow<ConversationOptions> = _options.asStateFlow()

    /** Distinct enrolled names, for the participants picker. */
    val enrolledNames: StateFlow<List<String>> = enrolled.observeAll()
        .map { rows -> rows.map { it.name }.distinct() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setLanguage(language: String) {
        if (language == "en" || language == "fr") _options.update { it.copy(language = language) }
    }

    fun setNumSpeakers(count: Int) {
        _options.update { it.copy(numSpeakers = if (count < 1) -1 else count.coerceAtMost(10)) }
    }

    /** `null` (or an empty list) means anyone. */
    fun setParticipants(names: List<String>?) {
        _options.update { it.copy(participants = ParticipantsCodec.normalise(names)) }
    }

    fun setTitle(title: String) {
        _options.update { it.copy(title = title) }
    }

    /** Save the language, speaker count and participants as the defaults for future conversations. */
    fun saveAsDefault() {
        val chosen = _options.value
        settings.update {
            it.copy(
                asrLanguage = chosen.language,
                numSpeakers = chosen.numSpeakers,
                defaultParticipants = chosen.participants
            )
        }
    }

    /**
     * After a Start: whether to explain the floating control's "Display
     * over other apps" permission now (issue #7). Only once ever, only
     * while the floating control is on in Settings, and never when the
     * permission is already [granted]. Answering `true` records that
     * the user has been asked.
     */
    fun shouldAskForOverlay(granted: Boolean): Boolean {
        val current = settings.settings.value
        if (granted || !current.floatingControl || current.overlayPromptShown) return false
        settings.update { it.copy(overlayPromptShown = true) }
        return true
    }

    /** Whether the chosen language still needs its models downloaded before Start. */
    fun modelsMissing(): Boolean = missingModels(_options.value.language)

    /**
     * Run after navigation has consumed
     * [RecordingState.lastCompletedSessionId] so the same id is not
     * re-handled on the next recomposition. Safe to call when
     * nothing is pending.
     */
    fun onNavigatedToSession() {
        recordingController.consumeCompleted()
    }
}

/**
 * Format an elapsed time as the recording screen displays it.
 *
 *  - `mm:ss` (always two digits each) under one hour;
 *  - `h:mm:ss` (single-digit hours, padded minutes/seconds) once
 *    the elapsed time crosses one hour;
 *  - clamped to `0` for negative input so a transient glitch in
 *    the counter cannot throw.
 *
 * Locale is pinned to `Locale.US` so the colon never flips to a
 * comma on a European locale. Used in the body of the screen and
 * is also the formatter the recording-screen's notification text
 * indirectly relies on (separate function for the notification so
 * the two strings can evolve independently).
 *
 * Top-level on purpose: the unit test for this formatter must not
 * touch the Android framework, only pure JUnit.
 */
fun formatElapsed(ms: Long): String {
    val safe = max(0L, ms)
    val totalSeconds = safe / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
