package me.ash.reader.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.summary.ArticleSummary
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.db.MIGRATION_10_11
import me.ash.reader.infrastructure.db.allMigrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AI Summaries — MIGRATION_10_11 instrumented suite (schema v10 -> v11):
 *
 * - v10 fixture rows -> migrate -> Room validation against committed 11.json
 *   (exact article_summary table shape: columns, PK, FK, index).
 * - Fixture data preserved (additive migration: feed/article untouched).
 * - App-path `Room.databaseBuilder(..., *allMigrations)` open of a seeded v10
 *   database (the registration pin: the 10 -> 11 chain resolves).
 * - ArticleSummaryDao round-trip on the migrated schema, plus FK CASCADE:
 *   deleting the parent article row removes its cached summary.
 *
 * RED phase: with the database at version 11 and NO MIGRATION_10_11
 * registered, opening the v10 fixture through the real Room.databaseBuilder
 * path throws IllegalStateException("A migration from 10 to 11 was required
 * but not found…"), and runMigrationsAndValidate against 11.json fails on
 * the missing article_summary table — captured before the migration existed.
 */
@RunWith(AndroidJUnit4::class)
class Migration10To11Test {

    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AndroidDatabase::class.java,
            emptyList(),
        )

    /** v10-shaped fixture: one group, one feed, one article. */
    private fun seedV10(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO `group` (id, name, accountId) VALUES ('1\$g1', 'Tech', 1)"
        )
        db.execSQL(
            "INSERT INTO feed (id, name, icon, url, groupId, accountId, isNotification, isFullContent, isBrowser, siteUrl, muted, showUpdates, tags, lastFetchedAt, etag, medianBodyLength) " +
                "VALUES ('1\$f1', 'Example Feed', NULL, 'https://example.com/feed.xml', '1\$g1', 1, 0, 1, 0, 'https://example.com', 0, 1, 'tech', 1700000000000, 'W/\"v10-etag\"', 275)"
        )
        db.execSQL(
            "INSERT INTO article (id, date, title, author, rawDescription, shortDescription, fullContent, img, link, feedId, accountId, isUnread, isStarred, isReadLater, updateAt, guid, originalContent, contentChangedAt, isUpdated, readAt, extractedAt, publishedAt, stateVersion, titleLower, shortLower, rawLower, fullLower) " +
                "VALUES ('1\$a1', 1700000000000, 'Fixture One', 'Author A', '<p>teaser body</p>', 'preview', NULL, NULL, 'https://example.com/1', '1\$f1', 1, 1, 0, 0, 1700000000000, 'guid-1', NULL, NULL, 0, NULL, NULL, 1699999999000, 3, 'fixture one', 'preview', 'teaser body', '')"
        )
    }

    @Test
    fun migrationPreservesDataAndValidatesAgainstV11Schema() {
        // runMigrationsAndValidate asserts the EXACT schema match against
        // the committed 11.json asset (columns, types, defaults, indices).
        helper.createDatabase(TEST_DB, 10).also { seedV10(it) }.close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 11, true, MIGRATION_10_11)

        // Fixture rows untouched by the additive migration.
        db.query("SELECT name, medianBodyLength FROM feed WHERE id = '1\$f1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Example Feed", c.getString(0))
            assertEquals(275, c.getInt(1))
        }
        db.query("SELECT title, stateVersion FROM article WHERE id = '1\$a1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Fixture One", c.getString(0))
            assertEquals(3L, c.getLong(1))
        }

        // The new table starts empty and carries Room's generated FK index.
        db.query("SELECT COUNT(*) FROM article_summary").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        var found = false
        db.query("PRAGMA index_list(article_summary)").use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == "index_article_summary_articleId") {
                    found = true
                    assertEquals(0, c.getInt(2)) // not UNIQUE
                }
            }
        }
        assertTrue("index_article_summary_articleId missing after migration", found)

        // FK is declared ON DELETE CASCADE toward article.
        var cascadeDelete = false
        db.query("PRAGMA foreign_key_list(article_summary)").use { c ->
            while (c.moveToNext()) {
                // cols: id, seq, table, from, to, on_update, on_delete, match
                if (c.getString(2) == "article" && c.getString(4) == "id") {
                    cascadeDelete = c.getString(6) == "CASCADE"
                }
            }
        }
        assertTrue("article_summary FK must cascade on delete", cascadeDelete)
        db.close()
    }

    @Test
    fun appPathOpensV10WithRegisteredMigrationsAndSummaryDaoRoundTrips() = runBlocking {
        helper.createDatabase(TEST_DB_APP, 10).also { seedV10(it) }.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val migrated = Room.databaseBuilder(context, AndroidDatabase::class.java, TEST_DB_APP)
            .addMigrations(*allMigrations)
            .build()

        // Seeded rows survive the app-path upgrade.
        assertEquals("Example Feed", migrated.feedDao().queryById("1\$f1")?.name)
        assertEquals("Fixture One", migrated.articleDao().queryById("1\$a1")?.article?.title)

        val dao = migrated.articleSummaryDao()
        assertNull(dao.getByArticleId("1\$a1"))

        // Insert + read-back.
        dao.upsert(
            ArticleSummary(
                articleId = "1\$a1",
                summary = "TL;DR cached summary.\n- point one",
                model = "test/model",
                sourceIsFullContent = true,
                createdAt = 1700000001234L,
            )
        )
        val stored = dao.getByArticleId("1\$a1")
        assertNotNull(stored)
        assertEquals("TL;DR cached summary.\n- point one", stored!!.summary)
        assertEquals("test/model", stored.model)
        assertTrue(stored.sourceIsFullContent)
        assertEquals(1700000001234L, stored.createdAt)

        // Upsert replaces in place (one row per article).
        dao.upsert(stored.copy(summary = "regenerated", model = "test/model-2"))
        val updated = dao.getByArticleId("1\$a1")
        assertEquals("regenerated", updated?.summary)
        assertEquals("test/model-2", updated?.model)

        // FK CASCADE: deleting the parent article drops the cached summary.
        migrated.openHelper.writableDatabase.execSQL("DELETE FROM article WHERE id = '1\$a1'")
        assertNull(dao.getByArticleId("1\$a1"))

        // FK enforcement is ON (Room enables it): an orphan summary row
        // (no parent article) must be rejected by SQLite.
        var orphanRejected = false
        try {
            dao.upsert(
                ArticleSummary(
                    articleId = "ghost",
                    summary = "orphan candidate",
                    model = "m",
                    sourceIsFullContent = false,
                    createdAt = 1L,
                )
            )
        } catch (_: Exception) {
            orphanRejected = true
        }
        assertTrue("FK enforcement must reject an orphan summary row", orphanRejected)
        assertNull(dao.getByArticleId("ghost"))
        migrated.close()
    }

    companion object {
        private const val TEST_DB = "feedbucket-migration-10-to-11-test"
        private const val TEST_DB_APP = "feedbucket-migration-10-to-11-app-path-test"
    }
}
