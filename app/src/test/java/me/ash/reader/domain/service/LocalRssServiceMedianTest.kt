package me.ash.reader.domain.service

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.data.SyncLogger
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.extract.ExtractPolicy
import me.ash.reader.domain.model.extract.MedianBodyLength
import me.ash.reader.domain.model.rule.ActionRule
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.domain.repository.ActionRuleDao
import me.ash.reader.infrastructure.android.NotificationHelper
import me.ash.reader.infrastructure.rss.RssHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * X3 task 5 — the E7 median hook in `LocalRssService.sync`, pinned with a
 * fake-DAO sync run (the repo has no pre-existing LocalRssService sync test
 * seam; this is the fake-DAO seam the card's task 5 calls for).
 *
 * Contract: immediately AFTER the feed's `storeEntries` transaction, sync
 * samples via `queryRecentRawDescriptionsForMedian(feedId, 100)` and writes
 * `updateMedianBodyLength(feedId, MedianBodyLength.recompute(samples))` —
 * the E7 value from the single recompute implementation. Invocation ORDER
 * (storeEntries → sample query → median write) is asserted, not assumed.
 */
class LocalRssServiceMedianTest {

    private val feedId = "1\$feed-1"

    private val samples = listOf(
        "<p>${"a".repeat(100)}</p>",
        "<p>${"b".repeat(200)}</p>",
        "<p>${"c".repeat(300)}</p>",
    )

    @Test
    fun syncRecomputesMedianRightAfterStoreEntries() = runBlocking {
        val events = mutableListOf<String>()

        val context = mock<Context>()
        val articleDao = mock<ArticleDao>()
        val feedDao = mock<FeedDao>()
        val rssHelper = mock<RssHelper>()
        val notificationHelper = mock<NotificationHelper>()
        val groupDao = mock<GroupDao>()
        val actionRuleDao = mock<ActionRuleDao>()
        val stateApplyLedger = mock<StateApplyLedger>()
        val workManager = mock<WorkManager>()
        val accountService = mock<AccountService>()
        val syncLogger = mock<SyncLogger>()
        val downloadService = mock<DownloadService>()

        val feed = extractTestFeed(id = feedId).copy(icon = "icon-bytes")
        val article = extractTestArticle(feedId = feedId)

        whenever(accountService.getAccountById(1))
            .thenReturn(Account(id = 1, name = "Local", type = AccountType.Local))
        whenever(feedDao.queryAll(1)).thenReturn(listOf(feed))
        whenever(groupDao.queryAll(1)).thenReturn(emptyList())
        whenever(actionRuleDao.queryByType(ActionRule.TYPE_STANDARD)).thenReturn(emptyList())
        whenever(feedDao.queryArchivedArticles(feedId)).thenReturn(emptyList())
        whenever(rssHelper.queryRssXml(any(), anyOrNull(), any())).thenReturn(listOf(article))
        whenever(articleDao.storeEntries(any(), any(), any(), anyOrNull(), any(), any())).thenAnswer {
            events += "storeEntries"
            emptyList<me.ash.reader.domain.model.article.Article>()
        }
        whenever(articleDao.queryRecentRawDescriptionsForMedian(any(), any())).thenAnswer { invocation ->
            assertEquals(feedId, invocation.getArgument<String>(0))
            assertEquals(ExtractPolicy.MEDIAN_SAMPLE_CAP, invocation.getArgument<Int>(1).toInt())
            events += "sampleQuery"
            samples
        }
        whenever(feedDao.updateMedianBodyLength(any(), any())).thenAnswer { invocation ->
            events += "updateMedian:${invocation.getArgument<String>(0)}:${invocation.getArgument<Int>(1)}"
            Unit
        }

        val service = LocalRssService(
            context = context,
            articleDao = articleDao,
            feedDao = feedDao,
            rssHelper = rssHelper,
            notificationHelper = notificationHelper,
            groupDao = groupDao,
            actionRuleDao = actionRuleDao,
            stateApplyLedger = stateApplyLedger,
            ioDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined,
            workManager = workManager,
            accountService = accountService,
            syncLogger = syncLogger,
            downloadService = downloadService,
        )

        val result = service.sync(accountId = 1, feedId = null, groupId = null)

        assertTrue("sync should succeed, got $result", result is ListenableWorker.Result.Success)
        // E7 — the median write happens immediately AFTER storeEntries, with
        // the value computed by the single recompute implementation from the
        // DAO sample query.
        val expectedMedian = MedianBodyLength.recompute(samples)
        assertTrue("expected a non-zero fixture median", expectedMedian > 0)
        assertEquals(
            listOf("storeEntries", "sampleQuery", "updateMedian:$feedId:$expectedMedian"),
            events,
        )
    }

    @Test
    fun syncWithEmptySampleQueryWritesZeroMedian() = runBlocking {
        // E7 degradation: no non-empty rawDescription rows → median 0
        // ("unknown until the next recompute", safe via the E6 floor).
        val context = mock<Context>()
        val articleDao = mock<ArticleDao>()
        val feedDao = mock<FeedDao>()
        val rssHelper = mock<RssHelper>()
        val notificationHelper = mock<NotificationHelper>()
        val groupDao = mock<GroupDao>()
        val actionRuleDao = mock<ActionRuleDao>()
        val stateApplyLedger = mock<StateApplyLedger>()
        val workManager = mock<WorkManager>()
        val accountService = mock<AccountService>()
        val syncLogger = mock<SyncLogger>()
        val downloadService = mock<DownloadService>()

        val feed = extractTestFeed(id = feedId).copy(icon = "icon-bytes")
        val captured = mutableListOf<Pair<String, Int>>()

        whenever(accountService.getAccountById(1))
            .thenReturn(Account(id = 1, name = "Local", type = AccountType.Local))
        whenever(feedDao.queryAll(1)).thenReturn(listOf(feed))
        whenever(groupDao.queryAll(1)).thenReturn(emptyList())
        whenever(actionRuleDao.queryByType(ActionRule.TYPE_STANDARD)).thenReturn(emptyList())
        whenever(feedDao.queryArchivedArticles(feedId)).thenReturn(emptyList())
        whenever(rssHelper.queryRssXml(any(), anyOrNull(), any())).thenReturn(emptyList())
        whenever(articleDao.storeEntries(any(), any(), any(), anyOrNull(), any(), any()))
            .thenReturn(emptyList())
        whenever(articleDao.queryRecentRawDescriptionsForMedian(any(), any())).thenReturn(emptyList())
        whenever(feedDao.updateMedianBodyLength(any(), any())).thenAnswer { invocation ->
            captured += invocation.getArgument<String>(0) to invocation.getArgument<Int>(1)
            Unit
        }

        val service = LocalRssService(
            context = context,
            articleDao = articleDao,
            feedDao = feedDao,
            rssHelper = rssHelper,
            notificationHelper = notificationHelper,
            groupDao = groupDao,
            actionRuleDao = actionRuleDao,
            stateApplyLedger = stateApplyLedger,
            ioDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined,
            workManager = workManager,
            accountService = accountService,
            syncLogger = syncLogger,
            downloadService = downloadService,
        )

        val result = service.sync(accountId = 1, feedId = null, groupId = null)

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(listOf(feedId to 0), captured)
    }
}
