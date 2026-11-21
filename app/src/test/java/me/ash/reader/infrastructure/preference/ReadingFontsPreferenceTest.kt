package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import me.ash.reader.ui.component.webview.WebViewStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * Card t_0f68461a — the four new bundled reading fonts (Literata, Source
 * Serif 4, Atkinson Hyperlegible, Lexend) must round-trip through the
 * preference store, keep every pre-existing int mapping untouched, expose
 * their bundled faces to the WebView and offline renderers, and appear in
 * the dialog order after the existing choices.
 *
 * REDCHECKs (recorded in the review handoff, mutations restored before
 * commit):
 * 1. removing `7 -> Literata` from fromPreferences fails
 *    newIntsRoundTripToNewEntries (expected Literata, fell back to default);
 * 2. suppressing the italic `@font-face` block in WebViewStyle.applyFontFace
 *    fails webStyleDeclaresRegularAndItalicFacesWithFamilyName (face count
 *    2 -> 1, missing font-style italic);
 * 3. forcing the offline generic fallback to sans-serif fails
 *    offlinePageStyleDeclaresFacesForBundledFonts (Literata body rule lost
 *    its serif fallback).
 */
class ReadingFontsPreferenceTest {

    private val key = intPreferencesKey("readingFonts")
    private val context: Context = mock(Context::class.java)

    private fun prefs(intValue: Int) = ReadingFontsPreference.fromPreferences(preferencesOf(key to intValue))

    // --- preference round trips ------------------------------------------------

    @Test
    fun newIntsRoundTripToNewEntries() {
        assertEquals(ReadingFontsPreference.Literata, prefs(7))
        assertEquals(ReadingFontsPreference.SourceSerif, prefs(8))
        assertEquals(ReadingFontsPreference.AtkinsonHyperlegible, prefs(9))
        assertEquals(ReadingFontsPreference.Lexend, prefs(10))
    }

    @Test
    fun oldIntsStillMapToOldEntries() {
        assertSame(ReadingFontsPreference.System, prefs(0))
        assertSame(ReadingFontsPreference.Serif, prefs(1))
        assertSame(ReadingFontsPreference.SansSerif, prefs(2))
        assertSame(ReadingFontsPreference.Monospace, prefs(3))
        assertSame(ReadingFontsPreference.Cursive, prefs(4))
        assertSame(ReadingFontsPreference.External, prefs(5))
        assertSame(ReadingFontsPreference.GoogleSans, prefs(6))
    }

    @Test
    fun unknownIntsFallBackToDefault() {
        assertSame(ReadingFontsPreference.default, prefs(11))
        assertSame(ReadingFontsPreference.default, prefs(-1))
        assertSame(ReadingFontsPreference.default, ReadingFontsPreference.fromPreferences(preferencesOf()))
        assertSame(ReadingFontsPreference.GoogleSans, ReadingFontsPreference.default)
    }

    @Test
    fun entryIntValuesAreStable() {
        assertEquals(6, ReadingFontsPreference.GoogleSans.value)
        assertEquals(7, ReadingFontsPreference.Literata.value)
        assertEquals(8, ReadingFontsPreference.SourceSerif.value)
        assertEquals(9, ReadingFontsPreference.AtkinsonHyperlegible.value)
        assertEquals(10, ReadingFontsPreference.Lexend.value)
    }

    // --- dialog order and labels ------------------------------------------------

    @Test
    fun valuesListsAllElevenChoicesInDialogOrder() {
        assertEquals(
            listOf(
                ReadingFontsPreference.GoogleSans,
                ReadingFontsPreference.System,
                ReadingFontsPreference.Serif,
                ReadingFontsPreference.SansSerif,
                ReadingFontsPreference.Monospace,
                ReadingFontsPreference.Cursive,
                ReadingFontsPreference.External,
                ReadingFontsPreference.Literata,
                ReadingFontsPreference.SourceSerif,
                ReadingFontsPreference.AtkinsonHyperlegible,
                ReadingFontsPreference.Lexend,
            ),
            ReadingFontsPreference.values,
        )
        assertEquals(11, ReadingFontsPreference.values.size)
    }

    @Test
    fun toDescUsesProductNames() {
        assertEquals("Literata", ReadingFontsPreference.Literata.toDesc(context))
        assertEquals("Source Serif 4", ReadingFontsPreference.SourceSerif.toDesc(context))
        assertEquals(
            "Atkinson Hyperlegible",
            ReadingFontsPreference.AtkinsonHyperlegible.toDesc(context),
        )
        assertEquals("Lexend", ReadingFontsPreference.Lexend.toDesc(context))
        ReadingFontsPreference.values.forEach {
            // generic entries use literal labels; string-resource entries need
            // a real Context, so only the literal ones are checked for content
            if (it != ReadingFontsPreference.GoogleSans &&
                it != ReadingFontsPreference.System &&
                it != ReadingFontsPreference.External
            ) {
                assertTrue("toDesc must be non-empty for $it", it.toDesc(context).isNotEmpty())
            }
        }
    }

    // --- native renderer (Compose) ----------------------------------------------

    @Test
    fun asFontFamilyBundlesRegularAndItalicFaces() {
        val literata = ReadingFontsPreference.Literata.asFontFamily(context) as FontListFontFamily
        assertEquals(2, literata.fonts.size)
        val sourceSerif = ReadingFontsPreference.SourceSerif.asFontFamily(context) as FontListFontFamily
        assertEquals(2, sourceSerif.fonts.size)
        val atkinson =
            ReadingFontsPreference.AtkinsonHyperlegible.asFontFamily(context) as FontListFontFamily
        assertEquals(2, atkinson.fonts.size)
        // Lexend ships no italic cut upstream
        val lexend = ReadingFontsPreference.Lexend.asFontFamily(context) as FontListFontFamily
        assertEquals(1, lexend.fonts.size)
    }

    // --- WebView renderer plumbing ------------------------------------------------

    @Test
    fun bundledFacesUseAndroidResFontPaths() {
        assertEquals("/android_res/font/literata.ttf", ReadingFontsPreference.Literata.webViewFontPath())
        assertEquals(
            "/android_res/font/literata_italic.ttf",
            ReadingFontsPreference.Literata.webViewItalicFontPath(),
        )
        assertEquals("/android_res/font/source_serif_4.ttf", ReadingFontsPreference.SourceSerif.webViewFontPath())
        assertEquals(
            "/android_res/font/source_serif_4_italic.ttf",
            ReadingFontsPreference.SourceSerif.webViewItalicFontPath(),
        )
        assertEquals(
            "/android_res/font/atkinson_hyperlegible.ttf",
            ReadingFontsPreference.AtkinsonHyperlegible.webViewFontPath(),
        )
        assertEquals(
            "/android_res/font/atkinson_hyperlegible_italic.ttf",
            ReadingFontsPreference.AtkinsonHyperlegible.webViewItalicFontPath(),
        )
        assertEquals("/android_res/font/lexend.ttf", ReadingFontsPreference.Lexend.webViewFontPath())
        assertNull(ReadingFontsPreference.Lexend.webViewItalicFontPath())
        // untouched legacy behavior
        assertEquals(
            "/android_res/font/google_sans_flex.ttf",
            ReadingFontsPreference.GoogleSans.webViewFontPath(),
        )
        assertNull(ReadingFontsPreference.GoogleSans.webViewItalicFontPath())
        assertNull(ReadingFontsPreference.System.webViewFontPath())
        assertNull(ReadingFontsPreference.Serif.webViewFontPath())
        assertNull(ReadingFontsPreference.External.webViewFontPath())
    }

    @Test
    fun cssFontFamilyUsesProductNamesAndKeepsExternalDefault() {
        assertEquals("Literata", ReadingFontsPreference.Literata.cssFontFamily())
        assertEquals("Source Serif 4", ReadingFontsPreference.SourceSerif.cssFontFamily())
        assertEquals("Atkinson Hyperlegible", ReadingFontsPreference.AtkinsonHyperlegible.cssFontFamily())
        assertEquals("Lexend", ReadingFontsPreference.Lexend.cssFontFamily())
        assertEquals("external", ReadingFontsPreference.GoogleSans.cssFontFamily())
        assertEquals("external", ReadingFontsPreference.External.cssFontFamily())
    }

    // --- offline renderer CSS ------------------------------------------------------

    @Test
    fun offlinePageStyleDeclaresFacesForBundledFonts() {
        val literata = ReadingFontsPreference.Literata.offlinePageStyle()
        assertNotNull(literata)
        assertTrue(literata!!.contains("@font-face"))
        assertTrue(literata.contains("url(\"fonts/literata.ttf\")"))
        assertTrue(literata.contains("url(\"fonts/literata_italic.ttf\")"))
        assertTrue(literata.contains("font-style: italic"))
        assertTrue(literata.contains("body { font-family: 'Literata', serif !important; }"))

        val lexend = ReadingFontsPreference.Lexend.offlinePageStyle()
        assertNotNull(lexend)
        assertTrue(lexend!!.contains("url(\"fonts/lexend.ttf\")"))
        assertFalse(lexend.contains("font-style: italic"))
        assertTrue(lexend.contains("body { font-family: 'Lexend', sans-serif !important; }"))

        val atkinson = ReadingFontsPreference.AtkinsonHyperlegible.offlinePageStyle()
        assertNotNull(atkinson)
        assertTrue(atkinson!!.contains("url(\"fonts/atkinson_hyperlegible.ttf\")"))
        assertTrue(atkinson.contains("url(\"fonts/atkinson_hyperlegible_italic.ttf\")"))
    }

    @Test
    fun offlinePageStyleIsNullWithoutBundledFace() {
        assertNull(ReadingFontsPreference.System.offlinePageStyle())
        assertNull(ReadingFontsPreference.Serif.offlinePageStyle())
        assertNull(ReadingFontsPreference.SansSerif.offlinePageStyle())
        assertNull(ReadingFontsPreference.Monospace.offlinePageStyle())
        assertNull(ReadingFontsPreference.Cursive.offlinePageStyle())
        assertNull(ReadingFontsPreference.External.offlinePageStyle())
        // Google Sans Flex keeps its bundled face offline too
        val googleSans = ReadingFontsPreference.GoogleSans.offlinePageStyle()
        assertNotNull(googleSans)
        assertTrue(googleSans!!.contains("url(\"fonts/google_sans_flex.ttf\")"))
        assertTrue(googleSans.contains("body { font-family: 'external', sans-serif !important; }"))
    }

    // --- generated reader CSS -------------------------------------------------------

    @Test
    fun webStyleDeclaresRegularAndItalicFacesWithFamilyName() {
        val css = style(fontPath = "/android_res/font/source_serif_4.ttf", italicFontPath = "/android_res/font/source_serif_4_italic.ttf", fontFamilyName = "Source Serif 4")
        assertTrue(css.contains("font-family: 'Source Serif 4';"))
        assertTrue(css.contains("src: url(\"file:///android_res/font/source_serif_4.ttf\");"))
        assertTrue(css.contains("src: url(\"file:///android_res/font/source_serif_4_italic.ttf\");"))
        assertTrue(css.contains("font-style: italic;"))
        assertTrue(css.contains("--font-family: 'Source Serif 4';"))
        assertEquals(2, Regex("@font-face").findAll(css).count())
    }

    @Test
    fun webStyleWithoutItalicDeclaresSingleFace() {
        val css = style(fontPath = "/android_res/font/lexend.ttf", fontFamilyName = "Lexend")
        assertEquals(1, Regex("@font-face").findAll(css).count())
        assertTrue(css.contains("--font-family: 'Lexend';"))
        assertFalse(css.contains("font-style: italic"))
    }

    @Test
    fun webStyleKeepsLegacyExternalShape() {
        val css = style(fontPath = "/data/user/0/app/files/reading_font.ttf")
        assertTrue(css.contains("font-family: 'external';"))
        assertTrue(css.contains("--font-family: 'external';"))
        assertEquals(1, Regex("@font-face").findAll(css).count())
    }

    @Test
    fun webStyleWithoutFontPathEmitsNoFontFace() {
        val css = style(fontPath = null)
        assertFalse(css.contains("@font-face"))
        // "--code-font-family" is a distinct variable; the reading-family
        // variable is only emitted alongside a @font-face declaration
        assertFalse(css.contains("--font-family:"))
    }

    private fun style(
        fontPath: String?,
        italicFontPath: String? = null,
        fontFamilyName: String = WebViewStyle.DEFAULT_FONT_FAMILY,
    ): String =
        WebViewStyle.get(
            fontSize = 17,
            fontPath = fontPath,
            italicFontPath = italicFontPath,
            fontFamilyName = fontFamilyName,
            lineHeight = 1f,
            letterSpacing = 0f,
            textMargin = 16,
            textColor = 0xFF202020.toInt(),
            textBold = false,
            textAlign = "left",
            boldTextColor = 0xFF000000.toInt(),
            subheadBold = false,
            subheadUpperCase = false,
            imgMargin = 16,
            imgBorderRadius = 8,
            linkTextColor = 0xFF0000FF.toInt(),
            codeTextColor = 0xFF444444.toInt(),
            codeBgColor = 0xFFCCCCCC.toInt(),
            tableMargin = 16,
            selectionTextColor = 0xFF000000.toInt(),
            selectionBgColor = 0xFFFFFF00.toInt(),
        )
}
