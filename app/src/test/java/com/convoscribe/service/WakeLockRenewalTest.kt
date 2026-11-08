package com.convoscribe.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a recording takes a fresh wake lock (issue #124). */
class WakeLockRenewalTest {

    private val start = 5_000_000L

    @Test
    fun due_withoutALock() {
        assertTrue(wakeLockRenewalDue(acquiredAtMs = null, nowMs = start))
    }

    @Test
    fun notDue_forAYoungLock() {
        assertFalse(wakeLockRenewalDue(start, start))
        assertFalse(wakeLockRenewalDue(start, start + RECORDING_WAKE_LOCK_RENEW_AFTER_MS - 1))
    }

    @Test
    fun due_onceHalfTheTimeoutHasPassed() {
        assertTrue(wakeLockRenewalDue(start, start + RECORDING_WAKE_LOCK_RENEW_AFTER_MS))
        assertTrue(wakeLockRenewalDue(start, start + RECORDING_WAKE_LOCK_TIMEOUT_MS + 1))
    }

    @Test
    fun renewal_comesWellBeforeTheTimeout() {
        assertTrue(RECORDING_WAKE_LOCK_RENEW_AFTER_MS < RECORDING_WAKE_LOCK_TIMEOUT_MS)
        assertTrue(RECORDING_WAKE_LOCK_TIMEOUT_MS - RECORDING_WAKE_LOCK_RENEW_AFTER_MS >= 10L * 60L * 1_000L)
    }
}
