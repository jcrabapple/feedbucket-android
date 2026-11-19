package me.ash.reader.domain.model.query

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S11a/S14 — exact SQL rendering and positional parameter order.
 */
class SearchSqlTest {

    // Review lane 3 defect 4 cleanup: likeTerm() already wraps `%…%`, so the
    // column clause binds the pattern directly (no `'%' || ? || '%'`
    // re-wrapping into `%%term%%`).
    private val TERM_SQL = "(titleLower LIKE ? ESCAPE '\\' OR " +
        "shortLower LIKE ? ESCAPE '\\' OR " +
        "rawLower LIKE ? ESCAPE '\\' OR " +
        "fullLower LIKE ? ESCAPE '\\')"

    private val TAG_SQL = "EXISTS (SELECT 1 FROM feed f2 WHERE f2.id = article.feedId " +
        "AND (',' || f2.tags || ',' LIKE '%,' || ? || ',%' ESCAPE '\\'))"

    @Test
    fun likeTermEscapesAfterNormalization() {
        assertEquals("%foo%", SearchSql.likeTerm("foo"))
        assertEquals("%50\\%\\_off\\\\%", SearchSql.likeTerm("50%_off\\"))
        // normalization happens before escaping (S11a)
        assertEquals("%feedbucket%", SearchSql.likeTerm(TextNormalizer.normalize("FeedBucket")))
    }

    @Test
    fun escapeLikeEscapesWithoutWrapping() {
        // Review lane 3 defect 4: tag containment escapes WITHOUT the %…% wrap.
        assertEquals("foo", SearchSql.escapeLike("foo"))
        assertEquals("foo\\_bar", SearchSql.escapeLike("foo_bar"))
        assertEquals("100\\%", SearchSql.escapeLike("100%"))
        assertEquals("50\\%\\_off\\\\", SearchSql.escapeLike("50%_off\\"))
    }

    @Test
    fun singleTermRendersFourColumnOrWithParamPerLike() {
        val rendered = SearchSql.renderTerm("FeedBucket")
        assertEquals(TERM_SQL, rendered.sql)
        assertEquals(listOf("%feedbucket%", "%feedbucket%", "%feedbucket%", "%feedbucket%"), rendered.params)
    }

    @Test
    fun multipleTermsAndedInOrder() {
        val rendered = SearchSql.renderTerms(SearchQuery.parse("alpha beta"))
        assertEquals("$TERM_SQL AND $TERM_SQL", rendered.sql)
        assertEquals(
            listOf("%alpha%", "%alpha%", "%alpha%", "%alpha%", "%beta%", "%beta%", "%beta%", "%beta%"),
            rendered.params,
        )
    }

    @Test
    fun emptyTermsRenderEmptyClause() {
        val rendered = SearchSql.renderTerms(SearchQuery.parse(""))
        assertEquals("", rendered.sql)
        assertEquals(emptyList<String>(), rendered.params)
    }

    @Test
    fun tagRendersCsvContainmentNormalized() {
        val rendered = SearchSql.renderTag(SearchQuery.parse("tag:Tech"))
        assertEquals(TAG_SQL, rendered.sql)
        assertEquals(listOf("tech"), rendered.params)
    }

    @Test
    fun tagEscapesLikeWildcardsLiterally() {
        // Review lane 3 defect 4: `_` and `%` inside tag names must bind as
        // ESCAPE '\' literals, never as LIKE wildcards (`foo_bar` must not
        // match `fooXbar`, `100%` must not match `1000`).
        assertEquals(
            listOf("foo\\_bar"),
            SearchSql.renderTag(SearchQuery.parse("tag:foo_bar")).params,
        )
        assertEquals(
            listOf("100\\%"),
            SearchSql.renderTag(SearchQuery.parse("tag:100%")).params,
        )
        // SearchQuery.parse consumes `\` as a token escape, so the raw
        // backslash case is constructed directly at the renderer level.
        assertEquals(
            listOf("back\\\\slash"),
            SearchSql.renderTag(
                SearchQuery(
                    terms = emptyList(),
                    tag = "back\\slash",
                    sort = SearchQuery.Sort.DESC,
                ),
            ).params,
        )
        // The clause itself declares the escape character.
        val rendered = SearchSql.renderTag(SearchQuery.parse("tag:foo_bar"))
        assertEquals(TAG_SQL, rendered.sql)
    }

    @Test
    fun whereCombinesTermsThenTag() {
        val rendered = SearchSql.renderWhere(SearchQuery.parse("tag:tech foo"))
        assertEquals("$TERM_SQL AND $TAG_SQL", rendered.sql)
        assertEquals(
            listOf("%foo%", "%foo%", "%foo%", "%foo%", "tech"),
            rendered.params,
        )
    }

    @Test
    fun relevanceScorePerTermWeights() {
        val rendered = SearchSql.renderRelevanceScore(SearchQuery.parse("foo"))
        assertEquals(
            "( CASE WHEN titleLower LIKE ? ESCAPE '\\' THEN 5 ELSE 0 END + " +
                "CASE WHEN shortLower LIKE ? ESCAPE '\\' THEN 2 ELSE 0 END + " +
                "CASE WHEN rawLower LIKE ? ESCAPE '\\' OR " +
                "fullLower LIKE ? ESCAPE '\\' THEN 1 ELSE 0 END )",
            rendered.sql,
        )
        assertEquals(listOf("%foo%", "%foo%", "%foo%", "%foo%"), rendered.params)
    }

    @Test
    fun orderByClauses() {
        assertEquals("date ASC, rowid ASC", SearchSql.renderOrderBy(SearchQuery.Sort.ASC))
        assertEquals("date DESC, rowid DESC", SearchSql.renderOrderBy(SearchQuery.Sort.DESC))
        assertEquals("score DESC, date DESC, rowid DESC", SearchSql.renderOrderBy(SearchQuery.Sort.RELEVANCE))
        assertEquals("a.date ASC, rowid ASC", SearchSql.renderOrderBy(SearchQuery.Sort.ASC, dateColumn = "a.date"))
        assertEquals("a.date DESC, rowid DESC", SearchSql.renderOrderBy(SearchQuery.Sort.DESC, dateColumn = "a.date"))
    }

    @Test
    fun escapeFixtureFromAcceptanceList() {
        // acceptance #12 fixture: term `50%_off\` must not wildcard-explode
        val rendered = SearchSql.renderTerm("50%_off\\")
        assertEquals(listOf("%50\\%\\_off\\\\%", "%50\\%\\_off\\\\%", "%50\\%\\_off\\\\%", "%50\\%\\_off\\\\%"), rendered.params)
    }
}
