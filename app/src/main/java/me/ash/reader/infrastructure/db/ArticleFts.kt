package me.ash.reader.infrastructure.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey
import me.ash.reader.domain.model.article.Article

/**
 * S2.3/S5.4/S16 — FTS4 accelerator + coverage index over the article body
 * columns; NEVER a semantic authority (the canonical predicate is S12a).
 *
 * External-content FTS4 (`contentEntity`): Room owns the
 * `room_fts_content_sync_*` triggers; MIGRATION_7_8 creates this table with
 * Room's EXACT generated DDL then backfills existing rows with
 * `INSERT INTO article_fts(article_fts) VALUES('rebuild')`. The FTS `docid`
 * maps directly to article's implicit rowid (TEXT-PK table = rowid table).
 */
@Fts4(contentEntity = Article::class)
@Entity(tableName = "article_fts")
data class ArticleFts(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowid: Long,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "shortDescription")
    val shortDescription: String,
    @ColumnInfo(name = "rawDescription")
    val rawDescription: String,
    @ColumnInfo(name = "fullContent")
    val fullContent: String?,
)
