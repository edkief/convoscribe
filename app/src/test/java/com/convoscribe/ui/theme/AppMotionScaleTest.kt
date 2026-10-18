package com.convoscribe.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class AppMotionScaleTest {

    @Test
    fun standard_followsTheSystemScale() {
        var system = 1f
        val scale = AppMotionScale { system }
        assertEquals(1f, scale.scaleFactor, 0f)
        system = 0.5f
        assertEquals(0.5f, scale.scaleFactor, 0f)
    }

    @Test
    fun eink_turnsAnimationsOff_andBackOn() {
        val scale = AppMotionScale { 1f }
        scale.eink = true
        assertEquals(0f, scale.scaleFactor, 0f)
        scale.eink = false
        assertEquals(1f, scale.scaleFactor, 0f)
    }
}
