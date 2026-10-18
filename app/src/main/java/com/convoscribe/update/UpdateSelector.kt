package com.convoscribe.update

/**
 * A release newer than the installed app, with the APK to fetch and,
 * when the release has one, its `<apk>.sha256` file.
 */
data class UpdateOffer(
    val version: SemVer,
    val release: Release,
    val apk: ReleaseAsset,
    val sha256: ReleaseAsset?
) {
    val versionName: String get() = version.toString()
}

/**
 * Picks the update to offer from a list of [Release]s (issue #15).
 *
 * A release qualifies when it is not a draft, is not a pre-release
 * (unless [includePrereleases]), has a tag that parses as a [SemVer]
 * greater than the installed version, and has an `*.apk` asset. The
 * asset is matched by extension, not exact name, so both
 * `convoscribe-X.Y.Z.apk` (scripts/release.sh) and the older
 * `convoscribe-0.1.0-debug.apk` count; when a release has several, one
 * without `-debug` wins. The highest qualifying version is returned.
 */
object UpdateSelector {

    fun pick(releases: List<Release>, installed: SemVer, includePrereleases: Boolean): UpdateOffer? =
        releases.asSequence()
            .filter { !it.draft && (includePrereleases || !it.prerelease) }
            .mapNotNull { release ->
                val version = SemVer.parse(release.tag) ?: return@mapNotNull null
                if (version <= installed) return@mapNotNull null
                val apk = apkAsset(release.assets) ?: return@mapNotNull null
                UpdateOffer(
                    version = version,
                    release = release,
                    apk = apk,
                    sha256 = release.assets.firstOrNull { it.name.equals(apk.name + ".sha256", ignoreCase = true) }
                )
            }
            .maxByOrNull { it.version }

    private fun apkAsset(assets: List<ReleaseAsset>): ReleaseAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        return apks.firstOrNull { !it.name.contains("-debug", ignoreCase = true) } ?: apks.firstOrNull()
    }
}
