package me.ash.reader.ui.component.webview

import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import me.ash.reader.domain.model.download.DownloadManifest
import me.ash.reader.domain.model.download.DownloadStore
import me.ash.reader.infrastructure.html.ReaderTables
import me.ash.reader.ui.theme.palette.FeedBucketColorSchemes
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Card t_1ed5c518 — LOCAL-ONLY visual harness for the reader-table fixtures.
 *
 * Gated on the instrumentation argument `fbTableShots=1`: hosted CI (which
 * runs the full suite with no arguments) passes straight through. Locally the
 * operator drives it with the deferred adb tools:
 *
 *   ./gradlew :app:connectedGithubDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=me.ash.reader.ui.component.webview.ReaderTableScreenshotTest \
 *     -Pandroid.testInstrumentationRunnerArguments.fbTableShots=1
 *
 * Each test renders one variant, touches `/sdcard/fb-table-shots/ready_NAME`
 * and then waits (max 5 min) for the operator to capture the screen and
 * touch `/sdcard/fb-table-shots/done_NAME`. Wide variants raise the emulated
 * display to 960dp via `wm size` and always reset it in `finally`.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ReaderTableScreenshotTest {

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun enabled(): Boolean = InstrumentationRegistry.getArguments().getString("fbTableShots") == "1"

    private fun shell(cmd: String): String {
        val pfd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { String(it.readBytes()) }
    }

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
        return JSONObject(JSONTokener(raw).nextValue() as String)
    }

    /** Render the live WebView pipeline with the fixture; wait for labels. */
    private fun renderFixture(dark: Boolean, widthDp: Int?) {
        composeRule.setContent {
            MaterialTheme(
                colorScheme = if (dark) FeedBucketColorSchemes.Dark else FeedBucketColorSchemes.Light,
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val boxModifier =
                        if (widthDp != null) {
                            Modifier.width(widthDp.dp).fillMaxHeight()
                        } else {
                            Modifier.fillMaxSize()
                        }
                    Box(modifier = boxModifier) {
                        RYWebView(content = ReaderTableFixture.HTML)
                    }
                }
            }
        }
        awaitLabels()
    }

    private fun renderOfflineFixture() {
        val accountId = 78
        val articleId = "78\$reader-table-shot-fixture"
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
        composeRule.setContent {
            OfflineArticleWebView(target = target, modifier = Modifier.fillMaxSize())
        }
        // JavaScript is disabled on this path; just let the CSS-driven layout settle.
        composeRule.waitUntil(timeoutMillis = 20_000) {
            findWebView(composeRule.activity.window.decorView) != null
        }
        Thread.sleep(3_000)
        composeRule.waitForIdle()
    }

    private fun awaitLabels() {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(400)
            composeRule.waitForIdle()
            val webView = findWebView(composeRule.activity.window.decorView)
            if (webView != null) {
                val json = runCatching { evalJson(webView, ReaderTableFixture.PROBE) }.getOrNull()
                if ((json?.optInt("labelCount") ?: 0) >= ReaderTableFixture.EXPECTED_LABELED_TDS) break
            }
        }
        // let fonts/paint settle so the capture is representative
        Thread.sleep(2_000)
        composeRule.waitForIdle()
    }

    private fun handshake(name: String) {
        shell("mkdir -p /sdcard/fb-table-shots && touch /sdcard/fb-table-shots/ready_$name")
        val deadline = System.currentTimeMillis() + 300_000
        while (System.currentTimeMillis() < deadline) {
            if (shell("ls /sdcard/fb-table-shots").contains("done_$name")) return
            Thread.sleep(1_000)
        }
        throw AssertionError("operator did not capture screenshot '$name' within 5 minutes")
    }

    private fun wideDisplay(block: () -> Unit) {
        shell("wm size 1440x2560")
        shell("wm density 240")
        Thread.sleep(4_000) // let the display + activity recreation settle
        try {
            block()
        } finally {
            shell("wm size reset")
            shell("wm density reset")
            Thread.sleep(2_000)
        }
    }

    @Test
    fun shot1LightPhone360() {
        if (!enabled()) return
        renderFixture(dark = false, widthDp = 360)
        handshake("light_phone_360")
    }

    @Test
    fun shot2DarkPhone360() {
        if (!enabled()) return
        renderFixture(dark = true, widthDp = 360)
        handshake("dark_phone_360")
    }

    @Test
    fun shot3OfflineLight() {
        if (!enabled()) return
        renderOfflineFixture()
        handshake("offline_light")
        // clean the seeded fixture download
        DownloadStore(context).delete(78, "78\$reader-table-shot-fixture")
    }

    @Test
    fun shot4LightWide() {
        if (!enabled()) return
        wideDisplay {
            renderFixture(dark = false, widthDp = null)
            handshake("light_wide")
        }
    }

    @Test
    fun shot5DarkWide() {
        if (!enabled()) return
        wideDisplay {
            renderFixture(dark = true, widthDp = null)
            handshake("dark_wide")
        }
        shell("rm -rf /sdcard/fb-table-shots")
        // sanity: the display override is gone
        assertTrue(shell("wm size").contains("Physical size"))
        assertEquals("", shell("wm size").substringAfter("Override size", "").trim())
    }
}
