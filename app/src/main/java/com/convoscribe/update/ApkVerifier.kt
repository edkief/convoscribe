package com.convoscribe.update

/**
 * What the update check needs to know about an APK: its package, its
 * `versionCode`, the SHA-256 digests of its current signing
 * certificates ([signers]) and of every certificate in its rotation
 * history ([lineage], which includes [signers]).
 */
data class ApkFacts(
    val packageName: String,
    val versionCode: Long,
    val signers: Set<String>,
    val lineage: Set<String> = signers
)

/** Reads [ApkFacts] from a downloaded APK and from the installed app; Android's `PackageManager` in production. */
interface ApkInspector {
    /** `null` when [path] is not a readable APK. */
    fun archive(path: String): ApkFacts?

    fun installed(): ApkFacts
}

/** Why a downloaded update is refused. */
enum class UpdateRejection {
    /** Not an APK the package manager can read. */
    UNREADABLE,

    /** Another app's package. */
    WRONG_PACKAGE,

    /** Its `versionCode` is not higher than the installed one; Android refuses downgrades. */
    NOT_NEWER,

    /**
     * Signed with another key (a debug-signed install meeting a release
     * signed with the #16 key): Android cannot update in place, the user
     * has to uninstall and reinstall from the release page.
     */
    SIGNATURE_MISMATCH
}

/**
 * The checks an update must pass before it goes to the system installer
 * (issue #15): same package, higher `versionCode`, and signed by the
 * installed app's certificate (or a key it rotated to). The installer
 * would refuse the last two anyway, but with no explanation.
 */
object ApkVerifier {

    fun verify(candidate: ApkFacts?, installed: ApkFacts): UpdateRejection? = when {
        candidate == null -> UpdateRejection.UNREADABLE
        candidate.packageName != installed.packageName -> UpdateRejection.WRONG_PACKAGE
        candidate.versionCode <= installed.versionCode -> UpdateRejection.NOT_NEWER
        installed.signers.isEmpty() || candidate.signers.isEmpty() -> UpdateRejection.SIGNATURE_MISMATCH
        // The candidate's lineage must vouch for every key the installed app is signed with.
        !(candidate.lineage + candidate.signers).containsAll(installed.signers) -> UpdateRejection.SIGNATURE_MISMATCH
        else -> null
    }
}
