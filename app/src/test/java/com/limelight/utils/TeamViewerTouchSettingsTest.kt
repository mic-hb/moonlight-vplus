package com.limelight.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamViewerTouchSettingsTest {
    private val d = TeamViewerTouchSettings.defaults()

    @Test
    fun defaultsEqualTheCurrentCodeConstants() {
        // Pixel values are dp = px / 3 (spec 1.5 D10), so px at density 3 are the old constants.
        assertEquals(40f, d.px(TeamViewerTouchSettings.TAP_MOVE_DP, 3f), 1e-4f)
        assertEquals(50f, d.px(TeamViewerTouchSettings.MOVE_CONFIRM_DP, 3f), 1e-4f)
        assertEquals(10f, d.px(TeamViewerTouchSettings.DRAG_START_DP, 3f), 1e-4f)
        assertEquals(40f, d.px(TeamViewerTouchSettings.DOUBLE_TAP_AREA_DP, 3f), 1e-4f)
        assertEquals(40f, d.px(TeamViewerTouchSettings.TWO_FINGER_MOVE_DP, 3f), 1e-4f)
        assertEquals(250, d.tapTimeMs)
        assertEquals(650, d.holdDragMs)
        assertEquals(200, d.doubleTapHoldMs)
        assertEquals(100L, d.twoFingerTapMs)
        assertEquals(TouchScaling.CURSOR_ZOOM_EXPONENT, d.cursorZoomExponent, 0f)
        assertEquals(TouchScaling.SCROLL_ZOOM_EXPONENT, d.scrollZoomExponent, 0f)
        assertEquals(TouchScaling.CURSOR_SIZE_EXPONENT, d.cursorSizeExponent, 0f)
        assertEquals(CursorFollowCamera.DEFAULT_MAX_SCALE, d.maxZoom, 0f)
        assertEquals(CursorFollowCamera.DEFAULT_PINCH_GAIN, d.pinchGain, 0f)
        assertTrue(d.allowSwitch)
    }

    @Test
    fun everyDefaultIsInsideItsRange() {
        for (def in TeamViewerTouchSettings.DEFS) {
            assertTrue(def.key, def.default >= def.min && def.default <= def.max)
            assertEquals(def.key, def.default, d.get(def.key), 0f)
        }
    }

    @Test
    fun keysAreUnique() {
        val keys = TeamViewerTouchSettings.DEFS.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun badValuesAreClampedOrDefaulted() {
        for (def in TeamViewerTouchSettings.DEFS) {
            val s = TeamViewerTouchSettings.from(
                mapOf(def.key to Float.NaN)
            )
            assertEquals(def.key, def.default, s.get(def.key), 0f)
            assertEquals(def.max, TeamViewerTouchSettings.from(mapOf(def.key to 1e9f)).get(def.key), 0f)
            assertEquals(def.min, TeamViewerTouchSettings.from(mapOf(def.key to -1e9f)).get(def.key), 0f)
            // Infinity is a bad number, not a large one: it becomes the default.
            assertEquals(def.default, TeamViewerTouchSettings.from(mapOf(def.key to Float.POSITIVE_INFINITY)).get(def.key), 0f)
        }
    }

    @Test
    fun missingAndUnknownKeysGiveDefaults() {
        val s = TeamViewerTouchSettings.from(mapOf("no_such_key" to 5f, TeamViewerTouchSettings.MAX_ZOOM to null))
        assertEquals(d, s)
    }

    @Test
    fun withClampsAndIgnoresUnknownKeys() {
        assertEquals(20f, d.with(TeamViewerTouchSettings.MAX_ZOOM, 99f).maxZoom, 0f)
        assertEquals(d, d.with("no_such_key", 1f))
        assertEquals(10f, d.maxZoom, 0f) // the original is unchanged
    }

    @Test
    fun aSnapshotDoesNotChangeWhenAnotherCopyIsEdited() {
        val snapshot = d.with(TeamViewerTouchSettings.TAP_TIME_MS, 300f)
        val edited = snapshot.with(TeamViewerTouchSettings.TAP_TIME_MS, 400f)
        assertEquals(300, snapshot.tapTimeMs)
        assertEquals(400, edited.tapTimeMs)
    }

    @Test
    fun classifierConfigWithDefaultsEqualsFromTouchSlop() {
        for (slop in listOf(8f, 24f, 40.5f)) {
            assertEquals(ClassifierConfig.fromTouchSlop(slop), d.toClassifierConfig(slop))
        }
    }

    @Test
    fun classifierConfigFollowsTheValues() {
        val s = d.with(TeamViewerTouchSettings.PINCH_MIN_SLOP, 2f)
            .with(TeamViewerTouchSettings.ALLOW_SWITCH, 0f)
            .with(TeamViewerTouchSettings.SWITCH_WINDOW_MS, 300f)
        val c = s.toClassifierConfig(10f)
        assertEquals(20f, c.pinchMinPx, 1e-4f)
        assertFalse(c.allowSwitch)
        assertEquals(300L, c.switchWindowMs)
    }

    @Test
    fun cameraDefaultsMatchTheDefaultCamera() {
        val camera = CursorFollowCamera(d.maxZoom, d.pinchGain)
        val plain = CursorFollowCamera()
        assertEquals(plain.maxScale, camera.maxScale, 0f)
        assertEquals(plain.pinchGain, camera.pinchGain, 0f)
    }

    @Test
    fun pxScalesWithDensityAndSurvivesBadDensity() {
        assertEquals(80f, d.px(TeamViewerTouchSettings.TAP_MOVE_DP, 6f), 1e-3f)
        assertEquals(40f, d.px(TeamViewerTouchSettings.TAP_MOVE_DP, Float.NaN), 1e-3f)
        assertEquals(40f, d.px(TeamViewerTouchSettings.TAP_MOVE_DP, 0f), 1e-3f)
    }

    @Test
    fun resettingAValueIsDoneByTakingTheDefaultsAgain() {
        val tuned = d.with(TeamViewerTouchSettings.CURSOR_ZOOM_EXPONENT, 0.2f)
        assertEquals(d, TeamViewerTouchSettings.defaults())
        assertFalse(tuned == d)
    }

    @Test
    fun defaultsGiveNoWarnings() {
        assertTrue(d.warnings().isEmpty())
    }

    @Test
    fun warningsNameTheBadCombinations() {
        val w = d.with(TeamViewerTouchSettings.TAP_MOVE_DP, 30f).warnings()
        assertTrue(w.contains(TeamViewerTouchSettings.Warning.TAP_LARGER_THAN_MOVE))
        val w2 = d.with(TeamViewerTouchSettings.SWITCH_RATIO, 1f).warnings()
        assertTrue(w2.contains(TeamViewerTouchSettings.Warning.SWITCH_BELOW_DOMINANCE))
    }

    @Test
    fun basicSetContainsTheThreeZoomFactors() {
        val basic = TeamViewerTouchSettings.DEFS.filter { it.basic }.map { it.key }
        assertTrue(basic.containsAll(listOf(
            TeamViewerTouchSettings.CURSOR_ZOOM_EXPONENT,
            TeamViewerTouchSettings.SCROLL_ZOOM_EXPONENT,
            TeamViewerTouchSettings.CURSOR_SIZE_EXPONENT,
        )))
    }
}
