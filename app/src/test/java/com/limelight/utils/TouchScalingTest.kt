package com.limelight.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 1.3 tests T10 to T12. Numbers are fixtures, not measurements of any phone. */
class TouchScalingTest {

    private val zooms = floatArrayOf(1f, 2f, 5f)

    // T10: one division only

    @Test
    fun t10_cursorDeltaIsTheNormalizedDeltaTimesSenseAtEveryZoom() {
        // A glass move of 60 px at zoom z arrives as 60 / z. The cursor must travel 60 / z
        // stream pixels, so the picture (scaled by z) moves 60 glass pixels under it.
        for (z in zooms) {
            val normalized = (600 / z).toInt()
            assertEquals(normalized * 1.5f, TouchScaling.cursorDelta(normalized, 1.5), 0.0001f)
            val onGlass = TouchScaling.cursorDelta(normalized, 1.0) * z
            assertEquals(600f, onGlass, z) // within one normalized unit of rounding
        }
    }

    @Test
    fun t10_cursorDeltaHasNoZoomInput() {
        // The signature has no zoom, so a second division is impossible. Sign and zero are kept.
        assertEquals(0f, TouchScaling.cursorDelta(0, 2.0), 0f)
        assertEquals(-7f, TouchScaling.cursorDelta(-7, 1.0), 0f)
    }

    // T11: constant remote scroll distance

    @Test
    fun t11_sameGlassTravelSendsTheSameWheelAmountAtEveryZoom() {
        val factor = 0.9
        val glass = 400
        val atFit = TouchScaling.scrollDelta(glass, factor, 1f)
        for (z in zooms) {
            val normalized = (glass / z).toInt()
            val sent = TouchScaling.scrollDelta(normalized, factor, z)
            assertEquals(atFit.toFloat(), sent.toFloat(), z) // within rounding of one normalized unit
        }
    }

    @Test
    fun t11_zoomOneMatchesTheOldFormulaIncludingNegativeHalves() {
        fun old(d: Int, f: Double): Int =
            Math.round(Math.abs(d) * f * if (d < 0) -1 else 1).toInt()
        for (d in -50..50) {
            for (f in doubleArrayOf(0.5, 0.9, 1.0, 1.25, 2.5)) {
                assertEquals("d=$d f=$f", old(d, f), TouchScaling.scrollDelta(d, f, 1f))
            }
        }
    }

    @Test
    fun t11_zoomBelowOneCountsAsOne() {
        assertEquals(TouchScaling.scrollDelta(10, 1.0, 1f), TouchScaling.scrollDelta(10, 1.0, 0.3f))
        assertEquals(TouchScaling.scrollDelta(10, 1.0, 1f), TouchScaling.scrollDelta(10, 1.0, Float.NaN))
    }

    // T12: thresholds in screen units

    @Test
    fun t12_theSameGlassDistanceClassifiesTheSameAtEveryZoom() {
        val tapLimitPx = 40f
        for (glass in intArrayOf(10, 39, 41, 100)) {
            val expected = glass <= tapLimitPx
            for (z in zooms) {
                val normalized = glass / z
                val onGlass = TouchScaling.toScreenUnits(normalized, z)
                assertEquals("glass=$glass z=$z", expected, onGlass <= tapLimitPx + 0.001f)
            }
        }
    }

    @Test
    fun t12_withoutTheConversionTheDeadZoneGrowsWithZoom() {
        // Documents the bug that was fixed on 2026-11-08: 100 glass px at zoom 5 is 20 normalized px,
        // which looked like a tap (under 40). With the conversion it is 100 and is a move.
        val normalized = 100f / 5f
        assertTrue(normalized <= 40f)
        assertTrue(TouchScaling.toScreenUnits(normalized, 5f) > 40f)
    }

    @Test
    fun t12_zoomBelowOneAndBadZoomAreSafe() {
        assertEquals(30f, TouchScaling.toScreenUnits(30f, 0.5f), 0f)
        assertEquals(30f, TouchScaling.toScreenUnits(30f, Float.NaN), 0f)
    }

    // Zoom compensation (spec 1.3 batch 5b)

    @Test
    fun compensationIsOneAtZoomOneAndForExponentZero() {
        assertEquals(1.0, TouchScaling.compensation(1f, 0.8f), 1e-9)
        assertEquals(1.0, TouchScaling.compensation(6f, 0f), 1e-9)
    }

    @Test
    fun compensationGrowsWithZoomAndExponent() {
        assertEquals(6.0, TouchScaling.compensation(6f, 1f), 1e-9)
        assertTrue(TouchScaling.compensation(6f, 0.8f) < TouchScaling.compensation(6f, 1f))
        assertTrue(TouchScaling.compensation(6f, 0.8f) > TouchScaling.compensation(3f, 0.8f))
    }

    @Test
    fun compensationHandlesBadInput() {
        assertEquals(1.0, TouchScaling.compensation(Float.NaN, 0.5f), 1e-9)
        assertEquals(1.0, TouchScaling.compensation(0.3f, 0.5f), 1e-9)
        assertEquals(1.0, TouchScaling.compensation(4f, Float.NaN), 1e-9)
        assertEquals(4.0, TouchScaling.compensation(4f, 7f), 1e-9) // exponent limited to 1
        assertEquals(1.0, TouchScaling.compensation(4f, -2f), 1e-9) // and to 0
    }

    @Test
    fun cursorCompensationExponentZeroEqualsPlainAndOneIsConstantRemoteDistance() {
        assertEquals(TouchScaling.cursorDelta(10, 1.5), TouchScaling.cursorDelta(10, 1.5, 5f, 0f), 1e-6f)
        for (z in zooms) {
            val normalized = (600 / z).toInt()
            val remote = TouchScaling.cursorDelta(normalized, 1.0, z, 1f)
            assertEquals(600f, remote, z)
        }
    }

    @Test
    fun cursorCompensationAtZoomOneChangesNothing() {
        assertEquals(TouchScaling.cursorDelta(-9, 1.2), TouchScaling.cursorDelta(-9, 1.2, 1f, 0.8f), 1e-6f)
    }

    @Test
    fun cursorContentScaleExponentZeroIsConstantSizeAndOneIsUntouched() {
        for (z in listOf(1f, 2f, 5f, 10f)) {
            assertEquals(1f / z, TouchScaling.cursorContentScale(z, 0f), 1e-5f)
            assertEquals(1f, TouchScaling.cursorContentScale(z, 1f), 1e-5f)
        }
    }

    @Test
    fun cursorContentScaleHalfGivesSquareRootGrowthOnScreen() {
        // On-screen size = zoom * contentScale = zoom^0.5.
        assertEquals(Math.sqrt(4.0).toFloat(), 4f * TouchScaling.cursorContentScale(4f, 0.5f), 1e-5f)
        assertEquals(1f, TouchScaling.cursorContentScale(1f, 0.5f), 1e-6f)
    }

    @Test
    fun cursorContentScaleIgnoresBadInput() {
        assertEquals(1f, TouchScaling.cursorContentScale(Float.NaN, 0.5f), 1e-6f)
        assertEquals(1f / 3f, TouchScaling.cursorContentScale(3f, Float.NaN), 1e-5f)
        assertEquals(1f, TouchScaling.cursorContentScale(3f, 7f), 1e-5f)
    }

    @Test
    fun scrollCompensationExponentOneIsTheFullFormulaAndSmallerSlowsIt() {
        for (d in -30..30) {
            assertEquals(TouchScaling.scrollDelta(d, 0.9, 4f), TouchScaling.scrollDelta(d, 0.9, 4f, 1f))
        }
        assertTrue(Math.abs(TouchScaling.scrollDelta(40, 0.9, 6f, 0.6f)) < Math.abs(TouchScaling.scrollDelta(40, 0.9, 6f, 1f)))
        for (d in -50..50) {
            assertEquals(TouchScaling.scrollDelta(d, 0.9, 1f), TouchScaling.scrollDelta(d, 0.9, 1f, 0.6f))
        }
    }
}
