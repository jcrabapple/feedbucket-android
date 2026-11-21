package me.ash.reader.infrastructure.preference

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.webkit.WebView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.core.content.res.ResourcesCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import me.ash.reader.R
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.page.settings.color.reading.ReadingStylePage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Card t_0f68461a — the four new bundled reading fonts (Literata, Source
 * Serif 4, Atkinson Hyperlegible, Lexend) must resolve on device for BOTH
 * renderer paths (native Typeface + WebView /android_res resource URL),
 * appear in the reader font dialog, and persist when chosen.
 */
@RunWith(AndroidJUnit4::class)
class ReadingFontsInstrumentedTest {

    @get:org.junit.Rule val composeRule = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private data class BundledFace(
        val preference: ReadingFontsPreference,
        val regularRes: Int,
        val italicRes: Int?,
        val fontFile: String,
        val cssFamily: String,
    )

    private val faces =
        listOf(
            BundledFace(
                ReadingFontsPreference.Literata,
                R.font.literata,
                R.font.literata_italic,
                "literata.ttf",
                "Literata",
            ),
            BundledFace(
                ReadingFontsPreference.SourceSerif,
                R.font.source_serif_4,
                R.font.source_serif_4_italic,
                "source_serif_4.ttf",
                "Source Serif 4",
            ),
            BundledFace(
                ReadingFontsPreference.AtkinsonHyperlegible,
                R.font.atkinson_hyperlegible,
                R.font.atkinson_hyperlegible_italic,
                "atkinson_hyperlegible.ttf",
                "Atkinson Hyperlegible",
            ),
            BundledFace(
                ReadingFontsPreference.Lexend,
                R.font.lexend,
                null,
                "lexend.ttf",
                "Lexend",
            ),
        )

    @After
    fun restoreDefaultFont() {
        runBlocking {
            context.dataStore.edit { it[intPreferencesKey("readingFonts")] = 6 }
        }
    }

    // --- native renderer path ---------------------------------------------------

    @Test
    fun everyBundledFaceResolvesToANonDefaultTypeface() {
        val probe = "Handgloves 123 fi fl Qu"
        val defaultPaint = Paint().apply { typeface = Typeface.DEFAULT; textSize = 48f }
        val defaultWidth = defaultPaint.measureText(probe)
        for (face in faces) {
            val regular = ResourcesCompat.getFont(context, face.regularRes)
            assertNotNull("${face.fontFile} must resolve", regular)
            assertTrue(
                "${face.fontFile} must not be the system default typeface",
                regular != Typeface.DEFAULT,
            )
            val paint = Paint().apply { typeface = regular; textSize = 48f }
            assertTrue(
                "${face.fontFile} must measure differently from the system default",
                kotlin.math.abs(paint.measureText(probe) - defaultWidth) > 0.5f,
            )
            if (face.italicRes != null) {
                val italic = ResourcesCompat.getFont(context, face.italicRes)
                assertNotNull("${face.fontFile} italic must resolve", italic)
                assertTrue(
                    "${face.fontFile} italic must differ from its regular cut",
                    italic != regular,
                )
            }
        }
    }

    @Test
    fun preferenceFontFamiliesCarryTheBundledFaces() {
        for (face in faces) {
            val family = face.preference.asFontFamily(context)
            assertTrue(
                "${face.cssFamily} must map to a bundled font list family",
                family is FontListFontFamily,
            )
            val expectedSize = if (face.italicRes != null) 2 else 1
            assertEquals(
                "${face.cssFamily} must carry regular${if (face.italicRes != null) " and italic" else ""} faces",
                expectedSize,
                (family as FontListFontFamily).fonts.size,
            )
        }
    }

    // --- WebView renderer path ----------------------------------------------------

    @Test
    fun webViewLoadsEveryBundledFaceFromAndroidRes() {
        for (face in faces) {
            assertTrue(
                "WebView must load /android_res/font/${face.fontFile} as '${face.cssFamily}'",
                webViewFontLoads(face.fontFile, face.cssFamily),
            )
        }
    }

    private fun webViewFontLoads(fontFile: String, family: String): Boolean {
        val themed =
            android.view.ContextThemeWrapper(
                context,
                android.R.style.Theme_Material_Light_NoActionBar,
            )
        var webViewRef: WebView? = null
        instrumentation.runOnMainSync {
            webViewRef =
                WebView(themed).apply {
                    settings.javaScriptEnabled = true
                    val html =
                        "<html><head><style>" +
                            "@font-face { font-family: '$family';" +
                            " src: url(\"file:///android_res/font/$fontFile\"); }" +
                            "</style></head><body>" +
                            "<p style=\"font-family:'$family'\">Handgloves 123</p>" +
                            "</body></html>"
                    loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                }
        }
        val webView = webViewRef!!
        val script = "document.fonts.check(\"16px '$family'\")"
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (jsEval(webView, script) == "true") {
                instrumentation.runOnMainSync { webView.destroy() }
                return true
            }
            Thread.sleep(250)
        }
        instrumentation.runOnMainSync { webView.destroy() }
        return false
    }

    private fun jsEval(webView: WebView, script: String): String? {
        val latch = CountDownLatch(1)
        var out: String? = null
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(script) { value ->
                out = value
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        return out
    }

    // --- dialog -------------------------------------------------------------------

    @Test
    fun readingFontDialogListsAllChoicesAndChoosingOnePersists() {
        composeRule.setContent {
            ReadingStylePage(
                onBack = {},
                navigateToReadingBoldCharacters = {},
                navigateToReadingPageTitle = {},
                navigateToReadingPageText = {},
                navigateToReadingPageImage = {},
                navigateToReadingPageVideo = {},
            )
        }
        composeRule.waitForIdle()
        composeRule
            .onNodeWithText(context.getString(R.string.reading_fonts))
            .performClick()
        composeRule.waitForIdle()

        // every choice is listed (the dialog body is a lazy list — scroll to
        // each; "Google Sans Flex" also matches the SettingItem description
        // behind the dialog, so address the dialog node as the LAST match)
        val expected = ReadingFontsPreference.values.map { it.toDesc(context) }
        assertEquals(11, expected.size)
        for (label in expected) {
            composeRule.onAllNodesWithText(label).onLast().performScrollTo()
            composeRule.onAllNodesWithText(label).onLast().assertExists("dialog must list $label")
        }

        // choosing Lexend persists preference int 10
        composeRule.onAllNodesWithText("Lexend").onLast().performScrollTo()
        composeRule.onAllNodesWithText("Lexend").onLast().performClick()
        composeRule.waitForIdle()

        val deadline = System.currentTimeMillis() + 10_000
        var stored: ReadingFontsPreference? = null
        while (System.currentTimeMillis() < deadline) {
            stored =
                runBlocking {
                    context.dataStore.data
                        .map { ReadingFontsPreference.fromPreferences(it) }
                        .first()
                }
            if (stored == ReadingFontsPreference.Lexend) break
            Thread.sleep(200)
        }
        assertEquals(ReadingFontsPreference.Lexend, stored)
    }
}
