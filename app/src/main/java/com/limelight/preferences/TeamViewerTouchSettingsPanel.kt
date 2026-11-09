package com.limelight.preferences

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.limelight.R
import com.limelight.utils.AppSlider
import com.limelight.utils.TeamViewerTouchSettings
import com.limelight.utils.TeamViewerTouchSettings.Def
import kotlin.math.roundToInt

/**
 * The one list of controls for the TeamViewer style touch values (spec 1.5 SR5). The Settings page
 * screen and the in-stream sheet both show this panel, and both write to [TeamViewerTouchStore],
 * so a change on one is visible on the other (ST-07).
 *
 * @param showMasterSwitch show the main on/off switch (not in the stream, where it only applies
 *   from the next connection)
 */
@Composable
fun TeamViewerTouchSettingsPanel(
    store: TeamViewerTouchStore,
    masterOn: Boolean,
    onMasterChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var settings by remember { mutableStateOf(store.snapshot()) }
    var advancedOpen by remember { mutableStateOf(false) }
    DisposableEffect(store) {
        val listener: () -> Unit = { settings = store.snapshot() }
        store.addListener(listener)
        onDispose { store.removeListener(listener) }
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (onMasterChange != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.title_teamviewer_style_touch), style = MaterialTheme.typography.titleMedium)
                Switch(checked = masterOn, onCheckedChange = onMasterChange)
            }
        } else {
            Text(
                stringResource(R.string.tv_touch_master_note),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!masterOn) {
            Text(
                stringResource(R.string.tv_touch_master_off),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Text(stringResource(R.string.tv_touch_basic), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        for (def in TeamViewerTouchSettings.DEFS.filter { it.basic }) {
            ValueRow(def, settings, masterOn, store)
        }

        Row(
            modifier = Modifier.fillMaxWidth().clickable { advancedOpen = !advancedOpen }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.tv_touch_advanced), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(if (advancedOpen) "▲" else "▼", color = MaterialTheme.colorScheme.primary)
        }
        if (advancedOpen) {
            for (group in TeamViewerTouchSettings.Group.values()) {
                val defs = TeamViewerTouchSettings.DEFS.filter { it.group == group && !it.basic }
                if (defs.isEmpty()) continue
                Text(stringResource(groupTitle(group)), style = MaterialTheme.typography.titleSmall)
                for (def in defs) ValueRow(def, settings, masterOn, store)
            }
        }

        for (warning in settings.warnings()) {
            Text(
                stringResource(warningText(warning)),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        TextButton(onClick = { store.reset() }, enabled = true) {
            Text(stringResource(R.string.tv_touch_reset))
        }
    }
}

@Composable
private fun ValueRow(def: Def, settings: TeamViewerTouchSettings, enabled: Boolean, store: TeamViewerTouchStore) {
    val stored = settings.value(def)
    if (def.isSwitch) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(titleOf(def.key)), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(summaryOf(def.key)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = stored >= 0.5f, enabled = enabled, onCheckedChange = { store.set(def.key, if (it) 1f else 0f) })
        }
        return
    }
    // The slider shows the value being dragged and writes the store only when the finger lifts,
    // so a drag does not rebuild the touch settings on every pixel.
    var dragging by remember(def.key) { mutableFloatStateOf(Float.NaN) }
    val shown = if (dragging.isNaN()) stored else dragging
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(titleOf(def.key)), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(format(def, shown), color = MaterialTheme.colorScheme.primary)
        }
        Text(
            stringResource(summaryOf(def.key)),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        AppSlider(
            value = shown,
            onValueChange = { dragging = snap(def, it) },
            onValueChangeFinished = {
                if (!dragging.isNaN()) store.set(def.key, dragging)
                dragging = Float.NaN
            },
            valueRange = def.min..def.max,
            enabled = enabled,
        )
    }
}

/** Slider step by unit, so values stay readable (0.05 for factors, 0.5 dp, whole ms). */
private fun snap(def: Def, value: Float): Float {
    val step = when (def.unit) {
        "ms" -> 1f
        "dp" -> 0.5f
        else -> 0.05f
    }
    return ((value / step).roundToInt() * step).coerceIn(def.min, def.max)
}

private fun format(def: Def, value: Float): String = when (def.unit) {
    "ms" -> "${value.roundToInt()} ms"
    "dp" -> String.format(java.util.Locale.US, "%.1f dp", value)
    "" -> ""
    else -> String.format(java.util.Locale.US, "%.2f %s", value, def.unit).trim()
}

@StringRes
private fun groupTitle(group: TeamViewerTouchSettings.Group): Int = when (group) {
    TeamViewerTouchSettings.Group.DECISION -> R.string.tv_touch_group_decision
    TeamViewerTouchSettings.Group.ZOOM -> R.string.tv_touch_group_zoom
    TeamViewerTouchSettings.Group.THRESHOLDS -> R.string.tv_touch_group_thresholds
}

@StringRes
private fun warningText(w: TeamViewerTouchSettings.Warning): Int = when (w) {
    TeamViewerTouchSettings.Warning.TAP_LARGER_THAN_MOVE -> R.string.tv_touch_warn_tap_larger_than_move
    TeamViewerTouchSettings.Warning.DRAG_START_LARGER_THAN_TAP -> R.string.tv_touch_warn_drag_start_larger_than_tap
    TeamViewerTouchSettings.Warning.SWITCH_BELOW_DOMINANCE -> R.string.tv_touch_warn_switch_below_dominance
    TeamViewerTouchSettings.Warning.PINCH_START_TOO_HIGH -> R.string.tv_touch_warn_pinch_start_too_high
}

@StringRes
private fun titleOf(key: String): Int = when (key) {
    TeamViewerTouchSettings.CURSOR_ZOOM_EXPONENT -> R.string.tv_touch_cursor_zoom_exponent
    TeamViewerTouchSettings.SCROLL_ZOOM_EXPONENT -> R.string.tv_touch_scroll_zoom_exponent
    TeamViewerTouchSettings.CURSOR_SIZE_EXPONENT -> R.string.tv_touch_cursor_size_exponent
    TeamViewerTouchSettings.MAX_ZOOM -> R.string.tv_touch_max_zoom
    TeamViewerTouchSettings.PINCH_GAIN -> R.string.tv_touch_pinch_gain
    TeamViewerTouchSettings.ALLOW_SWITCH -> R.string.tv_touch_allow_switch
    TeamViewerTouchSettings.PINCH_MIN_SLOP -> R.string.tv_touch_pinch_min_slop
    TeamViewerTouchSettings.SCROLL_MIN_SLOP -> R.string.tv_touch_scroll_min_slop
    TeamViewerTouchSettings.DOMINANCE_RATIO -> R.string.tv_touch_dominance_ratio
    TeamViewerTouchSettings.MAX_UNDECIDED_MS -> R.string.tv_touch_max_undecided_ms
    TeamViewerTouchSettings.SWITCH_MIN_SLOP -> R.string.tv_touch_switch_min_slop
    TeamViewerTouchSettings.SWITCH_RATIO -> R.string.tv_touch_switch_ratio
    TeamViewerTouchSettings.SWITCH_WINDOW_MS -> R.string.tv_touch_switch_window_ms
    TeamViewerTouchSettings.TAP_MOVE_DP -> R.string.tv_touch_tap_move_dp
    TeamViewerTouchSettings.MOVE_CONFIRM_DP -> R.string.tv_touch_move_confirm_dp
    TeamViewerTouchSettings.TAP_TIME_MS -> R.string.tv_touch_tap_time_ms
    TeamViewerTouchSettings.HOLD_DRAG_MS -> R.string.tv_touch_hold_drag_ms
    TeamViewerTouchSettings.DRAG_START_DP -> R.string.tv_touch_drag_start_dp
    TeamViewerTouchSettings.DOUBLE_TAP_AREA_DP -> R.string.tv_touch_double_tap_area_dp
    TeamViewerTouchSettings.DOUBLE_TAP_HOLD_MS -> R.string.tv_touch_double_tap_hold_ms
    TeamViewerTouchSettings.TWO_FINGER_TAP_MS -> R.string.tv_touch_two_finger_tap_ms
    TeamViewerTouchSettings.TWO_FINGER_MOVE_DP -> R.string.tv_touch_two_finger_move_dp
    else -> throw IllegalArgumentException("no title for $key")
}

@StringRes
private fun summaryOf(key: String): Int = when (key) {
    TeamViewerTouchSettings.CURSOR_ZOOM_EXPONENT -> R.string.tv_touch_cursor_zoom_exponent_summary
    TeamViewerTouchSettings.SCROLL_ZOOM_EXPONENT -> R.string.tv_touch_scroll_zoom_exponent_summary
    TeamViewerTouchSettings.CURSOR_SIZE_EXPONENT -> R.string.tv_touch_cursor_size_exponent_summary
    TeamViewerTouchSettings.MAX_ZOOM -> R.string.tv_touch_max_zoom_summary
    TeamViewerTouchSettings.PINCH_GAIN -> R.string.tv_touch_pinch_gain_summary
    TeamViewerTouchSettings.ALLOW_SWITCH -> R.string.tv_touch_allow_switch_summary
    TeamViewerTouchSettings.PINCH_MIN_SLOP -> R.string.tv_touch_pinch_min_slop_summary
    TeamViewerTouchSettings.SCROLL_MIN_SLOP -> R.string.tv_touch_scroll_min_slop_summary
    TeamViewerTouchSettings.DOMINANCE_RATIO -> R.string.tv_touch_dominance_ratio_summary
    TeamViewerTouchSettings.MAX_UNDECIDED_MS -> R.string.tv_touch_max_undecided_ms_summary
    TeamViewerTouchSettings.SWITCH_MIN_SLOP -> R.string.tv_touch_switch_min_slop_summary
    TeamViewerTouchSettings.SWITCH_RATIO -> R.string.tv_touch_switch_ratio_summary
    TeamViewerTouchSettings.SWITCH_WINDOW_MS -> R.string.tv_touch_switch_window_ms_summary
    TeamViewerTouchSettings.TAP_MOVE_DP -> R.string.tv_touch_tap_move_dp_summary
    TeamViewerTouchSettings.MOVE_CONFIRM_DP -> R.string.tv_touch_move_confirm_dp_summary
    TeamViewerTouchSettings.TAP_TIME_MS -> R.string.tv_touch_tap_time_ms_summary
    TeamViewerTouchSettings.HOLD_DRAG_MS -> R.string.tv_touch_hold_drag_ms_summary
    TeamViewerTouchSettings.DRAG_START_DP -> R.string.tv_touch_drag_start_dp_summary
    TeamViewerTouchSettings.DOUBLE_TAP_AREA_DP -> R.string.tv_touch_double_tap_area_dp_summary
    TeamViewerTouchSettings.DOUBLE_TAP_HOLD_MS -> R.string.tv_touch_double_tap_hold_ms_summary
    TeamViewerTouchSettings.TWO_FINGER_TAP_MS -> R.string.tv_touch_two_finger_tap_ms_summary
    TeamViewerTouchSettings.TWO_FINGER_MOVE_DP -> R.string.tv_touch_two_finger_move_dp_summary
    else -> throw IllegalArgumentException("no summary for $key")
}
