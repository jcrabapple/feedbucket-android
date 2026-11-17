package me.ash.reader.domain.service.migration

/**
 * S32a step 3 — the no-writes preview: exact counts table plus the first 10
 * subscription titles, the input kind (v1 package vs raw copy) and the file
 * fingerprint recorded at preview time (re-checked before apply).
 */
data class ImportPreview(
    val kind: ImportSchemaChecker.SourceKind,
    val fingerprint: String,
    val counts: Counts,
    val firstSubscriptionTitles: List<String>,
) {

    /** Exact preview counts (UI-CONTRACT §6.10 counts table). */
    data class Counts(
        val subscriptions: Int,
        val tags: Int,
        val savedSearches: Int,
        val entries: Int,
        val unread: Int,
        val starred: Int,
        val updated: Int,
        val recentlyRead: Int,
        val standardActions: Int,
        val muteActions: Int,
        val snapshots: Int,
        val extracted: Int,
    )

    companion object {
        private const val TITLE_SAMPLE = 10

        /** Read the preview from an OPEN, VALIDATED source. No writes. */
        fun read(
            source: FeedBucketImporter.SourceDb,
            fingerprint: String,
            kind: ImportSchemaChecker.SourceKind,
        ): ImportPreview {
            fun count(sql: String): Int =
                source.query(sql).singleOrNull()?.get("c")?.toIntOrNull() ?: 0

            val titles = source
                .query("SELECT title FROM subscriptions ORDER BY id LIMIT $TITLE_SAMPLE")
                .map { it.getValue("title").orEmpty() }

            return ImportPreview(
                kind = kind,
                fingerprint = fingerprint,
                counts = Counts(
                    subscriptions = count("SELECT COUNT(*) AS c FROM subscriptions"),
                    tags = count("SELECT COUNT(*) AS c FROM tags"),
                    savedSearches = count("SELECT COUNT(*) AS c FROM saved_searches"),
                    entries = count("SELECT COUNT(*) AS c FROM entries"),
                    unread = count("SELECT COUNT(*) AS c FROM unread"),
                    starred = count("SELECT COUNT(*) AS c FROM starred"),
                    updated = count("SELECT COUNT(*) AS c FROM updated"),
                    recentlyRead = count("SELECT COUNT(*) AS c FROM recently_read"),
                    standardActions =
                        count("SELECT COUNT(*) AS c FROM actions WHERE action_type = 'standard'"),
                    muteActions =
                        count("SELECT COUNT(*) AS c FROM actions WHERE action_type = 'mute'"),
                    snapshots =
                        count("SELECT COUNT(*) AS c FROM entries WHERE original_content IS NOT NULL"),
                    extracted = count("SELECT COUNT(*) AS c FROM entries WHERE extracted = 1"),
                ),
                firstSubscriptionTitles = titles,
            )
        }
    }
}
