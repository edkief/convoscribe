package com.convoscribe.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApkVerifierTest {

    private val installed = ApkFacts("com.convoscribe", 3L, setOf("debug-key"))

    @Test
    fun samePackageHigherVersionSameKey_passes() {
        assertNull(ApkVerifier.verify(ApkFacts("com.convoscribe", 4L, setOf("debug-key")), installed))
    }

    @Test
    fun unreadableApk_isRefused() {
        assertEquals(UpdateRejection.UNREADABLE, ApkVerifier.verify(null, installed))
    }

    @Test
    fun otherPackage_isRefused() {
        assertEquals(
            UpdateRejection.WRONG_PACKAGE,
            ApkVerifier.verify(ApkFacts("com.example", 4L, setOf("debug-key")), installed)
        )
    }

    @Test
    fun equalOrLowerVersionCode_isRefused() {
        assertEquals(
            UpdateRejection.NOT_NEWER,
            ApkVerifier.verify(ApkFacts("com.convoscribe", 3L, setOf("debug-key")), installed)
        )
        assertEquals(
            UpdateRejection.NOT_NEWER,
            ApkVerifier.verify(ApkFacts("com.convoscribe", 2L, setOf("debug-key")), installed)
        )
    }

    @Test
    fun anotherKey_isRefused() {
        assertEquals(
            UpdateRejection.SIGNATURE_MISMATCH,
            ApkVerifier.verify(ApkFacts("com.convoscribe", 4L, setOf("release-key")), installed)
        )
    }

    @Test
    fun noSigners_isRefused() {
        assertEquals(
            UpdateRejection.SIGNATURE_MISMATCH,
            ApkVerifier.verify(ApkFacts("com.convoscribe", 4L, emptySet()), installed)
        )
    }

    @Test
    fun aKeyRotatedFromTheInstalledKey_passes() {
        val rotated = ApkFacts(
            "com.convoscribe",
            4L,
            signers = setOf("release-key"),
            lineage = setOf("debug-key", "release-key")
        )
        assertNull(ApkVerifier.verify(rotated, installed))
    }
}
