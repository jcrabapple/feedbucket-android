package me.ash.reader.domain.model.search

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * S2.3 — saved search (web `saved_searches` parity).
 *
 * Names are NOT unique (web index is non-unique); identity = rowid.
 * The query string is stored VERBATIM including `sort:`/`tag:` tokens.
 */
@Entity(tableName = "saved_search")
data class SavedSearch(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "query")
    val query: String,
    @ColumnInfo(name = "createdAt")
    val createdAt: Long,
)
