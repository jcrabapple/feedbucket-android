package me.ash.reader.domain.model.query

import me.ash.reader.domain.model.rule.ActionRule

/**
 * S15/S17a — query-mute SQL rendering (port of web `_query_mute_filters`,
 * db.py:652–676).
 *
 * Each mute becomes one `NOT (feedId IN (<scope>) AND <canonical match>)`
 * clause, rendered via [SearchSql] (S11a) — the ONLY mute renderer (D7).
 * Web parity skips:
 *  - mutes with blank queries (`if not mute["query"].strip(): continue`)
 *  - mutes whose S13-resolved scope is empty (`if not scope: continue`)
 *
 * (amended, R1) mutes NEVER have ingest effects (see RuleEngine); they are
 * pure query-time filters, applied ONLY to the unread/updated views by the
 * caller ([ScopeSql] pins that view matrix).
 *
 * [resolvedScope] maps each mute's id to its S13-resolved feed-id set
 * (empty scopes must be pre-dropped by the caller OR are dropped here —
 * dropping here keeps one parity implementation).
 */
object MuteFilters {

    data class Clause(
        /** Full `NOT (…)` WHERE contribution. */
        val sql: String,
        /** Bind args in positional order (scope ids, then match params). */
        val args: List<String>,
    )

    /**
     * Renders [mutes] to NOT-clauses; blank-query/empty-scope mutes are
     * skipped exactly like the web loop. [resolvedScope] supplies the S13
     * scope per mute id.
     */
    fun render(
        mutes: List<ActionRule>,
        resolvedScope: (ActionRule) -> Set<String>,
    ): List<Clause> = mutes.mapNotNull { mute -> renderOne(mute, resolvedScope(mute)) }

    /** One mute; null when web parity says skip (blank query/empty scope). */
    fun renderOne(mute: ActionRule, scope: Set<String>): Clause? {
        if (mute.type != ActionRule.TYPE_MUTE) return null
        if (mute.query.isBlank()) return null
        if (scope.isEmpty()) return null
        val match = SearchSql.renderWhere(SearchQuery.parse(mute.query))
        val placeholders = scope.joinToString(", ") { "?" }
        return Clause(
            sql = "NOT (feedId IN ($placeholders) AND ${match.sql})",
            args = scope.toList() + match.params,
        )
    }
}
