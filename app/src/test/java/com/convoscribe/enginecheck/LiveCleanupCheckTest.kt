package com.convoscribe.enginecheck

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.audio.StreamingCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The live noise cleanup check (issue #37): timing, verdict and its store. */
@RunWith(RobolectricTestRunner::class)
class LiveCleanupCheckTest {

    /** Takes 10 ms of [clock] per chunk and records the chunk sizes. */
    private class TimedCleaner(private val tick: () -> Unit) : StreamingCleaner {
        val chunks = mutableListOf<Int>()
        var closed = false
        override fun process(samples: FloatArray): FloatArray {
            chunks += samples.size
            tick()
            return samples
        }
        override fun flush() = FloatArray(0)
        override fun close() {
            closed = true
        }
    }

    @Test
    fun measureCleanup_timesRecordingSizedChunksAndCloses() {
        var now = 0L
        val cleaner = TimedCleaner { now += 10 }
        // 2 s of audio in 100 ms chunks: 20 chunks, 200 ms.
        val result = measureCleanup(cleaner, FloatArray(32_000), clock = { now })
        assertEquals(List(20) { 1_600 }, cleaner.chunks)
        assertEquals(2_000L, result.audioMs)
        assertEquals(200L, result.processMs)
        assertEquals(0.1, result.realTimeFactor, 1e-9)
        assertNull(result.error)
        assertTrue(cleaner.closed)
    }

    @Test
    fun measureCleanup_turnsAFailureIntoAnError() {
        val failing = object : StreamingCleaner {
            override fun process(samples: FloatArray): FloatArray = error("native crash")
            override fun flush() = FloatArray(0)
            override fun close() = Unit
        }
        assertEquals("native crash", measureCleanup(failing, FloatArray(1_600), clock = { 0L }).error)
    }

    @Test
    fun verdict_followsTheEngineCheckThresholds() {
        assertEquals(LiveCleanupVerdict.RECOMMENDED, LiveCleanupVerdict.of(0.49))
        assertEquals(LiveCleanupVerdict.MARGINAL, LiveCleanupVerdict.of(0.5))
        assertEquals(LiveCleanupVerdict.MARGINAL, LiveCleanupVerdict.of(0.99))
        assertEquals(LiveCleanupVerdict.NOT_RECOMMENDED, LiveCleanupVerdict.of(1.0))
    }

    @Test
    fun store_keepsOneVerdictPerEngine() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = LiveCleanupStore(context.getSharedPreferences("cleanup-${System.nanoTime()}", Context.MODE_PRIVATE))
        assertNull(store.verdict("cpu"))
        store.record("cpu", 0.3)
        store.record("npu", 1.2)
        assertEquals(LiveCleanupVerdict.RECOMMENDED, store.verdict("cpu"))
        assertEquals(LiveCleanupVerdict.NOT_RECOMMENDED, store.verdict("npu"))
        store.record("cpu", 0.7)
        assertEquals(LiveCleanupVerdict.MARGINAL, store.verdict("cpu"))
    }
}
