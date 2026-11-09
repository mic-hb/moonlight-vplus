package com.limelight.utils

/**
 * Every tunable number of the TeamViewer style touch feature (spec 1.5). Pure Kotlin, no Android
 * types, so defaults, limits and mappings run in plain JUnit.
 *
 * Values are floats keyed by name, so the store, the two screens, reset and export all work from
 * one list ([DEFS]). Yes/no values are 0 or 1. A missing, NaN or out of range value is replaced by
 * the default or clamped on read (spec 1.5 D8). Instances are immutable: a gesture takes one and
 * keeps it, so a change in the middle of a gesture applies from the next one (D6).
 *
 * Distances shown in dp are converted to pixels with the screen density at the point of use (D10).
 * The dp defaults are today's px divided by [REFERENCE_DENSITY], so the owner's phone behaves as
 * before.
 */
class TeamViewerTouchSettings private constructor(private val values: Map<String, Float>) {

    enum class Group { DECISION, ZOOM, THRESHOLDS }

    /** One tunable value. [unit] is a display hint such as "dp", "ms", "x slop" or "factor". */
    class Def(
        val key: String,
        val default: Float,
        val min: Float,
        val max: Float,
        val unit: String,
        val group: Group,
        val basic: Boolean = false,
        val isSwitch: Boolean = false,
    ) {
        fun clamp(value: Float): Float = if (value.isFinite()) value.coerceIn(min, max) else default
    }

    fun get(key: String): Float = values[key] ?: defOf(key).default

    fun value(def: Def): Float = get(def.key)

    /** A copy with one value changed (clamped). Unknown keys are ignored. */
    fun with(key: String, value: Float): TeamViewerTouchSettings {
        val def = DEFS_BY_KEY[key] ?: return this
        return TeamViewerTouchSettings(values + (key to def.clamp(value)))
    }

    /** Every value by key, for storing. */
    fun toMap(): Map<String, Float> = values

    val allowSwitch: Boolean get() = get(ALLOW_SWITCH) >= 0.5f
    val maxZoom: Float get() = get(MAX_ZOOM)
    val pinchGain: Float get() = get(PINCH_GAIN)
    val cursorZoomExponent: Float get() = get(CURSOR_ZOOM_EXPONENT)
    val scrollZoomExponent: Float get() = get(SCROLL_ZOOM_EXPONENT)
    val cursorSizeExponent: Float get() = get(CURSOR_SIZE_EXPONENT)

    val tapTimeMs: Int get() = get(TAP_TIME_MS).toInt()
    val holdDragMs: Int get() = get(HOLD_DRAG_MS).toInt()
    val doubleTapHoldMs: Int get() = get(DOUBLE_TAP_HOLD_MS).toInt()
    val twoFingerTapMs: Long get() = get(TWO_FINGER_TAP_MS).toLong()

    /** Pixels for a dp setting at [density] (pixels per dp). A bad density counts as the reference density. */
    fun px(key: String, density: Float): Float = get(key) * safeDensity(density)

    /** The classifier numbers for a phone with the given touch slop in pixels (spec 1.2). */
    fun toClassifierConfig(touchSlopPx: Float): ClassifierConfig = ClassifierConfig(
        pinchMinPx = get(PINCH_MIN_SLOP) * touchSlopPx,
        scrollMinPx = get(SCROLL_MIN_SLOP) * touchSlopPx,
        dominanceRatio = get(DOMINANCE_RATIO),
        maxUndecidedMs = get(MAX_UNDECIDED_MS).toLong(),
        allowSwitch = allowSwitch,
        switchMinPx = get(SWITCH_MIN_SLOP) * touchSlopPx,
        switchRatio = get(SWITCH_RATIO),
        switchWindowMs = get(SWITCH_WINDOW_MS).toLong(),
    )

    /** Combinations that probably cannot work well. The screens warn and still save (spec 1.5 D13). */
    enum class Warning { TAP_LARGER_THAN_MOVE, DRAG_START_LARGER_THAN_TAP, SWITCH_BELOW_DOMINANCE, PINCH_START_TOO_HIGH }

    fun warnings(): List<Warning> {
        val out = ArrayList<Warning>()
        if (get(TAP_MOVE_DP) > get(MOVE_CONFIRM_DP)) out.add(Warning.TAP_LARGER_THAN_MOVE)
        if (get(DRAG_START_DP) > get(TAP_MOVE_DP)) out.add(Warning.DRAG_START_LARGER_THAN_TAP)
        if (get(SWITCH_RATIO) < get(DOMINANCE_RATIO)) out.add(Warning.SWITCH_BELOW_DOMINANCE)
        if (get(PINCH_MIN_SLOP) > 2f) out.add(Warning.PINCH_START_TOO_HIGH)
        return out
    }

    override fun equals(other: Any?): Boolean = other is TeamViewerTouchSettings && other.values == values
    override fun hashCode(): Int = values.hashCode()

    companion object {
        /** Density (pixels per dp) of the phone the px defaults came from: 480 dpi. */
        const val REFERENCE_DENSITY = 3f

        const val ALLOW_SWITCH = "allow_switch"
        const val PINCH_MIN_SLOP = "pinch_min_slop"
        const val SCROLL_MIN_SLOP = "scroll_min_slop"
        const val DOMINANCE_RATIO = "dominance_ratio"
        const val MAX_UNDECIDED_MS = "max_undecided_ms"
        const val SWITCH_MIN_SLOP = "switch_min_slop"
        const val SWITCH_RATIO = "switch_ratio"
        const val SWITCH_WINDOW_MS = "switch_window_ms"

        const val MAX_ZOOM = "max_zoom"
        const val PINCH_GAIN = "pinch_gain"
        const val CURSOR_ZOOM_EXPONENT = "cursor_zoom_exponent"
        const val SCROLL_ZOOM_EXPONENT = "scroll_zoom_exponent"
        const val CURSOR_SIZE_EXPONENT = "cursor_size_exponent"

        const val TAP_MOVE_DP = "tap_move_dp"
        const val MOVE_CONFIRM_DP = "move_confirm_dp"
        const val TAP_TIME_MS = "tap_time_ms"
        const val HOLD_DRAG_MS = "hold_drag_ms"
        const val DRAG_START_DP = "drag_start_dp"
        const val DOUBLE_TAP_AREA_DP = "double_tap_area_dp"
        const val DOUBLE_TAP_HOLD_MS = "double_tap_hold_ms"
        const val TWO_FINGER_TAP_MS = "two_finger_tap_ms"
        const val TWO_FINGER_MOVE_DP = "two_finger_move_dp"

        private fun dp(px: Float) = px / REFERENCE_DENSITY

        /** The list that drives the store, both screens, reset and export. Order is the display order. */
        val DEFS: List<Def> = listOf(
            Def(CURSOR_ZOOM_EXPONENT, 0.8f, 0f, 1f, "factor", Group.ZOOM, basic = true),
            Def(SCROLL_ZOOM_EXPONENT, 0.6f, 0f, 1f, "factor", Group.ZOOM, basic = true),
            Def(CURSOR_SIZE_EXPONENT, 0.5f, 0f, 1f, "factor", Group.ZOOM, basic = true),
            Def(MAX_ZOOM, 10f, 2f, 20f, "x", Group.ZOOM, basic = true),
            Def(PINCH_GAIN, 1f, 0.5f, 2f, "factor", Group.ZOOM),

            Def(ALLOW_SWITCH, 1f, 0f, 1f, "", Group.DECISION, isSwitch = true),
            Def(PINCH_MIN_SLOP, 1f, 0.25f, 4f, "x slop", Group.DECISION, basic = true),
            Def(SCROLL_MIN_SLOP, 2f, 0.25f, 4f, "x slop", Group.DECISION),
            Def(DOMINANCE_RATIO, 1.5f, 1f, 4f, "ratio", Group.DECISION),
            Def(MAX_UNDECIDED_MS, 120f, 30f, 400f, "ms", Group.DECISION),
            Def(SWITCH_MIN_SLOP, 2f, 0.25f, 4f, "x slop", Group.DECISION),
            Def(SWITCH_RATIO, 2f, 1f, 5f, "ratio", Group.DECISION, basic = true),
            Def(SWITCH_WINDOW_MS, 200f, 50f, 600f, "ms", Group.DECISION),

            Def(TAP_MOVE_DP, dp(40f), 2f, 40f, "dp", Group.THRESHOLDS),
            Def(MOVE_CONFIRM_DP, dp(50f), 2f, 50f, "dp", Group.THRESHOLDS),
            Def(TAP_TIME_MS, 250f, 100f, 600f, "ms", Group.THRESHOLDS),
            Def(HOLD_DRAG_MS, 650f, 200f, 1500f, "ms", Group.THRESHOLDS),
            Def(DRAG_START_DP, dp(10f), 1f, 20f, "dp", Group.THRESHOLDS),
            Def(DOUBLE_TAP_AREA_DP, dp(40f), 2f, 40f, "dp", Group.THRESHOLDS),
            Def(DOUBLE_TAP_HOLD_MS, 200f, 50f, 800f, "ms", Group.THRESHOLDS),
            Def(TWO_FINGER_TAP_MS, 100f, 50f, 400f, "ms", Group.THRESHOLDS),
            Def(TWO_FINGER_MOVE_DP, dp(40f), 2f, 40f, "dp", Group.THRESHOLDS),
        )

        private val DEFS_BY_KEY: Map<String, Def> = DEFS.associateBy { it.key }

        fun defOf(key: String): Def = DEFS_BY_KEY[key] ?: throw IllegalArgumentException("unknown key: $key")

        fun hasKey(key: String): Boolean = DEFS_BY_KEY.containsKey(key)

        private val DEFAULTS = TeamViewerTouchSettings(DEFS.associate { it.key to it.default })

        fun defaults(): TeamViewerTouchSettings = DEFAULTS

        /**
         * Builds settings from stored entries. Unknown keys are ignored, missing ones get the
         * default, bad numbers become the default and the rest is clamped (spec 1.5 D8).
         */
        fun from(stored: Map<String, Float?>): TeamViewerTouchSettings {
            val map = DEFS.associate { def ->
                val raw = stored[def.key]
                def.key to (if (raw == null) def.default else def.clamp(raw))
            }
            return TeamViewerTouchSettings(map)
        }

        private fun safeDensity(density: Float): Float =
            if (density.isFinite() && density > 0f) density else REFERENCE_DENSITY
    }
}
