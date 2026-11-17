package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.DataStoreKey.Companion.markReadOnOpen
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.put

/**
 * S23 — mark-read-on-open. Default ON (web `mark_read_on_open` default ON):
 * opening an article sets isUnread=0, readAt=now, isUpdated=0 in one transaction.
 */
val LocalMarkReadOnOpen =
    compositionLocalOf<MarkReadOnOpenPreference> { MarkReadOnOpenPreference.default }

sealed class MarkReadOnOpenPreference(val value: Boolean) : Preference() {
    data object ON : MarkReadOnOpenPreference(true)
    data object OFF : MarkReadOnOpenPreference(false)

    override fun put(context: Context, scope: CoroutineScope) {
        scope.launch {
            context.dataStore.put(
                markReadOnOpen,
                value
            )
        }
    }

    fun toggle(context: Context, scope: CoroutineScope) = scope.launch {
        context.dataStore.put(
            markReadOnOpen,
            !value
        )
    }

    companion object {

        val default = ON
        val values = listOf(ON, OFF)

        fun fromPreferences(preferences: Preferences) =
            when (preferences[DataStoreKey.keys[markReadOnOpen]?.key as Preferences.Key<Boolean>]) {
                true -> ON
                false -> OFF
                else -> default
            }
    }
}
