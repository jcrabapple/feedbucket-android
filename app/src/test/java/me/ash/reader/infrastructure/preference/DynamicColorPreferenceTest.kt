package me.ash.reader.infrastructure.preference

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Wave H: DynamicColorPreference round trip (orchestrator ruling 2026-11-17):
 * default OFF, persists true, persists false.
 */
class DynamicColorPreferenceTest {

    private val key = booleanPreferencesKey("dynamicColor")

    @Test
    fun defaultIsOff() {
        assertEquals(DynamicColorPreference.OFF, DynamicColorPreference.fromPreferences(preferencesOf()))
    }

    @Test
    fun persistsTrue() {
        assertEquals(
            DynamicColorPreference.ON,
            DynamicColorPreference.fromPreferences(preferencesOf(key to true)),
        )
    }

    @Test
    fun persistsFalse() {
        assertEquals(
            DynamicColorPreference.OFF,
            DynamicColorPreference.fromPreferences(preferencesOf(key to false)),
        )
    }

    @Test
    fun toggleOperatorFlipsState() {
        assertEquals(DynamicColorPreference.ON, !DynamicColorPreference.OFF)
        assertEquals(DynamicColorPreference.OFF, !DynamicColorPreference.ON)
    }
}
