package me.ash.reader.domain.service

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.ash.reader.domain.data.SyncLogger
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.extract.ExtractPolicy
import me.ash.reader.domain.model.extract.MedianBodyLength
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.FeedWithArticle
import me.ash.reader.domain.model.query.QueryMatcher
import me.ash.reader.domain.model.rule.ActionRule
import me.ash.reader.domain.repository.ActionRuleDao
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.infrastructure.android.NotificationHelper
import me.ash.reader.infrastructure.di.DefaultDispatcher
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.rss.RssHelper
import timber.log.Timber

private const val TAG = "LocalRssService"

class LocalRssService
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val articleDao: ArticleDao,
    private val feedDao: FeedDao,
    private val rssHelper: RssHelper,
    private val notificationHelper: NotificationHelper,
    private val groupDao: GroupDao,
    private val actionRuleDao: ActionRuleDao,
    private val stateApplyLedger: StateApplyLedger,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    private val workManager: WorkManager,
    private val accountService: AccountService,
    private val syncLogger: SyncLogger,
    private val downloadService: DownloadService,
) :
    AbstractRssRepository(
        articleDao,
        groupDao,
        feedDao,
        workManager,
        rssHelper,
        notificationHelper,
        ioDispatcher,
        defaultDispatcher,
        accountService,
        actionRuleDao,
        stateApplyLedger,
        downloadService,
    ) {

    override suspend fun sync(
        accountId: Int,
        feedId: String?,
        groupId: String?
    ): ListenableWorker.Result = supervisorScope {
        return@supervisorScope runCatching {
            val preTime = System.currentTimeMillis()
            val preDate = Date(preTime)
            val currentAccount = accountService.getAccountById(accountId)!!
            require(currentAccount.type.id == AccountType.Local.id) {
                "Account type is invalid"
            }
            val semaphore = Semaphore(16)

            val feedsToSync =
                when {
                    feedId != null -> listOfNotNull(feedDao.queryById(feedId))
                    groupId != null -> feedDao.queryByGroupId(accountId, groupId)
                    else -> feedDao.queryAll(accountId)
                }

            // S17a — load the standard rules once per sync run; scope is
            // resolved per feed below. Mute-type rules NEVER apply at
            // ingest (R1): they are pure query-time filters (S15).
            val standardRules = actionRuleDao.queryByType(ActionRule.TYPE_STANDARD)
            val accountGroups = groupDao.queryAll(accountId)
            val scopedFeeds =
                feedDao.queryAll(accountId).map {
                    QueryMatcher.ScopedFeed(id = it.id, groupId = it.groupId, tags = it.tags)
                }

            feedsToSync
                .mapIndexed { _, currentFeed ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val archivedArticles =
                                feedDao
                                    .queryArchivedArticles(currentFeed.id)
                                    .map { it.link }
                                    .toSet()
                            val fetchedFeed = syncFeed(currentFeed, preDate)
                            val fetchedArticles =
                                fetchedFeed.articles.filterNot {
                                    archivedArticles.contains(it.link)
                                }

                            val newArticles =
                                // S20a/S17a — the full web store_entries
                                // state machine in ONE per-feed transaction:
                                // new articles insert with rule effects;
                                // identity matches run the S20a update path
                                // (snapshot gate, updated queue, S17a update
                                // effects). S13 scope resolution happens
                                // against this account's feeds/groups.
                                articleDao.storeEntries(
                                    articles = fetchedArticles,
                                    feed = currentFeed,
                                    feedTags =
                                        currentFeed.tags.split(',').filter { it.isNotEmpty() },
                                    groupName =
                                        accountGroups.firstOrNull { it.id == currentFeed.groupId }?.name,
                                    rules =
                                        standardRules.filter { rule ->
                                            currentFeed.id in
                                                QueryMatcher.resolveScope(
                                                    allFeeds = rule.allFeeds,
                                                    feedIds = rule.feedIds.split(',').filter { it.isNotEmpty() },
                                                    groupIds = rule.groupIds.split(',').filter { it.isNotEmpty() },
                                                    feeds = scopedFeeds,
                                                    groups = accountGroups,
                                                )
                                        },
                                    now = preDate,
                                )
                            // E7 — recompute this feed's median body-length
                            // estimate immediately AFTER its storeEntries
                            // transaction (same per-feed block; a crash
                            // between the two is harmless — the value is an
                            // estimate, recomputed every sync).
                            feedDao.updateMedianBodyLength(
                                currentFeed.id,
                                MedianBodyLength.recompute(
                                    articleDao.queryRecentRawDescriptionsForMedian(
                                        currentFeed.id,
                                        ExtractPolicy.MEDIAN_SAMPLE_CAP,
                                    ),
                                ),
                            )
                            if (currentFeed.isNotification && newArticles.isNotEmpty()) {
                                notificationHelper.notify(
                                    fetchedFeed.copy(articles = newArticles, feed = currentFeed)
                                )
                            }
                        }
                    }
                }
                .awaitAll()

            Timber.tag("RlOG").i("onCompletion: ${System.currentTimeMillis() - preTime}")
            accountService.update(currentAccount.copy(updateAt = Date()))
            ListenableWorker.Result.success()
        }
            .onFailure { syncLogger.log(it) }
            .getOrNull() ?: ListenableWorker.Result.retry()
    }

    private suspend fun syncFeed(feed: Feed, preDate: Date = Date()): FeedWithArticle {
        val articles = rssHelper.queryRssXml(feed, "", preDate)
        if (feed.icon == null) {
            val iconLink = rssHelper.queryRssIconLink(feed.url)
            if (iconLink != null) {
                rssHelper.saveRssIcon(feedDao, feed, iconLink)
            }
        }
        return FeedWithArticle(
            feed = feed.copy(isNotification = feed.isNotification && articles.isNotEmpty()),
            articles = articles,
        )
    }
}
