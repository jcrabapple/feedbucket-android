package me.ash.reader.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.query.TextNormalizer
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.db.MIGRATION_7_8
import me.ash.reader.infrastructure.db.NormalizationBackfill
import me.ash.reader.infrastructure.db.allMigrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S5.6 / acceptance #11 — MIGRATION_7_8 instrumented suite (D15: explicitly
 * selected emulator only):
 *
 * - v7 fixture rows -> migrate -> Room validation against committed 8.json
 *   (exact schema/columns/defaults/indices).
 * - Fixture data preserved + §2.1/§2.2 defaults correct on old rows.
 * - FTS `rebuild` round-trip: a pre-migration v7 article is findable by a
 *   body-only term AFTER migrate (external-content FTS tables are created
 *   EMPTY — sqlite.org/fts3.html §6.2.2).
 * - Behavioral trigger sync: post-migration DAO insert/update/delete are
 *   reflected in MATCH (Room owns `room_fts_content_sync_*`; no hand-written
 *   trigger-name assertions).
 * - S5.5 tags backfill from group name.
 * - S5.7 normalization backfill: Unicode fixture row searchable
 *   case-insensitively after the Kotlin pass completes.
 *
 * RED phase (R4): before MIGRATION_7_8 existed, opening the v7 fixture at
 * version 8 threw IllegalStateException("A migration from 7 to 8 was
 * required but not found…") — a runtime failure, not a compile failure
 * (captured in the Wave B review packet).
 */
@RunWith(AndroidJUnit4::class)
class Migration7To8Test {

    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AndroidDatabase::class.java,
            emptyList(),
        )

    /** v7-shaped fixture: one group, one feed, two articles. */
    private fun seedV7(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO `group` (id, name, accountId) VALUES ('1\$g1', 'Tech', 1)"
        )
        db.execSQL(
            "INSERT INTO feed (id, name, icon, url, groupId, accountId, isNotification, isFullContent, isBrowser) " +
                "VALUES ('1\$f1', 'Example Feed', NULL, 'https://example.com/feed.xml', '1\$g1', 1, 0, 0, 0)"
        )
        // Body-only term article: 'xyzzybody' appears only in rawDescription.
        db.execSQL(
            "INSERT INTO article (id, date, title, author, rawDescription, shortDescription, fullContent, img, link, feedId, accountId, isUnread, isStarred, isReadLater, updateAt) " +
                "VALUES ('1\$a1', 1700000000000, 'Fixture One', NULL, '<p>alpha xyzzybody omega</p>', 'alpha preview', NULL, NULL, 'https://example.com/1', '1\$f1', 1, 1, 0, 0, 1700000000000)"
        )
        // Unicode title article (Turkish dotted capital I).
        db.execSQL(
            "INSERT INTO article (id, date, title, author, rawDescription, shortDescription, fullContent, img, link, feedId, accountId, isUnread, isStarred, isReadLater, updateAt) " +
                "VALUES ('1\$a2', 1700000001000, 'İstanbul Diary', NULL, 'body two', 'short two', NULL, NULL, 'https://example.com/2', '1\$f1', 1, 1, 0, 0, 1700000001000)"
        )
    }

    /** Migrates the seeded fixture to v8 and returns the raw migrated db. */
    private fun migrateSeeded(): SupportSQLiteDatabase {
        helper.createDatabase(TEST_DB, 7).also { seedV7(it) }.close()
        return helper.runMigrationsAndValidate(TEST_DB, 8, true, MIGRATION_7_8)
    }

    @Test
    fun migrationPreservesDataAndValidatesAgainstV8Schema() {
        val db = migrateSeeded()

        // Fixture rows preserved.
        db.query("SELECT title, guid, stateVersion, isUpdated, titleLower FROM article WHERE id = '1\$a1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Fixture One", c.getString(0))
            // §2.1 defaults on old rows.
            assertEquals("", c.getString(1))
            assertEquals(0L, c.getLong(2))
            assertEquals(0, c.getInt(3))
            // Shadows NOT backfilled in SQL (S5 step 5) — '' until S5.7 pass.
            assertEquals("", c.getString(4))
        }

        // Indices expected by 8.json exist.
        val indices = mutableListOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'index_%'").use { c ->
            while (c.moveToNext()) indices.add(c.getString(0))
        }
        listOf(
            "index_article_download_state",
            "index_download_asset_articleId",
            "index_list_position_updatedAt",
            "index_reading_position_updatedAt",
            "index_import_run_state",
            "index_state_apply_before_articleId",
        ).forEach { assertTrue("missing index $it in $indices", it in indices) }
        db.close()
    }

    @Test
    fun ftsRebuildBackfillsExistingCorpus() {
        val db = migrateSeeded()

        // Body-only term of the PRE-migration article is findable AFTER migrate.
        db.query("SELECT count(*) FROM article_fts WHERE article_fts MATCH ?", arrayOf("xyzzybody")).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        db.close()
    }

    @Test
    fun ftsTriggerSyncFollowsDaoWrites() = runBlocking {
        // App-path open: seed the v7 file, then let Room itself run the
        // 7 -> 9 upgrade through the registered allMigrations chain
        // (MIGRATION_7_8 then MIGRATION_8_9 — the live AndroidDatabase is
        // version 9, and Room requires the full path from the seeded
        // version). onPostMigrate creates the room_fts_content_sync_*
        // triggers — it only runs on upgrade, so a plain re-open at the
        // current version does not create them.
        helper.createDatabase(TEST_DB, 7).also { seedV7(it) }.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val migrated = Room.databaseBuilder(context, AndroidDatabase::class.java, TEST_DB)
            .addMigrations(*allMigrations)
            .build()

        fun matchCount(term: String): Int =
            migrated.openHelper.readableDatabase.query(
                "SELECT count(*) FROM article_fts WHERE article_fts MATCH ?", arrayOf(term)
            ).use { c -> c.moveToFirst(); c.getInt(0) }

        // Insert via DAO -> indexed (AFTER_INSERT trigger).
        val inserted = Article(
            id = "1\$a3",
            date = java.util.Date(1700000002000),
            title = "Fresh DAO Post",
            rawDescription = "daoinsertbody",
            shortDescription = "dao insert",
            link = "https://example.com/3",
            feedId = "1\$f1",
            accountId = 1,
        )
        migrated.articleDao().insert(inserted)
        assertEquals(1, matchCount("daoinsertbody"))

        // Update via DAO -> index follows (BEFORE_UPDATE + AFTER_UPDATE).
        migrated.articleDao().update(inserted.copy(title = "Renamed DAO Post"))
        assertEquals(1, matchCount("renamed"))

        // Delete via DAO -> gone from the index.
        migrated.articleDao().delete(inserted.copy(title = "Renamed DAO Post"))
        assertEquals(0, matchCount("renamed"))
        migrated.close()
    }

    @Test
    fun tagsBackfillFromGroupName() {
        val db = migrateSeeded()

        db.query("SELECT tags FROM feed WHERE id = '1\$f1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("tech", c.getString(0))
        }
        db.close()
    }

    @Test
    fun normalizationBackfillMakesUnicodeRowSearchable() {
        val db = migrateSeeded()

        // Before the pass: shadow empty, LIKE over shadow misses.
        assertEquals(0, countTitleShadowMatches(db, TextNormalizer.normalize("İ")))

        // S5.7 pass (idempotent; the empty-shadow condition is the locator).
        NormalizationBackfill.run(db)

        // After: Unicode case-insensitive match works (İ -> i + U+0307).
        assertEquals(1, countTitleShadowMatches(db, TextNormalizer.normalize("İ")))
        db.query("SELECT titleLower FROM article WHERE id = '1\$a2'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(TextNormalizer.normalize("İstanbul Diary"), c.getString(0))
        }

        // Second run is a no-op (resumable/self-gating).
        NormalizationBackfill.run(db)
        assertEquals(1, countTitleShadowMatches(db, TextNormalizer.normalize("İ")))
        db.close()
    }

    private fun countTitleShadowMatches(db: SupportSQLiteDatabase, term: String): Int =
        db.query(
            "SELECT count(*) FROM article WHERE titleLower LIKE '%' || ? || '%' ESCAPE '\\'",
            arrayOf(term)
        ).use { c -> c.moveToFirst(); c.getInt(0) }

    companion object {
        private const val TEST_DB = "feedbucket-migration-7-to-8-test"
    }
}
