package me.ash.reader.domain.service

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.model.group.GroupWithFeed
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.infrastructure.rss.OPMLDataSource
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Review lane 3 defect 5 — [OpmlService.saveToString] must run its DAO
 * reads + OpmlWriter serialization on the injected IO dispatcher, never on
 * the caller's thread (MigrationViewModel.exportOpml() calls it from
 * viewModelScope, i.e. Main).
 */
class OpmlServiceTest {

    private val ioExecutor =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "opml-test-io") }
    private val ioDispatcher = ioExecutor.asCoroutineDispatcher()

    @After
    fun tearDown() {
        ioExecutor.shutdown()
    }

    @Test
    fun saveToStringRunsOnIoDispatcherNotCallerThread() {
        val groupDao = mock<GroupDao>()
        val feedDao = mock<FeedDao>()
        val accountService = mock<AccountService>()
        val rssService = mock<RssService>()
        val opmlDataSource = mock<OPMLDataSource>()

        val group = Group(id = "1\$default", name = "Default", accountId = 1)
        val feed = Feed(
            id = "1\$feed",
            name = "Test Feed",
            url = "https://example.com/feed.xml",
            groupId = group.id,
            accountId = 1,
            siteUrl = "https://example.com",
            tags = "tech",
        )
        val daoThread = AtomicReference<String>()
        runBlocking {
            whenever(groupDao.queryById(any())).thenReturn(group)
            whenever(groupDao.queryAllGroupWithFeed(1)).thenAnswer {
                daoThread.set(Thread.currentThread().name)
                listOf(GroupWithFeed(group, mutableListOf(feed)))
            }
        }
        whenever(accountService.getCurrentAccount())
            .thenReturn(Account(name = "Test Account", type = AccountType(1)))

        val service =
            OpmlService(groupDao, feedDao, accountService, rssService, opmlDataSource, ioDispatcher)

        val callerThread = Thread.currentThread().name
        val opml = runBlocking { service.saveToString(accountId = 1, attachInfo = false) }

        // The DAO read + serialization ran on the IO dispatcher thread,
        // NOT on the caller's thread (kotlinx appends " @coroutine#N" to
        // the worker thread name).
        assertTrue(
            "expected the opml-test-io worker, got: ${daoThread.get()}",
            daoThread.get()?.startsWith("opml-test-io") == true,
        )
        assertNotEquals(callerThread, daoThread.get())
        // The export itself is intact (S33 shape).
        assertTrue(opml.contains("https://example.com/feed.xml"))
        assertTrue(opml.contains("Test Feed"))
        assertTrue(opml.contains("category=\"tech\""))
    }
}
