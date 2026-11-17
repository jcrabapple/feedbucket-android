package me.ash.reader.domain.service.migration

/**
 * S32a — source schema validation for the FeedBucket migration importer.
 *
 * Two accepted input shapes:
 *  - **PackageV1**: `feedbucket-export-v1.sqlite` from
 *    `scripts/export_feedbucket_package.py` (`package_meta.schemaVersion=1`).
 *  - **RawCopy**: a user-made SQLite backup of the web database (compat
 *    path) — the same allowlist + secret guard apply.
 *
 * Anything else is rejected with a precise message and NOTHING is written
 * (the retained copy is purged by the caller).
 *
 * The allowlist mirrors the python export tool's ALLOWLIST exactly; both
 * must stay in sync (pinned by MigrationImporterTest fixture round-trips).
 */
object ImportSchemaChecker {

    const val SOURCE_FEEDBUCKET = "feedbucket"
    const val PACKAGE_TABLE = "package_meta"

    /** Explicit column lists per table — `users` is deliberately absent. */
    val TABLE_COLUMNS: Map<String, List<String>> = linkedMapOf(
        "subscriptions" to listOf(
            "id", "feed_url", "site_url", "title", "muted", "show_updates",
            "last_fetched", "etag",
        ),
        "entries" to listOf(
            "id", "feed_id", "external_id", "title", "url", "author",
            "summary", "content", "original_content", "extracted", "published",
            "created_at",
        ),
        "unread" to listOf("entry_id"),
        "starred" to listOf("entry_id"),
        "updated" to listOf("entry_id", "updated_at"),
        "recently_read" to listOf("entry_id", "read_at"),
        "tags" to listOf("id", "name"),
        "subscription_tags" to listOf("feed_id", "tag_id"),
        "saved_searches" to listOf("id", "name", "query", "created_at"),
        "actions" to listOf(
            "id", "action_type", "title", "query", "all_feeds", "feed_ids",
            "tag_ids", "effects", "created_at",
        ),
        // Optional in the source; imported only by the export tool.
        "user_prefs" to listOf(
            "sort", "default_view", "mark_read_on_open", "starred_view",
            "links_new_tab", "article_links_new_tab", "disable_image_proxy",
            "reading_font", "auto_fetch_full", "hide_updated", "theme",
            "font_size", "entry_width", "entries_density", "mastodon_instance",
            "show_unread_count", "hide_recently_read",
            "mark_as_read_confirmation", "starred_feed_enabled",
        ),
    )

    val REQUIRED_TABLES: Set<String> = TABLE_COLUMNS.keys - "user_prefs"

    sealed interface SourceKind {
        /** Preferred input: v1 export package (schemaVersion == 1). */
        data object PackageV1 : SourceKind

        /** Compat input: raw user-made SQLite copy of the web database. */
        data object RawCopy : SourceKind

        /** Precise rejection; nothing may be written for this input. */
        data class Rejected(val reason: String) : SourceKind
    }

    /** Classify the source: v1 package vs raw copy vs precise rejection. */
    fun classify(source: FeedBucketImporter.SourceDb): SourceKind {
        if (source.tableExists(PACKAGE_TABLE)) {
            val version = source
                .query("SELECT schemaVersion FROM $PACKAGE_TABLE LIMIT 1")
                .singleOrNull()
                ?.get("schemaVersion")
            return when (version) {
                null -> SourceKind.Rejected("package_meta has no schemaVersion row")
                "1" -> SourceKind.PackageV1
                else -> SourceKind.Rejected("unsupported package schemaVersion: $version")
            }
        }
        return SourceKind.RawCopy
    }

    /**
     * Required-table/column validation, applied to BOTH input shapes.
     * Returns null when valid, otherwise the precise rejection reason.
     */
    fun validate(source: FeedBucketImporter.SourceDb): SourceKind.Rejected? {
        for (table in REQUIRED_TABLES) {
            if (!source.tableExists(table)) {
                return SourceKind.Rejected("missing required table: $table")
            }
            val present = source.tableColumns(table).toSet()
            val missing = TABLE_COLUMNS.getValue(table) - present
            if (missing.isNotEmpty()) {
                return SourceKind.Rejected("table $table missing columns: ${missing.joinToString()}")
            }
        }
        return null
    }
}
