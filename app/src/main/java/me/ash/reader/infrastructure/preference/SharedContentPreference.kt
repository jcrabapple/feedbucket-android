package me.ash.reader.infrastructure.preference

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ash.reader.R
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.DataStoreKey.Companion.sharedContent
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.put

val LocalSharedContent = compositionLocalOf<SharedContentPreference> { SharedContentPreference.default }

sealed class SharedContentPreference(val value: Int) : Preference() {
    object OnlyLink : SharedContentPreference(0)
    object TitleAndLink : SharedContentPreference(1)

    override fun put(context: Context, scope: CoroutineScope) {
        scope.launch {
            context.dataStore.put(
                DataStoreKey.sharedContent,
                value
            )
        }
    }

    @Stable
    fun toDesc(context: Context): String =
        when (this) {
            OnlyLink -> context.getString(R.string.only_link)
            TitleAndLink -> context.getString(R.string.title_and_link)
        }

    fun share(context: Context, title: String?, link: String?) {
        share(context, formatContent(title, link))
    }

    /**
     * Pure share-text formatting, extracted for JVM testability.
     *
     * [TitleAndLink] produces `<title>\n\n<link>` (a blank line between the
     * two). A null/blank side is dropped WITHOUT leaving a leading or
     * trailing blank line: title-only and link-only degrade to the single
     * available part, both-blank produces an empty string.
     */
    fun formatContent(title: String?, link: String?): String {
        return when (this) {
            OnlyLink -> link.orEmpty()
            TitleAndLink -> {
                val t = title?.takeIf { it.isNotBlank() }
                val l = link?.takeIf { it.isNotBlank() }
                when {
                    t != null && l != null -> t + "\n\n" + l
                    t != null -> t
                    l != null -> l
                    else -> ""
                }
            }
        }
    }

    private fun share(context: Context, content: String) {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, content)
            type = "text/plain"
        }, context.getString(R.string.share)))
    }

    companion object {

        // FeedBucket: share title + blank line + link out of the box.
        // OnlyLink stays selectable in Settings -> Interaction.
        // Getters (not eagerly initialized fields): a companion field is
        // assigned during <clinit>, and if the FIRST class-init entry point
        // is one of the nested objects themselves, that object's INSTANCE is
        // still null while the companion initializes (JVM recursive-init
        // semantics) — the field would capture a null. Lazy evaluation on
        // access is always fully initialized.
        val default: SharedContentPreference
            get() = TitleAndLink

        val values: List<SharedContentPreference>
            get() = listOf(OnlyLink, TitleAndLink)

        fun fromPreferences(preferences: Preferences): SharedContentPreference =
            when (preferences[DataStoreKey.keys[sharedContent]?.key as Preferences.Key<Int>]) {
                0 -> OnlyLink
                1 -> TitleAndLink
                else -> default
            }
    }
}
