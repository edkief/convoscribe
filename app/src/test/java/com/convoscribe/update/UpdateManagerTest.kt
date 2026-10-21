package com.convoscribe.update

import com.convoscribe.models.ModelDownloadException
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UpdateManager]: the check (throttle, auto switch, busy guard, skip,
 * manual check) and the download (progress, verification, cancel).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateManagerTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    private class FakeSource(var releases: List<Release>?) : ReleaseSource {
        var calls = 0
        override suspend fun releases(): List<Release>? {
            calls++
            return releases
        }
    }

    private class MemoryStore : UpdateStore {
        override var lastCheckAtMs: Long = 0L
        override var skippedVersion: String? = null
        override var offer: UpdateOffer? = null
    }

    private fun release(tag: String) = Release(
        tag = tag,
        name = tag,
        notes = "",
        htmlUrl = "https://github.com/edkief/convoscribe/releases/tag/$tag",
        prerelease = false,
        draft = false,
        assets = listOf(ReleaseAsset("convoscribe-${tag.removePrefix("v")}.apk", "https://x/$tag.apk", 10L))
    )

    /** Reports [progress], then waits for the test to [finish] it. */
    private class FakeDownloads : UpdateDownloads {
        var result = CompletableDeferred<File>()
        var progress: Triple<Long, Long, Boolean>? = null
        val discarded = mutableListOf<String>()
        var cleanedUpFor: SemVer? = null
        var calls = 0

        override suspend fun download(
            offer: UpdateOffer,
            onProgress: suspend (bytesDone: Long, bytesTotal: Long, waiting: Boolean) -> Unit
        ): File {
            calls++
            progress?.let { (done, total, waiting) -> onProgress(done, total, waiting) }
            return result.await()
        }

        override fun discard(offer: UpdateOffer) {
            discarded += offer.versionName
        }

        override fun cleanUp(installed: SemVer?): Boolean {
            cleanedUpFor = installed
            return false
        }
    }

    private class FakeInspector(var archive: ApkFacts?, var installed: ApkFacts) : ApkInspector {
        override fun archive(path: String): ApkFacts? = archive
        override fun installed(): ApkFacts = installed
    }

    private class FakeInstaller : ApkInstaller {
        val installed = mutableListOf<File>()
        var failWith: Exception? = null
        override suspend fun install(apk: File) {
            failWith?.let { throw it }
            installed += apk
        }
    }

    private val installer = FakeInstaller()
    private val downloads = FakeDownloads()
    private val inspector = FakeInspector(
        archive = ApkFacts("com.convoscribe", 4L, setOf("key")),
        installed = ApkFacts("com.convoscribe", 3L, setOf("key"))
    )
    private val apk = File("/tmp/updates/0.3.0/convoscribe-0.3.0.apk")

    private val source = FakeSource(listOf(release("v0.3.0"), release("v0.2.1")))
    private val store = MemoryStore()
    private var now = 10L * UpdateManager.CHECK_INTERVAL_MS
    private var auto = true
    private var busy = false
    private var installed = InstalledApp("0.2.1", 3L)
    private var prereleases = false

    private fun manager() = UpdateManager(
        source = source,
        store = store,
        downloads = downloads,
        inspector = inspector,
        installer = installer,
        installed = { installed },
        autoCheck = { auto },
        includePrereleases = { prereleases },
        isBusy = { busy },
        scope = scope,
        clock = { now },
        ioDispatcher = dispatcher
    )

    /** A manager holding the 0.3.0 offer. */
    private fun offered(): UpdateManager = manager().also {
        it.checkNow()
        scope.runCurrent()
        check(it.state.value is UpdateState.Available)
    }

    private fun offeredVersion(manager: UpdateManager): String? =
        (manager.state.value as? UpdateState.Available)?.offer?.versionName

    @Test
    fun autoCheck_offersANewerRelease_andRecordsTheCheck() {
        val manager = manager()
        manager.checkIfDue()
        assertEquals(UpdateState.Checking(manual = false), manager.state.value)
        scope.runCurrent()
        assertEquals("0.3.0", offeredVersion(manager))
        assertEquals(now, store.lastCheckAtMs)
    }

    @Test
    fun autoCheck_equalVersion_staysIdle() {
        installed = InstalledApp("0.3.0", 4L)
        val manager = manager()
        manager.checkIfDue()
        scope.runCurrent()
        assertEquals(UpdateState.Idle, manager.state.value)
    }

    @Test
    fun autoCheck_isThrottledTo8Hours() {
        store.lastCheckAtMs = now - UpdateManager.CHECK_INTERVAL_MS + 1
        manager().checkIfDue()
        scope.runCurrent()
        assertEquals(0, source.calls)

        store.lastCheckAtMs = now - UpdateManager.CHECK_INTERVAL_MS
        manager().checkIfDue()
        scope.runCurrent()
        assertEquals(1, source.calls)
    }

    @Test
    fun autoCheck_runsWhenTheClockWentBackPastTheLastCheck() {
        store.lastCheckAtMs = now + 1_000L
        manager().checkIfDue()
        scope.runCurrent()
        assertEquals(1, source.calls)
    }

    @Test
    fun autoCheck_runsAgainInTheSameProcessOnceTheIntervalPassed() {
        val manager = manager()
        manager.checkIfDue()
        scope.runCurrent()
        now += UpdateManager.CHECK_INTERVAL_MS - 1
        manager.checkIfDue()
        scope.runCurrent()
        assertEquals(1, source.calls)

        now += 1
        manager.checkIfDue()
        scope.runCurrent()
        assertEquals(2, source.calls)
    }

    @Test
    fun autoCheck_failureIsRetriedOnTheNextCall() {
        source.releases = null
        val manager = manager()
        manager.checkIfDue()
        scope.runCurrent()

        source.releases = listOf(release("v0.3.0"))
        manager.checkIfDue()
        scope.runCurrent()
        assertEquals(2, source.calls)
        assertEquals("0.3.0", offeredVersion(manager))
    }

    @Test
    fun autoCheck_keepsTheOfferOnScreenWhileItRuns_andAfterAFailure() {
        val manager = offered()
        now += UpdateManager.CHECK_INTERVAL_MS
        source.releases = null
        manager.checkIfDue()
        assertEquals("0.3.0", offeredVersion(manager))
        scope.runCurrent()
        assertEquals("0.3.0", offeredVersion(manager))
    }

    @Test
    fun autoCheck_leavesAFailedDownloadForTheUserToRetry() {
        val manager = offered()
        assertTrue(manager.download())
        downloads.result.completeExceptionally(ModelDownloadException("offline"))
        scope.runCurrent()
        check(manager.state.value is UpdateState.Failed)
        now += UpdateManager.CHECK_INTERVAL_MS

        assertEquals(null, manager.checkIfDue())
        assertTrue(manager.state.value is UpdateState.Failed)
    }

    @Test
    fun autoCheck_returnsTheJobItStarted() {
        val job = manager().checkIfDue()
        assertTrue(job != null && job.isActive)
        scope.runCurrent()
        assertTrue(job!!.isCompleted)
        auto = false
        assertEquals(null, manager().checkIfDue())
    }

    @Test
    fun autoCheck_skippedWhenAutoCheckIsOff() {
        auto = false
        val manager = manager()
        manager.checkIfDue()
        scope.runCurrent()
        assertEquals(0, source.calls)
        assertEquals(UpdateState.Idle, manager.state.value)
    }

    @Test
    fun autoCheck_skippedWhileBusy_andRunsOnALaterStart() {
        busy = true
        val manager = manager()
        manager.checkIfDue()
        scope.runCurrent()
        assertEquals(0, source.calls)

        busy = false
        manager.checkIfDue()
        scope.runCurrent()
        assertEquals(1, source.calls)
    }

    @Test
    fun autoCheck_failureIsSilent_andNotRecorded() {
        source.releases = null
        val manager = manager()
        manager.checkIfDue()
        scope.runCurrent()
        assertEquals(UpdateState.Idle, manager.state.value)
        assertEquals(0L, store.lastCheckAtMs)
    }

    @Test
    fun skippedVersion_isNotOfferedAutomatically_butANewerOneIs() {
        store.skippedVersion = "0.3.0"
        manager().let {
            it.checkIfDue()
            scope.runCurrent()
            assertEquals(UpdateState.Idle, it.state.value)
        }

        source.releases = listOf(release("v0.4.0"), release("v0.3.0"))
        store.lastCheckAtMs = 0L
        manager().let {
            it.checkIfDue()
            scope.runCurrent()
            assertEquals("0.4.0", offeredVersion(it))
        }
    }

    @Test
    fun foundOffer_isStored_andOfferedAgainByTheNextProcess() {
        offered()
        assertEquals("0.3.0", store.offer?.versionName)

        val next = manager()
        assertEquals("0.3.0", offeredVersion(next))
        assertEquals(1, source.calls)
    }

    @Test
    fun storedOffer_isForgottenOnceInstalled() {
        offered()
        installed = InstalledApp("0.3.0", 4L)
        assertEquals(UpdateState.Idle, manager().state.value)
        assertEquals(null, store.offer)
    }

    @Test
    fun storedOffer_isForgottenWhenSkipped_orAPreReleaseNoLongerWanted() {
        prereleases = true
        source.releases = listOf(release("v0.3.0").copy(prerelease = true))
        offered()
        prereleases = false
        assertEquals(UpdateState.Idle, manager().state.value)
        assertEquals(null, store.offer)

        source.releases = listOf(release("v0.3.0"))
        offered()
        store.skippedVersion = "0.3.0"
        assertEquals(UpdateState.Idle, manager().state.value)
        assertEquals(null, store.offer)
    }

    @Test
    fun checkFindingNothing_forgetsTheStoredOffer() {
        offered()
        source.releases = listOf(release("v0.2.1"))
        val manager = manager()
        manager.checkNow()
        scope.runCurrent()
        assertEquals(null, store.offer)
    }

    @Test
    fun skip_forgetsTheStoredOffer() {
        val manager = offered()
        manager.skip((manager.state.value as UpdateState.Available).offer)
        assertEquals(null, store.offer)
        assertEquals(UpdateState.Idle, manager().state.value)
    }

    @Test
    fun skip_recordsTheVersionAndHidesTheOffer() {
        val manager = manager()
        manager.checkIfDue()
        scope.runCurrent()
        val offer = (manager.state.value as UpdateState.Available).offer
        manager.skip(offer)
        assertEquals("0.3.0", store.skippedVersion)
        assertEquals(UpdateState.Idle, manager.state.value)
    }

    @Test
    fun manualCheck_ignoresThrottleAutoSwitchAndSkip() {
        auto = false
        store.lastCheckAtMs = now - 1
        store.skippedVersion = "0.3.0"
        val manager = manager()
        manager.checkNow()
        assertEquals(UpdateState.Checking(manual = true), manager.state.value)
        scope.runCurrent()
        assertEquals("0.3.0", offeredVersion(manager))
    }

    @Test
    fun manualCheck_reportsUpToDateAndFailure() {
        installed = InstalledApp("0.3.0", 4L)
        val manager = manager()
        manager.checkNow()
        scope.runCurrent()
        assertEquals(UpdateState.UpToDate, manager.state.value)

        source.releases = null
        manager.checkNow()
        scope.runCurrent()
        assertEquals(UpdateState.CheckFailed, manager.state.value)
    }

    @Test
    fun unparseableInstalledVersion_offersNothing() {
        installed = InstalledApp("", 0L)
        val manager = manager()
        manager.checkIfDue()
        scope.runCurrent()
        assertEquals(UpdateState.Idle, manager.state.value)
    }

    @Test
    fun dismissBanner_untilAManualCheckOffersAgain() {
        val manager = manager()
        manager.checkIfDue()
        scope.runCurrent()
        manager.dismissBanner()
        assertTrue(manager.bannerDismissed.value)
        manager.checkNow()
        scope.runCurrent()
        assertFalse(manager.bannerDismissed.value)
    }

    @Test
    fun aSecondCheckWhileOneRuns_isIgnored() {
        val manager = manager()
        manager.checkNow()
        manager.checkNow()
        scope.runCurrent()
        assertEquals(1, source.calls)
    }

    @Test
    fun autoCheck_cleansUpDownloadsOfTheInstalledVersion() {
        manager().checkIfDue()
        scope.runCurrent()
        assertEquals(SemVer(0, 2, 1), downloads.cleanedUpFor)
    }

    @Test
    fun download_reportsProgress_thenReadyOnceVerified() {
        val manager = offered()
        downloads.progress = Triple(400L, 1_000L, true)
        assertTrue(manager.download())
        scope.runCurrent()
        val running = manager.state.value as UpdateState.Downloading
        assertEquals(400L, running.bytesDone)
        assertTrue(running.waiting)
        assertTrue(manager.isDownloading)
        // A second tap attaches to the running download.
        assertFalse(manager.download())

        downloads.result.complete(apk)
        scope.runCurrent()
        val ready = manager.state.value as UpdateState.ReadyToInstall
        assertEquals("0.3.0", ready.offer.versionName)
        assertEquals(apk, ready.apk)
    }

    @Test
    fun download_withoutAnOffer_doesNothing() {
        assertFalse(manager().download())
        assertEquals(0, downloads.calls)
    }

    @Test
    fun download_refusesAnotherSigningKey_andDeletesTheApk() {
        inspector.archive = ApkFacts("com.convoscribe", 4L, setOf("release-key"))
        val manager = offered()
        manager.download()
        downloads.result.complete(apk)
        scope.runCurrent()
        val failed = manager.state.value as UpdateState.Failed
        assertEquals(UpdateFailure.SIGNATURE_MISMATCH, failed.reason)
        assertEquals(listOf("0.3.0"), downloads.discarded)
    }

    @Test
    fun download_refusesAnotherPackageOrAnOlderVersionCode() {
        inspector.archive = ApkFacts("com.example", 4L, setOf("key"))
        offered().let {
            it.download()
            downloads.result.complete(apk)
            scope.runCurrent()
            assertEquals(UpdateFailure.WRONG_PACKAGE, (it.state.value as UpdateState.Failed).reason)
        }
        inspector.archive = ApkFacts("com.convoscribe", 3L, setOf("key"))
        downloads.result = CompletableDeferred()
        offered().let {
            it.download()
            downloads.result.complete(apk)
            scope.runCurrent()
            assertEquals(UpdateFailure.NOT_NEWER, (it.state.value as UpdateState.Failed).reason)
        }
    }

    @Test
    fun download_sha256Mismatch_andNetworkFailure_areReported_andRetryable() {
        val manager = offered()
        manager.download()
        downloads.result.completeExceptionally(Sha256MismatchException("bad"))
        scope.runCurrent()
        assertEquals(UpdateFailure.SHA256_MISMATCH, (manager.state.value as UpdateState.Failed).reason)

        downloads.result = CompletableDeferred()
        assertTrue(manager.download())
        downloads.result.completeExceptionally(ModelDownloadException("Network error", transient = true))
        scope.runCurrent()
        val failed = manager.state.value as UpdateState.Failed
        assertEquals(UpdateFailure.DOWNLOAD, failed.reason)
        assertEquals("Network error", failed.detail)
    }

    @Test
    fun cancel_deletesWhatWasFetched_andKeepsTheOffer() {
        val manager = offered()
        manager.download()
        scope.runCurrent()
        manager.cancel()
        assertTrue(manager.state.value is UpdateState.Available)
        scope.runCurrent()
        assertEquals(listOf("0.3.0"), downloads.discarded)
        // A late result of the cancelled download is dropped.
        downloads.result.complete(apk)
        scope.runCurrent()
        assertTrue(manager.state.value is UpdateState.Available)
    }

    @Test
    fun interrupt_keepsTheDownloadForARetry() {
        val manager = offered()
        manager.download()
        scope.runCurrent()
        manager.interrupt("time limit")
        val failed = manager.state.value as UpdateState.Failed
        assertEquals("time limit", failed.detail)
        scope.runCurrent()
        assertTrue(downloads.discarded.isEmpty())
    }

    @Test
    fun noCheckWhileDownloading() {
        val manager = offered()
        manager.download()
        scope.runCurrent()
        manager.checkNow()
        scope.runCurrent()
        assertEquals(1, source.calls)
        assertTrue(manager.isDownloading)
    }

    /** A manager with 0.3.0 downloaded and verified. */
    private fun ready(): UpdateManager = offered().also {
        it.download()
        downloads.result.complete(apk)
        scope.runCurrent()
        check(it.state.value is UpdateState.ReadyToInstall)
    }

    @Test
    fun install_handsTheApkToTheInstaller() {
        val manager = ready()
        assertEquals(InstallStart.STARTED, manager.install())
        assertTrue(manager.state.value is UpdateState.Installing)
        scope.runCurrent()
        assertEquals(listOf(apk), installer.installed)
    }

    @Test
    fun install_isRefusedWhileBusy_andStaysReady() {
        val manager = ready()
        busy = true
        assertEquals(InstallStart.BUSY, manager.install())
        assertTrue(manager.state.value is UpdateState.ReadyToInstall)
        scope.runCurrent()
        assertTrue(installer.installed.isEmpty())
    }

    @Test
    fun install_withNothingDownloaded_doesNothing() {
        assertEquals(InstallStart.NOTHING_TO_INSTALL, offered().install())
    }

    @Test
    fun installResult_cancelledGoesBackToReady_failureIsReported() {
        val manager = ready()
        manager.install()
        scope.runCurrent()
        manager.onInstallResult(InstallResult.CANCELLED, null)
        assertEquals(apk, (manager.state.value as UpdateState.ReadyToInstall).apk)

        manager.install()
        manager.onInstallResult(InstallResult.FAILED, "INSTALL_FAILED_INSUFFICIENT_STORAGE")
        val failed = manager.state.value as UpdateState.Failed
        assertEquals(UpdateFailure.INSTALL, failed.reason)
        assertEquals("INSTALL_FAILED_INSUFFICIENT_STORAGE", failed.detail)
    }

    @Test
    fun installResult_incompatible_isTheSignatureCase() {
        val manager = ready()
        manager.install()
        manager.onInstallResult(InstallResult.INCOMPATIBLE, "signatures do not match")
        assertEquals(UpdateFailure.SIGNATURE_MISMATCH, (manager.state.value as UpdateState.Failed).reason)
    }

    @Test
    fun install_thatCannotStartASession_isReported() {
        installer.failWith = java.io.IOException("no space")
        val manager = ready()
        manager.install()
        scope.runCurrent()
        assertEquals(UpdateFailure.INSTALL, (manager.state.value as UpdateState.Failed).reason)
    }

    @Test
    fun installResult_outsideAnInstall_isIgnored() {
        val manager = offered()
        manager.onInstallResult(InstallResult.FAILED, "late")
        assertTrue(manager.state.value is UpdateState.Available)
    }
}
