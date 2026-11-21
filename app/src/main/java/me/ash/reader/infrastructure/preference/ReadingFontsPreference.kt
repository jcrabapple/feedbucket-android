package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ash.reader.R
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.DataStoreKey.Companion.readingFonts
import me.ash.reader.ui.ext.ExternalFonts
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.put
import me.ash.reader.ui.ext.restart
import me.ash.reader.ui.theme.GoogleSansFontFamily

val LocalReadingFonts =
    compositionLocalOf<ReadingFontsPreference> { ReadingFontsPreference.default }

sealed class ReadingFontsPreference(val value: Int) : Preference() {
    object GoogleSans : ReadingFontsPreference(6)

    object System : ReadingFontsPreference(0)

    object Serif : ReadingFontsPreference(1)

    object SansSerif : ReadingFontsPreference(2)

    object Monospace : ReadingFontsPreference(3)

    object Cursive : ReadingFontsPreference(4)

    object External : ReadingFontsPreference(5)

    object Literata : ReadingFontsPreference(7)

    object SourceSerif : ReadingFontsPreference(8)

    object AtkinsonHyperlegible : ReadingFontsPreference(9)

    object Lexend : ReadingFontsPreference(10)

    override fun put(context: Context, scope: CoroutineScope) {
        scope.launch {
            context.dataStore.put(DataStoreKey.readingFonts, value)
            if (this@ReadingFontsPreference == External) {
                context.restart()
            }
        }
    }

    fun toDesc(context: Context): String =
        when (this) {
            GoogleSans -> context.getString(R.string.google_sans)
            System -> context.getString(R.string.system_default)
            Serif -> "Serif"
            SansSerif -> "Sans-Serif"
            Monospace -> "Monospace"
            Cursive -> "Cursive"
            External -> context.getString(R.string.external_fonts)
            Literata -> "Literata"
            SourceSerif -> "Source Serif 4"
            AtkinsonHyperlegible -> "Atkinson Hyperlegible"
            Lexend -> "Lexend"
        }

    fun asFontFamily(context: Context): FontFamily =
        when (this) {
            GoogleSans -> GoogleSansFontFamily
            System -> FontFamily.Default
            Serif -> FontFamily.Serif
            SansSerif -> FontFamily.SansSerif
            Monospace -> FontFamily.Monospace
            Cursive -> FontFamily.Cursive
            External ->
                ExternalFonts.loadReadingTypography(context).displayLarge.fontFamily
                    ?: FontFamily.Default
            Literata ->
                FontFamily(
                    Font(R.font.literata),
                    Font(R.font.literata_italic, style = FontStyle.Italic),
                )
            SourceSerif ->
                FontFamily(
                    Font(R.font.source_serif_4),
                    Font(R.font.source_serif_4_italic, style = FontStyle.Italic),
                )
            AtkinsonHyperlegible ->
                FontFamily(
                    Font(R.font.atkinson_hyperlegible),
                    Font(R.font.atkinson_hyperlegible_italic, style = FontStyle.Italic),
                )
            Lexend -> FontFamily(Font(R.font.lexend))
        }

    /**
     * File name under `res/font` of the bundled regular face, or null when the
     * choice is a platform-generic family or a user-provided external file.
     */
    fun bundledFontFileName(): String? =
        when (this) {
            GoogleSans -> "google_sans_flex.ttf"
            Literata -> "literata.ttf"
            SourceSerif -> "source_serif_4.ttf"
            AtkinsonHyperlegible -> "atkinson_hyperlegible.ttf"
            Lexend -> "lexend.ttf"
            else -> null
        }

    /**
     * File name under `res/font` of the bundled italic face, or null when the
     * choice has no bundled italic cut (system/generic/external, Google Sans
     * Flex and Lexend are shipped as single-face files).
     */
    fun bundledItalicFileName(): String? =
        when (this) {
            Literata -> "literata_italic.ttf"
            SourceSerif -> "source_serif_4_italic.ttf"
            AtkinsonHyperlegible -> "atkinson_hyperlegible_italic.ttf"
            else -> null
        }

    /**
     * Resource path of the bundled regular face for the live WebView renderer,
     * matching the Google Sans Flex reference path. Null for choices without a
     * bundled face.
     */
    fun webViewFontPath(): String? = bundledFontFileName()?.let { "/android_res/font/$it" }

    /** Null when the choice has no bundled face (see [webViewFontPath]). */
    fun webViewItalicFontPath(): String? =
        bundledItalicFileName()?.let { "/android_res/font/$it" }

    /**
     * CSS font-family name the `@font-face` blocks declare in the WebView
     * renderers. Bundled reading faces use their product name; the historical
     * Google Sans Flex and user-provided external faces keep the "external"
     * family the reader CSS has always referenced.
     */
    fun cssFontFamily(): String =
        when (this) {
            Literata -> "Literata"
            SourceSerif -> "Source Serif 4"
            AtkinsonHyperlegible -> "Atkinson Hyperlegible"
            Lexend -> "Lexend"
            else -> "external"
        }

    /**
     * CSS injected into the downloaded-article `index.html` at serve time so
     * offline reading uses the same bundled face (served from APK resources
     * through the appassets origin — zero network). Null when the choice has
     * no bundled face; the offline template's own sans-serif body rule stays
     * in effect then.
     */
    fun offlinePageStyle(): String? {
        val regular = bundledFontFileName() ?: return null
        val family = cssFontFamily()
        val generic = if (this == Literata || this == SourceSerif) "serif" else "sans-serif"
        val sb = StringBuilder()
        sb.append("@font-face { font-family: '$family'; src: url(\"fonts/$regular\"); }\n")
        bundledItalicFileName()?.let {
            sb.append("@font-face { font-family: '$family'; src: url(\"fonts/$it\"); font-style: italic; }\n")
        }
        sb.append("body { font-family: '$family', $generic !important; }\n")
        return sb.toString()
    }

    companion object {

        val default = GoogleSans

        // Getter, not an eager val: an eager list assigned during the sealed
        // class initializer can permanently capture null for whichever nested
        // object triggered class initialization (JVM recursive-init
        // pass-through); lazy evaluation is always post-init.
        val values
            get() = listOf(
                GoogleSans, System, Serif, SansSerif, Monospace, Cursive, External,
                Literata, SourceSerif, AtkinsonHyperlegible, Lexend,
            )

        fun fromPreferences(preferences: Preferences): ReadingFontsPreference =
            when (preferences[DataStoreKey.keys[readingFonts]?.key as Preferences.Key<Int>]) {
                0 -> System
                1 -> Serif
                2 -> SansSerif
                3 -> Monospace
                4 -> Cursive
                5 -> External
                6 -> GoogleSans
                7 -> Literata
                8 -> SourceSerif
                9 -> AtkinsonHyperlegible
                10 -> Lexend
                else -> default
            }
    }
}
