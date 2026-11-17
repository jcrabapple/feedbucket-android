package me.ash.reader.offline

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import me.ash.reader.domain.model.download.DownloadManifest
import me.ash.reader.domain.model.download.DownloadStore
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
 * Acceptance #14 (amended, R5) — instrumented, on the hosted CI emulator.
 *
 * Renders a FIXTURE-downloaded article through the REAL hardened offline
 * renderer entry ([OfflineArticleWebView] — the component Content routes
 * downloaded articles to) with a recording client wrapper: every requested
 * URL must be appassets-origin and the non-local request count must be ZERO
 * (the OkHttp-interceptor check is insufficient — the online client fetches
 * via raw HttpURLConnection in shouldInterceptRequest; this test watches the
 * ACTUAL WebView request path). Also asserts the hardened WebSettings values
 * and the S30a scheme block (javascript:/data:/file:/intent:/content: never
 * load and never navigate on this path).
 */
@RunWith(AndroidJUnit4::class)
class OfflineRenderTest {

    @get:Rule val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val accountId = 1
    private val articleId = "1\$offline-render-fixture"
    private val target = OfflineArticleTarget(accountId, articleId)
    private val store = DownloadStore(context)

    private val imageUrl = "https://cdn.example.com/pic.png"

    @Before
    fun seedFixtureDownload() {
        // A fixture "download" produced through the real store: asset file,
        // sanitized text with a LOCAL img src, manifest written LAST, atomic
        // commit (S27a staging/atomic-rename/manifest contract).
        val staging = store.prepareStaging(accountId, articleId)
        val assetDir = java.io.File(staging, "assets")
        assetDir.mkdirs()
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(32)
        java.io.File(assetDir, "pic.png").writeBytes(pngBytes)
        val html =
            """<html><head><title>Fixture</title></head><body>
               |<p>Offline fixture body</p>
               |<img src="assets/pic.png">
               |</body></html>""".trimMargin()
        store.writeText(staging, html)
        store.writeManifestAtomic(
            staging,
            DownloadManifest(
                schemaVersion = 1,
                textComplete = true,
                assets =
                    listOf(
                        DownloadManifest.Asset(
                            url = imageUrl,
                            path = "assets/pic.png",
                            bytes = pngBytes.size.toLong(),
                            sha256 = sha256(pngBytes),
                        )
                    ),
                assetsComplete = true,
                expectedImageCount = 1,
            ),
        )
        assertTrue(store.commit(accountId, articleId))
    }

    @After
    fun cleanupFixture() {
        store.delete(accountId, articleId)
    }

    @Test
    fun rendersFixtureThroughHardenedEntryWithZeroNonLocalRequests() {
        val recorded = CopyOnWriteArrayList<String>()
        composeRule.setContent {
            OfflineArticleWebView(
                target = target,
                requestListener = { recorded.add(it) },
            )
        }
        // Wait until the page load hit the interceptor.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            recorded.any { it.endsWith(OfflineArticleOrigin.PATH_PREFIX + "index.html") }
        }
        composeRule.waitForIdle()

        assertTrue("page load must hit the interceptor", recorded.isNotEmpty())
        val nonLocal = recorded.filter { !OfflineArticleOrigin.isAppAssetsUrl(it) }
        assertEquals(
            "non-appassets requests must be ZERO (offline render), saw: $recorded",
            0,
            nonLocal.size,
        )
        assertTrue(
            "every served URL must be appassets-origin, saw: $recorded",
            recorded.all { OfflineArticleOrigin.isAppAssetsUrl(it) },
        )
    }

    @Test
    fun hardenedWebSettingsAreApplied() {
        val webView = newWebViewOnMainThread()
        OfflineArticleClient.harden(webView.settings)
        assertFalse(webView.settings.javaScriptEnabled)
        assertFalse(webView.settings.domStorageEnabled)
        assertFalse(webView.settings.allowFileAccess)
        assertFalse(webView.settings.allowContentAccess)
        assertFalse(webView.settings.allowFileAccessFromFileURLs)
        assertFalse(webView.settings.allowUniversalAccessFromFileURLs)
        // S30a: no JavascriptInterface on this path.
        // (WebView has no enumeration API; the composable never calls
        // addJavascriptInterface — asserted by construction and by the
        // scheme/loader behavior tests below.)
    }

    @Test
    fun dangerousSchemesNeverLoadAndNeverNavigate() {
        val openedExternally = mutableListOf<String>()
        val client =
            OfflineArticleClient(
                assetLoader = OfflineArticleClient.assetLoaderFor(context, target.articleDir(context)),
                onOpenLink = { openedExternally.add(it) },
            )
        val webView = newWebViewOnMainThread()

        for (scheme in OfflineArticleOrigin.BLOCKED_SCHEMES) {
            val url = scheme + "payload"
            val response = client.shouldInterceptRequest(webView, request(url))
            assertNull(
                "$url must be blocked with an empty response",
                response?.data,
            )
            // Non-load: any navigation attempt is intercepted and routed out.
            assertTrue(
                "$url must never navigate in-view",
                client.shouldOverrideUrlLoading(webView, request(url)),
            )
        }
        assertEquals(OfflineArticleOrigin.BLOCKED_SCHEMES.size, openedExternally.size)
    }

    @Test
    fun appAssetsRequestsAreServedFromTheStoreAndExternalLinksRouteOut() {
        val openedExternally = mutableListOf<String>()
        val client =
            OfflineArticleClient(
                assetLoader = OfflineArticleClient.assetLoaderFor(context, target.articleDir(context)),
                onOpenLink = { openedExternally.add(it) },
            )
        val webView = newWebViewOnMainThread()

        val served = client.shouldInterceptRequest(webView, request(target.pageUrl()))
        assertNotNull("fixture index.html must be served from the store", served?.data)

        // Link taps route to the external browser intent, never in-view.
        assertTrue(client.shouldOverrideUrlLoading(webView, request("https://example.com/article")))
        assertEquals(listOf("https://example.com/article"), openedExternally)
    }

    /** WebView must be created on a Looper thread (main), with a themed context. */
    private fun newWebViewOnMainThread(): WebView {
        var webView: WebView? = null
        val themed =
            android.view.ContextThemeWrapper(
                context,
                android.R.style.Theme_Material_Light_NoActionBar,
            )
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .runOnMainSync { webView = WebView(themed) }
        return webView!!
    }

    private fun request(url: String): WebResourceRequest =
        object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)
            override fun isForMainFrame(): Boolean = true
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = false
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): Map<String, String> = emptyMap()
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
