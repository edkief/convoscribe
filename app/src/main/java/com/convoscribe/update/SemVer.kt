package com.convoscribe.update

/**
 * A `MAJOR.MINOR.PATCH[-PRERELEASE]` version, as in release tags
 * (`v0.2.1`) and `versionName` (issue #15). Build metadata (`+...`) is
 * accepted and ignored.
 *
 * Ordered by semver precedence: numerically by the three numbers, then
 * a pre-release sorts before its release (`1.0.0-rc.1 < 1.0.0`), and
 * pre-release identifiers compare numerically when both are numbers,
 * otherwise as text, with a shorter list first when one is a prefix.
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String> = emptyList()
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
            // A release outranks any of its pre-releases.
            return compareValues(other.preRelease.size, preRelease.size).coerceIn(-1, 1)
        }
        for (i in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val c = compareIdentifier(preRelease[i], other.preRelease[i])
            if (c != 0) return c
        }
        return compareValues(preRelease.size, other.preRelease.size)
    }

    override fun toString(): String =
        "$major.$minor.$patch" + if (preRelease.isEmpty()) "" else "-" + preRelease.joinToString(".")

    companion object {
        private val PATTERN = Regex("""^[vV]?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

        /** Parse `v1.2.3`, `1.2.3` or `1.2.3-rc.1`; `null` for anything else. */
        fun parse(text: String): SemVer? {
            val match = PATTERN.matchEntire(text.trim()) ?: return null
            val (major, minor, patch, pre) = match.destructured
            return SemVer(
                major = major.toIntOrNull() ?: return null,
                minor = minor.toIntOrNull() ?: return null,
                patch = patch.toIntOrNull() ?: return null,
                preRelease = if (pre.isEmpty()) emptyList() else pre.split('.')
            )
        }

        private fun compareIdentifier(a: String, b: String): Int {
            val na = a.toLongOrNull()
            val nb = b.toLongOrNull()
            return when {
                na != null && nb != null -> compareValues(na, nb)
                // Numeric identifiers have lower precedence than text ones.
                na != null -> -1
                nb != null -> 1
                else -> a.compareTo(b)
            }
        }
    }
}
