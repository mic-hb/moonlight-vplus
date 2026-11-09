package com.limelight.preferences

import android.content.SharedPreferences
import com.limelight.utils.TeamViewerTouchSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamViewerTouchStoreTest {
    /** Minimal in-memory SharedPreferences that also fires change listeners. */
    private class FakePrefs : SharedPreferences {
        val data = HashMap<String, Any?>()
        private val listeners = ArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): MutableMap<String, *> = data
        override fun getString(key: String?, defValue: String?) = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long) = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float {
            val v = data[key] ?: return defValue
            return v as? Float ?: throw ClassCastException("not a float: $key")
        }
        override fun getBoolean(key: String?, defValue: Boolean) = data[key] as? Boolean ?: defValue
        override fun contains(key: String?) = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners.add(l)
        }
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners.remove(l)
        }

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val puts = HashMap<String, Any?>()
            private val removes = HashSet<String>()
            override fun putString(key: String, value: String?) = apply { puts[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { puts[key] = values }
            override fun putInt(key: String, value: Int) = apply { puts[key] = value }
            override fun putLong(key: String, value: Long) = apply { puts[key] = value }
            override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
            override fun remove(key: String) = apply { removes.add(key) }
            override fun clear() = apply { removes.addAll(data.keys) }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                removes.forEach { data.remove(it) }
                data.putAll(puts)
                (removes + puts.keys).forEach { k -> listeners.toList().forEach { it.onSharedPreferenceChanged(this@FakePrefs, k) } }
            }
        }
    }

    @Test
    fun emptyStorageGivesDefaults() {
        assertEquals(TeamViewerTouchSettings.defaults(), TeamViewerTouchStore(FakePrefs()).snapshot())
    }

    @Test
    fun aValueRoundTripsAndIsClamped() {
        val prefs = FakePrefs()
        val store = TeamViewerTouchStore(prefs)
        store.set(TeamViewerTouchSettings.CURSOR_ZOOM_EXPONENT, 0.3f)
        assertEquals(0.3f, store.snapshot().cursorZoomExponent, 0f)
        store.set(TeamViewerTouchSettings.MAX_ZOOM, 999f)
        assertEquals(20f, store.snapshot().maxZoom, 0f)
        // A second store on the same file reads the same values.
        assertEquals(0.3f, TeamViewerTouchStore(prefs).snapshot().cursorZoomExponent, 0f)
    }

    @Test
    fun unknownKeysAreIgnoredOnSet() {
        val prefs = FakePrefs()
        TeamViewerTouchStore(prefs).set("no_such_key", 1f)
        assertTrue(prefs.data.isEmpty())
    }

    @Test
    fun corruptStoredValuesBecomeDefaultsOrClamped() {
        val prefs = FakePrefs()
        prefs.data[TeamViewerTouchSettings.MAX_ZOOM] = "ten" // wrong type
        prefs.data[TeamViewerTouchSettings.TAP_TIME_MS] = Float.NaN
        prefs.data[TeamViewerTouchSettings.SWITCH_RATIO] = 1000f
        val s = TeamViewerTouchStore(prefs).snapshot()
        assertEquals(10f, s.maxZoom, 0f)
        assertEquals(250, s.tapTimeMs)
        assertEquals(5f, s.get(TeamViewerTouchSettings.SWITCH_RATIO), 0f)
    }

    @Test
    fun resetRestoresOnlyThisFeaturesKeys() {
        val prefs = FakePrefs()
        prefs.data["touch_sense"] = 250 // belongs to another store file in the app, here only to prove it is left alone
        val store = TeamViewerTouchStore(prefs)
        store.set(TeamViewerTouchSettings.CURSOR_SIZE_EXPONENT, 0.1f)
        store.reset()
        assertEquals(TeamViewerTouchSettings.defaults(), store.snapshot())
        assertEquals(250, prefs.data["touch_sense"])
    }

    @Test
    fun listenersHearChangesIncludingOnesMadeByAnotherStoreOnTheSameFile() {
        val prefs = FakePrefs()
        val a = TeamViewerTouchStore(prefs)
        val b = TeamViewerTouchStore(prefs)
        var heard = 0
        a.addListener { heard++ }
        b.set(TeamViewerTouchSettings.PINCH_GAIN, 1.5f)
        assertEquals(1.5f, a.snapshot().pinchGain, 0f)
        assertTrue(heard >= 1)
    }

    @Test
    fun aSnapshotKeptByAGestureDoesNotChange() {
        val store = TeamViewerTouchStore(FakePrefs())
        val snapshot = store.snapshot()
        store.set(TeamViewerTouchSettings.TAP_TIME_MS, 400f)
        assertEquals(250, snapshot.tapTimeMs)
        assertEquals(400, store.snapshot().tapTimeMs)
        assertFalse(snapshot == store.snapshot())
    }

    @Test
    fun portableKeysAreAllTheDefinedKeys() {
        assertEquals(TeamViewerTouchSettings.DEFS.map { it.key }.toSet(), TeamViewerTouchStore.PORTABLE_KEYS)
    }
}
