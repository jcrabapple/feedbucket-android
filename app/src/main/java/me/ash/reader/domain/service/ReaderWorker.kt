package me.ash.reader.domain.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * E9 — sync-time PREFETCH for feeds flagged `isFullContent`, folded into the
 * single [ExtractCoordinator] pipeline (E2). Contract changes vs the legacy
 * disk-cache prefetch:
 *  - the skip condition is DB truth (`fullContent IS NULL`, X1's
 *    `queryUnreadFullContentArticles` filter), not cache-file existence;
 *  - per-article extraction failures are typed best-effort no-ops (E21) and
 *    NEVER produce `Result.retry()` (E9c) — one dead link no longer retries
 *    the entire prefetch chain forever. The worker returns `Result.retry()`
 *    ONLY when the worker itself fails structurally (article query, feed
 *    resolution scope);
 *  - `Semaphore(2)` prefetch concurrency is preserved (E9d).
 */
@HiltWorker
class ReaderWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val rssService: RssService,
    private val extractCoordinator: ExtractCoordinator,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val semaphore = Semaphore(2) // E9d — prefetch concurrency preserved.

        return try {
            val outcomes =
                withContext(Dispatchers.IO) {
                    val rssService = rssService.get()
                    val articleList = rssService.queryUnreadFullContentArticles()
                    // Feeds resolved once per distinct feedId (prefetch feeds
                    // are few); a missing feed row is a per-article skip, not
                    // a structural failure.
                    val feeds =
                        articleList.map { it.feedId }.distinct()
                            .associateWith { rssService.findFeedById(it) }
                    articleList.map { article ->
                        async {
                            semaphore.withPermit {
                                val feed = feeds[article.feedId]
                                if (feed == null) {
                                    Timber.tag(TAG)
                                        .w("prefetch: feed ${article.feedId} missing for ${article.id} — skip")
                                    ExtractOutcome.Skipped
                                } else {
                                    extractCoordinator.extract(article, feed, ExtractMode.PREFETCH)
                                }
                            }
                        }
                    }.awaitAll()
                }

            val failures = outcomes.count { it is ExtractOutcome.Failure }
            if (failures > 0) {
                // E9c/E21 — best-effort: failures are logged, never retried.
                Timber.tag(TAG)
                    .d("prefetch: $failures/${outcomes.size} articles failed (no retry, E9c)")
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e // WorkManager stop/cancel — normal structured cancellation.
        } catch (e: Exception) {
            // Structural worker failure only (query/scope) — retry preserved.
            Timber.tag(TAG).w(e, "prefetch: structural failure — retry")
            Result.retry()
        }
    }

    private companion object {
        const val TAG = "RLog"
    }
}
