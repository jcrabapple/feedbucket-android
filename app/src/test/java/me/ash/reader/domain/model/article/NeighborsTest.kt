package me.ash.reader.domain.model.article

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S25a — prev/next resolution over the current scope ordering with the position
 * fallback ported from web `db.py:678 neighbors` (row-value comparison): when the
 * current article no longer satisfies the scope predicate (e.g. just marked read in
 * an unread scope), navigation still resolves by its sort position among the
 * remaining scoped rows.
 *
 * TDD pin: scope [A,B,C]; open B (marks read); B leaves the predicate.
 *  - membership-only implementation returns (null, null)  <- RED witness
 *  - fallback returns prev=A, next=C
 */
class NeighborsTest {

    /** DESC default scope order (S15: date DESC, rowid DESC). */
    private val descRows =
        listOf(
            NeighborRow("A", sortKey = 30, rowid = 3),
            NeighborRow("B", sortKey = 20, rowid = 2),
            NeighborRow("C", sortKey = 10, rowid = 1),
        )

    @Test
    fun `membership path returns adjacent rows`() {
        val n = NeighborResolver.neighbors(descRows, currentId = "B")
        assertEquals("A", n.previous)
        assertEquals("C", n.next)
    }

    @Test
    fun `fallback finds neighbors after current left the predicate`() {
        // B was marked read: the re-queried unread scope no longer contains it.
        val remaining = descRows.filter { it.articleId != "B" }
        val n =
            NeighborResolver.neighbors(
                remaining,
                currentId = "B",
                currentSortKey = 20,
                currentRowid = 2,
            )
        assertEquals("A", n.previous)
        assertEquals("C", n.next)
    }

    @Test
    fun `membership-only stub returns null null`() {
        // RED witness: this documents the failing behavior of a membership-only
        // implementation against the fallback pin above.
        val remaining = descRows.filter { it.articleId != "B" }
        val membershipOnly =
            runCatching {
                val index = remaining.indexOfFirst { it.articleId == "B" }
                if (index == -1) Neighbors(null, null)
                else
                    Neighbors(
                        previous = remaining.getOrNull(index - 1)?.articleId,
                        next = remaining.getOrNull(index + 1)?.articleId,
                    )
            }.getOrDefault(Neighbors(null, null))
        assertEquals(Neighbors(null, null), membershipOnly)
    }

    @Test
    fun `fallback respects descending order boundaries`() {
        // Newest row marked read: prev is null (nothing newer), next = second row.
        val remaining = descRows.filter { it.articleId != "A" }
        val n =
            NeighborResolver.neighbors(
                remaining,
                currentId = "A",
                currentSortKey = 30,
                currentRowid = 3,
            )
        assertNull(n.previous)
        assertEquals("B", n.next)
    }

    @Test
    fun `fallback respects ascending scope order`() {
        val ascRows = descRows.sortedBy { it.sortKey } // C, B, A
        val remaining = ascRows.filter { it.articleId != "B" }
        val n =
            NeighborResolver.neighbors(
                remaining,
                currentId = "B",
                currentSortKey = 20,
                currentRowid = 2,
                ascending = true,
            )
        assertEquals("C", n.previous)
        assertEquals("A", n.next)
    }

    @Test
    fun `fallback breaks sort key ties by rowid`() {
        // A and B share sortKey 20; rowid decides order (DESC: B before A).
        val rows =
            listOf(
                NeighborRow("B", sortKey = 20, rowid = 2),
                NeighborRow("A", sortKey = 20, rowid = 1),
                NeighborRow("C", sortKey = 10, rowid = 0),
            )
        val remaining = rows.filter { it.articleId != "B" }
        val n =
            NeighborResolver.neighbors(
                remaining,
                currentId = "B",
                currentSortKey = 20,
                currentRowid = 2,
            )
        assertNull(n.previous)
        assertEquals("A", n.next)
    }

    @Test
    fun `edge cases return null at list ends`() {
        assertEquals(Neighbors(null, "B"), NeighborResolver.neighbors(descRows, currentId = "A"))
        assertEquals(Neighbors("B", null), NeighborResolver.neighbors(descRows, currentId = "C"))
        assertEquals(Neighbors(null, null), NeighborResolver.neighbors(emptyList(), currentId = "Z"))
    }

    @Test
    fun `unknown current without position keys returns nulls`() {
        val n = NeighborResolver.neighbors(descRows, currentId = "ZZZ")
        assertNull(n.previous)
        assertNull(n.next)
    }
}
