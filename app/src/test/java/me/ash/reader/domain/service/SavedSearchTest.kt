package me.ash.reader.domain.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.query.SearchQuery
import me.ash.reader.domain.model.search.SavedSearch
import me.ash.reader.domain.repository.SavedSearchDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S2.3/S15 — saved searches: verbatim-query persistence (the query string
 * is stored EXACTLY as entered, `sort:`/`tag:` tokens included), duplicate
 * names allowed (web parity), rename/delete, and the full S10 parser path
 * exercised end-to-end on a saved-search query (acceptance: querying
 * `sort:relevance tag:tech foo "bar baz"`).
 */
class SavedSearchTest {

    private class FakeSavedSearchDao : SavedSearchDao {
        val store = mutableMapOf<Long, SavedSearch>()
        private var nextId = 1L

        override fun queryAllFlow(): Flow<List<SavedSearch>> =
            throw UnsupportedOperationException("flow unused in JVM tests")

        override suspend fun queryById(id: Long): SavedSearch? = store[id]

        override suspend fun insert(savedSearch: SavedSearch): Long {
            val id = nextId++
            store[id] = savedSearch.copy(id = id)
            return id
        }

        override suspend fun update(savedSearch: SavedSearch) {
            store[savedSearch.id] = savedSearch
        }

        override suspend fun deleteById(id: Long) {
            store.remove(id)
        }
    }

    @Test
    fun `create persists the query verbatim including sort and tag tokens`() = runBlocking {
        val dao = FakeSavedSearchDao()
        val service = SavedSearchService(dao)
        val query = "sort:relevance tag:tech foo \"bar baz\""
        val id = service.create("Tech rel", query)
        val saved = dao.store.getValue(id)
        assertEquals(query, saved.query)
        assertEquals("Tech rel", saved.name)
    }

    @Test
    fun `duplicate names are allowed - identity is the rowid`() = runBlocking {
        val dao = FakeSavedSearchDao()
        val service = SavedSearchService(dao)
        val first = service.create("news", "alpha")
        val second = service.create("news", "beta")
        assertEquals(2, dao.store.size)
        assertEquals("alpha", dao.store.getValue(first).query)
        assertEquals("beta", dao.store.getValue(second).query)
    }

    @Test
    fun `rename and delete`() = runBlocking {
        val dao = FakeSavedSearchDao()
        val service = SavedSearchService(dao)
        val id = service.create("old", "foo")
        service.rename(id, "new")
        assertEquals("new", dao.store.getValue(id).name)
        assertEquals("foo", dao.store.getValue(id).query) // rename keeps the query
        service.delete(id)
        assertNull(dao.store[id])
    }

    @Test
    fun `saved search query exercises the full S10 parser path`() {
        // The exact Wave D acceptance fixture: the string a user saves is
        // parsed by the ONE grammar into terms/tag/sort with no second path.
        val parsed = SearchQuery.parse("sort:relevance tag:tech foo \"bar baz\"")
        assertEquals(SearchQuery.Sort.RELEVANCE, parsed.sort)
        assertEquals("tech", parsed.tag)
        assertEquals(listOf("foo", "bar baz"), parsed.terms)
    }

    @Test
    fun `verbatim persistence keeps leading and trailing whitespace`() = runBlocking {
        val dao = FakeSavedSearchDao()
        val service = SavedSearchService(dao)
        val id = service.create("spaced", "  foo bar  ")
        assertEquals("  foo bar  ", dao.store.getValue(id).query)
    }
}
