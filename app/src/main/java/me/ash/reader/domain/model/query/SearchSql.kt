package me.ash.reader.domain.model.query

/**
 * S11a/S14 — SQL rendering over the `*Lower` shadow columns.
 *
 * Pure string builder; the ONLY SQL renderer for the canonical S12a
 * predicate. Both sides pass through [TextNormalizer], so the SQL path
 * implements the canonical Unicode case-insensitive substring predicate
 * exactly (deliberate improvement over the web's ASCII-only LIKE).
 *
 * ESCAPE (`\`, `%`, `_`) port of web `like_term` (db.py:167), applied
 * AFTER normalization.
 */
object SearchSql {

    private const val LIKE_PATTERN = "LIKE '%' || ? || '%' ESCAPE '\\'"

    data class Rendered(
        /** Empty string when there is nothing to render. */
        val sql: String,
        /** Bind parameters in positional order matching [sql]. */
        val params: List<String>,
    )

    /**
     * Port of web `like_term` (db.py:167): escape `\`, `%`, `_`, then wrap
     * in `%…%`. Call with an ALREADY-normalized term.
     */
    fun likeTerm(normalizedTerm: String): String =
        "%" + normalizedTerm
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_") + "%"

    /** One term AND-ed across the four normalized shadow columns. */
    fun renderTerm(term: String): Rendered {
        val pattern = likeTerm(TextNormalizer.normalize(term))
        val clause = "(" + listOf("titleLower", "shortLower", "rawLower", "fullLower")
            .joinToString(" OR ") { "$it $LIKE_PATTERN" } + ")"
        return Rendered(clause, listOf(pattern, pattern, pattern, pattern))
    }

    /**
     * All terms AND-ed, order preserved. Empty [SearchQuery.terms] renders
     * an empty clause (caller combines with scope/view filters).
     */
    fun renderTerms(query: SearchQuery): Rendered {
        val parts = query.terms.map { renderTerm(it) }
        return Rendered(
            sql = parts.joinToString(" AND ") { it.sql },
            params = parts.flatMap { it.params },
        )
    }

    /**
     * `tag:` as CSV-safe containment over `feed.tags` (S11a). Both sides
     * lowercase: tags are stored lowercased (S4).
     */
    fun renderTag(query: SearchQuery): Rendered {
        val tag = query.tag ?: return Rendered("", emptyList())
        return Rendered(
            sql = "EXISTS (SELECT 1 FROM feed f2 WHERE f2.id = article.feedId " +
                "AND (',' || f2.tags || ',' LIKE '%,' || ? || ',%'))",
            params = listOf(TextNormalizer.normalize(tag)),
        )
    }

    /** Combined WHERE contribution for a parsed query. */
    fun renderWhere(query: SearchQuery): Rendered {
        val terms = renderTerms(query)
        val tag = renderTag(query)
        val parts = listOf(terms, tag).filter { it.sql.isNotEmpty() }
        return Rendered(
            sql = parts.joinToString(" AND ") { it.sql },
            params = parts.flatMap { it.params },
        )
    }

    /**
     * S14 relevance score: per term, title=5 + short=2 + raw/full=1,
     * summed (port of db.py:632-642).
     */
    fun renderRelevanceScore(query: SearchQuery): Rendered {
        val parts = query.terms.map { term ->
            val pattern = likeTerm(TextNormalizer.normalize(term))
            Rendered(
                "CASE WHEN titleLower $LIKE_PATTERN THEN 5 ELSE 0 END + " +
                    "CASE WHEN shortLower $LIKE_PATTERN THEN 2 ELSE 0 END + " +
                    "CASE WHEN rawLower $LIKE_PATTERN OR fullLower $LIKE_PATTERN " +
                    "THEN 1 ELSE 0 END",
                listOf(pattern, pattern, pattern, pattern),
            )
        }
        return Rendered(
            sql = parts.joinToString(" + ") { "( ${it.sql} )" },
            params = parts.flatMap { it.params },
        )
    }

    /**
     * S14 ordering. `contentChangedAt` is NEVER a sort key (D8); ties break
     * on `rowid` (the implicit rowid of the TEXT-PK article table, same
     * observable as the web's integer id tiebreak).
     */
    fun renderOrderBy(sort: SearchQuery.Sort, dateColumn: String = "date"): String =
        when (sort) {
            SearchQuery.Sort.ASC -> "$dateColumn ASC, rowid ASC"
            SearchQuery.Sort.DESC -> "$dateColumn DESC, rowid DESC"
            SearchQuery.Sort.RELEVANCE -> "score DESC, $dateColumn DESC, rowid DESC"
        }
}
