package me.ash.reader.domain.service

import java.util.Date
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.article.UpdateDetection
import me.ash.reader.domain.model.download.DownloadHtmlSanitizer
import me.ash.reader.infrastructure.html.ExtractCleaner
import me.ash.reader.infrastructure.html.Readability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// ---------------------------------------------------------------------------
// SPEC-EXTRACT amendment A1 — productive-ratio guard basis fixtures.
//
// A1 moved the E16.1 ratio guard's basis from the RAW fetched document to the
// SANITIZED FRAGMENT (`plainTextLength × 20 < sanitizedFragment.length`). The
// fixtures below are synthetic per D16 and mirror the device-verified
// Engadget diagnosis on 0.1.4: a ~61 KB raw page whose complete ~2.5K-char
// article fragment was false-rejected because 2,503 × 20 = 50,060 < 61,559.
// ---------------------------------------------------------------------------

internal const val HEAVY_PAGE_TITLE = "Waymo's robotaxis are expanding to Miami"

/**
 * Heavy modern news page (the Engadget byte profile): ~61 KB raw document,
 * most of it chrome the extracted fragment never contains — an inline CSS
 * bundle, a JS boot-config payload, primary nav, a trending aside and a
 * legal footer — around one `<article>` with [paragraphCount] substantial
 * paragraphs (~2.5K chars of text total, a few KB of fragment HTML).
 *
 * Padding is computed so the raw length lands on [HEAVY_PAGE_RAW_TARGET]
 * deterministically; nothing here may contain the S19 challenge markers.
 */
internal fun heavyNewsPageFixture(paragraphCount: Int = 16): String {
    val head = buildString {
        append("<meta charset=\"utf-8\">")
        append("<title>").append(HEAVY_PAGE_TITLE).append(" — TechDaily</title>")
        append("<style>")
        repeat(170) {
            append(
                ".site-chrome-layout-region-$it { display: flex; align-items: center; " +
                    "justify-content: space-between; margin: 0 auto; padding: 12px 16px; }",
            )
        }
        append("</style>")
    }
    val mastheadAndNav = buildString {
        append("<header class=\"site-masthead\"><a href=\"/\" class=\"brand-wordmark\">TechDaily</a></header>")
        append("<nav class=\"primary-navigation\"><ul>")
        repeat(60) {
            append("<li><a href=\"/section/gadget-reviews-$it\" class=\"navigation-entry-$it\">Section $it</a></li>")
        }
        append("</ul></nav>")
    }
    val article = buildString {
        append("<main class=\"content-main\"><article class=\"post-body\">")
        append("<h1>").append(HEAVY_PAGE_TITLE).append("</h1>")
        repeat(paragraphCount) {
            append("<p>").append(TEST_PARAGRAPH)
            append(
                " <a class=\"story-inline-reference\" " +
                    "href=\"https://techdaily.example/2026-11-18/waymo-robotaxi-miami-launch-coverage-index\">More</a>",
            )
            // Inline photo (no text; keeps the fragment HTML in the diagnosed
            // 6–7 KB band while the article text stays ~2.5K chars).
            append(
                "<img src=\"https://cdn.techdaily.example/2026/11/18/robotaxi-miami-inline-photo-$it.jpg\">",
            )
            append("</p>")
        }
        append("</article>")
    }
    val aside = buildString {
        append("<aside class=\"recommended-stories\"><h2>Trending</h2><ul>")
        repeat(25) {
            append("<li><a href=\"/trending/story-$it\">Trending story number $it</a></li>")
        }
        append("</ul></aside></main>")
    }
    val footer = buildString {
        append("<footer class=\"site-footer\"><ul>")
        repeat(30) {
            append("<li><a href=\"/legal/term-$it\">Legal term $it</a></li>")
        }
        append("</ul></footer>")
    }

    fun assemble(scriptPayload: String): String =
        "<!DOCTYPE html><html lang=\"en\"><head>" + head +
            (if (scriptPayload.isEmpty()) "" else "<script>$scriptPayload</script>") +
            "</head><body>" + mastheadAndNav + article + aside + footer + "</body></html>"

    // Inert JS boot config pads the raw document to the heavy-page byte
    // profile, exactly how real news sites carry weight OUTSIDE the article.
    val chunkPrefix = "window.__SITE_BOOT__ = window.__SITE_BOOT__ || {}; "
    val skeleton = assemble("")
    val scriptPayload = buildString {
        var slot = 0
        while (true) {
            val chunk = chunkPrefix + "window.__SITE_BOOT__.slot$slot = { enabled: true, variant: 'control' };"
            if (skeleton.length + length + "<script></script>".length + chunk.length > HEAVY_PAGE_RAW_TARGET) break
            append(chunk)
            slot++
        }
    }
    return assemble(scriptPayload)
}

/** Raw-document byte profile of the diagnosed Engadget page (61,559 bytes). */
internal const val HEAVY_PAGE_RAW_TARGET = 61_000

/**
 * Markup-heavy junk fragment for the retained E16.1 guard (A1 test 2):
 * ~280 chars of text inside ~11 KB of surviving fragment markup. The filler
 * anchors carry long hrefs and a one-char label because RYArticleGrabberExtended
 * drops DIV/SECTION/HEADER/H1–H6 nodes without content AND readability4j
 * strips `class` attributes — empty-div or attribute-padded filler never
 * reaches the fragment, so the OLD fixture pinned the raw-document basis by
 * accident. Only markup the whole pipeline preserves (anchor hrefs, per the
 * heavy-page probe) can make the FRAGMENT itself markup-heavy. The guard must
 * reject under BOTH bases: raw (~11 KB) and fragment (~11 KB) each exceed
 * 20 × text (~8 K).
 */
internal fun markupHeavyJunkPage(): String {
    val junk = buildString {
        repeat(120) {
            val entry = "%04d".format(it)
            append(
                "<a href=\"https://sponsored-link-farm.example/entry/$entry" +
                    "?utm_campaign=filler_payload\">·</a>",
            )
        }
    }
    return "<!DOCTYPE html><html><head><title>t</title></head><body><article>" +
        "<p>$TEST_PARAGRAPH$junk</p></article></body></html>"
}

/**
 * Runs the exact production stages (Readability → ExtractCleaner → sanitize)
 * a fixture would travel through, for fixture characterization in tests.
 */
internal fun pipelineSanitizedFragment(page: String, title: String): String {
    val parsed = Readability.parseToElement(page, EXTRACT_TEST_LINK)
        ?: error("fixture must be Readability-parseable (got null articleContent)")
    return DownloadHtmlSanitizer.sanitize(ExtractCleaner.clean(parsed.toString(), title))
}

/**
 * Amendment A1 regression — the Engadget false-reject. A heavy (~61 KB raw)
 * page whose Readability-parseable article is a COMPLETE ~2.5K-char fragment
 * MUST store: the site chrome outside the extracted fragment no longer counts
 * against the productive ratio. Under the pre-A1 raw-document basis this
 * exact fixture was Failure(UNPRODUCTIVE) (20 × text < rawLen), which is the
 * bug Jason reproduced on device in 0.1.4.
 *
 * REDCHECK target (recorded in the review handoff): restoring the
 * `body.html.length` basis in ExtractCoordinator turns this test red.
 */
class ExtractCoordinatorRatioBasisTest {

    @Test
    fun heavyPageWithCompleteArticleFragmentIsStored() = runBlocking {
        val page = heavyNewsPageFixture()

        // Fixture verification (card D16): pin the arithmetic that makes this
        // page an A1 regression BEFORE asserting the coordinator outcome.
        val sanitized = pipelineSanitizedFragment(page, HEAVY_PAGE_TITLE)
        val textLength = UpdateDetection.plainTextLength(sanitized)
        assertTrue("fixture yields ~2.5K chars of article text, got $textLength", textLength in 2000..3500)
        assertTrue(
            "old raw-document basis must reject this page (20×$textLength < raw ${page.length})",
            textLength * 20L < page.length.toLong(),
        )
        assertTrue(
            "A1 fragment basis must accept this page (20×$textLength >= fragment ${sanitized.length})",
            textLength * 20L >= sanitized.length.toLong(),
        )

        val recorder = DaoRecorder()
        val transport = FakeTransport()
        transport.respond(EXTRACT_TEST_LINK, page)
        val coordinator = testExtractCoordinator(transport = transport, dao = fakeArticleDao(recorder))

        val outcome = coordinator.extract(
            extractTestArticle(title = HEAVY_PAGE_TITLE),
            extractTestFeed(),
            ExtractMode.MANUAL,
        )

        assertTrue("A1: heavy page with a complete article MUST store, got $outcome", outcome is ExtractOutcome.Stored)
        assertEquals("1\$article-1", (outcome as ExtractOutcome.Stored).articleId)
        assertEquals(Date(EXTRACT_TEST_NOW), outcome.extractedAt)
        assertEquals(1, recorder.writeCount)
        assertEquals(1, transport.openCount)

        val stored = recorder.lastContent!!
        // The article text is what got written; page chrome stayed out of the
        // fragment, and the exact-title h1 was dropped by the cleaner (E15.1).
        assertTrue(stored.contains("quick brown fox"))
        assertFalse(stored.contains("primary-navigation"))
        assertFalse(stored.contains("Trending story number"))
        assertFalse(stored.contains("__SITE_BOOT__"))
        assertFalse(stored.contains("<h1", ignoreCase = true))
    }

    /**
     * A1 test (b) companion to [ExtractCoordinatorTest.unproductiveRatioIsTypedNoOp]:
     * the junk fixture's fragment itself is markup-heavy, so the guard rejects
     * on the NEW basis too — it did not survive A1 merely because the raw
     * document is large.
     */
    @Test
    fun markupHeavyJunkFragmentRejectsUnderFragmentBasis() {
        val page = markupHeavyJunkPage()
        val sanitized = pipelineSanitizedFragment(page, "Test Article")
        val textLength = UpdateDetection.plainTextLength(sanitized)
        assertTrue(
            "junk fragment must stay markup-heavy under A1 (20×$textLength < fragment ${sanitized.length})",
            textLength * 20L < sanitized.length.toLong(),
        )
        assertTrue("junk fixture text is short (~280 chars), got $textLength", textLength < 500)
    }
}
