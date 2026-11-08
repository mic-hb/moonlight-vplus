package com.limelight.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 1.3 tests T1, T2 and T13. Numbers are test fixtures, not measurements of any phone.
 */
class VirtualCursorModelTest {

    private val delta = 0.0001f

    // T1: start, move, clamp

    @Test
    fun startsAtAreaCenter() {
        val m = VirtualCursorModel(1000, 600)
        assertEquals(500f, m.x, delta)
        assertEquals(300f, m.y, delta)
    }

    @Test
    fun moveByAddsDeltasAndKeepsFractions() {
        val m = VirtualCursorModel(1000, 600)
        m.moveBy(10.25f, -3.5f)
        m.moveBy(0.5f, 0.5f)
        assertEquals(510.75f, m.x, delta)
        assertEquals(297f, m.y, delta)
    }

    @Test
    fun clampsAtZeroAndLastPixel() {
        val m = VirtualCursorModel(1000, 600)
        m.moveBy(-5000f, -5000f)
        assertEquals(0f, m.x, delta)
        assertEquals(0f, m.y, delta)
        m.moveBy(99999f, 99999f)
        assertEquals(999f, m.x, delta)
        assertEquals(599f, m.y, delta)
    }

    @Test
    fun nonFiniteDeltaIsIgnored() {
        val m = VirtualCursorModel(1000, 600)
        m.moveBy(Float.NaN, Float.POSITIVE_INFINITY)
        assertEquals(500f, m.x, delta)
        assertEquals(300f, m.y, delta)
    }

    @Test
    fun resetReturnsToCenter() {
        val m = VirtualCursorModel(1000, 600)
        m.moveBy(123f, 45f)
        m.reset()
        assertEquals(500f, m.x, delta)
        assertEquals(300f, m.y, delta)
    }

    @Test
    fun tinyAreaIsTreatedAsOnePixel() {
        val m = VirtualCursorModel(0, -5)
        assertEquals(1, m.areaWidth)
        assertEquals(1, m.areaHeight)
        m.moveBy(10f, 10f)
        assertEquals(0, m.absoluteX())
        assertEquals(0, m.absoluteY())
    }

    // T2: setArea keeps the fractional position

    @Test
    fun setAreaKeepsFractionalPosition() {
        val m = VirtualCursorModel(1001, 601)
        m.moveBy(-1000f, 1000f) // clamped to x = 0, y = 600 (last pixel)
        m.setArea(2001, 1201)
        assertEquals(0f, m.x, delta)
        assertEquals(1200f, m.y, delta)
        m.reset() // center of 2001 x 1201 is 1000.5, 600.5
        m.setArea(101, 101)
        assertEquals(1000.5f * 100f / 2000f, m.x, delta)
        assertEquals(600.5f * 100f / 1200f, m.y, delta)
    }

    @Test
    fun setAreaWithSameSizeChangesNothing() {
        val m = VirtualCursorModel(1000, 600)
        m.moveBy(12.5f, -7.25f)
        m.setArea(1000, 600)
        assertEquals(512.5f, m.x, delta)
        assertEquals(292.75f, m.y, delta)
    }

    @Test
    fun setAreaFromSinglePixelCentersThePosition() {
        val m = VirtualCursorModel(1, 1)
        m.setArea(1000, 600)
        assertEquals(500f, m.x, delta)
        assertEquals(300f, m.y, delta)
    }

    @Test
    fun setAreaKeepsPositionInsideNewBounds() {
        val m = VirtualCursorModel(1000, 600)
        m.moveBy(10000f, 10000f)
        m.setArea(200, 100)
        assertTrue(m.x in 0f..199f)
        assertTrue(m.y in 0f..99f)
    }

    // T13: values that go into the absolute packet

    @Test
    fun absoluteValuesAreRoundedAndInRange() {
        val m = VirtualCursorModel(1000, 600)
        m.moveBy(0.4f, 0.6f) // 500.4, 300.6
        assertEquals(500, m.absoluteX())
        assertEquals(301, m.absoluteY())
        m.moveBy(5000f, 5000f)
        assertEquals(999, m.absoluteX())
        assertEquals(599, m.absoluteY())
        m.moveBy(-9999f, -9999f)
        assertEquals(0, m.absoluteX())
        assertEquals(0, m.absoluteY())
    }

    @Test
    fun areaIsCappedSoPacketValuesFitInAShort() {
        val m = VirtualCursorModel(100000, 100000)
        assertEquals(Short.MAX_VALUE.toInt(), m.areaWidth)
        assertEquals(Short.MAX_VALUE.toInt(), m.areaHeight)
        m.moveBy(1e9f, 1e9f)
        assertTrue(m.absoluteX() <= Short.MAX_VALUE.toInt())
        assertTrue(m.absoluteY() <= Short.MAX_VALUE.toInt())
    }

    @Test
    fun areaAtExactlyShortMaxIsAccepted() {
        val m = VirtualCursorModel(Short.MAX_VALUE.toInt(), 1)
        assertEquals(Short.MAX_VALUE.toInt(), m.areaWidth)
    }

    @Test
    fun noDriftAfterManySmallMoves() {
        val m = VirtualCursorModel(2000, 1000)
        repeat(1000) { m.moveBy(0.1f, 0f) }
        // Float accumulation, not exact, but it must stay close to the 100 px of real travel.
        assertEquals(1100f, m.x, 0.5f)
    }
}
