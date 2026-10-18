package com.convoscribe.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayModeTest {

    @Test
    fun onyx_and_boox_devices_are_eink() {
        assertTrue(DisplayMode.isEinkDevice(manufacturer = "ONYX", brand = "Onyx"))
        assertTrue(DisplayMode.isEinkDevice(manufacturer = "Onyx International", brand = "qcom"))
        assertTrue(DisplayMode.isEinkDevice(manufacturer = "unknown", brand = "BOOX"))
    }

    @Test
    fun other_devices_are_not_eink() {
        assertFalse(DisplayMode.isEinkDevice(manufacturer = "Google", brand = "google"))
        assertFalse(DisplayMode.isEinkDevice(manufacturer = "QUALCOMM", brand = "qti"))
        assertFalse(DisplayMode.isEinkDevice(manufacturer = "", brand = ""))
    }

    @Test
    fun auto_follows_the_device() {
        assertTrue(DisplayMode.resolvesToEink(DisplayMode.AUTO, einkDevice = true))
        assertFalse(DisplayMode.resolvesToEink(DisplayMode.AUTO, einkDevice = false))
    }

    @Test
    fun explicit_modes_win_over_the_device() {
        assertFalse(DisplayMode.resolvesToEink(DisplayMode.STANDARD, einkDevice = true))
        assertTrue(DisplayMode.resolvesToEink(DisplayMode.EINK, einkDevice = false))
    }

    @Test
    fun keys_round_trip_and_unknown_is_null() {
        DisplayMode.entries.forEach { assertEquals(it, DisplayMode.fromKey(it.key)) }
        assertNull(DisplayMode.fromKey("sepia"))
    }
}
