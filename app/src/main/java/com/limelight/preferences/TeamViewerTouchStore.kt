package com.limelight.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.limelight.utils.TeamViewerTouchSettings

/**
 * Stores the tunable values of the TeamViewer style touch feature (spec 1.5). One instance per
 * process. The touch code reads [snapshot], an in-memory copy, so it never waits for storage
 * (invariant of spec 1.5 section 3.3). Both screens write here and see each other's changes through
 * [addListener].
 */
class TeamViewerTouchStore internal constructor(private val prefs: SharedPreferences) {

    @Volatile
    private var current: TeamViewerTouchSettings = load()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    // A strong reference is needed: SharedPreferences keeps its listeners weakly.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        current = load()
        listeners.forEach { it() }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    /** The values now. Take it once at the start of a gesture and keep it for that gesture. */
    fun snapshot(): TeamViewerTouchSettings = current

    fun set(key: String, value: Float) {
        if (!TeamViewerTouchSettings.hasKey(key)) return
        val clamped = current.with(key, value).get(key)
        prefs.edit { putFloat(key, clamped) }
    }

    /** Restores every value of this feature. Other touch settings are not touched (spec 1.5 D12). */
    fun reset() {
        prefs.edit {
            for (def in TeamViewerTouchSettings.DEFS) remove(def.key)
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun load(): TeamViewerTouchSettings {
        val stored = HashMap<String, Float?>()
        for (def in TeamViewerTouchSettings.DEFS) {
            stored[def.key] = readFloat(def.key)
        }
        return TeamViewerTouchSettings.from(stored)
    }

    // A value of the wrong type (a bad backup) counts as missing instead of crashing.
    private fun readFloat(key: String): Float? = try {
        if (prefs.contains(key)) prefs.getFloat(key, Float.NaN) else null
    } catch (e: ClassCastException) {
        null
    }

    companion object {
        const val FILE_NAME = "teamviewer_touch"

        /** Keys for the settings export (spec 1.5 ST-12). */
        val PORTABLE_KEYS: Set<String> = TeamViewerTouchSettings.DEFS.map { it.key }.toSet()

        @Volatile
        private var instance: TeamViewerTouchStore? = null

        fun get(context: Context): TeamViewerTouchStore =
            instance ?: synchronized(this) {
                instance ?: TeamViewerTouchStore(
                    context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }
}
