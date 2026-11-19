package me.ash.reader.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Date
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.model.query.TextNormalizer
import me.ash.reader.domain.model.rule.ActionRule
import me.ash.reader.infrastructure.db.AndroidDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Wave X4 task 2 — E22 Wave-E pin (SPEC-EXTRACT-v1): once LOCAL extraction
 * writes `fullContent` via the X1 `applyExtraction` path, the SHIPPED Wave E
 * machinery must activate for that row WITHOUT any Wave E code change:
 *
 *  - a feed refresh with a changed `rawDescription` + `showUpdates = true`
 *    takes the extracted row through `applyExtractedUpdate` — content fields
 *    untouched, `originalContent` stays NULL (the snapshot gate's `!extracted`
 *    term), `isUpdated`/`contentChangedAt` move ONLY per the existing
 *    compare-key/markUpdated rules;
 *  - a NON-extracted control row in the SAME `storeEntries` transaction
 *    snapshots normally — proving the gate discriminates on the LOCAL
 *    extraction write, not merely on imported rows (E24);
 *  - a compare-key change (title) on the extracted row updates metadata
 *    (title/isUpdated/contentChangedAt per showUpdates) while content fields
 *    and the snapshot stay excluded.
 *
 * REDCHECK (recorded in the review handoff, mutation restored before commit):
 * flipping `UpdateDetection.decide`'s `val extracted = existing.fullContent
 * != null` to `false` makes the extracted row take the web path — snapshot
 * written + `rawDescription` clobbered — turning
 * [localExtractionActivatesWaveEGatesWhileControlRowSnapshots] red.
 */
@RunWith(AndroidJUnit4::class)
class ExtractedDiffExclusionTest {

    private lateinit var db: AndroidDatabase
    private lateinit var feed: Feed

    private val t0 = Date(1_800_000_000_000L)
    private val t1 = Date(1_800_000_100_000L)
    private val extractedAt = Date(1_800_000_050_000L)

    private val oldBody = "<p>${"o".repeat(200)}</p>"
    private val newBody = "<p>${"o".repeat(60)}${"n".repeat(80)}</p>"
    private val extractedHtml = "<p>locally extracted full text</p>"

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AndroidDatabase::class.java).build()
        db.groupDao().insert(Group(id = "1\$g1", name = "Tech", accountId = 1))
        feed = Feed(
            id = "1\$f1", name = "Tech Feed", url = "https://example.com/t.xml",
            groupId = "1\$g1", accountId = 1, tags = "tech",
            // showUpdates defaults true — the snapshot path is armed.
        )
        db.feedDao().insert(feed)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun article(
        id: String,
        title: String = "Title $id",
        raw: String = oldBody,
        short: String = "old",
        publishedAt: Date? = t0,
    ) = Article(
        id = "1\$$id",
        date = publishedAt ?: t0,
        title = title,
        rawDescription = raw,
        shortDescription = short,
        link = "https://example.com/$id",
        feedId = feed.id,
        accountId = 1,
        updateAt = t0,
        guid = "guid-$id",
        publishedAt = publishedAt,
    )

    private suspend fun store(articles: List<Article>, now: Date, rules: List<ActionRule> = emptyList()) =
        db.articleDao().storeEntries(
            articles = articles,
            feed = feed,
            feedTags = listOf("tech"),
            groupName = "Tech",
            rules = rules,
            now = now,
        )

    private suspend fun row(id: String) = db.articleDao().queryById("1\$$id")!!.article

    private suspend fun extract(id: String) {
        db.articleDao().applyExtraction(
            id = "1\$$id",
            fullContent = extractedHtml,
            fullLower = TextNormalizer.normalize(extractedHtml),
            extractedAt = extractedAt,
        )
    }

    @Test
    fun localExtractionActivatesWaveEGatesWhileControlRowSnapshots() = runBlocking {
        // Ingest both rows, then extract ONLY "ext" through the X1 local
        // write path (the same call ExtractCoordinator makes — E3/E4).
        store(listOf(article("ext"), article("ctl")), now = t0)
        extract("ext")
        assertEquals(extractedHtml, row("ext").fullContent)

        // Refresh: significant body change for BOTH rows, showUpdates = true.
        store(
            listOf(
                article("ext", raw = newBody, short = "new"),
                article("ctl", raw = newBody, short = "new"),
            ),
            now = t1,
        )

        // --- Extracted row: applyExtractedUpdate semantics -----------------
        val ext = row("ext")
        assertEquals("content fields are NEVER clobbered", oldBody, ext.rawDescription)
        assertEquals("old", ext.shortDescription)
        assertEquals(extractedHtml, ext.fullContent)
        assertEquals(extractedAt, ext.extractedAt)
        assertNull("snapshot gate never fires for extracted rows", ext.originalContent)
        // Extracted compare keys = (title, link, author, publishedAt) — a
        // body-only change is UNCHANGED: no isUpdated, no updateAt bump.
        assertFalse("body-only change: isUpdated per existing rules only", ext.isUpdated)
        assertNull(ext.contentChangedAt)
        assertEquals("unchanged row keeps its updateAt", t0, ext.updateAt)

        // --- Non-extracted control row: snapshots normally (SAME transaction)
        val ctl = row("ctl")
        assertEquals(newBody, ctl.rawDescription)
        assertEquals("control row keeps the OLD content snapshot", oldBody, ctl.originalContent)
        assertTrue(ctl.isUpdated)
        assertEquals(t1, ctl.contentChangedAt)
        assertEquals(t1, ctl.updateAt)
        assertNull(ctl.fullContent)
        assertNull(ctl.extractedAt)
    }

    @Test
    fun extractedRowKeyChangeUpdatesMetadataWithoutSnapshotOrContentClobber() = runBlocking {
        store(listOf(article("ext")), now = t0)
        extract("ext")

        // Title change → extracted compare keys DIFFER → applyExtractedUpdate
        // runs: metadata moves per the existing markUpdated rules while the
        // body/snapshot exclusions hold.
        store(listOf(article("ext", title = "Edited", raw = newBody, short = "new")), now = t1)

        val ext = row("ext")
        assertEquals("Edited", ext.title)
        assertEquals(oldBody, ext.rawDescription)
        assertEquals("old", ext.shortDescription)
        assertEquals(extractedHtml, ext.fullContent)
        assertEquals(extractedAt, ext.extractedAt)
        assertNull("no snapshot for extracted rows, ever", ext.originalContent)
        assertTrue("markUpdated = showUpdates (existing rule)", ext.isUpdated)
        assertEquals(t1, ext.contentChangedAt)
        assertEquals(t1, ext.updateAt)
    }
}
