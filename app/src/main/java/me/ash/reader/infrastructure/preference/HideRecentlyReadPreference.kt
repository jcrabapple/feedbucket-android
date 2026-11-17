package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.DataStoreKey.Companion.hideRecentlyRead
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.put

/**
 * S15/D5 — hide the Recently Read view from the flow filter bar. Default OFF
 * (web `hide_recently_read` default 0): the recentlyRead view is listed unless
 * the user hides it. This is display-only; the view and its data always exist.
 */
val LocalHideRecentlyRead =
    compositionLocalOf<HideRecentlyReadPreference> { HideRecentlyReadPreference.default }

sealed class HideRecentlyReadPreference(val value: Boolean) : Preference() {
    data object ON : HideRecentlyReadPreference(true)
    data object OFF : HideRecentlyReadPreference(false)

    override fun put(context: Context, scope: CoroutineScope) {
        scope.launch {
            context.dataStore.put(
                hideRecentlyRead,
                value
            )
        }
    }

    fun toggle(context: Context, scope: CoroutineScope) = scope.launch {
        context.dataStore.put(
            hideRecentlyRead,
            !value
        )
    }

    companion object {

        // `by lazy` avoids the JVM recursive-init trap: touching OFF first
        // would otherwise leave `default` null during outer-class <clinit>.
        val default: HideRecentlyReadPreference by lazy { OFF }
        val values: List<HideRecentlyReadPreference> by lazy { listOf(ON, OFF) }

        fun fromPreferences(preferences: Preferences) =
            when (preferences[DataStoreKey.keys[hideRecentlyRead]?.key as Preferences.Key<Boolean>]) {
                true -> ON
                false -> OFF
                else -> default
            }
    }
}
