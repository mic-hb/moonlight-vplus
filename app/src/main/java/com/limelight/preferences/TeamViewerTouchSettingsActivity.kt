package com.limelight.preferences

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.preference.PreferenceManager
import com.limelight.R
import com.limelight.ui.ThemedAppCompatActivity
import com.limelight.utils.UiHelper
import com.limelight.utils.appAccentColor

/** Settings page entry for the TeamViewer style touch values (spec 1.5 ST-05). */
class TeamViewerTouchSettingsActivity : ThemedAppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiHelper.setLocale(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val store = TeamViewerTouchStore.get(this)

        setContent {
            val darkTheme = isSystemInDarkTheme()
            val baseColors = if (darkTheme) darkColorScheme() else lightColorScheme()
            val colors = baseColors.copy(
                primary = appAccentColor(),
                onPrimary = Color.White,
                background = colorResource(R.color.game_menu_dialog_background),
                onBackground = colorResource(R.color.game_menu_text_primary),
                surface = colorResource(R.color.game_menu_card_background),
                onSurface = colorResource(R.color.game_menu_text_primary),
                onSurfaceVariant = colorResource(R.color.game_menu_text_secondary),
            )
            var masterOn by remember { mutableStateOf(prefs.getBoolean(MASTER_KEY, true)) }
            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize(), color = colors.background) {
                    Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .widthIn(max = 620.dp)
                                .fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = ::finish) { Text("‹", fontSize = 30.sp) }
                                Text(stringResource(R.string.tv_touch_settings_title), style = MaterialTheme.typography.titleLarge)
                            }
                            TeamViewerTouchSettingsPanel(
                                store = store,
                                masterOn = masterOn,
                                onMasterChange = {
                                    masterOn = it
                                    prefs.edit { putBoolean(MASTER_KEY, it) }
                                },
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** Same key as the checkbox on the Settings page. */
        const val MASTER_KEY = "pref_teamviewer_style_touch"
    }
}
