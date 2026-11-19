package me.ash.reader.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.article.ArchivedArticle
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.db.MIGRATION_9_10
import me.ash.reader.infrastructure.db.allMigrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Review fix lane 6 — MIGRATION_9_10 instrumented suite (D15: explicitly
 * selected emulator only):
 *
 * - v9 fixture rows -> migrate -> Room validation against committed 10.json
 *   (exact schema/columns/defaults/indices).
 * - Fixture data preserved (index-only migration: feed, article and
 *   archived_article rows untouched).
 * - The new `index_archived_article_feedId` exists and is query-planner
 *   visible for `WHERE feedId = ?` lookups on archived_article.
 * - App-path open of a v9 database through `allMigrations` succeeds (the
 *   registration pin: 9 -> 10 chain resolves) and the archived-article DAO
 *   round-trips on the migrated schema.
 *
 * RED phase: with the database at version 10 and NO MIGRATION_9_10
 * registered, opening the v9 fixture through the real Room.databaseBuilder
 * path throws IllegalStateException("A migration from 9 to 10 was required
 * but not found…"), and runMigrationsAndValidate against 10.json fails on
 * the missing index — captured in the lane 6 review packet before the
 * migration existed.
 */
@RunWith(AndroidJUnit4::class)
class Migration9To10Test {

    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AndroidDatabase::class.java,
            emptyList(),
        )

    /** v9-shaped fixture: one group, one feed, one article, two archived links. */
    private fun seedV9(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO `group` (id, name, accountId) VALUES ('1\$g1', 'Tech', 1)"
        )
        db.execSQL(
            "INSERT INTO feed (id, name, icon, url, groupId, accountId, isNotification, isFullContent, isBrowser, siteUrl, muted, showUpdates, tags, lastFetchedAt, etag, medianBodyLength) " +
                "VALUES ('1\$f1', 'Example Feed', NULL, 'https://example.com/feed.xml', '1\$g1', 1, 0, 1, 0, 'https://example.com', 0, 1, 'tech', 1700000000000, 'W/\"v9-etag\"', 275)"
        )
        db.execSQL(
            "INSERT INTO article (id, date, title, author, rawDescription, shortDescription, fullContent, img, link, feedId, accountId, isUnread, isStarred, isReadLater, updateAt, guid, originalContent, contentChangedAt, isUpdated, readAt, extractedAt, publishedAt, stateVersion, titleLower, shortLower, rawLower, fullLower) " +
                "VALUES ('1\$a1', 1700000000000, 'Fixture One', 'Author A', '<p>teaser body</p>', 'preview', NULL, NULL, 'https://example.com/1', '1\$f1', 1, 1, 0, 0, 1700000000000, 'guid-1', NULL, NULL, 0, NULL, NULL, 1699999999000, 3, 'fixture one', 'preview', 'teaser body', '')"
        )
        db.execSQL(
            "INSERT INTO archived_article (feedId, link) VALUES ('1\$f1', 'https://example.com/archived/1')"
        )
        db.execSQL(
            "INSERT INTO archived_article (feedId, link) VALUES ('1\$f1', 'https://example.com/archived/2')"
        )
    }

    /** Migrates the seeded fixture to v10 and returns the raw migrated db. */
    private fun migrateSeeded(): SupportSQLiteDatabase {
        helper.createDatabase(TEST_DB, 9).also { seedV9(it) }.close()
        return helper.runMigrationsAndValidate(TEST_DB, 10, true, MIGRATION_9_10)
    }

    @Test
    fun migrationPreservesDataAndValidatesAgainstV10Schema() {
        // runMigrationsAndValidate asserts the EXACT schema match against
        // the committed 10.json asset (columns, types, defaults, indices).
        val db = migrateSeeded()

        // Feed row preserved verbatim (index-only migration touches nothing else).
        db.query(
            "SELECT name, url, tags, etag, medianBodyLength FROM feed WHERE id = '1\$f1'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Example Feed", c.getString(0))
            assertEquals("https://example.com/feed.xml", c.getString(1))
            assertEquals("tech", c.getString(2))
            assertEquals("W/\"v9-etag\"", c.getString(3))
            assertEquals(275, c.getInt(4))
        }

        // Article row untouched by the migration.
        db.query(
            "SELECT title, guid, stateVersion, publishedAt FROM article WHERE id = '1\$a1'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Fixture One", c.getString(0))
            assertEquals("guid-1", c.getString(1))
            assertEquals(3L, c.getLong(2))
            assertEquals(1699999999000L, c.getLong(3))
        }

        // archived_article rows preserved.
        db.query(
            "SELECT link FROM archived_article WHERE feedId = '1\$f1' ORDER BY id"
        ).use { c ->
            assertTrue(c.moveToNext())
            assertEquals("https://example.com/archived/1", c.getString(0))
            assertTrue(c.moveToNext())
            assertEquals("https://example.com/archived/2", c.getString(0))
            assertEquals(2, c.count)
        }

        // The v10 index exists with Room's generated name and shape
        // (PRAGMA-based: independent of sqlite_master.sql text quirks).
        var found = false
        db.query("PRAGMA index_list(archived_article)").use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == "index_archived_article_feedId") {
                    found = true
                    assertEquals(0, c.getInt(2)) // not UNIQUE (cols: seq,name,unique,origin,partial)
                }
            }
        }
        assertTrue("index_archived_article_feedId missing after migration", found)

        val indexedColumns = mutableListOf<String>()
        db.query("PRAGMA index_info(index_archived_article_feedId)").use { c ->
            while (c.moveToNext()) indexedColumns.add(c.getString(2))
        }
        assertEquals(listOf("feedId"), indexedColumns)

        // Query-planner pin: the index actually serves feedId lookups.
        db.query(
            "EXPLAIN QUERY PLAN SELECT * FROM archived_article WHERE feedId = '1\$f1'"
        ).use { c ->
            val plan = StringBuilder()
            while (c.moveToNext()) plan.append(c.getString(c.columnCount - 1)).append(' ')
            assertTrue(
                "expected SEARCH via index_archived_article_feedId, got: $plan",
                plan.contains("index_archived_article_feedId")
            )
        }
        db.close()
    }

    @Test
    fun appPathOpensV9WithRegisteredMigrationAndArchivedRoundTrips() = runBlocking {
        // App-path open: seed the v9 file, then let Room itself run the
        // 9 -> 10 upgrade through the registered allMigrations array.
        helper.createDatabase(TEST_DB_APP, 9).also { seedV9(it) }.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val migrated = Room.databaseBuilder(context, AndroidDatabase::class.java, TEST_DB_APP)
            .addMigrations(*allMigrations)
            .build()

        // Seeded archived rows survive the app-path upgrade.
        val archived = migrated.feedDao().queryArchivedArticles("1\$f1")
        assertEquals(2, archived.size)
        assertTrue(archived.any { it.link == "https://example.com/archived/1" })

        // DAO insert round-trips on the migrated (indexed) schema.
        migrated.feedDao().insertArchivedArticles(
            listOf(ArchivedArticle(feedId = "1\$f1", link = "https://example.com/archived/3"))
        )
        val afterInsert = migrated.feedDao().queryArchivedArticles("1\$f1")
        assertEquals(3, afterInsert.size)
        assertNotNull(afterInsert.firstOrNull { it.link == "https://example.com/archived/3" })

        // Feed/article rows survive intact too.
        assertEquals("Example Feed", migrated.feedDao().queryById("1\$f1")?.name)
        assertEquals("Fixture One", migrated.articleDao().queryById("1\$a1")?.article?.title)
        migrated.close()
    }

    companion object {
        private const val TEST_DB = "feedbucket-migration-9-to-10-test"
        private const val TEST_DB_APP = "feedbucket-migration-9-to-10-app-path-test"
    }
}
