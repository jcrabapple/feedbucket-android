package me.ash.reader.domain.model.article

import androidx.paging.PagingSource
import androidx.paging.PagingState

/**
 * S25a (amended, R8) — a list GENERATION: the Paging snapshot of one scope,
 * anchored at scope entry or at the last refresh/sync roll.
 *
 * Contract (both halves test-pinned):
 *  1. Read-state writes (isUnread/isStarred/isUpdated/readAt) of articles already
 *     in the current generation update those items in place and NEVER remove,
 *     reorder or invalidate the generation — the immutable [rows] cannot change,
 *     and [GenerationPagingSource] does not observe DB invalidation, so a
 *     marked-read article remains visible (badged read by the diff overlay)
 *     until the user changes scope, refreshes, or restarts.
 *  2. Sync completion or explicit refresh ROLLS a new generation
 *     ([ListGeneration.rolledAt] advances), which MAY legitimately include newly
 *     arrived articles — sticky membership never blocks incoming content.
 */
data class ListGeneration(
    val rows: List<NeighborRow>,
    val rolledAt: Long,
) {
    val size: Int get() = rows.size

    val ids: List<String> by lazy { rows.map { it.articleId } }

    fun indexOf(articleId: String): Int = rows.indexOfFirst { it.articleId == articleId }

    /** S25a — prev/next over the CURRENT generation with the S25a position fallback. */
    fun neighbors(articleId: String, ascending: Boolean = false): Neighbors {
        val row = rows.find { it.articleId == articleId }
        return NeighborResolver.neighbors(
            rows = rows,
            currentId = articleId,
            currentSortKey = row?.sortKey,
            currentRowid = row?.rowid,
            ascending = ascending,
        )
    }
}

/**
 * S25a — a [PagingSource] over one immutable [ListGeneration]. Because the
 * generation is fixed at roll time, read-state DB writes cannot remove, reorder
 * or invalidate loaded pages; item badges are updated in place by the diff
 * overlay. Only a new [ListGeneration] (new source instance) can change what
 * the pager shows.
 */
class GenerationPagingSource(
    private val generation: ListGeneration,
    private val loader: suspend (List<String>) -> List<ArticleWithFeed>,
) : PagingSource<Int, ArticleWithFeed>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ArticleWithFeed> {
        return try {
            val position = params.key ?: 0
            val total = generation.rows.size
            if (position >= total) {
                return LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)
            }
            val end = minOf(position + params.loadSize, total)
            val pageIds = generation.ids.subList(position, end)
            val byId = loader(pageIds).associateBy { it.article.id }
            // IN() row order is undefined — restore generation order explicitly.
            val items = pageIds.mapNotNull { byId[it] }
            LoadResult.Page(
                data = items,
                prevKey = if (position == 0) null else maxOf(0, position - params.loadSize),
                nextKey = if (end >= total) null else end,
            )
        } catch (t: Throwable) {
            LoadResult.Error(t)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, ArticleWithFeed>): Int? =
        state.anchorPosition?.let { maxOf(0, it - state.config.initialLoadSize / 2) }
}
