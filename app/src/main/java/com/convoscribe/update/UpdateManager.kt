package com.convoscribe.update

import android.util.Log
import com.convoscribe.TAG
import com.convoscribe.models.ModelDownloadException
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the system installer reported for an update ([UpdateInstallReceiver]). */
enum class InstallResult { SUCCESS, CANCELLED, INCOMPATIBLE, FAILED }

/** Whether [UpdateManager.install] handed the update to the system installer. */
enum class InstallStart { STARTED, BUSY, NOTHING_TO_INSTALL }

/** The installed app's version, from `PackageManager`. */
data class InstalledApp(val versionName: String, val versionCode: Long)

/** Why an update did not get to the installer; the screens word it. */
enum class UpdateFailure {
    /** The download stopped; [UpdateState.Failed.detail] says why. Retry resumes it. */
    DOWNLOAD,
    SHA256_MISMATCH,
    UNREADABLE,
    WRONG_PACKAGE,
    NOT_NEWER,

    /** Needs an uninstall and a reinstall from the release page (#16). */
    SIGNATURE_MISMATCH,

    /** A session is open or an import / re-transcription runs; installing would stop it. */
    BUSY,

    /** The system installer failed; [UpdateState.Failed.detail] has its message. */
    INSTALL
}

/**
 * The app's update, observed by the Home banner, Settings › About and
 * the download service's notification (issue #15).
 */
sealed interface UpdateState {
    data object Idle : UpdateState

    /** A check is in flight; [manual] when the user asked for it. */
    data class Checking(val manual: Boolean) : UpdateState

    /** A check the user asked for found nothing newer. */
    data object UpToDate : UpdateState

    /** A check the user asked for could not reach GitHub. */
    data object CheckFailed : UpdateState

    /** [offer] is newer than the installed app. */
    data class Available(val offer: UpdateOffer) : UpdateState

    /** [bytesDone] of [bytesTotal] fetched; [waiting] while a retry waits for the network. */
    data class Downloading(
        val offer: UpdateOffer,
        val bytesDone: Long,
        val bytesTotal: Long,
        val waiting: Boolean = false
    ) : UpdateState

    /** [apk] is downloaded and verified, ready for the system installer. */
    data class ReadyToInstall(val offer: UpdateOffer, val apk: File) : UpdateState

    /** [apk] is with the system installer, waiting for the user and the result. */
    data class Installing(val offer: UpdateOffer, val apk: File) : UpdateState

    data class Failed(val offer: UpdateOffer, val reason: UpdateFailure, val detail: String? = null) : UpdateState
}

/** The offer a state is about, if any. */
val UpdateState.offer: UpdateOffer?
    get() = when (this) {
        is UpdateState.Available -> offer
        is UpdateState.Downloading -> offer
        is UpdateState.ReadyToInstall -> offer
        is UpdateState.Installing -> offer
        is UpdateState.Failed -> offer
        UpdateState.Idle, UpdateState.UpToDate, UpdateState.CheckFailed, is UpdateState.Checking -> null
    }

/**
 * Looks for a newer release of the app and fetches it (issue #15),
 * outside any screen.
 *
 * [checkIfDue] is the automatic check (issue #41), called whenever the
 * app comes to the foreground and from the periodic background job: it
 * runs once [CHECK_INTERVAL_MS] has passed since the last successful
 * check, only while [autoCheck] is on, and never while [isBusy] (a
 * recording is open or paused, an import or a re-transcription runs).
 * A skipped or failed check runs on the next call. It is silent: a
 * failure leaves [state] as it was, and a skipped version is not
 * offered. It leaves a download, a ready APK or a failure alone.
 * [checkNow] is the *Check for updates* button: it ignores the
 * throttle, [autoCheck] and the skipped version, and reports
 * [UpdateState.UpToDate] or [UpdateState.CheckFailed].
 *
 * [download] fetches the offer's APK through [downloads] (started by
 * [com.convoscribe.service.UpdateDownloadService], which keeps the
 * process alive), then checks it against the installed app with
 * [ApkVerifier] before it is [UpdateState.ReadyToInstall].
 *
 * [install] gives a [UpdateState.ReadyToInstall] APK to [installer],
 * unless [isBusy]: an update stops the app, which would cut a live
 * recording short. The installer's verdict comes back through
 * [onInstallResult]; a cancelled prompt goes back to *Install*.
 *
 * [bannerDismissed] is *Not now* on Home: the banner hides until the
 * next process start (or a manual check), while Settings › About still
 * shows the offer.
 *
 * Calls come from the main thread; the work runs in [scope]. The state
 * writes of a cancelled download are dropped, like
 * [com.convoscribe.models.ModelDownloadManager]'s.
 */
class UpdateManager(
    private val source: ReleaseSource,
    private val store: UpdateStore,
    private val downloads: UpdateDownloads,
    private val inspector: ApkInspector,
    private val installer: ApkInstaller,
    private val installed: () -> InstalledApp,
    private val autoCheck: () -> Boolean,
    private val includePrereleases: () -> Boolean,
    private val isBusy: () -> Boolean,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _bannerDismissed = MutableStateFlow(false)
    val bannerDismissed: StateFlow<Boolean> = _bannerDismissed.asStateFlow()

    private val lock = Any()
    private var cleanedUp = false
    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    /** Bumped by every download start and stop; a download writes state only while it is current. */
    private var generation = 0

    val installedApp: InstalledApp get() = installed()

    val isDownloading: Boolean get() = _state.value is UpdateState.Downloading

    /**
     * The automatic check; see the class comment for when it is skipped.
     * Returns the check's job, `null` when none was started.
     */
    fun checkIfDue(): Job? {
        if (!cleanedUp) {
            cleanedUp = true
            // Downloads of the installed version (just updated) or older.
            scope.launch(ioDispatcher) { runCatching { downloads.cleanUp(SemVer.parse(installed().versionName)) } }
        }
        if (!autoCheck() || isBusy()) return null
        val last = store.lastCheckAtMs
        val now = clock()
        // A clock set back past the last check counts as due.
        if (last in 1..now && now - last < CHECK_INTERVAL_MS) return null
        return check(manual = false)
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

    /**
     * Download the offered update (from [UpdateState.Available] or a
     * failure, which resumes); `false` when there is nothing to fetch
     * or a download already runs.
     */
    fun download(): Boolean {
        val myGeneration: Int
        val offer: UpdateOffer
        synchronized(lock) {
            val current = _state.value
            offer = when (current) {
                is UpdateState.Available -> current.offer
                is UpdateState.Failed -> current.offer
                else -> return false
            }
            _state.value = UpdateState.Downloading(offer, 0L, offer.apk.sizeBytes)
            myGeneration = ++generation
        }

        fun publish(value: UpdateState) = synchronized(lock) {
            if (generation == myGeneration) _state.value = value
        }
        downloadJob = scope.launch {
            val next = try {
                val apk = downloads.download(offer) { done, total, waiting ->
                    publish(UpdateState.Downloading(offer, done, total, waiting))
                }
                val rejection = withContext(ioDispatcher) { verify(apk) }
                if (rejection == null) {
                    UpdateState.ReadyToInstall(offer, apk)
                } else {
                    Log.w(TAG, "update ${offer.versionName} refused: $rejection")
                    // A file that can never be installed is not worth keeping.
                    withContext(ioDispatcher) { downloads.discard(offer) }
                    UpdateState.Failed(offer, rejection.toFailure())
                }
            } catch (e: Sha256MismatchException) {
                UpdateState.Failed(offer, UpdateFailure.SHA256_MISMATCH, e.message)
            } catch (e: ModelDownloadException) {
                UpdateState.Failed(offer, UpdateFailure.DOWNLOAD, e.message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "update download failed", e)
                UpdateState.Failed(offer, UpdateFailure.DOWNLOAD, e.message ?: e.javaClass.simpleName)
            }
            publish(next)
        }
        return true
    }

    /** Cancel the download and delete what it fetched; the offer stays available. */
    fun cancel() {
        val (stopped, offer) = synchronized(lock) {
            val current = _state.value as? UpdateState.Downloading ?: return
            generation++
            _state.value = UpdateState.Available(current.offer)
            val job = downloadJob
            downloadJob = null
            job?.cancel()
            job to current.offer
        }
        scope.launch {
            stopped?.cancelAndJoin()
            withContext(ioDispatcher) { downloads.discard(offer) }
        }
    }

    /**
     * Stop the download but keep what it fetched, so Retry resumes; shown
     * as a download failure with [message] (the system's time limit for
     * the service ran out).
     */
    fun interrupt(message: String) {
        synchronized(lock) {
            val current = _state.value as? UpdateState.Downloading ?: return
            generation++
            _state.value = UpdateState.Failed(current.offer, UpdateFailure.DOWNLOAD, message)
            downloadJob?.cancel()
            downloadJob = null
        }
    }

    /** *Install*: hand the downloaded APK to the system installer. */
    fun install(): InstallStart {
        val ready = synchronized(lock) {
            val ready = _state.value as? UpdateState.ReadyToInstall ?: return InstallStart.NOTHING_TO_INSTALL
            if (isBusy()) return InstallStart.BUSY
            _state.value = UpdateState.Installing(ready.offer, ready.apk)
            ready
        }
        scope.launch {
            try {
                installer.install(ready.apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "update install failed to start", e)
                onInstallResult(InstallResult.FAILED, e.message ?: e.javaClass.simpleName)
            }
        }
        return InstallStart.STARTED
    }

    /**
     * The installer's verdict. On success the process is usually
     * stopped before this runs; [UpdatedReceiver] takes over then.
     */
    fun onInstallResult(result: InstallResult, message: String?) {
        synchronized(lock) {
            val installing = _state.value as? UpdateState.Installing ?: return
            _state.value = when (result) {
                InstallResult.SUCCESS -> UpdateState.Idle
                InstallResult.CANCELLED -> UpdateState.ReadyToInstall(installing.offer, installing.apk)
                // The verification should have caught this; the installer has the last word.
                InstallResult.INCOMPATIBLE ->
                    UpdateState.Failed(installing.offer, UpdateFailure.SIGNATURE_MISMATCH, message)
                InstallResult.FAILED -> UpdateState.Failed(installing.offer, UpdateFailure.INSTALL, message)
            }
        }
    }

    private fun verify(apk: File): UpdateRejection? =
        ApkVerifier.verify(inspector.archive(apk.path), inspector.installed())

    private fun check(manual: Boolean): Job? {
        checkJob?.takeIf { it.isActive }?.let { return it }
        val before = _state.value
        if (!canCheck(manual)) return null
        // An offer on screen stays there while the automatic check runs.
        if (manual || before !is UpdateState.Available) _state.value = UpdateState.Checking(manual)
        val job = scope.launch {
            val releases = source.releases()
            if (releases == null) {
                _state.value = if (manual) UpdateState.CheckFailed else before.afterSilentFailure()
                return@launch
            }
            store.lastCheckAtMs = clock()
            val installedVersion = SemVer.parse(installed().versionName)
            if (installedVersion == null) {
                Log.w(TAG, "update check: installed versionName ${installed().versionName} is not X.Y.Z")
                _state.value = if (manual) UpdateState.CheckFailed else before.afterSilentFailure()
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
        checkJob = job
        return job
    }

    /** What a failed automatic check leaves: an offer stays, anything else is [UpdateState.Idle]. */
    private fun UpdateState.afterSilentFailure(): UpdateState = this as? UpdateState.Available ?: UpdateState.Idle

    /**
     * Only from a state that holds nothing in progress or ready; the
     * automatic check also leaves a failed download for the user to retry.
     */
    private fun canCheck(manual: Boolean): Boolean = when (_state.value) {
        UpdateState.Idle, UpdateState.UpToDate, UpdateState.CheckFailed, is UpdateState.Available -> true
        is UpdateState.Failed -> manual
        is UpdateState.Checking, is UpdateState.Downloading, is UpdateState.ReadyToInstall,
        is UpdateState.Installing -> false
    }

    companion object {
        /** The automatic check runs at most once in this long: three times a day (issue #41). */
        const val CHECK_INTERVAL_MS = 8L * 60L * 60L * 1_000L
    }
}

private fun UpdateRejection.toFailure(): UpdateFailure = when (this) {
    UpdateRejection.UNREADABLE -> UpdateFailure.UNREADABLE
    UpdateRejection.WRONG_PACKAGE -> UpdateFailure.WRONG_PACKAGE
    UpdateRejection.NOT_NEWER -> UpdateFailure.NOT_NEWER
    UpdateRejection.SIGNATURE_MISMATCH -> UpdateFailure.SIGNATURE_MISMATCH
}
