package me.ash.reader.domain.model.query

/**
 * S18a — SQL for rule preview and apply-to-existing match sets, rendered
 * via [SearchSql] (S11a) over the S13-resolved scope feed ids. Consumed
 * through Room `@RawQuery`; this builder is the ONLY renderer (D7).
 *
 * The article table is aliased `a` in [preview] (feed join for the feed
 * name) and unaliased in [states]; shadow-column references are qualified
 * accordingly.
 */
object RuleMatchSql {

    data class Built(
        val sql: String,
        val args: List<Any?>,
    )

    private fun scopeWhere(feedIds: List<String>): Pair<String, List<Any?>> =
        "feedId IN (${feedIds.joinToString(", ") { "?" }})" to feedIds.toList()

    private fun matchWhere(query: SearchQuery, alias: String?): Pair<String, List<Any?>> {
        // Terms render over the shadow columns (unambiguous in both queries —
        // feed has no *Lower columns); the tag clause needs the article alias.
        val terms = SearchSql.renderTerms(query.copy(tag = null))
        val tag = SearchSql.renderTag(query, articleAlias = alias ?: "article")
        val parts = listOf(terms, tag).filter { it.sql.isNotEmpty() }
        return parts.joinToString(" AND ") { it.sql } to parts.flatMap { it.params }
    }

    /**
     * S18a preview: total match count + the first [limit] samples
     * `(articleId, title, feed name, date)` (port of `preview_action`
     * cap 20). Callers run [count] separately for the total.
     */
    fun preview(accountId: Int, feedIds: List<String>, query: SearchQuery, limit: Int = 20): Built {
        require(feedIds.isNotEmpty()) { "empty S13 scope matches nothing (S13)" }
        val scope = scopeWhere(feedIds)
        val match = matchWhere(query, alias = "a")
        val order = when (query.sort) {
            SearchQuery.Sort.RELEVANCE -> {
                val score = SearchSql.renderRelevanceScore(query, alias = "a")
                "(${score.sql}) DESC, a.date DESC, a.rowid DESC" to score.params
            }
            SearchQuery.Sort.ASC -> "a.date ASC, a.rowid ASC" to emptyList()
            SearchQuery.Sort.DESC -> "a.date DESC, a.rowid DESC" to emptyList()
        }
        val sql = buildString {
            append(
                "SELECT a.id, a.title, f.name AS feedName, a.date FROM article AS a " +
                    "LEFT JOIN feed AS f ON f.id = a.feedId WHERE a.accountId = ?"
            )
            append(" AND a.${scope.first}")
            if (match.first.isNotEmpty()) append(" AND ${match.first}")
            append(" ORDER BY ${order.first} LIMIT ?")
        }
        return Built(
            sql = sql,
            args = listOf<Any?>(accountId) + scope.second + match.second + order.second + listOf(limit),
        )
    }

    /** S18a apply-to-existing: the full matched state set for the ledger. */
    fun states(accountId: Int, feedIds: List<String>, query: SearchQuery): Built {
        require(feedIds.isNotEmpty()) { "empty S13 scope matches nothing (S13)" }
        val scope = scopeWhere(feedIds)
        val match = matchWhere(query, alias = null)
        val sql = buildString {
            append(
                "SELECT id, isUnread, isStarred, isUpdated, readAt, stateVersion FROM article " +
                    "WHERE accountId = ?"
            )
            append(" AND ${scope.first}")
            if (match.first.isNotEmpty()) append(" AND ${match.first}")
            append(" ORDER BY date DESC, rowid DESC")
        }
        return Built(sql = sql, args = listOf<Any?>(accountId) + scope.second + match.second)
    }

    /** Companion count query for the preview total (S18a count confirmation). */
    fun count(accountId: Int, feedIds: List<String>, query: SearchQuery): Built {
        require(feedIds.isNotEmpty()) { "empty S13 scope matches nothing (S13)" }
        val scope = scopeWhere(feedIds)
        val match = matchWhere(query, alias = null)
        val sql = buildString {
            append("SELECT count(1) FROM article WHERE accountId = ?")
            append(" AND ${scope.first}")
            if (match.first.isNotEmpty()) append(" AND ${match.first}")
        }
        return Built(sql = sql, args = listOf<Any?>(accountId) + scope.second + match.second)
    }
}
