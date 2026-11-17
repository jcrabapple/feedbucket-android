package me.ash.reader.domain.model.article

/**
 * S25a — one row of the current scope in scope order (S15).
 *
 * @param sortKey the scope sort key (`date`, or `readAt` for the recentlyRead view).
 * @param rowid the SQLite rowid, the S15 default tiebreak (`rowid DESC`).
 */
data class NeighborRow(val articleId: String, val sortKey: Long, val rowid: Long)

data class Neighbors(val previous: String?, val next: String?)

/**
 * S25a — prev/next in the reading pager, port of web `db.py:678 neighbors`.
 *
 * The resolver mirrors the listing order exactly: [rows] must already be sorted in
 * scope order (default `sortKey DESC, rowid DESC`; pass [ascending] = true for
 * `sort:asc`).
 *
 * 1. Membership path: when the current article is in [rows], prev/next are the
 *    adjacent rows in list order.
 * 2. Position fallback: when the current article has left the scope predicate
 *    (e.g. marked read on open in an unread scope), neighbors are found by its
 *    sort position — previous = the closest row ordered before it, next = the
 *    closest row ordered after it — equivalent to the web SQL row-value
 *    comparison `(sortKey, rowid) < / > (:k, :r)` with direction flipped per sort.
 *    The same fallback is executed in SQL by `ArticleDao.queryNeighborBefore/After`
 *    for out-of-snapshot articles; both paths must agree (pinned by tests).
 */
object NeighborResolver {

    fun neighbors(
        rows: List<NeighborRow>,
        currentId: String,
        currentSortKey: Long? = null,
        currentRowid: Long? = null,
        ascending: Boolean = false,
    ): Neighbors {
        val index = rows.indexOfFirst { it.articleId == currentId }
        if (index != -1) {
            return Neighbors(
                previous = rows.getOrNull(index - 1)?.articleId,
                next = rows.getOrNull(index + 1)?.articleId,
            )
        }
        if (currentSortKey == null || currentRowid == null) return Neighbors(null, null)
        if (rows.isEmpty()) return Neighbors(null, null)

        // Insertion index: first row that orders AFTER the current article.
        val insertion =
            rows.indexOfFirst { row -> comparePosition(row, currentSortKey, currentRowid, ascending) > 0 }
        val before = if (insertion == -1) rows.size else insertion
        return Neighbors(
            previous = rows.getOrNull(before - 1)?.articleId,
            next = rows.getOrNull(before)?.articleId,
        )
    }

    /**
     * Negative when [row] orders before the current position, positive when after,
     * per scope direction. DESC (default): larger (sortKey, rowid) first.
     */
    private fun comparePosition(
        row: NeighborRow,
        currentSortKey: Long,
        currentRowid: Long,
        ascending: Boolean,
    ): Int {
        var cmp = Key(row.sortKey, row.rowid).compareTo(Key(currentSortKey, currentRowid))
        if (!ascending) cmp = -cmp
        return cmp
    }

    private data class Key(val sortKey: Long, val rowid: Long) : Comparable<Key> {
        override fun compareTo(other: Key): Int =
            compareValuesBy(this, other, { it.sortKey }, { it.rowid })
    }
}
