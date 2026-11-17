package me.ash.reader.domain.service

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import me.ash.reader.domain.model.search.SavedSearch
import me.ash.reader.domain.repository.SavedSearchDao

/**
 * S2.3/S15 — saved searches: verbatim-query persistence (the query string
 * is stored EXACTLY as entered, including `sort:`/`tag:` tokens), listed
 * in the flow filter bar and executable as scopes (view=all + query).
 *
 * Names are NOT unique (web parity — the web index is non-unique);
 * identity = rowid. Renaming and deletion are in scope.
 */
@Singleton
class SavedSearchService
@Inject
constructor(
    private val savedSearchDao: SavedSearchDao,
) {

    fun listFlow(): Flow<List<SavedSearch>> = savedSearchDao.queryAllFlow()

    /** Verbatim persistence: [query] is stored untrimmed/unmodified. */
    suspend fun create(name: String, query: String, now: Long = System.currentTimeMillis()): Long =
        savedSearchDao.insert(SavedSearch(name = name, query = query, createdAt = now))

    suspend fun rename(id: Long, name: String) {
        val existing = savedSearchDao.queryById(id) ?: return
        savedSearchDao.update(existing.copy(name = name))
    }

    suspend fun delete(id: Long) = savedSearchDao.deleteById(id)
}
