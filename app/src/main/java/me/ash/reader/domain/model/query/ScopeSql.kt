package me.ash.reader.domain.model.query

import me.ash.reader.domain.model.rule.ActionRule

/**
 * S15/S25a — dynamic SQL for scope snapshot rows and neighbor lookups,
 * consumed via Room `@RawQuery` (the mute NOT-clauses and the canonical
 * S10 query terms are runtime-dynamic, so static `@Query` cannot express
 * them). This builder is the ONLY source of scope SQL: for empty
 * query/mute inputs it reproduces the legacy static queries exactly
 * (pinned by exact-string tests), so the DAO can route every view through
 * the one raw path.
 *
 * The view matrix (S15) is pinned here: mute NOT-clauses are applied ONLY
 * for `unread`/`updated` views; starred/all/recentlyRead ignore [mutes].
 *
 * All bind args are String/Int/Long only (Room `@RawQuery` raw binding;
 * dates are never bound — they are read from cursors via Room converters).
 */
object ScopeSql {

    /** Views whose rows query-mutes filter (S15, web `_query_mute_filters`). */
    private val MUTED_VIEWS = setOf("unread", "updated")

    data class Built(
        val sql: String,
        val args: List<Any?>,
    )

    /** `(<view> = 'all' OR … )` — one positional placeholder per comparison. */
    private fun viewPredicate(view: String): Pair<String, List<Any?>> {
        val branches = listOf(
            "? = 'all'",
            "? = 'unread' AND isUnread = 1",
            "? = 'updated' AND isUpdated = 1",
            "? = 'starred' AND isStarred = 1",
            "? = 'recentlyRead' AND readAt IS NOT NULL",
        )
        return branches.joinToString(" OR ", "(", ")") { it } to
            List(branches.size) { view }
    }

    /** Feed/group scoping with NULL-tolerant binds, mirroring the legacy SQL. */
    private fun scopePredicate(feedId: String?, groupId: String?): Pair<String, List<Any?>> =
        "(? IS NULL OR feedId = ?)" to listOf(feedId, feedId)

    private fun groupPredicate(groupId: String?): Pair<String, List<Any?>> =
        "(? IS NULL OR feedId IN (SELECT id FROM feed WHERE groupId = ?))" to
            listOf(groupId, groupId)

    private fun combineSearch(query: SearchQuery?): Pair<String, List<Any?>> {
        if (query == null) return "" to emptyList()
        val where = SearchSql.renderWhere(query)
        return where.sql to where.params
    }

    private fun combineMutes(view: String, mutes: List<Pair<ActionRule, Set<String>>>): Pair<String, List<Any?>> {
        if (view !in MUTED_VIEWS || mutes.isEmpty()) return "" to emptyList()
        val clauses = mutes.mapNotNull { (mute, scope) -> MuteFilters.renderOne(mute, scope) }
        if (clauses.isEmpty()) return "" to emptyList()
        return clauses.joinToString(" AND ") { it.sql } to clauses.flatMap { it.args }
    }

    private fun orderBy(view: String, sortAscending: Boolean, query: SearchQuery?): Pair<String, List<Any?>> {
        if (view == "recentlyRead") {
            return "readAt DESC, rowid DESC" to emptyList()
        }
        return when (query?.sort) {
            SearchQuery.Sort.RELEVANCE -> {
                val score = SearchSql.renderRelevanceScore(query)
                "(${score.sql}) DESC, date DESC, rowid DESC" to score.params
            }
            SearchQuery.Sort.ASC -> "date ASC, rowid ASC" to emptyList()
            else ->
                if (sortAscending) "date ASC, rowid ASC" to emptyList()
                else "date DESC, rowid DESC" to emptyList()
        }
    }

    /**
     * Generation snapshot rows (S25a): ordered `(id, date AS sortKey,
     * rowid AS rowId)` of a scope. [search] adds the canonical query
     * terms (Wave D saved-search/search scopes); [mutes] apply only to
     * unread/updated.
     */
    fun rows(
        accountId: Int,
        view: String,
        feedId: String?,
        groupId: String?,
        sortAscending: Boolean,
        search: SearchQuery?,
        mutes: List<Pair<ActionRule, Set<String>>>,
    ): Built {
        val viewPred = viewPredicate(view)
        val scopePred = scopePredicate(feedId, groupId)
        val groupPred = groupPredicate(groupId)
        val searchPart = combineSearch(search)
        val mutePart = combineMutes(view, mutes)
        val order = orderBy(view, sortAscending, search)
        val sql = buildString {
            append("SELECT id, date AS sortKey, rowid AS rowId FROM article WHERE accountId = ?")
            append(" AND ${scopePred.first}")
            append(" AND ${groupPred.first}")
            append(" AND ${viewPred.first}")
            if (searchPart.first.isNotEmpty()) append(" AND ${searchPart.first}")
            if (mutePart.first.isNotEmpty()) append(" AND ${mutePart.first}")
            append(" ORDER BY ${order.first}")
        }
        return Built(
            sql = sql,
            args = listOf<Any?>(accountId) + scopePred.second + groupPred.second +
                viewPred.second + searchPart.second + mutePart.second + order.second,
        )
    }

    /**
     * S25a neighbor fallback (port of db.py:678 row-value comparison).
     * The view predicate intentionally matches the legacy neighbor SQL
     * (all/unread/starred only — no recentlyRead branch).
     */
    fun neighbor(
        accountId: Int,
        view: String,
        feedId: String?,
        groupId: String?,
        sortAscending: Boolean,
        sortKey: Long,
        rowId: Long,
        isPrev: Boolean,
        search: SearchQuery?,
        mutes: List<Pair<ActionRule, Set<String>>>,
    ): Built {
        val viewPred = viewPredicate(view)
        val scopePred = scopePredicate(feedId, groupId)
        val groupPred = groupPredicate(groupId)
        val searchPart = combineSearch(search)
        val mutePart = combineMutes(view, mutes)
        // Default (DESC) order: prev = closest row ABOVE (date, rowid) > pos,
        // next = closest BELOW. ASC flips both comparisons. The ORDER BY
        // mirrors the legacy queries: pick the CLOSEST row on the desired
        // side (prev: `asc→DESC, !asc→ASC`; next: `asc→ASC, !asc→DESC`).
        val comparison = if (isPrev) ">" else "<"
        val orderNear = if (isPrev) "ASC" else "DESC"
        val orderFar = if (isPrev) "DESC" else "ASC"
        val compare = "((? = 0 AND (date, rowid) $comparison (?, ?)) OR (? = 1 AND (date, rowid) ${flip(comparison)} (?, ?)))"
        val sql = buildString {
            append("SELECT id FROM article WHERE accountId = ?")
            append(" AND ${scopePred.first}")
            append(" AND ${groupPred.first}")
            append(" AND ${viewPred.first}")
            if (searchPart.first.isNotEmpty()) append(" AND ${searchPart.first}")
            if (mutePart.first.isNotEmpty()) append(" AND ${mutePart.first}")
            append(" AND $compare")
            append(
                " ORDER BY CASE WHEN ? = 1 THEN date END $orderFar, CASE WHEN ? = 0 THEN date END $orderNear, " +
                    "CASE WHEN ? = 1 THEN rowid END $orderFar, CASE WHEN ? = 0 THEN rowid END $orderNear LIMIT 1"
            )
        }
        val asc = if (sortAscending) 1 else 0
        return Built(
            sql = sql,
            args = listOf<Any?>(accountId) + scopePred.second + groupPred.second +
                viewPred.second + searchPart.second + mutePart.second +
                listOf<Any?>(asc, sortKey, rowId, asc, sortKey, rowId) +
                listOf<Any?>(asc, asc, asc, asc),
        )
    }

    private fun flip(comparison: String) = if (comparison == ">") "<" else ">"

    fun prev(
        accountId: Int, view: String, feedId: String?, groupId: String?,
        sortAscending: Boolean, sortKey: Long, rowId: Long,
        search: SearchQuery? = null,
        mutes: List<Pair<ActionRule, Set<String>>> = emptyList(),
    ): Built = neighbor(accountId, view, feedId, groupId, sortAscending, sortKey, rowId, true, search, mutes)

    fun next(
        accountId: Int, view: String, feedId: String?, groupId: String?,
        sortAscending: Boolean, sortKey: Long, rowId: Long,
        search: SearchQuery? = null,
        mutes: List<Pair<ActionRule, Set<String>>> = emptyList(),
    ): Built = neighbor(accountId, view, feedId, groupId, sortAscending, sortKey, rowId, false, search, mutes)
}
