package me.ash.reader.domain.model.query

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S10 — parser parity with Python `shlex.split` (web `parse_search`).
 *
 * Fixtures were generated read-only from the web repo venv:
 * `env -u PYTHONPATH /home/jason/projects/feedbucket/.venv/bin/python`
 * running `shlex.split` over the input strings; ValueError rows use the
 * whitespace-split fallback exactly like web `parse_search`
 * (`except ValueError: query.split()`).
 */
class SearchQueryParserTest {

    @Test
    fun shlexParityFixtures() {
        val fixtures = listOf(
            "" to listOf<String>(),
            "hello world" to listOf("hello", "world"),
            "foo \"bar baz\" qux" to listOf("foo", "bar baz", "qux"),
            "foo 'bar baz' qux" to listOf("foo", "bar baz", "qux"),
            "foo\\ bar baz" to listOf("foo bar", "baz"),
            "\"a\\\"b\" c" to listOf("a\"b", "c"),
            "'a\\'b' c" to listOf("'a\\'b'", "c"), // unbalanced: fallback split
            "\"C:\\\\Program Files\" x" to listOf("C:\\Program Files", "x"),
            "sort:asc foo" to listOf("sort:asc", "foo"),
            "foo sort:asc sort:desc" to listOf("foo", "sort:asc", "sort:desc"),
            "foo sort:bogus" to listOf("foo", "sort:bogus"),
            "SORT:ASC foo" to listOf("SORT:ASC", "foo"),
            "tag:tech foo" to listOf("tag:tech", "foo"),
            "tag:tech tag:science x" to listOf("tag:tech", "tag:science", "x"),
            "tag: tag:x" to listOf("tag:", "tag:x"),
            "unbalanced \"quote foo" to listOf("unbalanced", "\"quote", "foo"),
            "unbalanced 'quote foo" to listOf("unbalanced", "'quote", "foo"),
            "trailing\\" to listOf("trailing\\"), // unbalanced: fallback split
            "sort:relevance tag:News \"multi word\" plain" to
                listOf("sort:relevance", "tag:News", "multi word", "plain"),
            "a  b\tc" to listOf("a", "b", "c"),
            "\"quoted\"adjacent" to listOf("quotedadjacent"),
            "\"\"" to listOf(""),
            "''" to listOf(""),
            "\"\" \"\"" to listOf("", ""),
            "a\"\"b" to listOf("ab"),
            "a\\\"b" to listOf("a\"b"),
        )
        for ((input, expected) in fixtures) {
            assertEquals("tokenize($input)", expected, SearchQuery.tokenize(input))
        }
    }

    @Test
    fun sortLastWinsAndInvalidIgnored() {
        val parsed = SearchQuery.parse("foo sort:asc sort:desc")
        assertEquals(listOf("foo"), parsed.terms)
        assertEquals(SearchQuery.Sort.DESC, parsed.sort)

        val invalid = SearchQuery.parse("foo sort:bogus")
        assertEquals(listOf("foo"), invalid.terms)
        assertEquals(SearchQuery.Sort.DESC, invalid.sort)

        // Web parity (db.py:1047): the "sort:" PREFIX check is lowercase-only,
        // so SORT:ASC is a TERM; the VALUE after sort: is case-insensitive.
        val uppercasePrefix = SearchQuery.parse("SORT:ASC foo")
        assertEquals(SearchQuery.Sort.DESC, uppercasePrefix.sort)
        assertEquals(listOf("SORT:ASC", "foo"), uppercasePrefix.terms)

        val caseInsensitiveValue = SearchQuery.parse("sort:ASC foo")
        assertEquals(SearchQuery.Sort.ASC, caseInsensitiveValue.sort)
        assertEquals(listOf("foo"), caseInsensitiveValue.terms)

        val relevance = SearchQuery.parse("sort:relevance x")
        assertEquals(SearchQuery.Sort.RELEVANCE, relevance.sort)
    }

    @Test
    fun tagLastWinsAndEmptyNameIsTerm() {
        val parsed = SearchQuery.parse("tag:tech tag:science x")
        assertEquals("science", parsed.tag)
        assertEquals(listOf("x"), parsed.terms)

        val emptyName = SearchQuery.parse("tag: tag:x")
        assertEquals("x", emptyName.tag)
        assertEquals(listOf("tag:"), emptyName.terms)

        // Web parity: lowercase-only "tag:" prefix check too.
        val uppercasePrefix = SearchQuery.parse("TAG:tech foo")
        assertNull(uppercasePrefix.tag)
        assertEquals(listOf("TAG:tech", "foo"), uppercasePrefix.terms)
    }

    @Test
    fun emptyQueryYieldsEmptyTerms() {
        val parsed = SearchQuery.parse("")
        assertEquals(emptyList<String>(), parsed.terms)
        assertNull(parsed.tag)
        assertEquals(SearchQuery.Sort.DESC, parsed.sort)
    }

    @Test
    fun combinedQuery() {
        val parsed = SearchQuery.parse("sort:relevance tag:News \"multi word\" plain")
        assertEquals(SearchQuery.Sort.RELEVANCE, parsed.sort)
        assertEquals("News", parsed.tag)
        assertEquals(listOf("multi word", "plain"), parsed.terms)
    }
}
