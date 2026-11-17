package me.ash.reader.domain.model.feed

import androidx.room.*
import me.ash.reader.domain.model.group.Group
import java.util.*

/**
 * v8 feed (S2.2). New columns: `siteUrl` (OPML htmlUrl parity), `muted`
 * (excluded from unread/updated views only, S15), `showUpdates` (snapshot +
 * updated-queue gate, S20a), `tags` (comma-separated lowercase
 * order-preserving-dedupe tag names; groupId stays the single DISPLAY
 * folder, S4), `lastFetchedAt` and `etag` (conditional GET support).
 */
@Entity(
    tableName = "feed",
    foreignKeys = [ForeignKey(
        entity = Group::class,
        parentColumns = ["id"],
        childColumns = ["groupId"],
        onDelete = ForeignKey.CASCADE,
        onUpdate = ForeignKey.CASCADE,
    )],
)
data class Feed(
    @PrimaryKey
    val id: String,
    @ColumnInfo
    val name: String,
    @ColumnInfo
    val icon: String? = null,
    @ColumnInfo
    val url: String,
    @ColumnInfo(index = true)
    var groupId: String,
    @ColumnInfo(index = true)
    val accountId: Int,
    @ColumnInfo
    val isNotification: Boolean = false,
    @ColumnInfo
    val isFullContent: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val isBrowser: Boolean = false,
    // The v8 columns are `var` (not `val`) deliberately: Room's KSP setter
    // matching resolves through this class's secondary constructor, so new
    // `val` columns have no setter candidate and fail KSP with
    // "Cannot find setter for property" (verified on Room 2.7.2 / KSP2).
    @ColumnInfo(defaultValue = "")
    var siteUrl: String = "",
    @ColumnInfo(defaultValue = "0")
    var muted: Boolean = false,
    @ColumnInfo(defaultValue = "1")
    var showUpdates: Boolean = true,
    @ColumnInfo(defaultValue = "")
    var tags: String = "",
    @ColumnInfo
    var lastFetchedAt: Date? = null,
    @ColumnInfo
    var etag: String? = null,
    @Ignore val important: Int = 0
) {
    constructor(
        id: String,
        name: String,
        icon: String?,
        url: String,
        groupId: String,
        accountId: Int,
        isNotification: Boolean,
        isFullContent: Boolean,
        isBrowser: Boolean
    ) : this(
        id = id,
        name = name,
        icon = icon,
        url = url,
        groupId = groupId,
        accountId = accountId,
        isNotification = isNotification,
        isFullContent = isFullContent,
        isBrowser = isBrowser,
        important = 0
    )
}
