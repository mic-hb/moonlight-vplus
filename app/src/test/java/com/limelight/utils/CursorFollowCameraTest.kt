package com.limelight.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Spec 1.3 tests T3 to T9. All numbers are test fixtures, not measurements of any phone.
 * Fixture: stream area 1000 x 600 view pixels, viewport 1080 x 2000, so the picture is not
 * larger than the viewport at scale 1.
 */
class CursorFollowCameraTest {

    private val delta = 0.001f
    private val areaW = 1000f
    private val areaH = 600f
    private val viewW = 1080f
    private val viewH = 2000f
    private val baseX = 40f
    private val baseY = 700f

    private fun CursorFollowCamera.at(
        cx: Float, cy: Float, s: Float,
        aw: Float = areaW, ah: Float = areaH, vw: Float = viewW, vh: Float = viewH,
    ) = place(cx, cy, s, aw, ah, vw, vh, baseX, baseY)

    // T3: scale 1.0 equals the base placement

    @Test
    fun t3_scaleOneIsExactlyTheBasePlacement() {
        val c = CursorFollowCamera()
        c.at(500f, 300f, 1f)
        assertEquals(baseX, c.offsetX, 0f)
        assertEquals(baseY, c.offsetY, 0f)
        c.at(0f, 599f, 1f)
        assertEquals(baseX, c.offsetX, 0f)
        assertEquals(baseY, c.offsetY, 0f)
    }

    @Test
    fun t3_scaleBelowOneAndBadScaleUseTheBasePlacement() {
        val c = CursorFollowCamera()
        c.at(500f, 300f, 0.5f)
        assertEquals(baseX, c.offsetX, 0f)
        c.at(500f, 300f, Float.NaN)
        assertEquals(baseX, c.offsetX, 0f)
        assertEquals(baseY, c.offsetY, 0f)
    }

    // T4: cursor goes to the viewport center when nothing clamps

    @Test
    fun t4_cursorIsAtTheViewportCenterWhenNotClamped() {
        val c = CursorFollowCamera()
        // Area 2000 x 2000, viewport 1000 x 1000, scale 2: scaled 4000 x 4000, cursor in the middle.
        c.place(1000f, 1000f, 2f, 2000f, 2000f, 1000f, 1000f, 0f, 0f)
        // Cursor on screen: offset + cursor * s must be the viewport center.
        assertEquals(500f, c.offsetX + 1000f * 2f, delta)
        assertEquals(500f, c.offsetY + 1000f * 2f, delta)
    }

    // T5: clamp at the four edges

    @Test
    fun t5_clampsNearEachEdgeWithNoEmptySpace() {
        val c = CursorFollowCamera()
        val aw = 2000f
        val ah = 2000f
        val vw = 1000f
        val vh = 1000f
        val s = 2f // scaled 4000
        c.place(0f, 0f, s, aw, ah, vw, vh, 0f, 0f)
        assertEquals(0f, c.offsetX, delta) // left edge of the picture at the left of the viewport
        assertEquals(0f, c.offsetY, delta)
        c.place(1999f, 1999f, s, aw, ah, vw, vh, 0f, 0f)
        assertEquals(vw - aw * s, c.offsetX, delta) // right edge of the picture at the right
        assertEquals(vh - ah * s, c.offsetY, delta)
        c.place(0f, 1999f, s, aw, ah, vw, vh, 0f, 0f)
        assertEquals(0f, c.offsetX, delta)
        assertEquals(vh - ah * s, c.offsetY, delta)
        c.place(1999f, 0f, s, aw, ah, vw, vh, 0f, 0f)
        assertEquals(vw - aw * s, c.offsetX, delta)
        assertEquals(0f, c.offsetY, delta)
    }

    // T6: one axis smaller than the viewport

    @Test
    fun t6_smallAxisIsCenteredTheOtherFollowsTheCursor() {
        val c = CursorFollowCamera()
        // Scale 2: scaled 2000 x 1200 inside a 1080 x 2000 viewport.
        c.at(500f, 300f, 2f)
        // y: 1200 <= 2000, centered.
        assertEquals((viewH - areaH * 2f) / 2f, c.offsetY, delta)
        // x: 2000 > 1080, follows the cursor and is clamped to the range.
        val wanted = viewW / 2f - 500f * 2f
        assertEquals(wanted.coerceIn(viewW - areaW * 2f, 0f), c.offsetX, delta)
        // The cursor moves: y stays centered, x follows.
        val yBefore = c.offsetY
        c.at(900f, 100f, 2f)
        assertEquals(yBefore, c.offsetY, delta)
        assertEquals(viewW - areaW * 2f, c.offsetX, delta) // clamped at the right edge
    }

    // T7: a pinch sequence changes nothing but the scale

    @Test
    fun t7_pinchSequenceLeavesTheCursorAloneAndEndsAtTheBasePlacement() {
        val model = VirtualCursorModel(1000, 600)
        model.moveBy(123f, -45f)
        val x = model.x
        val y = model.y
        val c = CursorFollowCamera()
        var s = 1f
        // Zoom in to about 4, then out again to exactly 1.
        repeat(20) {
            s = c.nextScale(s, 1.07f)
            c.at(model.x, model.y, s)
        }
        assertTrue(s > 3f)
        repeat(40) {
            s = c.nextScale(s, 0.93f)
            c.at(model.x, model.y, s)
        }
        assertEquals(1f, s, 0f)
        assertEquals(x, model.x, 0f)
        assertEquals(y, model.y, 0f)
        assertEquals(baseX, c.offsetX, 0f)
        assertEquals(baseY, c.offsetY, 0f)
    }

    // T8: scale clamp

    @Test
    fun t8_scaleIsClampedBelowOneAndAboveTheMax() {
        val c = CursorFollowCamera(maxScale = 10f)
        assertEquals(1f, c.nextScale(1f, 0.1f), 0f)
        assertEquals(10f, c.nextScale(9f, 5f), 0f)
        assertEquals(10f, c.nextScale(10f, 1.5f), 0f)
        val low = CursorFollowCamera(maxScale = 3f)
        assertEquals(3f, low.nextScale(2f, 5f), 0f)
        assertEquals(3f, low.nextScale(10f, 1f), 0f) // an old larger scale is pulled down
    }

    @Test
    fun t8_badDetectorFactorChangesNothing() {
        val c = CursorFollowCamera()
        assertEquals(2f, c.nextScale(2f, Float.NaN), 0f)
        assertEquals(2f, c.nextScale(2f, 0f), 0f)
        assertEquals(2f, c.nextScale(2f, -1f), 0f)
        assertEquals(2f, c.nextScale(2f, Float.POSITIVE_INFINITY), 0f)
    }

    // T9: pinch gain

    @Test
    fun t9_gainOneIsTheDetectorFactorAndOtherGainsApplyThePower() {
        assertEquals(2.2f, CursorFollowCamera(pinchGain = 1f).nextScale(2f, 1.1f), 0.0001f)
        assertEquals(2f * 1.21f, CursorFollowCamera(pinchGain = 2f).nextScale(2f, 1.1f), 0.0001f)
        val half = CursorFollowCamera(pinchGain = 0.5f).nextScale(4f, 4f)
        assertEquals(8f, half, 0.0001f) // 4 * sqrt(4)
    }

    @Test
    fun invalidConfigIsRejected() {
        val bad = listOf<() -> Unit>(
            { CursorFollowCamera(maxScale = 0.5f) },
            { CursorFollowCamera(maxScale = Float.NaN) },
            { CursorFollowCamera(pinchGain = 0f) },
            { CursorFollowCamera(pinchGain = -1f) },
            { CursorFollowCamera(pinchGain = Float.POSITIVE_INFINITY) },
        )
        for (b in bad) {
            try {
                b()
                fail("expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
