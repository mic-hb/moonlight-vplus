package com.limelight.gamemenu

import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.limelight.Game
import com.limelight.R
import com.limelight.preferences.TeamViewerTouchSettingsPanel
import com.limelight.preferences.TeamViewerTouchStore
import com.limelight.utils.appAccentColor

/**
 * In-stream tuning sheet (spec 1.5 ST-06, Q6). A bottom sheet that does not dim the stream and
 * passes touches outside of it through, so a gesture can be tried right after a slider change.
 * It shows the same panel as the Settings page, so both edit the same values (ST-07).
 */
object TeamViewerTouchTuningSheet {
    fun show(game: Game) {
        val store = TeamViewerTouchStore.get(game)
        val composeView = ComposeView(game).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        }
        val dialog = ComponentDialog(game)
        composeView.setContent {
            val darkTheme = isSystemInDarkTheme()
            val base = if (darkTheme) darkColorScheme() else lightColorScheme()
            val colors = base.copy(
                primary = appAccentColor(),
                onPrimary = Color.White,
                background = colorResource(R.color.game_menu_dialog_background),
                onBackground = colorResource(R.color.game_menu_text_primary),
                surface = colorResource(R.color.game_menu_card_background),
                onSurface = colorResource(R.color.game_menu_text_primary),
                onSurfaceVariant = colorResource(R.color.game_menu_text_secondary),
            )
            MaterialTheme(colorScheme = colors) {
                Surface(color = colors.background) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.tv_touch_settings_title),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { dialog.dismiss() }) {
                                Text(stringResource(R.string.tv_touch_close))
                            }
                        }
                        TeamViewerTouchSettingsPanel(
                            store = store,
                            masterOn = game.prefConfig.teamViewerStyleTouch,
                            onMasterChange = null,
                        )
                    }
                }
            }
        }
        dialog.setContentView(composeView)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (game.resources.displayMetrics.heightPixels * 0.5f).toInt())
            setGravity(Gravity.BOTTOM)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // Touches outside the sheet reach the stream, so a gesture can be tried at once.
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        }
        dialog.show()
    }
}
