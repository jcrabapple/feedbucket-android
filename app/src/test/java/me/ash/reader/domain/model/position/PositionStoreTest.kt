package me.ash.reader.domain.model.position

import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.repository.PositionDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S24a (amended, R8) — canonical scope-key INJECTIVITY and anchor restore tiers.
 *
 * TDD pins:
 *  - empty vs `-` slot values produce DIFFERENT keys
 *  - queries containing `/`, `%`, `|` round-trip to DISTINCT keys (no slot collision)
 *  - encode -> decode round-trip equality (incl. Unicode)
 *  - restore tiers: exact anchorArticleId -> clamped anchorItemIndex -> top fallback
 *  - corrupt rows (negative index, bad version, unparsable key) fall back, never crash
 *  - 30-day prune boundary
 */
class PositionStoreTest {

    private fun store(
        rows: MutableMap<String, ListPosition> = mutableMapOf(),
        now: Long = NOW,
    ): Pair<PositionStore, MutableMap<String, ListPosition>> {
        val fakeDao =
            object : PositionDao {
                override suspend fun queryListPosition(scopeKey: String): ListPosition? = rows[scopeKey]

                override suspend fun upsertListPosition(position: ListPosition) {
                    rows[position.scopeKey] = position
                }

                override suspend fun pruneListPositions(before: Long) {
                    rows.entries.removeIf { it.value.updatedAt < before }
                }

                override suspend fun queryReadingPosition(articleId: String): ReadingPosition? = null

                override suspend fun upsertReadingPosition(position: ReadingPosition) = Unit
            }
        return PositionStore(fakeDao) { now } to rows
    }

    private fun scope(query: String = "", feedId: String? = null, view: String = "unread") =
        PositionScope(view = view, feedId = feedId, query = query)

    @Test
    fun `empty slot differs from dash slot`() {
        val emptyFeed = scope(feedId = "").key
        val dashFeed = scope(feedId = "-").key
        assertNotEquals(emptyFeed, dashFeed)
    }

    @Test
    fun `query with delimiter and escape chars round-trips to distinct keys`() {
        val base = scope(query = "plain")
        val slash = scope(query = "a/b")
        val pct = scope(query = "a%b")
        val pipe = scope(query = "a|b")
        val dash = scope(query = "-")

        for (s in listOf(slash, pct, pipe, dash)) {
            assertNotEquals(base.key, s.key)
        }
        // distinct from each other too
        assertEquals(
            setOf(base, slash, pct, pipe, dash).map { it.key }.toSet().size,
            5,
        )
        // round-trip
        assertEquals("a/b", ScopeKey.decode(slash.key)!![4])
        assertEquals("a%b", ScopeKey.decode(pct.key)!![4])
        assertEquals("a|b", ScopeKey.decode(pipe.key)!![4])
        assertEquals("-", ScopeKey.decode(dash.key)!![4])
        assertEquals("plain", ScopeKey.decode(base.key)!![4])
    }

    @Test
    fun `unicode query round-trips`() {
        val s = scope(query = "straße İ 中文 🚀")
        val decoded = ScopeKey.decode(s.key)!!
        assertEquals("straße İ 中文 🚀", decoded[4])
    }

    @Test
    fun `full scope round-trips through encode and decode`() {
        val s =
            PositionScope(
                view = "recentlyRead",
                feedId = "f/1",
                groupId = "g%2",
                savedSearchId = "s|3",
                query = "q=4/5",
                sort = "asc",
            )
        val decoded = ScopeKey.decode(s.key)!!
        assertEquals(listOf("recentlyRead", "f/1", "g%2", "s|3", "q=4/5", "asc"), decoded)
    }

    @Test
    fun `decode rejects wrong version and slot count`() {
        assertNull(ScopeKey.decode("v2/unread"))
        assertNull(ScopeKey.decode("unread/// /desc"))
        assertNull(ScopeKey.decode("v1/only-three/slots/desc"))
        assertNull(ScopeKey.decode("v1/a/b/c/d/e/f/g")) // 7 slots -> reject
    }

    @Test
    fun `restore exact anchor by article id`() = runBlocking {
        val (store, _) = store()
        val ids = listOf("a1", "a2", "a3", "a4")
        store.persist(scope(), anchorArticleId = "a3", anchorItemIndex = 2, anchorOffsetPx = 42)

        val restored = store.restore(scope(), itemIds = ids)
        assertEquals(RestoredPosition(itemIndex = 2, itemOffsetPx = 42), restored)
    }

    @Test
    fun `restore clamps index when anchor id left the scope`() = runBlocking {
        val (store, _) = store()
        val ids = listOf("a1", "a2", "a3")
        store.persist(scope(), anchorArticleId = "gone", anchorItemIndex = 99, anchorOffsetPx = 7)

        val restored = store.restore(scope(), itemIds = ids)
        assertEquals(RestoredPosition(itemIndex = 2, itemOffsetPx = 7), restored)
    }

    @Test
    fun `restore falls back to null when stored row is missing`() = runBlocking {
        val (store, _) = store()
        assertNull(store.restore(scope(), itemIds = listOf("a1")))
    }

    @Test
    fun `restore falls back to null on corrupt row instead of crashing`() = runBlocking {
        val rows =
            mutableMapOf(
                scope().key to
                    ListPosition(
                        scopeKey = scope().key,
                        anchorArticleId = "a1",
                        anchorItemIndex = -5,
                        anchorOffsetPx = 0,
                        updatedAt = NOW,
                    )
            )
        val (store, _) = store(rows)
        assertNull(store.restore(scope(), itemIds = listOf("a1", "a2")))
    }

    @Test
    fun `restore falls back when dao throws`() = runBlocking {
        val throwingDao =
            object : PositionDao {
                override suspend fun queryListPosition(scopeKey: String): ListPosition =
                    throw IllegalStateException("corrupt row decoding")

                override suspend fun upsertListPosition(position: ListPosition) = Unit

                override suspend fun pruneListPositions(before: Long) = Unit

                override suspend fun queryReadingPosition(articleId: String): ReadingPosition? = null

                override suspend fun upsertReadingPosition(position: ReadingPosition) = Unit
            }
        val store = PositionStore(throwingDao) { NOW }
        assertNull(store.restore(scope(), itemIds = listOf("a1")))
    }

    @Test
    fun `prune keeps 30 day boundary rows and removes older ones`() = runBlocking {
        val rows = mutableMapOf<String, ListPosition>()
        val (store, _) = store(rows, now = NOW)
        val cutoff = NOW - PositionStore.RETENTION_MS

        rows["old"] =
            ListPosition("old", anchorArticleId = null, anchorItemIndex = 0, anchorOffsetPx = 0, updatedAt = cutoff - 1)
        rows["boundary"] =
            ListPosition("boundary", anchorArticleId = null, anchorItemIndex = 0, anchorOffsetPx = 0, updatedAt = cutoff)
        rows["fresh"] =
            ListPosition("fresh", anchorArticleId = null, anchorItemIndex = 0, anchorOffsetPx = 0, updatedAt = cutoff + 1)

        store.prune()
        assertEquals(setOf("boundary", "fresh"), rows.keys)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
