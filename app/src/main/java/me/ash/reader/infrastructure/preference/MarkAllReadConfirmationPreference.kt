package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.DataStoreKey.Companion.markAllReadConfirmation
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.put

/**
 * S25a — confirmation before scoped mark-all-read. Default ON (web
 * `mark_as_read_confirmation` default 1): the dialog reports the exact scoped
 * unread count before applying.
 */
val LocalMarkAllReadConfirmation =
    compositionLocalOf<MarkAllReadConfirmationPreference> { MarkAllReadConfirmationPreference.default }

sealed class MarkAllReadConfirmationPreference(val value: Boolean) : Preference() {
    data object ON : MarkAllReadConfirmationPreference(true)
    data object OFF : MarkAllReadConfirmationPreference(false)

    override fun put(context: Context, scope: CoroutineScope) {
        scope.launch {
            context.dataStore.put(
                markAllReadConfirmation,
                value
            )
        }
    }

    fun toggle(context: Context, scope: CoroutineScope) = scope.launch {
        context.dataStore.put(
            markAllReadConfirmation,
            !value
        )
    }

    companion object {

        // `by lazy` avoids the JVM recursive-init trap: touching ON first
        // would otherwise leave `default` null during outer-class <clinit>.
        val default: MarkAllReadConfirmationPreference by lazy { ON }
        val values: List<MarkAllReadConfirmationPreference> by lazy { listOf(ON, OFF) }

        fun fromPreferences(preferences: Preferences) =
            when (preferences[DataStoreKey.keys[markAllReadConfirmation]?.key as Preferences.Key<Boolean>]) {
                true -> ON
                false -> OFF
                else -> default
            }
    }
}
