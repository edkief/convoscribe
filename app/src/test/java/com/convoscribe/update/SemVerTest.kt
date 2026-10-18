package com.convoscribe.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {

    private fun v(text: String) = requireNotNull(SemVer.parse(text)) { text }

    @Test
    fun parse_acceptsTagsAndVersionNames() {
        assertEquals(SemVer(0, 2, 1), v("v0.2.1"))
        assertEquals(SemVer(0, 2, 1), v("0.2.1"))
        assertEquals(SemVer(1, 0, 0, listOf("rc", "1")), v("v1.0.0-rc.1"))
        assertEquals(SemVer(1, 0, 0), v("1.0.0+build.5"))
    }

    @Test
    fun parse_rejectsOtherShapes() {
        assertNull(SemVer.parse("0.2"))
        assertNull(SemVer.parse("release-1"))
        assertNull(SemVer.parse("v0.2.1.4"))
        assertNull(SemVer.parse(""))
    }

    @Test
    fun ordersNumericallyNotAsText() {
        assertTrue(v("0.10.0") > v("0.9.9"))
        assertTrue(v("1.0.0") > v("0.99.99"))
        assertTrue(v("0.2.10") > v("0.2.2"))
        assertEquals(0, v("v0.2.1").compareTo(v("0.2.1")))
    }

    @Test
    fun preReleaseSortsBeforeItsRelease() {
        assertTrue(v("1.0.0-rc.1") < v("1.0.0"))
        assertTrue(v("1.0.0-rc.1") > v("0.9.0"))
        assertTrue(v("1.0.0-alpha") < v("1.0.0-alpha.1"))
        assertTrue(v("1.0.0-alpha.1") < v("1.0.0-beta"))
        assertTrue(v("1.0.0-rc.2") < v("1.0.0-rc.10"))
        assertTrue(v("1.0.0-1") < v("1.0.0-alpha"))
    }

    @Test
    fun toString_roundTrips() {
        assertEquals("0.2.1", v("v0.2.1").toString())
        assertEquals("1.0.0-rc.1", v("1.0.0-rc.1").toString())
    }
}
