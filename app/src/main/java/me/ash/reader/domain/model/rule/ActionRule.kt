package me.ash.reader.domain.model.rule

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * S2.3 — action rule (web `actions` parity).
 *
 * `type` IN ('standard','mute'); ONLY 'standard' rules participate in ingest
 * application, previews and apply-to-existing (S17a). Mutes are pure
 * query-time filters on the unread/updated views.
 *
 * `feedIds`/`groupIds` are CSVs of TEXT feed/group ids; `effects` is a CSV
 * subset of {mark_read,star}.
 */
@Entity(tableName = "action_rule")
data class ActionRule(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "type", defaultValue = "standard")
    val type: String = "standard",
    @ColumnInfo(name = "title", defaultValue = "")
    val title: String = "",
    @ColumnInfo(name = "query", defaultValue = "")
    val query: String = "",
    @ColumnInfo(name = "allFeeds", defaultValue = "1")
    val allFeeds: Boolean = true,
    @ColumnInfo(name = "feedIds", defaultValue = "")
    val feedIds: String = "",
    @ColumnInfo(name = "groupIds", defaultValue = "")
    val groupIds: String = "",
    @ColumnInfo(name = "effects", defaultValue = "mark_read")
    val effects: String = "mark_read",
    @ColumnInfo(name = "createdAt")
    val createdAt: Long,
) {

    companion object {
        const val TYPE_STANDARD = "standard"
        const val TYPE_MUTE = "mute"
        const val EFFECT_MARK_READ = "mark_read"
        const val EFFECT_STAR = "star"
    }
}
