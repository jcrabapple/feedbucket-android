package me.ash.reader.infrastructure.preference

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.PreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * X4 task 3 — E20 `AutoExtractPreference` round trip (state-level): default
 * ON, `fromPreferences` fallback, persisted true/false, and the registry +
 * `Settings` wiring the InteractionPage `reading_page` row binds to
 * (`LocalAutoExtractFullContent` ← `Settings.autoExtractFullContent` ←
 * `toSettings` ← the DataStore key `put`/`toggle` write).
 */
class AutoExtractPreferenceTest {

    private val key = booleanPreferencesKey("autoExtractFullContent")

    @Test
    fun defaultIsOn() {
        // E20/E26.1 — default ON (amends SPEC-v1 §12).
        assertSame(AutoExtractPreference.ON, AutoExtractPreference.default)
        assertTrue(AutoExtractPreference.default.value)
        // fromPreferences fallback: absent key → default ON.
        assertEquals(
            AutoExtractPreference.ON,
            AutoExtractPreference.fromPreferences(preferencesOf()),
        )
    }

    @Test
    fun roundTripsPersistedValues() {
        assertEquals(
            AutoExtractPreference.ON,
            AutoExtractPreference.fromPreferences(preferencesOf(key to true)),
        )
        assertEquals(
            AutoExtractPreference.OFF,
            AutoExtractPreference.fromPreferences(preferencesOf(key to false)),
        )
        assertEquals(
            listOf<AutoExtractPreference>(AutoExtractPreference.ON, AutoExtractPreference.OFF),
            AutoExtractPreference.values,
        )
    }

    @Test
    fun putAndToggleTargetTheRegisteredDataStoreKey() {
        // put/toggle persist through the DataStoreKey registry — pin the
        // registered name + type so the write lands under the exact key
        // `fromPreferences` reads back (the round-trip seam).
        assertEquals("autoExtractFullContent", DataStoreKey.autoExtractFullContent)
        val registered = DataStoreKey.keys[DataStoreKey.autoExtractFullContent]
        assertNotNull("autoExtractFullContent must be registered for put/toggle", registered)
        assertEquals("autoExtractFullContent", registered!!.key.name)
        assertEquals(Boolean::class.java, registered.type)
        // The type-safe registry (settings export/import) knows the key too.
        assertTrue(PreferencesKey.keys.containsKey("autoExtractFullContent"))
    }

    @Test
    fun settingsWiringRoundTrip() {
        // State-level assertion for the InteractionPage row (E20): the
        // RYSwitch binds `Settings.autoExtractFullContent`, which toSettings
        // maps from the SAME DataStore key put/toggle write.
        assertEquals(AutoExtractPreference.ON, Settings().autoExtractFullContent)
        assertEquals(
            AutoExtractPreference.OFF,
            preferencesOf(key to false).toSettings().autoExtractFullContent,
        )
        assertEquals(
            AutoExtractPreference.ON,
            preferencesOf(key to true).toSettings().autoExtractFullContent,
        )
        // Fallback: unrelated keys only → default ON.
        assertEquals(
            AutoExtractPreference.ON,
            preferencesOf(booleanPreferencesKey("somethingElse") to false)
                .toSettings()
                .autoExtractFullContent,
        )
    }
}
