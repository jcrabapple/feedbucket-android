package me.ash.reader.ui.component.webview

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import me.ash.reader.R
import me.ash.reader.domain.model.download.DownloadManifest
import me.ash.reader.domain.model.download.DownloadStore
import me.ash.reader.infrastructure.html.ReaderTables
import me.ash.reader.ui.component.reader.htmlFormattedText
import me.ash.reader.ui.theme.palette.FeedBucketColorSchemes
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Card t_1ed5c518 — instrumented contract for responsive reader tables.
 *
 * WebView renderer: the fixture is loaded into the REAL [RYWebView] pipeline
 * (WebViewStyle CSS + WebViewScript label JS) at phone width (~360dp) and at
 * wide width (>=600 CSS px). Phone: rows stack into cards, every fixture td
 * carries the data-label of its column header, and the document never
 * overflows horizontally. Wide: a real table layout is kept inside the
 * overflow-x wrapper. Native renderer: data rows flatten to
 * "<header>: <value>" lines instead of running columns together. Offline
 * renderer: the Kotlin-annotated page (JavaScript disabled there) renders
 * with zero non-local requests.
 */
@RunWith(AndroidJUnit4::class)
class ReaderTableInstrumentedTest {

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    // --- helpers ---------------------------------------------------------------

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findWebView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun evalJson(webView: WebView, script: String): JSONObject {
        val latch = CountDownLatch(1)
        var raw: String? = null
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(script) { value ->
                raw = value
                latch.countDown()
            }
        }
        assertTrue("evaluateJavascript timed out", latch.await(10, TimeUnit.SECONDS))
        val inner = JSONTokener(raw).nextValue()
        return JSONObject(inner as String)
    }

    /** Compose [RYWebView] with the fixture and wait until the label script ran. */
    private fun renderFixtureAndWaitForLabels(widthDp: Int): JSONObject {
        composeRule.setContent {
            MaterialTheme(colorScheme = FeedBucketColorSchemes.Light) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.width(widthDp.dp).fillMaxHeight()) {
                        RYWebView(content = ReaderTableFixture.HTML)
                    }
                }
            }
        }
        val deadline = System.currentTimeMillis() + 30_000
        var json: JSONObject? = null
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(400)
            composeRule.waitForIdle()
            val webView = findWebView(composeRule.activity.window.decorView)
            if (webView != null) {
                json = runCatching { evalJson(webView, ReaderTableFixture.PROBE) }.getOrNull()
                if ((json?.optInt("labelCount") ?: 0) >= ReaderTableFixture.EXPECTED_LABELED_TDS) break
            }
        }
        val result = json
        assertTrue("WebView never reached the labeled state: $result", result != null)
        assertEquals(
            "every data td of the 2/3/4-column tables must carry its column label",
            ReaderTableFixture.EXPECTED_LABELED_TDS,
            result!!.getInt("labelCount"),
        )
        return result
    }

    // --- WebView renderer: phone width ------------------------------------------

    @Test
    fun phoneWidthStacksRowsIntoLabeledCardsWithoutOverflow() {
        val json = renderFixtureAndWaitForLabels(widthDp = 360)

        val innerWidth = json.getInt("innerWidth")
        assertTrue("phone viewport expected, got $innerWidth", innerWidth in 320..420)
        assertTrue(
            "document must never overflow horizontally (scrollWidth=${json.getInt("scrollWidth")}, innerWidth=$innerWidth)",
            json.getInt("scrollWidth") <= innerWidth,
        )
        assertEquals("no td may stick out of the viewport", 0, json.getInt("overflowingCells"))

        // stacked card layout
        assertEquals("block", json.getString("tiersRowDisplay"))
        assertEquals("none", json.getString("theadDisplay"))
        assertEquals("table-scroll", json.getString("wrapperClass"))
        assertEquals("auto", json.getString("wrapperOverflowX"))

        // each cell shows its column header above the value
        val expected = ReaderTableFixture.EXPECTED_TIER_LABELS
        assertEquals(List(3) { expected }.flatten(), ReaderTableFixture.labelsOf(json))
        assertTrue(
            "td::before must render the label, got ${json.getString("beforeContent")}",
            json.getString("beforeContent").contains("Tier"),
        )

        // tables with fewer than two columns are skipped
        assertEquals(0, json.getInt("singleColLabeled"))
    }

    // --- WebView renderer: wide width --------------------------------------------

    @Test
    fun wideWidthKeepsRealTableInsideScrollWrapper() {
        composeRule.setContent {
            MaterialTheme(colorScheme = FeedBucketColorSchemes.Light) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // requiredWidth escapes the phone-screen constraint so the
                    // WebView viewport crosses the 600 CSS px breakpoint
                    Box(modifier = Modifier.requiredWidth(640.dp).fillMaxHeight()) {
                        RYWebView(content = ReaderTableFixture.HTML)
                    }
                }
            }
        }
        val deadline = System.currentTimeMillis() + 30_000
        var json: JSONObject? = null
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(400)
            composeRule.waitForIdle()
            val webView = findWebView(composeRule.activity.window.decorView)
            if (webView != null) {
                json = runCatching { evalJson(webView, ReaderTableFixture.PROBE) }.getOrNull()
                if ((json?.optInt("labelCount") ?: 0) >= ReaderTableFixture.EXPECTED_LABELED_TDS) break
            }
        }
        assertTrue("WebView never reached the labeled state: $json", json != null)

        val innerWidth = json!!.getInt("innerWidth")
        assertTrue("wide viewport expected, got $innerWidth", innerWidth >= 600)
        assertTrue(
            "document must never overflow horizontally (scrollWidth=${json.getInt("scrollWidth")})",
            json.getInt("scrollWidth") <= innerWidth,
        )
        assertEquals("table", json.getString("tiersTableDisplay"))
        assertEquals("table-row", json.getString("tiersRowDisplay"))
        assertEquals("table-header-group", json.getString("theadDisplay"))
        assertEquals("table-scroll", json.getString("wrapperClass"))
        assertEquals("auto", json.getString("wrapperOverflowX"))
        assertEquals("none", json.getString("beforeContent"))
        assertEquals(0, json.getInt("overflowingCells"))
    }

    // --- native Compose renderer ---------------------------------------------------

    @Test
    fun nativeRendererFlattensRowsToHeaderColonValueLines() {
        composeRule.setContent {
            MaterialTheme(colorScheme = FeedBucketColorSchemes.Light) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        htmlFormattedText(
                            inputStream = ReaderTableFixture.HTML.byteInputStream(),
                            baseUrl = "https://example.com/article",
                            imagePlaceholder = R.drawable.ic_launcher_foreground,
                            onLinkClick = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // every cell of a data row renders as its own "Header: value" line
        composeRule
            .onNodeWithText("Tier: Azure VM (NC/D series)", substring = true)
            .assertExists()
        composeRule
            .onNodeWithText("Cost driver: Pay-as-you-go per-second billing", substring = true)
            .assertExists()
        composeRule
            .onNodeWithText(
                "Gotchas: Must provision and de-provision manually or via Scale Sets",
                substring = true,
            ).assertExists()

        // the old bug concatenated cell texts with no separator at all
        composeRule
            .onAllNodesWithText("NC/D series)Short", substring = true)
            .assertCountEquals(0)

        // the header row itself is not rendered as a data row
        composeRule
            .onAllNodesWithText("Tier: Tier", substring = true)
            .assertCountEquals(0)

        // tables further down keep the same contract
        composeRule
            .onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText("Service: OpenAI proxy", substring = true))
        composeRule.onNodeWithText("Region: East US", substring = true).assertExists()
    }

    // --- offline renderer (JavaScript disabled) --------------------------------------

    @Test
    fun offlinePageRendersAnnotatedTablesWithZeroNonLocalRequests() {
        val accountId = 77
        val articleId = "77\$reader-table-offline-fixture"
        val target = OfflineArticleTarget(accountId, articleId)
        val store = DownloadStore(context)
        val staging = store.prepareStaging(accountId, articleId)
        store.writeText(staging, ReaderTables.offlinePage(ReaderTableFixture.HTML))
        store.writeManifestAtomic(
            staging,
            DownloadManifest(
                schemaVersion = DownloadManifest.CURRENT_SCHEMA_VERSION,
                textComplete = true,
                assets = emptyList(),
                assetsComplete = true,
                expectedImageCount = 0,
            ),
        )
        assertTrue(store.commit(accountId, articleId))
        try {
            val recorded = CopyOnWriteArrayList<String>()
            composeRule.setContent {
                OfflineArticleWebView(target = target, requestListener = { recorded.add(it) })
            }
            composeRule.waitUntil(timeoutMillis = 20_000) {
                recorded.any { it.endsWith(OfflineArticleOrigin.PATH_PREFIX + "index.html") }
            }
            composeRule.waitForIdle()

            val nonLocal =
                recorded.filter { !OfflineArticleOrigin.isAppAssetsUrl(it) }
            assertEquals(
                "offline render must issue ZERO non-local requests, saw: $recorded",
                0,
                nonLocal.size,
            )

            // the stored page carries the Kotlin-derived annotations (the
            // hardened renderer runs no JavaScript, so nothing is derived at
            // runtime there)
            val page = File(target.articleDir(context), "index.html").readText()
            assertTrue(page.contains("data-label=\"Tier\""))
            assertTrue(page.contains("data-label=\"Gotchas\""))
            assertTrue(page.contains(ReaderTables.TABLE_CSS))
            assertTrue(page.contains("class=\"${ReaderTables.SCROLL_WRAPPER_CLASS}\""))
            assertFalse("offline page must stay script-free", page.contains("<script"))
        } finally {
            store.delete(accountId, articleId)
        }
    }
}
