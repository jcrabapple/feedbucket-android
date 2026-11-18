package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.DataStoreKey.Companion.dynamicColor
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.put

/**
 * Wave H (UI-CONTRACT-v1 §1.3/§6.11): dynamic color is OPTIONAL on API 31+
 * and OFF by default. When ON, AppTheme uses the platform Material dynamic
 * schemes and falls back to the complete static FeedBucket schemes below
 * API 31 or when the preference is OFF.
 */
val LocalDynamicColor =
    compositionLocalOf<DynamicColorPreference> { DynamicColorPreference.default }

sealed class DynamicColorPreference(val value: Boolean) : Preference() {

    object ON : DynamicColorPreference(true)
    object OFF : DynamicColorPreference(false)

    override fun put(context: Context, scope: CoroutineScope) {
        scope.launch { context.dataStore.put(DataStoreKey.dynamicColor, value) }
    }

    companion object {

        val default = OFF
        val values = listOf(ON, OFF)

        fun fromPreferences(preferences: Preferences) =
            when (
                preferences[
                    DataStoreKey.keys[dynamicColor]?.key as Preferences.Key<Boolean>
                ]
            ) {
                true -> ON
                false -> OFF
                else -> default
            }
    }
}

operator fun DynamicColorPreference.not(): DynamicColorPreference =
    when (value) {
        true -> DynamicColorPreference.OFF
        false -> DynamicColorPreference.ON
    }
