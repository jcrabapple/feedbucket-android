package me.ash.reader.offline

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CopyOnWriteArrayList
import me.ash.reader.domain.model.download.DownloadManifest
import me.ash.reader.domain.model.download.DownloadStore
import me.ash.reader.infrastructure.preference.LocalReadingFonts
import me.ash.reader.infrastructure.preference.ReadingFontsPreference
import me.ash.reader.ui.component.webview.OfflineArticleClient
import me.ash.reader.ui.component.webview.OfflineArticleOrigin
import me.ash.reader.ui.component.webview.OfflineArticleTarget
import me.ash.reader.ui.component.webview.OfflineArticleWebView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Card t_0f68461a — the offline (downloaded-article) renderer must use the
 * same bundled reading fonts as the live renderers, served from APK
 * resources under the appassets origin (zero network), with the font CSS
 * injected into the served `index.html` at serve time.
 */
@RunWith(AndroidJUnit4::class)
class OfflineFontRenderTest {

    @get:Rule val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val accountId = 1
    private val articleId = "1\$offline-font-fixture"
    private val target = OfflineArticleTarget(accountId, articleId)
    private val store = DownloadStore(context)

    @Before
    fun seedFixtureDownload() {
        val staging = store.prepareStaging(accountId, articleId)
        val html =
            """<html><head><title>Font fixture</title>
               |<link rel="icon" href="data:,">
               |</head><body>
               |<p>Offline font fixture body with <em>italic</em> text.</p>
               |</body></html>"""
                .trimMargin()
        store.writeText(staging, html)
        store.writeManifestAtomic(
            staging,
            DownloadManifest(
                schemaVersion = 1,
                textComplete = true,
                assets = emptyList(),
                assetsComplete = true,
                expectedImageCount = 0,
            ),
        )
        assertTrue(store.commit(accountId, articleId))
    }

    @After
    fun cleanupFixture() {
        store.delete(accountId, articleId)
    }

    private fun clientWith(pageStyle: String?): OfflineArticleClient =
        OfflineArticleClient(
            assetLoader =
                OfflineArticleClient.assetLoaderFor(context, target.articleDir(context)),
            onOpenLink = {},
            pageStyle = pageStyle,
        )

    private fun stubRequest(url: String): WebResourceRequest =
        object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)

            override fun isForMainFrame(): Boolean = false

            override fun hasGesture(): Boolean = false

            override fun getMethod(): String = "GET"

            override fun getRequestHeaders(): Map<String, String> = emptyMap()

            override fun isRedirect(): Boolean = false
        }

    private fun fontUrl(fileName: String): String =
        OfflineArticleOrigin.ORIGIN + OfflineArticleOrigin.FONTS_PATH_PREFIX + fileName

    // --- serve-time CSS injection -------------------------------------------------

    @Test
    fun servedIndexCarriesTheInjectedReadingFontStyle() {
        val client = clientWith(ReadingFontsPreference.Literata.offlinePageStyle())
        val response = client.shouldInterceptRequest(null, stubRequest(target.pageUrl()))
        assertNotNull(response)
        val body = response!!.data.use { it.readBytes() }.toString(Charsets.UTF_8)
        assertTrue("injected style block must be present", body.contains("<style>"))
        assertTrue(
            "injected CSS must declare the Literata face",
            body.contains("url(\"fonts/literata.ttf\")"),
        )
        assertTrue(
            "injected CSS must declare the italic face",
            body.contains("url(\"fonts/literata_italic.ttf\")"),
        )
        assertTrue(
            "injected CSS must set the body family",
            body.contains("body { font-family: 'Literata', serif !important; }"),
        )
        assertTrue(
            "injection must land before the head close tag",
            body.indexOf("fonts/literata.ttf") < body.indexOf("</head>"),
        )
        assertTrue(
            "the template body text must survive injection",
            body.contains("Offline font fixture body"),
        )
    }

    @Test
    fun servedIndexIsUntouchedWithoutBundledFontChoice() {
        val client = clientWith(ReadingFontsPreference.System.offlinePageStyle())
        val response = client.shouldInterceptRequest(null, stubRequest(target.pageUrl()))
        assertNotNull(response)
        val body = response!!.data.use { it.readBytes() }.toString(Charsets.UTF_8)
        assertFalse(body.contains("@font-face"))
        assertTrue(body.contains("Offline font fixture body"))
    }

    // --- bundled font serving ------------------------------------------------------

    @Test
    fun everyWhitelistedBundledFontIsServedFromApkResources() {
        val client = clientWith(null)
        val expected =
            mapOf(
                "google_sans_flex.ttf" to 1_000_000,
                "literata.ttf" to 200_000,
                "literata_italic.ttf" to 200_000,
                "source_serif_4.ttf" to 150_000,
                "source_serif_4_italic.ttf" to 100_000,
                "atkinson_hyperlegible.ttf" to 40_000,
                "atkinson_hyperlegible_italic.ttf" to 40_000,
                "lexend.ttf" to 60_000,
            )
        for ((fileName, minBytes) in expected) {
            val response = client.shouldInterceptRequest(null, stubRequest(fontUrl(fileName)))
            assertNotNull("$fileName must be served", response)
            assertEquals("$fileName must be 200 OK", 200, response!!.statusCode)
            assertEquals("$fileName must be font/ttf", "font/ttf", response.mimeType)
            val bytes = response.data.use { it.readBytes() }
            assertTrue("$fileName must carry the real binary", bytes.size > minBytes)
            // TrueType magic number 0x00010000
            assertEquals(0x00.toByte(), bytes[0])
            assertEquals(0x01.toByte(), bytes[1])
            assertEquals(0x00.toByte(), bytes[2])
            assertEquals(0x00.toByte(), bytes[3])
        }
    }

    @Test
    fun nonWhitelistedFontPathsStayBlocked() {
        val client = clientWith(null)
        // NOTE: "fonts/../index.html" is deliberately NOT probed here — the
        // stub request skips Chromium's URL normalization, and the article
        // handler resolves it back to the SAME article's own index.html
        // (inside the mounted dir, already served at /app/index.html).
        // These probes cover unknown names and a real directory escape.
        for (path in listOf("evil.ttf", "secrets.txt", "../escaped.ttf")) {
            val response = client.shouldInterceptRequest(null, stubRequest(fontUrl(path)))
            // either an empty blocked response or a 404-shaped one — never data
            if (response != null && response.data != null) {
                val bytes = response.data.use { it.readBytes() }
                assertEquals("$path must not be served, got ${bytes.size} bytes", 0, bytes.size)
            }
        }
    }

    @Test
    fun nonAppassetsRequestsAreStillBlockedWithFontHandlerRegistered() {
        val client = clientWith(ReadingFontsPreference.Lexend.offlinePageStyle())
        for (url in listOf("https://example.com/font.ttf", "file:///android_res/font/lexend.ttf")) {
            val response = client.shouldInterceptRequest(null, stubRequest(url))
            assertNotNull(response)
            assertNull(response!!.data)
        }
    }

    // --- end-to-end through the real composable --------------------------------------

    @Test
    fun endToEndOfflineRenderRequestsOnlyLocalFontUrls() {
        val recorded = CopyOnWriteArrayList<String>()
        composeRule.setContent {
            CompositionLocalProvider(LocalReadingFonts provides ReadingFontsPreference.Literata) {
                OfflineArticleWebView(target = target, requestListener = { recorded.add(it) })
            }
        }
        composeRule.waitUntil(timeoutMillis = 20_000) {
            recorded.any { it.endsWith(OfflineArticleOrigin.FONTS_PATH_PREFIX + "literata.ttf") }
        }
        composeRule.waitForIdle()
        // give any (incorrect) extra requests a moment to surface
        Thread.sleep(1_000)

        assertTrue(
            "the injected font must be requested, saw: $recorded",
            recorded.any { it.endsWith("fonts/literata.ttf") },
        )
        val nonLocal = recorded.filter { !OfflineArticleOrigin.isAppAssetsUrl(it) }
        assertEquals(
            "non-appassets requests must be ZERO with fonts enabled, saw: $recorded",
            0,
            nonLocal.size,
        )
    }
}
