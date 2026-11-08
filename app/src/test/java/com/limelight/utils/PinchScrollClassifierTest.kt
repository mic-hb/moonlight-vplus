package com.limelight.utils

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

/**
 * Spec 1.2 section 8.1, tests T1 to T20. All pixel numbers are test data, not claims about a phone.
 * Fixture: touchSlopPx = 10, so pinchMinPx = scrollMinPx = 20, dominanceRatio 1.5, cap 120 ms.
 * Both fingers start on a horizontal line, 200 px apart, at y = 1000.
 */
class PinchScrollClassifierTest {

    private val startX0 = 400f
    private val startX1 = 600f
    private val startY = 1000f

    private fun newClassifier(slop: Float = 10f) =
        PinchScrollClassifier(ClassifierConfig.fromTouchSlop(slop, allowSwitch = false))

    /** Spec 1.2 batch 3c: the default config may switch after a decision. */
    private fun newSwitching(slop: Float = 10f) =
        PinchScrollClassifier(ClassifierConfig.fromTouchSlop(slop))

    private fun PinchScrollClassifier.beginAtStart(timeMs: Long = 0L) =
        begin(timeMs, startX0, startY, startX1, startY)


    /**
     * Moves from the start pose by a total of (m0x, m0y) for finger 0 and (m1x, m1y) for finger 1
     * in [steps] equal steps, 8 ms apart, starting at [t0]. Returns the last decision and records
     * every decision seen in [seen].
     */
    private fun PinchScrollClassifier.drive(
        steps: Int,
        m0x: Float, m0y: Float, m1x: Float, m1y: Float,
        t0: Long = 0L,
        seen: MutableSet<GestureDecision> = mutableSetOf(),
        fromX0: Float = startX0, fromY0: Float = startY,
        fromX1: Float = startX1, fromY1: Float = startY,
    ): GestureDecision {
        var d = decision
        for (i in 1..steps) {
            val f = i.toFloat() / steps
            d = update(
                t0 + 8L * i,
                fromX0 + m0x * f, fromY0 + m0y * f,
                fromX1 + m1x * f, fromY1 + m1y * f,
            )
            seen.add(d)
        }
        return d
    }

    @Test
    fun t1_stillFingersStayUndecided() {
        val c = newClassifier()
        c.beginAtStart()
        var t = 0L
        while (t <= 5000L) {
            assertEquals(GestureDecision.UNDECIDED, c.update(t, startX0, startY, startX1, startY))
            t += 100L
        }
        assertEquals(GestureDecision.UNDECIDED, c.decision)
    }

    @Test
    fun t2_jitterBelowMinimumsStaysUndecided() {
        val c = newClassifier()
        c.beginAtStart()
        val jitter = floatArrayOf(3f, -3f, 2f, -2f, 3f, 0f, -3f, 1f)
        var t = 0L
        for (round in 0 until 40) {
            val j = jitter[round % jitter.size]
            val k = jitter[(round + 3) % jitter.size]
            val d = c.update(t, startX0 + j, startY + k, startX1 - k, startY + j)
            assertEquals(GestureDecision.UNDECIDED, d)
            t += 50L
        }
    }

    @Test
    fun t3_parallelMoveDownIsScroll() {
        val c = newClassifier()
        c.beginAtStart()
        val seen = mutableSetOf<GestureDecision>()
        val d = c.drive(10, 0f, 60f, 0f, 60f, seen = seen)
        assertEquals(GestureDecision.SCROLL, d)
        assertFalse(seen.contains(GestureDecision.PINCH))
    }

    @Test
    fun t4_parallelMoveUpIsScroll() {
        val c = newClassifier()
        c.beginAtStart()
        val d = c.drive(10, 0f, -60f, 0f, -60f)
        assertEquals(GestureDecision.SCROLL, d)
    }

    @Test
    fun t5_spreadApartIsPinch() {
        val c = newClassifier()
        c.beginAtStart()
        val seen = mutableSetOf<GestureDecision>()
        val d = c.drive(10, -60f, 0f, 60f, 0f, seen = seen)
        assertEquals(GestureDecision.PINCH, d)
        assertFalse(seen.contains(GestureDecision.SCROLL))
    }

    @Test
    fun t6_pinchInIsPinch() {
        val c = newClassifier()
        c.beginAtStart()
        val d = c.drive(10, 60f, 0f, -60f, 0f)
        assertEquals(GestureDecision.PINCH, d)
    }

    @Test
    fun t7_diagonalAndLongAxisPinchIsPinch() {
        // Diagonal: fingers start on a 45 degree line and spread along it.
        val diag = newClassifier()
        diag.begin(0L, 450f, 950f, 550f, 1050f)
        val dd = diag.drive(
            10, -40f, -40f, 40f, 40f,
            fromX0 = 450f, fromY0 = 950f, fromX1 = 550f, fromY1 = 1050f,
        )
        assertEquals(GestureDecision.PINCH, dd)

        // Long axis of the phone: fingers on a vertical line, spreading vertically.
        val vert = newClassifier()
        vert.begin(0L, 500f, 900f, 500f, 1100f)
        val vd = vert.drive(
            10, 0f, -60f, 0f, 60f,
            fromX0 = 500f, fromY0 = 900f, fromX1 = 500f, fromY1 = 1100f,
        )
        assertEquals(GestureDecision.PINCH, vd)
    }

    @Test
    fun t8_oneFingerFixedIsTieThatGoesToScrollAtCap() {
        val c = newClassifier()
        c.beginAtStart(0L)
        // Finger 1 moves 60 px: span change 60 (pinchAmount 30), centroid travel 30. Exact tie.
        assertEquals(GestureDecision.UNDECIDED, c.update(60L, startX0, startY, startX1 + 60f, startY))
        assertEquals(GestureDecision.UNDECIDED, c.update(119L, startX0, startY, startX1 + 60f, startY))
        assertEquals(GestureDecision.SCROLL, c.update(120L, startX0, startY, startX1 + 60f, startY))
    }

    @Test
    fun t9_ratioJustUnderDominanceWaitsForCapThenLargerWins() {
        val c = newClassifier()
        c.beginAtStart(0L)
        // Each finger moves h = 30 sideways (pinchAmount 30) and v = 21 down (scrollAmount 21).
        // 30 < 1.5 * 21 = 31.5, so neither dominates.
        val x0 = startX0 - 30f
        val x1 = startX1 + 30f
        val y = startY + 21f
        assertEquals(GestureDecision.UNDECIDED, c.update(50L, x0, y, x1, y))
        assertEquals(GestureDecision.UNDECIDED, c.update(119L, x0, y, x1, y))
        assertEquals(GestureDecision.PINCH, c.update(120L, x0, y, x1, y))
    }

    @Test
    fun t10_capWithBothBelowMinimumsStaysUndecided() {
        val c = newClassifier()
        c.beginAtStart(0L)
        // pinchAmount 5 and scrollAmount 5, both under the minimum of 20.
        val x0 = startX0 - 5f
        val x1 = startX1 + 5f
        val y = startY + 5f
        assertEquals(GestureDecision.UNDECIDED, c.update(120L, x0, y, x1, y))
        assertEquals(GestureDecision.UNDECIDED, c.update(10_000L, x0, y, x1, y))
    }

    @Test
    fun t11_ratioJustOverDominanceDecidesBeforeCap() {
        val c = newClassifier()
        c.beginAtStart(0L)
        // pinchAmount 30, scrollAmount 19: 30 >= 1.5 * 19 = 28.5, minimum 20 reached.
        val x0 = startX0 - 30f
        val x1 = startX1 + 30f
        val y = startY + 19f
        assertEquals(GestureDecision.PINCH, c.update(10L, x0, y, x1, y))
    }

    @Test
    fun t12_scrollLocksAgainstLaterSpread() {
        val c = newClassifier()
        c.beginAtStart()
        assertEquals(GestureDecision.SCROLL, c.drive(10, 0f, 60f, 0f, 60f))
        // Now spread far apart. Still SCROLL.
        val d = c.drive(
            20, -300f, 0f, 300f, 0f, t0 = 100L,
            fromX0 = startX0, fromY0 = startY + 60f, fromX1 = startX1, fromY1 = startY + 60f,
        )
        assertEquals(GestureDecision.SCROLL, d)
        assertEquals(GestureDecision.SCROLL, c.decision)
    }

    @Test
    fun t13_pinchLocksAgainstLaterParallelMove() {
        val c = newClassifier()
        c.beginAtStart()
        assertEquals(GestureDecision.PINCH, c.drive(10, -60f, 0f, 60f, 0f))
        // Now both fingers travel far in parallel. Still PINCH.
        val d = c.drive(
            20, 0f, 400f, 0f, 400f, t0 = 100L,
            fromX0 = startX0 - 60f, fromY0 = startY, fromX1 = startX1 + 60f, fromY1 = startY,
        )
        assertEquals(GestureDecision.PINCH, d)
        assertEquals(GestureDecision.PINCH, c.decision)
    }

    @Test
    fun t14_resetThenBeginForgetsOldState() {
        val c = newClassifier()
        c.beginAtStart()
        assertEquals(GestureDecision.PINCH, c.drive(10, -60f, 0f, 60f, 0f))
        c.reset()
        assertEquals(GestureDecision.UNDECIDED, c.decision)

        // New gesture elsewhere on the glass, starts still. Must not inherit the old span.
        c.begin(5000L, 100f, 300f, 160f, 300f)
        assertEquals(GestureDecision.UNDECIDED, c.update(5100L, 100f, 300f, 160f, 300f))

        // And it can now decide the other way.
        val d = c.drive(
            10, 0f, 60f, 0f, 60f, t0 = 5100L,
            fromX0 = 100f, fromY0 = 300f, fromX1 = 160f, fromY1 = 300f,
        )
        assertEquals(GestureDecision.SCROLL, d)
    }

    @Test
    fun t15_updateBeforeBeginIsUndecidedAndSafe() {
        val c = newClassifier()
        assertEquals(GestureDecision.UNDECIDED, c.decision)
        assertEquals(GestureDecision.UNDECIDED, c.update(100L, 0f, 0f, 500f, 500f))
        assertEquals(GestureDecision.UNDECIDED, c.update(10_000L, 0f, 0f, 900f, 900f))
        // Also after a reset.
        c.beginAtStart()
        c.reset()
        assertEquals(GestureDecision.UNDECIDED, c.update(200L, 0f, 0f, 900f, 900f))
    }

    @Test
    fun t16_doublingTouchSlopRaisesThresholds() {
        val xa0 = startX0 - 30f
        val xa1 = startX1 + 30f

        val small = newClassifier(10f) // minimum 20, pinchAmount 30 qualifies
        small.beginAtStart()
        assertEquals(GestureDecision.PINCH, small.update(50L, xa0, startY, xa1, startY))

        val big = newClassifier(20f) // minimum 40, pinchAmount 30 does not
        big.beginAtStart()
        assertEquals(GestureDecision.UNDECIDED, big.update(50L, xa0, startY, xa1, startY))
        assertEquals(GestureDecision.UNDECIDED, big.update(5000L, xa0, startY, xa1, startY))
    }

    @Test
    fun t17_equalAndBackwardsTimestampsAreSafe() {
        val c = newClassifier()
        c.beginAtStart(1000L)
        // Tie input as in T8, with a timestamp equal to begin and one before it.
        // Neither may count as the cap having passed.
        assertEquals(GestureDecision.UNDECIDED, c.update(1000L, startX0, startY, startX1 + 60f, startY))
        assertEquals(GestureDecision.UNDECIDED, c.update(1000L, startX0, startY, startX1 + 60f, startY))
        assertEquals(GestureDecision.UNDECIDED, c.update(900L, startX0, startY, startX1 + 60f, startY))
        assertEquals(GestureDecision.UNDECIDED, c.update(Long.MIN_VALUE, startX0, startY, startX1 + 60f, startY))
        // Time moving forward again still works.
        assertEquals(GestureDecision.SCROLL, c.update(1120L, startX0, startY, startX1 + 60f, startY))
    }

    @Test
    fun t18_invalidConfigIsRejected() {
        val ok = ClassifierConfig.fromTouchSlop(10f)
        val bad = listOf<() -> Unit>(
            { ClassifierConfig.fromTouchSlop(0f) },
            { ClassifierConfig.fromTouchSlop(-1f) },
            { ClassifierConfig.fromTouchSlop(Float.NaN) },
            { ClassifierConfig.fromTouchSlop(Float.POSITIVE_INFINITY) },
            { ok.copy(pinchMinPx = 0f) },
            { ok.copy(pinchMinPx = Float.NaN) },
            { ok.copy(scrollMinPx = -5f) },
            { ok.copy(scrollMinPx = Float.NaN) },
            { ok.copy(dominanceRatio = 0.5f) },
            { ok.copy(dominanceRatio = Float.NaN) },
            { ok.copy(maxUndecidedMs = 0L) },
            { ok.copy(maxUndecidedMs = -10L) },
        )
        for ((i, block) in bad.withIndex()) {
            try {
                block()
                fail("bad config case $i was accepted")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun t19_decisionHasNoZoomInputAndIsDeterministic() {
        // The class takes raw pixels and time only, so the caller's zoom level cannot reach it.
        // The same raw trace must therefore give the same decision sequence every time.
        fun run(): List<GestureDecision> {
            val c = newClassifier()
            c.beginAtStart()
            val out = ArrayList<GestureDecision>()
            for (i in 1..12) {
                out.add(c.update(8L * i, startX0 - 5f * i, startY, startX1 + 5f * i, startY))
            }
            return out
        }
        assertEquals(run(), run())
        assertEquals(GestureDecision.PINCH, run().last())
    }

    @Test
    fun t20_noisyTracesStillClassify() {
        for (seed in 1L..20L) {
            val rnd = Random(seed)
            fun noise() = (rnd.nextFloat() - 0.5f) * 4f // +-2 px per sample

            val scroll = newClassifier()
            scroll.beginAtStart()
            var d = GestureDecision.UNDECIDED
            for (i in 1..30) {
                val travel = 4f * i
                d = scroll.update(
                    8L * i,
                    startX0 + noise(), startY + travel + noise(),
                    startX1 + noise(), startY + travel + noise(),
                )
            }
            assertEquals("scroll seed $seed", GestureDecision.SCROLL, d)

            val pinch = newClassifier()
            pinch.beginAtStart()
            d = GestureDecision.UNDECIDED
            for (i in 1..30) {
                val spread = 3f * i
                d = pinch.update(
                    8L * i,
                    startX0 - spread + noise(), startY + noise(),
                    startX1 + spread + noise(), startY + noise(),
                )
            }
            assertEquals("pinch seed $seed", GestureDecision.PINCH, d)
        }
    }

    // ---- Batch 3c: switching after a decision (spec 1.2 D8 revised, E4 seen on TeamViewer) ----

    /** Pose after a scroll of [dy] px down: fingers keep their 200 px gap. */
    private fun PinchScrollClassifier.scrollThenSpread(spread: Float, steps: Int = 10): GestureDecision {
        // Phase 1: scroll 60 px down.
        drive(10, 0f, 60f, 0f, 60f)
        // Phase 2: spread each finger by [spread] px sideways, from the scrolled pose.
        return drive(
            steps, -spread, 0f, spread, 0f, t0 = 100L,
            fromX0 = startX0, fromY0 = startY + 60f, fromX1 = startX1, fromY1 = startY + 60f,
        )
    }

    @Test
    fun t21_scrollThenSpreadSwitchesToPinch() {
        val c = newSwitching()
        c.beginAtStart()
        assertEquals(GestureDecision.SCROLL, c.drive(10, 0f, 60f, 0f, 60f))
        val d = c.drive(
            20, -80f, 0f, 80f, 0f, t0 = 100L,
            fromX0 = startX0, fromY0 = startY + 60f, fromX1 = startX1, fromY1 = startY + 60f,
        )
        assertEquals(GestureDecision.PINCH, d)
    }

    @Test
    fun t22_pinchThenParallelMoveSwitchesToScroll() {
        val c = newSwitching()
        c.beginAtStart()
        assertEquals(GestureDecision.PINCH, c.drive(10, -60f, 0f, 60f, 0f))
        val d = c.drive(
            20, 0f, 120f, 0f, 120f, t0 = 100L,
            fromX0 = startX0 - 60f, fromY0 = startY, fromX1 = startX1 + 60f, fromY1 = startY,
        )
        assertEquals(GestureDecision.SCROLL, d)
    }

    @Test
    fun t23_switchesManyTimesInOneGesture() {
        val c = newSwitching()
        c.beginAtStart()
        val seen = mutableListOf<GestureDecision>()
        var t = 0L
        var x0 = startX0; var x1 = startX1; var y = startY
        fun move(steps: Int, m0x: Float, m0y: Float, m1x: Float, m1y: Float) {
            val d = c.drive(steps, m0x, m0y, m1x, m1y, t0 = t, fromX0 = x0, fromY0 = y, fromX1 = x1, fromY1 = y)
            seen.add(d)
            t += 8L * steps + 300L // a pause longer than the window between phases
            x0 += m0x; x1 += m1x; y += m0y
        }
        move(15, 0f, 80f, 0f, 80f)      // scroll
        move(15, -50f, 0f, 50f, 0f)     // pinch
        move(15, 0f, -80f, 0f, -80f)    // scroll
        move(15, 50f, 0f, -50f, 0f)     // pinch in
        move(15, 0f, 80f, 0f, 80f)      // scroll again
        assertEquals(
            listOf(
                GestureDecision.SCROLL, GestureDecision.PINCH, GestureDecision.SCROLL,
                GestureDecision.PINCH, GestureDecision.SCROLL,
            ),
            seen,
        )
    }

    @Test
    fun t24_smallWobbleDoesNotSwitch() {
        val c = newSwitching()
        c.beginAtStart()
        assertEquals(GestureDecision.SCROLL, c.drive(10, 0f, 60f, 0f, 60f))
        // 10 px spread per finger is under the 20 px switch minimum.
        val d = c.drive(
            10, -8f, 0f, 8f, 0f, t0 = 100L,
            fromX0 = startX0, fromY0 = startY + 60f, fromX1 = startX1, fromY1 = startY + 60f,
        )
        assertEquals(GestureDecision.SCROLL, d)
    }

    @Test
    fun t25_mixedMotionWithoutDominanceKeepsTheCurrentMode() {
        val c = newSwitching()
        c.beginAtStart()
        assertEquals(GestureDecision.SCROLL, c.drive(10, 0f, 60f, 0f, 60f))
        // Spread 30 per finger while still scrolling 40 px: spread is not 2x the scroll amount.
        val d = c.drive(
            15, -30f, 40f, 30f, 40f, t0 = 100L,
            fromX0 = startX0, fromY0 = startY + 60f, fromX1 = startX1, fromY1 = startY + 60f,
        )
        assertEquals(GestureDecision.SCROLL, d)
    }

    @Test
    fun t26_slowDriftOverManyWindowsDoesNotSwitch() {
        val c = newSwitching()
        c.beginAtStart()
        assertEquals(GestureDecision.SCROLL, c.drive(10, 0f, 60f, 0f, 60f))
        // 40 px spread per finger over 2 s: far below the minimum inside any 200 ms window.
        val d = c.drive(
            250, -40f, 0f, 40f, 0f, t0 = 100L,
            fromX0 = startX0, fromY0 = startY + 60f, fromX1 = startX1, fromY1 = startY + 60f,
        )
        assertEquals(GestureDecision.SCROLL, d)
    }

    @Test
    fun t27_lockedConfigStillLocks() {
        val c = newClassifier() // allowSwitch = false
        c.beginAtStart()
        assertEquals(GestureDecision.SCROLL, c.drive(10, 0f, 60f, 0f, 60f))
        val d = c.drive(
            20, -80f, 0f, 80f, 0f, t0 = 100L,
            fromX0 = startX0, fromY0 = startY + 60f, fromX1 = startX1, fromY1 = startY + 60f,
        )
        assertEquals(GestureDecision.SCROLL, d)
    }

    @Test
    fun t28_noFlipBackAfterASwitchWhileSpreadingWithJitter() {
        val c = newSwitching()
        c.beginAtStart()
        c.drive(10, 0f, 60f, 0f, 60f)
        var last = GestureDecision.SCROLL
        var changes = 0
        var x0 = startX0
        var x1 = startX1
        var t = 100L
        for (i in 1..60) {
            x0 -= 3f
            x1 += 3f
            val jitter = if (i % 2 == 0) 2f else -2f
            val now = c.update(t, x0, startY + 60f + jitter, x1, startY + 60f - jitter)
            if (now != last) {
                changes++
                last = now
            }
            t += 8L
        }
        assertEquals(GestureDecision.PINCH, last)
        assertEquals(1, changes)
    }

    @Test
    fun t29_resetClearsSwitchHistory() {
        val c = newSwitching()
        c.beginAtStart()
        c.drive(10, 0f, 60f, 0f, 60f)
        c.reset()
        assertEquals(GestureDecision.UNDECIDED, c.decision)
        c.beginAtStart(timeMs = 5000L)
        assertEquals(GestureDecision.PINCH, c.drive(10, -60f, 0f, 60f, 0f, t0 = 5000L))
    }

    @Test
    fun t30_backwardsTimestampAfterDecisionIsSafe() {
        val c = newSwitching()
        c.beginAtStart()
        assertEquals(GestureDecision.SCROLL, c.drive(10, 0f, 60f, 0f, 60f))
        val d = c.update(1L, startX0 - 100f, startY + 60f, startX1 + 100f, startY + 60f)
        assertEquals(GestureDecision.SCROLL, d)
    }

    @Test
    fun t31_switchConfigIsValidated() {
        val ok = ClassifierConfig.fromTouchSlop(10f)
        val bad = listOf<() -> Unit>(
            { ok.copy(switchMinPx = 0f) },
            { ok.copy(switchMinPx = Float.NaN) },
            { ok.copy(switchRatio = 0.5f) },
            { ok.copy(switchRatio = Float.POSITIVE_INFINITY) },
            { ok.copy(switchWindowMs = 0L) },
        )
        for (b in bad) {
            try { b(); fail("expected IllegalArgumentException") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test
    fun t32_unusedHelperKeepsCompilerQuiet() {
        // scrollThenSpread is a convenience for manual traces. A big spread must switch.
        val c = newSwitching()
        c.beginAtStart()
        assertEquals(GestureDecision.PINCH, c.scrollThenSpread(spread = 90f, steps = 20))
    }
}
