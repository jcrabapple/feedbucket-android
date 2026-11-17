package me.ash.reader.domain.service.migration

/**
 * S32a step 2 — secret guard for the FeedBucket migration.
 *
 * The importer NEVER reads the web `users` table: its column allowlist
 * (ImportSchemaChecker.TABLE_COLUMNS) excludes `users` entirely and every
 * query is built from explicit allowlisted column lists. This guard makes
 * that exclusion testable: the importer records every SQL string it issues
 * ([FeedBucketImporter.SourceDb.queriesIssued]) and asserts none of them
 * reference the secret columns; the export package is additionally scanned
 * for token-shaped values so D11's "never export login/session secrets" is
 * true of the artifact, not merely of the query list.
 *
 * These two literals appear ONLY as exclusion checks/comments in this
 * package — never inside an executed query (Gate G grep).
 */
object SecretGuard {

    /** Web `users`-table columns that must never appear in any query. */
    val FORBIDDEN_COLUMNS = setOf("password_hash", "starred_token")

    /**
     * Session-token shape: itsdangerous-style signed tokens carry 2+ long
     * base64url segments separated by dots. Mirrored by the python export
     * tool's secret scan (scripts/export_feedbucket_package.py).
     */
    private val TOKEN_VALUE_REGEX = Regex("[A-Za-z0-9_-]{20,}(\\.[A-Za-z0-9_-]{16,})+")

    /** Query-log assertion: none of the issued queries reference a secret column. */
    fun assertQueriesSecretFree(queries: Collection<String>) {
        val offending = queries.filter { query ->
            FORBIDDEN_COLUMNS.any { query.contains(it, ignoreCase = true) }
        }
        require(offending.isEmpty()) {
            "secret column name referenced by importer query: ${offending.first()}"
        }
    }

    /** Value-level scan over imported rows: no value may match a token shape. */
    fun tokenShapedValues(values: Sequence<String?>): List<String> =
        values.filterNotNull()
            .filter { TOKEN_VALUE_REGEX.containsMatchIn(it) }
            .toList()

    /** Asserts a scanned set of imported values carries no session-token shape. */
    fun assertValuesSecretFree(values: Sequence<String?>) {
        val hits = tokenShapedValues(values)
        require(hits.isEmpty()) { "token-shaped imported value detected" }
    }
}
