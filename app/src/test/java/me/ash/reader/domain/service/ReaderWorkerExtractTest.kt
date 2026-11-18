package me.ash.reader.domain.service

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.infrastructure.net.SsrfGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * X3 task 4 — E9 ReaderWorker PREFETCH contract on the single E2 pipeline:
 *  - N articles with M per-article failures → `Result.success()` and exactly
 *    N−M `applyExtraction` writes (E9c: per-article failure NEVER retries);
 *  - all-success → success;
 *  - STRUCTURAL worker failure (the article query itself) → retry preserved.
 *
 * The worker runs a REAL [ExtractCoordinator] over the shared fake transport
 * (challenge pages are the per-article failures), so "writes" are counted on
 * the fake DAO exactly as in production.
 *
 * REDCHECK (recorded in the review handoff): restoring the legacy
 * `any { !it } → Result.retry()` aggregation turns
 * [partialFailuresYieldSuccessWithExactlyNMinusMWrites] red.
 */
class ReaderWorkerExtractTest {

    private fun testContext(): Context {
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        return context
    }

    private fun worker(
        context: Context,
        rssService: RssService,
        coordinator: ExtractCoordinator,
    ): ListenableWorker {
        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = ReaderWorker(appContext, workerParameters, rssService, coordinator)
            }
        return TestListenableWorkerBuilder.from(context, ReaderWorker::class.java)
            .setWorkerFactory(factory)
            .build()
    }

    private fun rssServiceWith(
        articles: List<Article>,
        feed: Feed?,
        queryFailure: Throwable? = null,
    ): RssService {
        val repository = mock<AbstractRssRepository>()
        val service = mock<RssService>()
        runBlocking {
            whenever(service.get()).thenReturn(repository)
            if (queryFailure != null) {
                whenever(repository.queryUnreadFullContentArticles()).thenThrow(queryFailure)
            } else {
                whenever(repository.queryUnreadFullContentArticles()).thenReturn(articles)
            }
            whenever(repository.findFeedById(any())).thenAnswer { invocation ->
                val id = invocation.getArgument<String>(0)
                if (feed != null && id == feed.id) feed else null
            }
        }
        return service
    }

    private fun prefetchArticles(count: Int): List<Article> =
        (1..count).map {
            extractTestArticle(
                id = "1\$article-$it",
                link = "http://93.184.216.34/p$it",
                title = "Article $it",
            )
        }

    private fun coordinatorWith(
        transport: FakeTransport,
        recorder: DaoRecorder,
    ): ExtractCoordinator = ExtractCoordinator(
        fetch = ExtractFetch(transport, SsrfGuard(FakeDns()), Dispatchers.Unconfined),
        articleDao = fakeArticleDao(recorder),
        ioDispatcher = Dispatchers.Unconfined,
        clock = { EXTRACT_TEST_NOW },
    )

    /** N articles, M challenge-failures → success + exactly N−M writes. */
    @Test
    fun partialFailuresYieldSuccessWithExactlyNMinusMWrites() = runBlocking {
        val n = 5
        val m = 2
        val articles = prefetchArticles(n)
        val transport = FakeTransport()
        articles.take(n - m).forEach { transport.respond(it.link, extractTestPage(title = it.title)) }
        articles.takeLast(m).forEach { transport.respond(it.link, CHALLENGE_HTML) }
        val recorder = DaoRecorder()

        val worker = worker(
            testContext(),
            rssServiceWith(articles, extractTestFeed()),
            coordinatorWith(transport, recorder),
        )
        val result = worker.startWork().get()

        assertTrue("E9c: per-article failures must not retry, got $result", result is ListenableWorker.Result.Success)
        assertEquals(n - m, recorder.writeCount)
        assertEquals(n, transport.openCount)
    }

    @Test
    fun allSuccessYieldsSuccessWithNWrites() = runBlocking {
        val n = 3
        val articles = prefetchArticles(n)
        val transport = FakeTransport()
        articles.forEach { transport.respond(it.link, extractTestPage(title = it.title)) }
        val recorder = DaoRecorder()

        val worker = worker(testContext(), rssServiceWith(articles, extractTestFeed()), coordinatorWith(transport, recorder))
        val result = worker.startWork().get()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(n, recorder.writeCount)
        assertEquals(Date(EXTRACT_TEST_NOW), recorder.lastExtractedAt)
    }

    @Test
    fun allFailuresStillYieldSuccessWithZeroWrites() = runBlocking {
        val n = 4
        val articles = prefetchArticles(n)
        val transport = FakeTransport()
        articles.forEach { transport.respond(it.link, CHALLENGE_HTML) }
        val recorder = DaoRecorder()

        val worker = worker(testContext(), rssServiceWith(articles, extractTestFeed()), coordinatorWith(transport, recorder))
        val result = worker.startWork().get()

        assertTrue("E9c: even ALL articles failing must not retry", result is ListenableWorker.Result.Success)
        assertEquals(0, recorder.writeCount)
    }

    @Test
    fun structuralQueryFailurePreservesRetry() = runBlocking {
        val recorder = DaoRecorder()
        val transport = FakeTransport()
        val worker = worker(
            testContext(),
            rssServiceWith(emptyList(), extractTestFeed(), queryFailure = RuntimeException("db unavailable")),
            coordinatorWith(transport, recorder),
        )

        val result = worker.startWork().get()

        assertTrue("structural failure must retry, got $result", result is ListenableWorker.Result.Retry)
        assertEquals(0, recorder.writeCount)
    }

    @Test
    fun missingFeedRowSkipsArticleWithoutRetry() = runBlocking {
        val articles = prefetchArticles(2)
        val transport = FakeTransport()
        val recorder = DaoRecorder()

        // findFeedById answers null for every id → per-article skip, not a
        // structural failure.
        val worker = worker(testContext(), rssServiceWith(articles, feed = null), coordinatorWith(transport, recorder))
        val result = worker.startWork().get()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(0, transport.openCount)
        assertEquals(0, recorder.writeCount)
    }
}
