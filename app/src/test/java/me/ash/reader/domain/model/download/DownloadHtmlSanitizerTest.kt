package me.ash.reader.domain.model.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S30a — downloaded index.html is sanitized at WRITE time: Jsoup
 * `Safelist.relaxed()` extended with `class` attributes and http/https/mailto
 * protocols only; `<script>`, event handlers and javascript:/data:/file: URIs
 * stripped (D16). Image srcs are rewritten to local asset paths; every
 * http(s) img src is collected as a download candidate.
 */
class DownloadHtmlSanitizerTest {

    @Test
    fun scriptAndEventHandlersStripped() {
        val dirty =
            "<div onclick=\"evil()\"><script>alert(1)</script><p>ok</p></div>"
        val clean = DownloadHtmlSanitizer.sanitize(dirty)
        assertFalse(clean.contains("<script"))
        assertFalse(clean.contains("onclick"))
        assertTrue(clean.contains("ok"))
    }

    @Test
    fun javascriptAndDataUrisStripped() {
        val dirty =
            """<a href="javascript:evil()">x</a><a href="data:text/html,bad">y</a><a href="https://ok.example">z</a>"""
        val clean = DownloadHtmlSanitizer.sanitize(dirty)
        assertFalse(clean.contains("javascript:"))
        assertFalse(clean.contains("data:text/html"))
        assertTrue(clean.contains("https://ok.example"))
    }

    @Test
    fun classAttributeAllowed() {
        val clean = DownloadHtmlSanitizer.sanitize("""<p class="intro">hi</p>""")
        assertTrue(clean.contains("class=\"intro\""))
    }

    @Test
    fun fileAndIntentUrisStrippedFromHrefs() {
        val dirty =
            """<a href="file:///etc/passwd">f</a><a href="intent:evil#Intent;end">i</a>"""
        val clean = DownloadHtmlSanitizer.sanitize(dirty)
        assertFalse(clean.contains("file:///etc/passwd"))
        assertFalse(clean.contains("intent:"))
    }

    @Test
    fun rewriteMapsHttpImagesToLocalPathsAndCollectsCandidates() {
        val html =
            """<p><img src="https://cdn.example/a.png"><img src="http://cdn.example/b.jpg"></p>"""
        val localPaths = mutableMapOf(
            "https://cdn.example/a.png" to "assets/a.png",
            "http://cdn.example/b.jpg" to "assets/b.jpg",
        )
        val (rewritten, candidates) = DownloadHtmlSanitizer.rewriteImages(html) { localPaths[it] }
        assertEquals(listOf("https://cdn.example/a.png", "http://cdn.example/b.jpg"), candidates)
        assertTrue(rewritten.contains("src=\"assets/a.png\""))
        assertTrue(rewritten.contains("src=\"assets/b.jpg\""))
    }

    @Test
    fun rewriteDropsUnmappedRemoteImagesSoNoNetworkIsNeeded() {
        // An image that did not land locally has its src removed: the offline
        // render must issue ZERO network calls (missing -> placeholder, S27a).
        val html = """<img src="https://cdn.example/missing.png" alt="pic">"""
        val (rewritten, candidates) = DownloadHtmlSanitizer.rewriteImages(html) { null }
        assertEquals(listOf("https://cdn.example/missing.png"), candidates)
        assertFalse(rewritten.contains("https://cdn.example/missing.png"))
        assertTrue(rewritten.contains("alt=\"pic\""))
    }

    @Test
    fun assetFileNameForIsStableSha256WithExtension() {
        val name1 = DownloadHtmlSanitizer.assetFileName("https://example.com/x/pic.PNG?query=1")
        val name2 = DownloadHtmlSanitizer.assetFileName("https://example.com/x/pic.PNG?query=1")
        assertEquals(name1, name2)
        assertTrue(name1.endsWith(".png"))
        assertFalse(name1.contains("/"))
        assertFalse(name1.contains("?"))
    }

    @Test
    fun challengeMarkersRejected() {
        assertTrue(DownloadHtmlSanitizer.isChallenge("<title>Just a moment...</title>html"))
        assertTrue(DownloadHtmlSanitizer.isChallenge("Access Denied body"))
        assertFalse(DownloadHtmlSanitizer.isChallenge("<p>ordinary article body</p>"))
        assertFalse(DownloadHtmlSanitizer.isChallenge(""))
    }
}
