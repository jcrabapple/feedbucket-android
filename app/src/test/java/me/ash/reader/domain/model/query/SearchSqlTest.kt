package me.ash.reader.domain.model.query

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S11a/S14 — exact SQL rendering and positional parameter order.
 */
class SearchSqlTest {

    private val TERM_SQL = "(titleLower LIKE '%' || ? || '%' ESCAPE '\\' OR " +
        "shortLower LIKE '%' || ? || '%' ESCAPE '\\' OR " +
        "rawLower LIKE '%' || ? || '%' ESCAPE '\\' OR " +
        "fullLower LIKE '%' || ? || '%' ESCAPE '\\')"

    @Test
    fun likeTermEscapesAfterNormalization() {
        assertEquals("%foo%", SearchSql.likeTerm("foo"))
        assertEquals("%50\\%\\_off\\\\%", SearchSql.likeTerm("50%_off\\"))
        // normalization happens before escaping (S11a)
        assertEquals("%feedbucket%", SearchSql.likeTerm(TextNormalizer.normalize("FeedBucket")))
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
        assertEquals(
            "EXISTS (SELECT 1 FROM feed f2 WHERE f2.id = article.feedId " +
                "AND (',' || f2.tags || ',' LIKE '%,' || ? || ',%'))",
            rendered.sql,
        )
        assertEquals(listOf("tech"), rendered.params)
    }

    @Test
    fun whereCombinesTermsThenTag() {
        val rendered = SearchSql.renderWhere(SearchQuery.parse("tag:tech foo"))
        assertEquals("$TERM_SQL AND " +
            "EXISTS (SELECT 1 FROM feed f2 WHERE f2.id = article.feedId " +
            "AND (',' || f2.tags || ',' LIKE '%,' || ? || ',%'))", rendered.sql)
        assertEquals(
            listOf("%foo%", "%foo%", "%foo%", "%foo%", "tech"),
            rendered.params,
        )
    }

    @Test
    fun relevanceScorePerTermWeights() {
        val rendered = SearchSql.renderRelevanceScore(SearchQuery.parse("foo"))
        assertEquals(
            "( CASE WHEN titleLower LIKE '%' || ? || '%' ESCAPE '\\' THEN 5 ELSE 0 END + " +
                "CASE WHEN shortLower LIKE '%' || ? || '%' ESCAPE '\\' THEN 2 ELSE 0 END + " +
                "CASE WHEN rawLower LIKE '%' || ? || '%' ESCAPE '\\' OR " +
                "fullLower LIKE '%' || ? || '%' ESCAPE '\\' THEN 1 ELSE 0 END )",
            rendered.sql,
        )
        assertEquals(listOf("%foo%", "%foo%", "%foo%", "%foo%"), rendered.params)
    }

    @Test
    fun orderByClauses() {
        assertEquals("date ASC, rowid ASC", SearchSql.renderOrderBy(SearchQuery.Sort.ASC))
        assertEquals("date DESC, rowid DESC", SearchSql.renderOrderBy(SearchQuery.Sort.DESC))
        assertEquals("score DESC, date DESC, rowid DESC", SearchSql.renderOrderBy(SearchQuery.Sort.RELEVANCE))
        assertEquals("a.date DESC, rowid DESC", SearchSql.renderOrderBy(SearchQuery.Sort.DESC, dateColumn = "a.date"))
    }

    @Test
    fun escapeFixtureFromAcceptanceList() {
        // acceptance #12 fixture: term `50%_off\` must not wildcard-explode
        val rendered = SearchSql.renderTerm("50%_off\\")
        assertEquals(listOf("%50\\%\\_off\\\\%", "%50\\%\\_off\\\\%", "%50\\%\\_off\\\\%", "%50\\%\\_off\\\\%"), rendered.params)
    }
}
