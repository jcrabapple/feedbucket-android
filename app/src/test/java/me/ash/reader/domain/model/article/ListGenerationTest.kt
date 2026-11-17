package me.ash.reader.domain.model.article

import androidx.paging.PagingSource
import java.util.Date
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.feed.Feed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S25a (amended, R8) — the list-GENERATION contract, both halves:
 *
 *  1. A read-state write (mark-read) on an article already in the generation
 *     updates the loaded item IN PLACE and never removes, reorders or
 *     invalidates the generation: paging the same generation after the write
 *     returns the same ids in the same order (badge updated by the loader),
 *     and the row count is unchanged.
 *  2. Rolling a new generation (refresh/sync) MAY include newly arrived
 *     articles; the new source pages them.
 */
class ListGenerationTest {

    private fun article(id: String, unread: Boolean = true) =
        Article(
            id = id,
            date = Date(id.removePrefix("a").toLong() * 1000),
            title = "t$id",
            rawDescription = "",
            shortDescription = "",
            link = "https://example.com/$id",
            feedId = "f1",
            accountId = 1,
            isUnread = unread,
        )

    private fun feed() =
        Feed(id = "f1", name = "feed", url = "https://example.com/feed", groupId = "g1", accountId = 1)

    private fun withFeed(id: String, unread: Boolean = true) =
        ArticleWithFeed(article(id, unread), feed())

    /** Generation [A,B,C] in unread scope order (date DESC). */
    private fun generationOf(vararg ids: String): ListGeneration =
        ListGeneration(
            rows =
                ids.mapIndexed { i, id ->
                    NeighborRow(id, sortKey = (100 - i).toLong(), rowid = (100 - i).toLong())
                },
            rolledAt = 1000L,
        )

    @Test
    fun `generation pages in scope order regardless of loader row order`() = runBlocking {
        val gen = generationOf("a1", "a2", "a3", "a4", "a5", "a6", "a7")
        // Loader returns rows scrambled, like an IN() query does.
        val source =
            GenerationPagingSource(gen) { ids ->
                ids.reversed().map { withFeed(it) }
            }

        val first = source.load(PagingSource.LoadParams.Refresh(null, 3, false))
        val page1 = (first as PagingSource.LoadResult.Page).data.map { it.article.id }
        assertEquals(listOf("a1", "a2", "a3"), page1)
        assertNull(first.prevKey)
        assertEquals(3, first.nextKey)

        val second = source.load(PagingSource.LoadParams.Append(3, 3, false))
        val page2 = (second as PagingSource.LoadResult.Page).data.map { it.article.id }
        assertEquals(listOf("a4", "a5", "a6"), page2)
        assertEquals(6, second.nextKey)

        val third = source.load(PagingSource.LoadParams.Append(6, 3, false))
        val page3 = (third as PagingSource.LoadResult.Page).data.map { it.article.id }
        assertEquals(listOf("a7"), page3)
        assertNull(third.nextKey)
    }

    @Test
    fun `half 1 - read-state write keeps row visible in place with updated badge`() = runBlocking {
        val gen = generationOf("a1", "a2", "a3")
        // Simulate the DB commit of mark-read on a2: the loader now reports the
        // updated flag, but the generation (fixed at roll time) is untouched.
        val loader = LoaderFlagSwitch { id, unread -> withFeed(id, unread) }
        val source = GenerationPagingSource(gen, loader::load)

        val before = source.load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page
        assertEquals(listOf("a1", "a2", "a3"), before.data.map { it.article.id })
        assertTrue(before.data[1].article.isUnread)

        loader.readIds += "a2" // S21a write landed in the DB; the generation does NOT roll.

        val after = source.load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page
        assertEquals(listOf("a1", "a2", "a3"), after.data.map { it.article.id })
        assertEquals(3, after.data.size) // row stayed visible, no removal
        assertFalse(after.data[1].article.isUnread) // badge updated in place
    }

    @Test
    fun `half 2 - rolled generation includes newly synced articles`() = runBlocking {
        val gen1 = generationOf("a1", "a2")
        val source1 = GenerationPagingSource(gen1) { ids -> ids.map { withFeed(it) } }
        val page1 = source1.load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page
        assertEquals(listOf("a1", "a2"), page1.data.map { it.article.id })

        // Sync completed: refresh rolled a NEW generation that legitimately
        // includes the newly arrived a3 (never blocked by sticky membership).
        val gen2 = generationOf("a1", "a2", "a3")
        assertTrue(gen2.rolledAt >= gen1.rolledAt)
        val source2 = GenerationPagingSource(gen2) { ids -> ids.map { withFeed(it) } }
        val page2 = source2.load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page
        assertEquals(listOf("a1", "a2", "a3"), page2.data.map { it.article.id })
    }

    @Test
    fun `generation neighbors survive the current article leaving the predicate`() {
        val gen = generationOf("a1", "a2", "a3") // a2 opened -> marked read
        val n = gen.neighbors("a2")
        assertEquals("a1", n.previous)
        assertEquals("a3", n.next)
    }

    /** Loader whose returned read flags can be flipped to simulate DB writes. */
    private class LoaderFlagSwitch(
        val factory: (String, Boolean) -> ArticleWithFeed,
    ) {
        val readIds = mutableSetOf<String>()

        suspend fun load(requested: List<String>): List<ArticleWithFeed> =
            requested.map { id -> factory(id, id !in readIds) }
    }
}
