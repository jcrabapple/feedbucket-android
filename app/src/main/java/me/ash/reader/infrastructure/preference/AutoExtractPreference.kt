package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.DataStoreKey.Companion.autoExtractFullContent
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.put

/**
 * E20 (SPEC-EXTRACT-v1) — global auto-extract toggle, default ON (E26.1
 * amends SPEC-v1 §12). Gates ONLY the adaptive on-open AUTO path (E8); it
 * does NOT gate `feed.isFullContent` force extraction / PREFETCH (E9) and
 * does NOT gate MANUAL extract / re-extract (E11/E12 — explicit user
 * intent, web parity: the manual icon always works).
 */
val LocalAutoExtractFullContent =
    compositionLocalOf<AutoExtractPreference> { AutoExtractPreference.default }

sealed class AutoExtractPreference(val value: Boolean) : Preference() {
    data object ON : AutoExtractPreference(true)
    data object OFF : AutoExtractPreference(false)

    override fun put(context: Context, scope: CoroutineScope) {
        scope.launch {
            context.dataStore.put(
                autoExtractFullContent,
                value
            )
        }
    }

    fun toggle(context: Context, scope: CoroutineScope) = scope.launch {
        context.dataStore.put(
            autoExtractFullContent,
            !value
        )
    }

    companion object {

        // `by lazy` avoids the JVM recursive-init trap: touching ON first
        // would otherwise leave `default` null during outer-class <clinit>.
        val default: AutoExtractPreference by lazy { ON }
        val values: List<AutoExtractPreference> by lazy { listOf(ON, OFF) }

        fun fromPreferences(preferences: Preferences) =
            when (preferences[DataStoreKey.keys[autoExtractFullContent]?.key as Preferences.Key<Boolean>]) {
                true -> ON
                false -> OFF
                else -> default
            }
    }
}
