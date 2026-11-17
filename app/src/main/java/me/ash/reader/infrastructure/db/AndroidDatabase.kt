package me.ash.reader.infrastructure.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.ash.reader.domain.model.account.*
import me.ash.reader.domain.model.account.security.DESUtils
import me.ash.reader.domain.model.article.ArchivedArticle
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.download.ArticleDownload
import me.ash.reader.domain.model.download.DownloadAsset
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.model.importing.ImportIdentity
import me.ash.reader.domain.model.importing.ImportRun
import me.ash.reader.domain.model.position.ListPosition
import me.ash.reader.domain.model.position.ReadingPosition
import me.ash.reader.domain.model.rule.ActionRule
import me.ash.reader.domain.model.rule.StateApplyBefore
import me.ash.reader.domain.model.rule.StateApplyRun
import me.ash.reader.domain.model.search.SavedSearch
import me.ash.reader.domain.repository.AccountDao
import me.ash.reader.domain.repository.ActionRuleDao
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.DownloadDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.domain.repository.ImportDao
import me.ash.reader.domain.repository.PositionDao
import me.ash.reader.domain.repository.SavedSearchDao
import me.ash.reader.domain.repository.StateApplyDao
import me.ash.reader.infrastructure.preference.*
import me.ash.reader.ui.ext.toInt
import java.util.*

@Database(
    entities = [
        Account::class, Feed::class, Article::class, Group::class, ArchivedArticle::class,
        SavedSearch::class, ActionRule::class,
        ArticleDownload::class, DownloadAsset::class,
        ListPosition::class, ReadingPosition::class,
        ImportRun::class, ImportIdentity::class,
        StateApplyRun::class, StateApplyBefore::class,
        ArticleFts::class,
    ],
    version = 8,
    // NOTE: the upstream AutoMigrations (5->6, 5->7, 6->7) were converted to
    // explicit Migration objects below: with v8 entity changes, Room's KSP
    // re-derives auto-migrations against the changed entities and clobbers
    // the committed 5/6/7.json baselines. Explicit migrations pin the SQL.
)
@TypeConverters(
    AndroidDatabase.DateConverters::class,
    AccountTypeConverters::class,
    SyncIntervalConverters::class,
    SyncOnStartConverters::class,
    SyncOnlyOnWiFiConverters::class,
    SyncOnlyWhenChargingConverters::class,
    KeepArchivedConverters::class,
    SyncBlockListConverters::class,
)
abstract class AndroidDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao
    abstract fun feedDao(): FeedDao
    abstract fun articleDao(): ArticleDao
    abstract fun groupDao(): GroupDao
    abstract fun savedSearchDao(): SavedSearchDao
    abstract fun actionRuleDao(): ActionRuleDao
    abstract fun downloadDao(): DownloadDao
    abstract fun positionDao(): PositionDao
    abstract fun importDao(): ImportDao
    abstract fun stateApplyDao(): StateApplyDao

    companion object {

        private var instance: AndroidDatabase? = null

        fun getInstance(context: Context): AndroidDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AndroidDatabase::class.java,
                    "Reader"
                )
                    .addMigrations(*allMigrations)
                    .addCallback(NormalizationBackfill.CALLBACK)
                    .build().also {
                        instance = it
                    }
            }
        }
    }

    class DateConverters {

        @TypeConverter
        fun toDate(dateLong: Long?): Date? {
            return dateLong?.let { Date(it) }
        }

        @TypeConverter
        fun fromDate(date: Date?): Long? {
            return date?.time
        }
    }
}

val allMigrations = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4,
    MIGRATION_4_5,
    MIGRATION_5_6,
    MIGRATION_6_7,
    MIGRATION_7_8,
)

@Suppress("ClassName")
object MIGRATION_1_2 : Migration(1, 2) {

    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            ALTER TABLE article ADD COLUMN img TEXT DEFAULT NULL
            """.trimIndent()
        )
    }
}

@Suppress("ClassName")
object MIGRATION_2_3 : Migration(2, 3) {

    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            ALTER TABLE article ADD COLUMN updateAt INTEGER DEFAULT ${System.currentTimeMillis()}
            """.trimIndent()
        )
        database.execSQL(
            """
            ALTER TABLE account ADD COLUMN syncInterval INTEGER NOT NULL DEFAULT ${SyncIntervalPreference.default.value}
            """.trimIndent()
        )
        database.execSQL(
            """
            ALTER TABLE account ADD COLUMN syncOnStart INTEGER NOT NULL DEFAULT ${SyncOnStartPreference.default.value.toInt()}
            """.trimIndent()
        )
        database.execSQL(
            """
            ALTER TABLE account ADD COLUMN syncOnlyOnWiFi INTEGER NOT NULL DEFAULT ${SyncOnlyOnWiFiPreference.default.value.toInt()}
            """.trimIndent()
        )
        database.execSQL(
            """
            ALTER TABLE account ADD COLUMN syncOnlyWhenCharging INTEGER NOT NULL DEFAULT ${SyncOnlyWhenChargingPreference.default.value.toInt()}
            """.trimIndent()
        )
        database.execSQL(
            """
            ALTER TABLE account ADD COLUMN keepArchived INTEGER NOT NULL DEFAULT ${KeepArchivedPreference.default.value}
            """.trimIndent()
        )
        database.execSQL(
            """
            ALTER TABLE account ADD COLUMN syncBlockList TEXT NOT NULL DEFAULT ''
            """.trimIndent()
        )
    }
}

@Suppress("ClassName")
object MIGRATION_3_4 : Migration(3, 4) {

    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            ALTER TABLE account ADD COLUMN securityKey TEXT DEFAULT '${DESUtils.empty}'
            """.trimIndent()
        )
    }
}

@Suppress("ClassName")
object MIGRATION_4_5 : Migration(4, 5) {

    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            ALTER TABLE account ADD COLUMN lastArticleId TEXT DEFAULT NULL
            """.trimIndent()
        )
    }
}

/**
 * Former AutoMigration(5, 6) — exact SQL preserved from the Room-generated
 * auto-migration (5.json -> 6.json diff: feed.isBrowser only).
 */
@Suppress("ClassName")
object MIGRATION_5_6 : Migration(5, 6) {

    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            ALTER TABLE feed ADD COLUMN isBrowser INTEGER NOT NULL DEFAULT 0
            """.trimIndent()
        )
    }
}

/**
 * Former AutoMigration(6, 7) — exact SQL preserved from the Room-generated
 * auto-migration (6.json -> 7.json diff: archived_article table only).
 */
@Suppress("ClassName")
object MIGRATION_6_7 : Migration(6, 7) {

    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `archived_article` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `feedId` TEXT NOT NULL, `link` TEXT NOT NULL, FOREIGN KEY(`feedId`) REFERENCES `feed`(`id`) ON UPDATE CASCADE ON DELETE CASCADE )
            """.trimIndent()
        )
    }
}

/**
 * S5 — the ONE v1 schema migration (S1): upstream v7 -> final v8 data shape.
 *
 * Pure SQL (plus the post-migration Kotlin normalization backfill,
 * [NormalizationBackfill], wired as a RoomDatabase callback — SQLite
 * `lower()` is ASCII-only and cannot produce the canonical S12a shadows).
 *
 * - §2.1/§2.2: article/feed columns, all NOT NULL DEFAULT so ALTER is safe.
 * - §2.3: new tables with Room's EXACT generated DDL (copied from the
 *   Room-generated createAllTables in 8.json), plus the indices Room
 *   declares there.
 * - S5.4: `article_fts` Room-exact external-content FTS4 DDL, then the
 *   existing-corpus backfill `INSERT INTO article_fts(article_fts)
 *   VALUES('rebuild')` (SQLite creates external-content FTS tables EMPTY).
 *   NO hand-written content-sync triggers: Room owns
 *   `room_fts_content_sync_*` and re-creates them in onPostMigrate.
 * - S5.5: `feed.tags` backfill from the feed's group name (lowercased) so
 *   `tag:` queries work on pre-existing libraries.
 * - The `*Lower` shadows are added with DEFAULT '' and deliberately NOT
 *   backfilled here (see [NormalizationBackfill]).
 */
@Suppress("ClassName")
object MIGRATION_7_8 : Migration(7, 8) {

    override fun migrate(database: SupportSQLiteDatabase) {
        // §2.1 article columns
        database.execSQL("ALTER TABLE article ADD COLUMN guid TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE article ADD COLUMN originalContent TEXT")
        database.execSQL("ALTER TABLE article ADD COLUMN contentChangedAt INTEGER")
        database.execSQL("ALTER TABLE article ADD COLUMN isUpdated INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE article ADD COLUMN readAt INTEGER")
        database.execSQL("ALTER TABLE article ADD COLUMN extractedAt INTEGER")
        database.execSQL("ALTER TABLE article ADD COLUMN publishedAt INTEGER")
        database.execSQL("ALTER TABLE article ADD COLUMN stateVersion INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE article ADD COLUMN titleLower TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE article ADD COLUMN shortLower TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE article ADD COLUMN rawLower TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE article ADD COLUMN fullLower TEXT NOT NULL DEFAULT ''")

        // §2.2 feed columns
        database.execSQL("ALTER TABLE feed ADD COLUMN siteUrl TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE feed ADD COLUMN muted INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE feed ADD COLUMN showUpdates INTEGER NOT NULL DEFAULT 1")
        database.execSQL("ALTER TABLE feed ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE feed ADD COLUMN lastFetchedAt INTEGER")
        database.execSQL("ALTER TABLE feed ADD COLUMN etag TEXT")

        // §2.3 new tables — Room-exact DDL from 8.json
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `saved_search` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `query` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `action_rule` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL DEFAULT 'standard', `title` TEXT NOT NULL DEFAULT '', `query` TEXT NOT NULL DEFAULT '', `allFeeds` INTEGER NOT NULL DEFAULT 1, `feedIds` TEXT NOT NULL DEFAULT '', `groupIds` TEXT NOT NULL DEFAULT '', `effects` TEXT NOT NULL DEFAULT 'mark_read', `createdAt` INTEGER NOT NULL)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `article_download` (`articleId` TEXT NOT NULL, `state` TEXT NOT NULL DEFAULT 'pending', `textBytes` INTEGER NOT NULL DEFAULT 0, `imageBytes` INTEGER NOT NULL DEFAULT 0, `imageCount` INTEGER NOT NULL DEFAULT 0, `expectedImageCount` INTEGER NOT NULL DEFAULT 0, `assetsComplete` INTEGER NOT NULL DEFAULT 0, `errorCause` TEXT, `attempts` INTEGER NOT NULL DEFAULT 0, `requestedAt` INTEGER NOT NULL, `lastAttemptAt` INTEGER, `completedAt` INTEGER, PRIMARY KEY(`articleId`), FOREIGN KEY(`articleId`) REFERENCES `article`(`id`) ON UPDATE CASCADE ON DELETE CASCADE )"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_article_download_state` ON `article_download` (`state`)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `download_asset` (`articleId` TEXT NOT NULL, `url` TEXT NOT NULL, `localPath` TEXT NOT NULL, `bytes` INTEGER NOT NULL, `sha256` TEXT NOT NULL, PRIMARY KEY(`articleId`, `url`), FOREIGN KEY(`articleId`) REFERENCES `article`(`id`) ON UPDATE CASCADE ON DELETE CASCADE )"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_download_asset_articleId` ON `download_asset` (`articleId`)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `list_position` (`scopeKey` TEXT NOT NULL, `anchorArticleId` TEXT, `anchorItemIndex` INTEGER NOT NULL DEFAULT 0, `anchorOffsetPx` INTEGER NOT NULL DEFAULT 0, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`scopeKey`))"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_list_position_updatedAt` ON `list_position` (`updatedAt`)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `reading_position` (`articleId` TEXT NOT NULL, `scrollRatioPx` INTEGER NOT NULL DEFAULT 0, `contentHeightPx` INTEGER NOT NULL DEFAULT 0, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`articleId`), FOREIGN KEY(`articleId`) REFERENCES `article`(`id`) ON UPDATE CASCADE ON DELETE CASCADE )"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_reading_position_updatedAt` ON `reading_position` (`updatedAt`)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `import_run` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `sourceFingerprint` TEXT NOT NULL, `sourceUri` TEXT, `sourcePath` TEXT, `state` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, `countsJson` TEXT NOT NULL DEFAULT '{}')"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_import_run_state` ON `import_run` (`state`)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `import_identity` (`source` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `targetKind` TEXT NOT NULL, `targetId` TEXT NOT NULL, `importRunId` INTEGER NOT NULL, `importedAt` INTEGER NOT NULL, `createdByRunId` INTEGER, `stateStamp` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`source`, `sourceId`, `targetKind`))"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `state_apply_run` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `ruleId` INTEGER, `kind` TEXT NOT NULL DEFAULT 'apply_to_existing', `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, `appliedCount` INTEGER NOT NULL DEFAULT 0, `undoneAt` INTEGER, `undoRestoredCount` INTEGER NOT NULL DEFAULT 0, `undoSkippedCount` INTEGER NOT NULL DEFAULT 0)"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `state_apply_before` (`runId` INTEGER NOT NULL, `articleId` TEXT NOT NULL, `wasUnread` INTEGER NOT NULL, `wasStarred` INTEGER NOT NULL, `wasUpdated` INTEGER NOT NULL, `readAtBefore` INTEGER, `stateVersionAtApply` INTEGER NOT NULL, PRIMARY KEY(`runId`, `articleId`), FOREIGN KEY(`runId`) REFERENCES `state_apply_run`(`id`) ON UPDATE CASCADE ON DELETE CASCADE , FOREIGN KEY(`articleId`) REFERENCES `article`(`id`) ON UPDATE CASCADE ON DELETE CASCADE )"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_state_apply_before_articleId` ON `state_apply_before` (`articleId`)"
        )

        // S5.4 FTS — Room-exact external-content DDL, then corpus backfill.
        // Room owns the content-sync triggers (onPostMigrate); none here.
        database.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS `article_fts` USING FTS4(`title` TEXT NOT NULL, `shortDescription` TEXT NOT NULL, `rawDescription` TEXT NOT NULL, `fullContent` TEXT, content=`article`)"
        )
        database.execSQL("INSERT INTO article_fts(article_fts) VALUES('rebuild')")

        // S5.5 tags backfill from group name (lowercased) for tag: queries.
        database.execSQL(
            """
            UPDATE feed SET tags = lower((SELECT name FROM `group` AS g WHERE g.id = feed.groupId))
            WHERE tags = '' AND EXISTS (SELECT 1 FROM `group` AS g WHERE g.id = feed.groupId)
            """.trimIndent()
        )
    }
}
