package me.ash.reader.infrastructure.preference

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FeedBucket share format: TitleAndLink shares `<title>\n\n<link>` (blank
 * line between), degrades cleanly when one side is null/blank, and is the
 * out-of-the-box default while OnlyLink stays selectable.
 */
class SharedContentPreferenceTest {

    private val key = intPreferencesKey("sharedContent")

    // --- TitleAndLink formatting -------------------------------------------

    @Test
    fun titleAndLinkJoinsWithBlankLine() {
        assertEquals(
            "Some Title\n\nhttps://example.com/post",
            SharedContentPreference.TitleAndLink.formatContent(
                "Some Title",
                "https://example.com/post",
            ),
        )
    }

    @Test
    fun titleAndLinkDropsBlankTitleWithoutLeadingBlankLine() {
        assertEquals(
            "https://example.com/post",
            SharedContentPreference.TitleAndLink.formatContent("", "https://example.com/post"),
        )
        assertEquals(
            "https://example.com/post",
            SharedContentPreference.TitleAndLink.formatContent("   ", "https://example.com/post"),
        )
        assertEquals(
            "https://example.com/post",
            SharedContentPreference.TitleAndLink.formatContent(null, "https://example.com/post"),
        )
    }

    @Test
    fun titleAndLinkDropsBlankLinkWithoutTrailingBlankLine() {
        assertEquals(
            "Some Title",
            SharedContentPreference.TitleAndLink.formatContent("Some Title", ""),
        )
        assertEquals(
            "Some Title",
            SharedContentPreference.TitleAndLink.formatContent("Some Title", "  \n "),
        )
        assertEquals(
            "Some Title",
            SharedContentPreference.TitleAndLink.formatContent("Some Title", null),
        )
    }

    @Test
    fun titleAndLinkBothBlankProducesEmpty() {
        assertEquals("", SharedContentPreference.TitleAndLink.formatContent(null, null))
        assertEquals("", SharedContentPreference.TitleAndLink.formatContent("", "  "))
    }

    @Test
    fun titleAndLinkKeepsMultilineTitleIntact() {
        assertEquals(
            "Line one\nLine two\n\nhttps://example.com",
            SharedContentPreference.TitleAndLink.formatContent(
                "Line one\nLine two",
                "https://example.com",
            ),
        )
    }

    // --- OnlyLink formatting -------------------------------------------------

    @Test
    fun onlyLinkSharesJustTheLink() {
        assertEquals(
            "https://example.com/post",
            SharedContentPreference.OnlyLink.formatContent("Some Title", "https://example.com/post"),
        )
    }

    @Test
    fun onlyLinkNullLinkProducesEmpty() {
        assertEquals("", SharedContentPreference.OnlyLink.formatContent("Some Title", null))
    }

    // --- Preference decoding -------------------------------------------------

    @Test
    fun defaultIsTitleAndLink() {
        assertEquals(SharedContentPreference.TitleAndLink, SharedContentPreference.default)
    }

    @Test
    fun decodesStoredValues() {
        assertEquals(
            SharedContentPreference.OnlyLink,
            SharedContentPreference.fromPreferences(preferencesOf(key to 0)),
        )
        assertEquals(
            SharedContentPreference.TitleAndLink,
            SharedContentPreference.fromPreferences(preferencesOf(key to 1)),
        )
    }

    @Test
    fun unsetAndUnknownValuesFallBackToDefault() {
        assertEquals(
            SharedContentPreference.TitleAndLink,
            SharedContentPreference.fromPreferences(preferencesOf()),
        )
        assertEquals(
            SharedContentPreference.TitleAndLink,
            SharedContentPreference.fromPreferences(preferencesOf(key to 42)),
        )
    }

    @Test
    fun valuesStillOfferBothOptions() {
        assertEquals(
            listOf(SharedContentPreference.OnlyLink, SharedContentPreference.TitleAndLink),
            SharedContentPreference.values,
        )
    }
}
