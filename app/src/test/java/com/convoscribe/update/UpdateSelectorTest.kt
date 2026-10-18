package com.convoscribe.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [UpdateSelector] over release shapes like the real v0.1.0 (pre-release, `-debug` APK) and v0.2.1. */
class UpdateSelectorTest {

    private fun asset(name: String) = ReleaseAsset(name, "https://example.test/$name", 1_000L)

    private fun release(
        tag: String,
        vararg assets: String = arrayOf("convoscribe-${tag.removePrefix("v")}.apk"),
        prerelease: Boolean = false,
        draft: Boolean = false
    ) = Release(
        tag = tag,
        name = "ConvoScribe ${tag.removePrefix("v")}",
        notes = "notes for $tag",
        htmlUrl = "https://github.com/edkief/convoscribe/releases/tag/$tag",
        prerelease = prerelease,
        draft = draft,
        assets = assets.map(::asset)
    )

    private val v010 = release("v0.1.0", "convoscribe-0.1.0-debug.apk", prerelease = true)
    private val v021 = release("v0.2.1", "convoscribe-0.2.1.apk", "convoscribe-0.2.1.apk.sha256")

    private fun installed(text: String) = requireNotNull(SemVer.parse(text))

    @Test
    fun newerRelease_isOffered_withItsSha256() {
        val offer = UpdateSelector.pick(listOf(v021, v010), installed("0.1.0"), includePrereleases = false)
        assertEquals("0.2.1", offer?.versionName)
        assertEquals("convoscribe-0.2.1.apk", offer?.apk?.name)
        assertEquals("convoscribe-0.2.1.apk.sha256", offer?.sha256?.name)
    }

    @Test
    fun equalOrOlderRelease_isNotOffered() {
        assertNull(UpdateSelector.pick(listOf(v021, v010), installed("0.2.1"), includePrereleases = true))
        assertNull(UpdateSelector.pick(listOf(v021, v010), installed("0.3.0"), includePrereleases = true))
    }

    @Test
    fun preRelease_isIgnoredUnlessIncluded() {
        val rc = release("v0.3.0-rc.1", prerelease = true)
        val list = listOf(rc, v021)
        assertEquals("0.2.1", UpdateSelector.pick(list, installed("0.2.0"), includePrereleases = false)?.versionName)
        assertEquals(
            "0.3.0-rc.1",
            UpdateSelector.pick(list, installed("0.2.0"), includePrereleases = true)?.versionName
        )
    }

    @Test
    fun debugApkOfOldPreRelease_isAcceptedWhenIncluded() {
        val offer = UpdateSelector.pick(listOf(v010), installed("0.0.9"), includePrereleases = true)
        assertEquals("convoscribe-0.1.0-debug.apk", offer?.apk?.name)
        assertNull(offer?.sha256)
    }

    @Test
    fun releaseWithoutApk_isIgnored() {
        val noApk = release("v0.4.0", "notes.txt")
        val offer = UpdateSelector.pick(listOf(noApk, v021), installed("0.2.0"), includePrereleases = false)
        assertEquals("0.2.1", offer?.versionName)
    }

    @Test
    fun draftsAndUnparseableTags_areIgnored() {
        val draft = release("v0.5.0", draft = true)
        val odd = release("nightly")
        assertNull(UpdateSelector.pick(listOf(draft, odd), installed("0.2.1"), includePrereleases = true))
    }

    @Test
    fun highestVersionWins_whateverTheListOrder() {
        val v030 = release("v0.3.0")
        val v0210 = release("v0.2.10")
        val offer = UpdateSelector.pick(listOf(v0210, v021, v030), installed("0.2.1"), includePrereleases = false)
        assertEquals("0.3.0", offer?.versionName)
    }

    @Test
    fun releaseApk_isPreferredOverDebugApk() {
        val both = release("v0.3.0", "convoscribe-0.3.0-debug.apk", "convoscribe-0.3.0.apk")
        assertEquals(
            "convoscribe-0.3.0.apk",
            UpdateSelector.pick(listOf(both), installed("0.2.1"), includePrereleases = false)?.apk?.name
        )
    }
}
