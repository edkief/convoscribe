package com.convoscribe.update

import android.util.Log
import com.convoscribe.TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The installed app's version, from `PackageManager`. */
data class InstalledApp(val versionName: String, val versionCode: Long)

/**
 * The app's update, observed by the Home banner and Settings › About
 * (issue #15).
 */
sealed interface UpdateState {
    data object Idle : UpdateState

    /** A check is in flight; [manual] when the user asked for it. */
    data class Checking(val manual: Boolean) : UpdateState

    /** A check the user asked for found nothing newer. */
    data object UpToDate : UpdateState

    /** [offer] is newer than the installed app. */
    data class Available(val offer: UpdateOffer) : UpdateState

    /** A check the user asked for could not reach GitHub. */
    data object CheckFailed : UpdateState
}

/**
 * Looks for a newer release of the app (issue #15), outside any screen.
 *
 * [checkOnStart] is the automatic check: at most once per process and
 * once every [CHECK_INTERVAL_MS], only while [autoCheck] is on, and
 * never while [isBusy] (a recording is open or paused, an import or a
 * re-transcription runs). It is silent: a failure leaves [state]
 * [UpdateState.Idle], and a skipped version is not offered.
 * [checkNow] is the *Check for updates* button: it ignores the
 * throttle, [autoCheck] and the skipped version, and reports
 * [UpdateState.UpToDate] or [UpdateState.CheckFailed].
 *
 * [bannerDismissed] is *Not now* on Home: the banner hides until the
 * next process start, while Settings › About still shows the offer.
 *
 * Calls come from the main thread; the check itself runs in [scope].
 */
class UpdateManager(
    private val source: ReleaseSource,
    private val store: UpdateStore,
    private val installed: () -> InstalledApp,
    private val autoCheck: () -> Boolean,
    private val includePrereleases: () -> Boolean,
    private val isBusy: () -> Boolean,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _bannerDismissed = MutableStateFlow(false)
    val bannerDismissed: StateFlow<Boolean> = _bannerDismissed.asStateFlow()

    private var startCheckDone = false
    private var checkJob: Job? = null

    val installedApp: InstalledApp get() = installed()

    /** The automatic check on app start; see the class comment for when it is skipped. */
    fun checkOnStart() {
        if (startCheckDone) return
        if (!autoCheck() || isBusy()) return
        val last = store.lastCheckAtMs
        val now = clock()
        // A clock set back past the last check counts as due.
        if (last in 1..now && now - last < CHECK_INTERVAL_MS) {
            startCheckDone = true
            return
        }
        startCheckDone = true
        check(manual = false)
    }

    /** *Check for updates*: ignores the throttle, the auto switch and a skipped version. */
    fun checkNow() {
        check(manual = true)
    }

    /** *Not now*: hide the Home banner until the next start. */
    fun dismissBanner() {
        _bannerDismissed.value = true
    }

    /** *Skip this version*: not offered again by the automatic check until a newer release appears. */
    fun skip(offer: UpdateOffer) {
        store.skippedVersion = offer.versionName
        if ((_state.value as? UpdateState.Available)?.offer == offer) _state.value = UpdateState.Idle
    }

    private fun check(manual: Boolean) {
        if (checkJob?.isActive == true) return
        if (!canCheck()) return
        _state.value = UpdateState.Checking(manual)
        checkJob = scope.launch {
            val releases = source.releases()
            if (releases == null) {
                _state.value = if (manual) UpdateState.CheckFailed else UpdateState.Idle
                return@launch
            }
            store.lastCheckAtMs = clock()
            val installedVersion = SemVer.parse(installed().versionName)
            if (installedVersion == null) {
                Log.w(TAG, "update check: installed versionName ${installed().versionName} is not X.Y.Z")
                _state.value = if (manual) UpdateState.CheckFailed else UpdateState.Idle
                return@launch
            }
            val offer = UpdateSelector.pick(releases, installedVersion, includePrereleases())
            Log.i(TAG, "update check: installed=$installedVersion offer=${offer?.versionName}")
            _state.value = when {
                offer == null -> if (manual) UpdateState.UpToDate else UpdateState.Idle
                !manual && offer.versionName == store.skippedVersion -> UpdateState.Idle
                else -> {
                    if (manual) _bannerDismissed.value = false
                    UpdateState.Available(offer)
                }
            }
        }
    }

    /** Only from a state that holds nothing in progress. */
    private fun canCheck(): Boolean = when (_state.value) {
        UpdateState.Idle, UpdateState.UpToDate, UpdateState.CheckFailed, is UpdateState.Available -> true
        is UpdateState.Checking -> false
    }

    companion object {
        /** The automatic check runs at most once in this long. */
        const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1_000L
    }
}
