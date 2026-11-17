package me.ash.reader.ui.component.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S30a — offline render origin contract (JVM pins; OfflineRenderTest re-asserts
 * on the real WebView request path, instrumented, on the hosted CI emulator).
 */
class OfflineArticleOriginTest {

    @Test
    fun onlyAppAssetsOriginIsServable() {
        assertTrue(
            OfflineArticleOrigin.isAppAssetsUrl(
                "https://appassets.androidplatform.net/app/index.html"
            )
        )
        assertTrue(
            OfflineArticleOrigin.isAppAssetsUrl(
                "https://appassets.androidplatform.net/app/assets/abc.png"
            )
        )
        // other origins, other paths on the origin, and non-https are blocked
        assertFalse(OfflineArticleOrigin.isAppAssetsUrl("https://evil.example/app/index.html"))
        assertFalse(OfflineArticleOrigin.isAppAssetsUrl("https://appassets.androidplatform.net/other/x"))
        assertFalse(OfflineArticleOrigin.isAppAssetsUrl("http://appassets.androidplatform.net/app/index.html"))
        assertFalse(OfflineArticleOrigin.isAppAssetsUrl("https://cdn.example/img.png"))
    }

    @Test
    fun dangerousSchemesAreBlocked() {
        assertEquals(
            listOf("javascript:", "data:", "file:", "intent:", "content:"),
            OfflineArticleOrigin.BLOCKED_SCHEMES,
        )
        for (scheme in OfflineArticleOrigin.BLOCKED_SCHEMES) {
            assertTrue(OfflineArticleOrigin.isBlockedScheme("${scheme}payload"))
        }
        assertFalse(OfflineArticleOrigin.isBlockedScheme("https://example.com"))
        assertFalse(OfflineArticleOrigin.isBlockedScheme("relative/path"))
    }

    @Test
    fun schemeBlockIsCaseInsensitive() {
        assertTrue(OfflineArticleOrigin.isBlockedScheme("JavaScript:alert(1)"))
        assertTrue(OfflineArticleOrigin.isBlockedScheme("FILE:///etc/passwd"))
    }
}
