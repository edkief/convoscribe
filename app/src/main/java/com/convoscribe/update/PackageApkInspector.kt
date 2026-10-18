package com.convoscribe.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import java.security.MessageDigest

/**
 * [ApkInspector] over `PackageManager`: `getPackageArchiveInfo` for a
 * downloaded APK, `getPackageInfo` for the installed app, both with
 * their signing certificates hashed with SHA-256.
 */
class PackageApkInspector(private val context: Context) : ApkInspector {

    override fun archive(path: String): ApkFacts? =
        runCatching { context.packageManager.getPackageArchiveInfo(path, FLAGS) }.getOrNull()?.toFacts()

    override fun installed(): ApkFacts = context.packageManager.getPackageInfo(context.packageName, FLAGS).toFacts()

    @Suppress("DEPRECATION")
    private fun PackageInfo.toFacts(): ApkFacts {
        val info = signingInfo
        val (signers, lineage) = when {
            info == null -> signatures.orEmpty().toList().let { it to it }
            info.hasMultipleSigners() -> info.apkContentsSigners.orEmpty().toList().let { it to it }
            else -> {
                // Oldest first, the current certificate last.
                val history = info.signingCertificateHistory.orEmpty().toList()
                listOfNotNull(history.lastOrNull()) to history
            }
        }
        return ApkFacts(
            packageName = packageName,
            versionCode = longVersionCode,
            signers = signers.map(::digest).toSet(),
            lineage = lineage.map(::digest).toSet()
        )
    }

    private fun digest(signature: Signature): String =
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        // GET_SIGNATURES as well: some releases leave signingInfo null for an archive.
        @Suppress("DEPRECATION")
        const val FLAGS = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
    }
}
