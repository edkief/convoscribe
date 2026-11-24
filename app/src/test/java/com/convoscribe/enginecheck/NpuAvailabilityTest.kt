package com.convoscribe.enginecheck

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [NpuAvailability] over real (Robolectric) SharedPreferences. */
@RunWith(RobolectricTestRunner::class)
class NpuAvailabilityTest {

    private lateinit var availability: NpuAvailability

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("npu-test-${System.nanoTime()}", Context.MODE_PRIVATE)
        availability = NpuAvailability(prefs)
    }

    @Test
    fun freshDevice_isNeitherVerifiedNorBlocked() {
        assertFalse(availability.isVerified())
        assertNull(availability.blockedReason())
    }

    @Test
    fun verifiedThenFailed_endsBlockedAndUnverified() {
        availability.markVerified()
        assertTrue(availability.isVerified())

        availability.markFailed("exit 255")
        assertFalse(availability.isVerified())
        assertEquals("exit 255", availability.blockedReason())

        availability.markVerified()
        assertTrue(availability.isVerified())
        assertNull(availability.blockedReason())
    }

    @Test
    fun whisperState_isSeparateFromNemotrons() {
        // Issue #159: a Whisper failure on the NPU does not block Nemotron.
        availability.markVerified()
        availability.markWhisperFailed("QNN context failed")

        assertEquals("QNN context failed", availability.whisperBlockedReason())
        assertEquals(false, availability.isWhisperVerified())
        assertEquals(null, availability.blockedReason())
        assertEquals(true, availability.isVerified())
    }

    @Test
    fun whisperVerified_clearsTheFailure_andClearWhisperForgetsBoth() {
        availability.markWhisperFailed("died")
        availability.markWhisperVerified()
        assertEquals(null, availability.whisperBlockedReason())
        assertEquals(true, availability.isWhisperVerified())

        availability.clearWhisper()
        assertEquals(null, availability.whisperBlockedReason())
        assertEquals(false, availability.isWhisperVerified())
    }
}
