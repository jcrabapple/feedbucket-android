package me.ash.reader.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.db.MIGRATION_8_9
import me.ash.reader.infrastructure.db.allMigrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E7 (SPEC-EXTRACT-v1) — MIGRATION_8_9 instrumented suite (D15: explicitly
 * selected emulator only):
 *
 * - v8 fixture rows -> migrate -> Room validation against committed 9.json
 *   (exact schema/columns/defaults/indices).
 * - Fixture data preserved + `medianBodyLength == 0` on old rows (E7: no SQL
 *   backfill — 0 means "unknown until the next recompute").
 * - App-path open of a v8 database through `allMigrations` succeeds (the
 *   registration pin) and `FeedDao.updateMedianBodyLength` round-trips on
 *   the migrated schema.
 *
 * RED phase (runtime RED per the Wave B precedent): with the database at
 * version 9 and NO MIGRATION_8_9 registered, opening the v8 fixture through
 * the real Room.databaseBuilder path threw
 * IllegalStateException("A migration from 8 to 9 was required but not
 * found…") — captured in the Wave X1 review packet before the migration
 * existed.
 */
@RunWith(AndroidJUnit4::class)
class Migration8To9Test {

    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AndroidDatabase::class.java,
            emptyList(),
        )

    /** v8-shaped fixture: one group, one flagged feed, one article. */
    private fun seedV8(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO `group` (id, name, accountId) VALUES ('1\$g1', 'Tech', 1)"
        )
        db.execSQL(
            "INSERT INTO feed (id, name, icon, url, groupId, accountId, isNotification, isFullContent, isBrowser, siteUrl, muted, showUpdates, tags, lastFetchedAt, etag) " +
                "VALUES ('1\$f1', 'Example Feed', NULL, 'https://example.com/feed.xml', '1\$g1', 1, 0, 1, 0, 'https://example.com', 0, 1, 'tech', 1700000000000, 'W/\"v8-etag\"')"
        )
        db.execSQL(
            "INSERT INTO article (id, date, title, author, rawDescription, shortDescription, fullContent, img, link, feedId, accountId, isUnread, isStarred, isReadLater, updateAt, guid, originalContent, contentChangedAt, isUpdated, readAt, extractedAt, publishedAt, stateVersion, titleLower, shortLower, rawLower, fullLower) " +
                "VALUES ('1\$a1', 1700000000000, 'Fixture One', 'Author A', '<p>teaser body</p>', 'preview', NULL, NULL, 'https://example.com/1', '1\$f1', 1, 1, 0, 0, 1700000000000, 'guid-1', NULL, NULL, 0, NULL, NULL, 1699999999000, 3, 'fixture one', 'preview', 'teaser body', '')"
        )
    }

    /** Migrates the seeded fixture to v9 and returns the raw migrated db. */
    private fun migrateSeeded(): SupportSQLiteDatabase {
        helper.createDatabase(TEST_DB, 8).also { seedV8(it) }.close()
        return helper.runMigrationsAndValidate(TEST_DB, 9, true, MIGRATION_8_9)
    }

    @Test
    fun migrationPreservesDataAndValidatesAgainstV9Schema() {
        // runMigrationsAndValidate asserts the EXACT schema match against
        // the committed 9.json asset (columns, types, defaults, indices).
        val db = migrateSeeded()

        // Feed row preserved; new column defaults to 0 (E7: no backfill).
        db.query(
            "SELECT name, url, isFullContent, showUpdates, tags, lastFetchedAt, etag, medianBodyLength FROM feed WHERE id = '1\$f1'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Example Feed", c.getString(0))
            assertEquals("https://example.com/feed.xml", c.getString(1))
            assertEquals(1, c.getInt(2))
            assertEquals(1, c.getInt(3))
            assertEquals("tech", c.getString(4))
            assertEquals(1700000000000L, c.getLong(5))
            assertEquals("W/\"v8-etag\"", c.getString(6))
            assertEquals(0, c.getInt(7))
        }

        // Article row untouched by the migration.
        db.query(
            "SELECT title, guid, stateVersion, publishedAt, rawLower FROM article WHERE id = '1\$a1'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Fixture One", c.getString(0))
            assertEquals("guid-1", c.getString(1))
            assertEquals(3L, c.getLong(2))
            assertEquals(1699999999000L, c.getLong(3))
            assertEquals("teaser body", c.getString(4))
        }

        // Column shape pin: NOT NULL with DEFAULT '0' exactly as 9.json exports.
        var found = false
        db.query("PRAGMA table_info(feed)").use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == "medianBodyLength") {
                    found = true
                    assertEquals("INTEGER", c.getString(2).uppercase())
                    assertEquals(1, c.getInt(3)) // notNull
                    assertEquals("0", c.getString(4)) // dflt_value
                }
            }
        }
        assertTrue("feed.medianBodyLength column missing after migration", found)
        db.close()
    }

    @Test
    fun appPathOpensV8WithRegisteredMigrationAndMedianRoundTrips() = runBlocking {
        // App-path open: seed the v8 file, then let Room itself run the
        // 8 -> 9 upgrade through the registered allMigrations array.
        helper.createDatabase(TEST_DB_APP, 8).also { seedV8(it) }.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val migrated = Room.databaseBuilder(context, AndroidDatabase::class.java, TEST_DB_APP)
            .addMigrations(*allMigrations)
            .build()

        // Entity mapping reads the migrated row; median is 0 pre-recompute.
        val feed = migrated.feedDao().queryById("1\$f1")
        assertNotNull(feed)
        assertEquals("Example Feed", feed!!.name)
        assertEquals(0, feed.medianBodyLength)

        // FeedDao.updateMedianBodyLength is the sole writer (E7) and persists.
        migrated.feedDao().updateMedianBodyLength("1\$f1", 350)
        assertEquals(350, migrated.feedDao().queryById("1\$f1")?.medianBodyLength)

        // Article rows survive the app-path upgrade intact.
        assertEquals("Fixture One", migrated.articleDao().queryById("1\$a1")?.article?.title)
        migrated.close()
    }

    companion object {
        private const val TEST_DB = "feedbucket-migration-8-to-9-test"
        private const val TEST_DB_APP = "feedbucket-migration-8-to-9-app-path-test"
    }
}
