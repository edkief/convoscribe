package com.convoscribe.update

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [UpdateManager]'s check: throttle, auto switch, busy guard, skip and the manual check. */
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

    private val source = FakeSource(listOf(release("v0.3.0"), release("v0.2.1")))
    private val store = MemoryStore()
    private var now = 10L * UpdateManager.CHECK_INTERVAL_MS
    private var auto = true
    private var busy = false
    private var installed = InstalledApp("0.2.1", 3L)

    private fun manager() = UpdateManager(
        source = source,
        store = store,
        installed = { installed },
        autoCheck = { auto },
        includePrereleases = { false },
        isBusy = { busy },
        scope = scope,
        clock = { now }
    )

    private fun offeredVersion(manager: UpdateManager): String? =
        (manager.state.value as? UpdateState.Available)?.offer?.versionName

    @Test
    fun startCheck_offersANewerRelease_andRecordsTheCheck() {
        val manager = manager()
        manager.checkOnStart()
        assertEquals(UpdateState.Checking(manual = false), manager.state.value)
        scope.runCurrent()
        assertEquals("0.3.0", offeredVersion(manager))
        assertEquals(now, store.lastCheckAtMs)
    }

    @Test
    fun startCheck_equalVersion_staysIdle() {
        installed = InstalledApp("0.3.0", 4L)
        val manager = manager()
        manager.checkOnStart()
        scope.runCurrent()
        assertEquals(UpdateState.Idle, manager.state.value)
    }

    @Test
    fun startCheck_isThrottledTo24Hours() {
        store.lastCheckAtMs = now - UpdateManager.CHECK_INTERVAL_MS + 1
        manager().checkOnStart()
        scope.runCurrent()
        assertEquals(0, source.calls)

        store.lastCheckAtMs = now - UpdateManager.CHECK_INTERVAL_MS
        manager().checkOnStart()
        scope.runCurrent()
        assertEquals(1, source.calls)
    }

    @Test
    fun startCheck_runsWhenTheClockWentBackPastTheLastCheck() {
        store.lastCheckAtMs = now + 1_000L
        manager().checkOnStart()
        scope.runCurrent()
        assertEquals(1, source.calls)
    }

    @Test
    fun startCheck_onlyOncePerProcess() {
        val manager = manager()
        manager.checkOnStart()
        scope.runCurrent()
        store.lastCheckAtMs = 0L
        manager.checkOnStart()
        scope.runCurrent()
        assertEquals(1, source.calls)
    }

    @Test
    fun startCheck_skippedWhenAutoCheckIsOff() {
        auto = false
        val manager = manager()
        manager.checkOnStart()
        scope.runCurrent()
        assertEquals(0, source.calls)
        assertEquals(UpdateState.Idle, manager.state.value)
    }

    @Test
    fun startCheck_skippedWhileBusy_andRunsOnALaterStart() {
        busy = true
        val manager = manager()
        manager.checkOnStart()
        scope.runCurrent()
        assertEquals(0, source.calls)

        busy = false
        manager.checkOnStart()
        scope.runCurrent()
        assertEquals(1, source.calls)
    }

    @Test
    fun startCheck_failureIsSilent_andNotRecorded() {
        source.releases = null
        val manager = manager()
        manager.checkOnStart()
        scope.runCurrent()
        assertEquals(UpdateState.Idle, manager.state.value)
        assertEquals(0L, store.lastCheckAtMs)
    }

    @Test
    fun skippedVersion_isNotOfferedOnStart_butANewerOneIs() {
        store.skippedVersion = "0.3.0"
        manager().let {
            it.checkOnStart()
            scope.runCurrent()
            assertEquals(UpdateState.Idle, it.state.value)
        }

        source.releases = listOf(release("v0.4.0"), release("v0.3.0"))
        store.lastCheckAtMs = 0L
        manager().let {
            it.checkOnStart()
            scope.runCurrent()
            assertEquals("0.4.0", offeredVersion(it))
        }
    }

    @Test
    fun skip_recordsTheVersionAndHidesTheOffer() {
        val manager = manager()
        manager.checkOnStart()
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
        manager.checkOnStart()
        scope.runCurrent()
        assertEquals(UpdateState.Idle, manager.state.value)
    }

    @Test
    fun dismissBanner_untilAManualCheckOffersAgain() {
        val manager = manager()
        manager.checkOnStart()
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
}
