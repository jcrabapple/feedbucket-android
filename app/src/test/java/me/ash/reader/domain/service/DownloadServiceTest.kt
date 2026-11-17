package me.ash.reader.domain.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.download.ArticleDownload
import me.ash.reader.domain.model.download.DownloadErrorCause
import me.ash.reader.domain.model.download.DownloadStore
import me.ash.reader.domain.repository.DownloadDao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Acceptance #7 (amended, R6) — worker retry rule via
 * [TestListenableWorkerBuilder], request constraint split, and S27a startup
 * reconciliation (orphan `downloading` rows reset + single re-enqueue +
 * staging GC; corrupt `ready` dirs demoted to failed(storage)).
 */
class DownloadServiceTest {

    @get:Rule val tmp = TemporaryFolder()

    private val articleId = "1\$article-1"

    private fun testContext(): Context {
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        return context
    }

    private fun workerWith(
        context: Context,
        service: DownloadService,
        runAttemptCount: Int,
    ): ListenableWorker {
        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = DownloadWorker(appContext, workerParameters, service)
            }
        return TestListenableWorkerBuilder.from(context, DownloadWorker::class.java)
            .setWorkerFactory(factory)
            .setInputData(workDataOf(DownloadWorker.KEY_ARTICLE_ID to articleId))
            .setRunAttemptCount(runAttemptCount)
            .build()
    }

    private fun failingService(
        cause: DownloadErrorCause = DownloadErrorCause.NETWORK,
    ): DownloadService {
        val service = mock<DownloadService>()
        runBlocking {
            whenever(service.download(any())).thenThrow(DownloadService.AttemptFailed(cause))
        }
        return service
    }

    // --- S28a retry rule ----------------------------------------------------

    @Test
    fun attempt0Retries() = runBlocking {
        val result = workerWith(testContext(), failingService(), 0).startWork().get()
        assertEquals(ListenableWorker.Result.Retry::class.java, result.javaClass)
        Unit
    }

    @Test
    fun attempt1Retries() = runBlocking {
        val result = workerWith(testContext(), failingService(), 1).startWork().get()
        assertEquals(ListenableWorker.Result.Retry::class.java, result.javaClass)
        Unit
    }

    @Test
    fun attempt2Retries() = runBlocking {
        val result = workerWith(testContext(), failingService(), 2).startWork().get()
        assertEquals(ListenableWorker.Result.Retry::class.java, result.javaClass)
        Unit
    }

    @Test
    fun attempt3IsTerminalFailedNetwork() = runBlocking {
        val service = failingService()
        val result = workerWith(testContext(), service, 3).startWork().get()
        // Terminal: the row is written failed(network) and the worker
        // finishes successfully so WorkManager does not retry again.
        assertEquals(ListenableWorker.Result.Success::class.java, result.javaClass)
        verify(service).markFailed(articleId, DownloadErrorCause.NETWORK)
        Unit
    }

    @Test
    fun storageFailureIsTerminalWithoutRetry() = runBlocking {
        val service = failingService(DownloadErrorCause.STORAGE)
        val result = workerWith(testContext(), service, 0).startWork().get()
        assertEquals(ListenableWorker.Result.Success::class.java, result.javaClass)
        verify(service).markFailed(articleId, DownloadErrorCause.STORAGE)
        Unit
    }

    @Test
    fun successPathDoesNotTouchFailureStates() = runBlocking {
        val service = mock<DownloadService>()
        val result = workerWith(testContext(), service, 0).startWork().get()
        assertEquals(ListenableWorker.Result.Success::class.java, result.javaClass)
        verify(service, never()).markFailed(any(), any())
        Unit
    }

    // --- request constraint split + backoff ---------------------------------

    @Test
    fun manualRequestHasNoNetworkConstraintAndExponential30sBackoff() {
        val request = DownloadWorker.manualRequest(articleId)
        val spec = request.workSpec
        assertEquals(
            NetworkType.NOT_REQUIRED,
            spec.constraints.requiredNetworkType,
        )
        assertEquals(
            TimeUnit.SECONDS.toMillis(30),
            spec.backoffDelayDuration,
        )
        assertEquals(
            androidx.work.BackoffPolicy.EXPONENTIAL,
            spec.backoffPolicy,
        )
        assertEquals(articleId, spec.input.getString(DownloadWorker.KEY_ARTICLE_ID))
        assertTrue(request.tags.contains(DownloadWorker.articleTag(articleId)))
    }

    @Test
    fun bulkRequestRequiresUnmetered() {
        val spec = DownloadWorker.bulkRequest().workSpec
        assertEquals(
            NetworkType.UNMETERED,
            spec.constraints.requiredNetworkType,
        )
        assertEquals(
            TimeUnit.SECONDS.toMillis(30),
            spec.backoffDelayDuration,
        )
    }

    // --- S27a startup reconciliation ------------------------------------------

    private fun serviceWith(
        dao: DownloadDao,
        store: DownloadStore,
    ): DownloadService =
        DownloadService(
            downloadDao = dao,
            articleDao = mock(),
            downloadStore = store,
            workManager = mock(),
            http = mock(),
        )

    private fun downloadingRow(id: String) =
        ArticleDownload(articleId = id, state = ArticleDownload.STATE_DOWNLOADING, requestedAt = 1L)

    @Test
    fun orphanDownloadingRowResetsToPendingAndReenqueuesOnce() = runBlocking {
        val dao = mock<DownloadDao>()
        whenever(dao.queryByState(ArticleDownload.STATE_DOWNLOADING))
            .thenReturn(listOf(downloadingRow(articleId)))
        whenever(dao.queryByState(ArticleDownload.STATE_READY)).thenReturn(emptyList())
        val store = DownloadStore(tmp.newFolder("downloads"))
        // orphan staging dir exists (interrupted attempt)
        store.prepareStaging(1, articleId)
        val service = serviceWith(dao, store)

        val reenqueued = mutableListOf<String>()
        service.reconcile(hasLiveWork = { false }, reenqueue = { reenqueued.add(it) })

        verify(dao).resetForRetry(articleId)
        assertEquals(listOf(articleId), reenqueued)
        // orphan staging GC'd (nothing in flight)
        assertTrue(!store.stagingDir(1, articleId).exists())
        Unit
    }

    @Test
    fun downloadingRowWithLiveWorkIsNotReenqueuedAndStagingSurvives() = runBlocking {
        val dao = mock<DownloadDao>()
        whenever(dao.queryByState(ArticleDownload.STATE_DOWNLOADING))
            .thenReturn(listOf(downloadingRow(articleId)))
        whenever(dao.queryByState(ArticleDownload.STATE_READY)).thenReturn(emptyList())
        val store = DownloadStore(tmp.newFolder("downloads"))
        val staging = store.prepareStaging(1, articleId)
        val service = serviceWith(dao, store)

        val reenqueued = mutableListOf<String>()
        service.reconcile(hasLiveWork = { true }, reenqueue = { reenqueued.add(it) })

        verify(dao, never()).resetForRetry(any())
        assertTrue(reenqueued.isEmpty())
        assertTrue(staging.isDirectory)
        Unit
    }

    @Test
    fun readyRowWithCorruptManifestDemotedToFailedStorage() = runBlocking {
        val dao = mock<DownloadDao>()
        whenever(dao.queryByState(ArticleDownload.STATE_DOWNLOADING)).thenReturn(emptyList())
        whenever(dao.queryByState(ArticleDownload.STATE_READY))
            .thenReturn(
                listOf(ArticleDownload(articleId = articleId, state = ArticleDownload.STATE_READY, requestedAt = 1L))
            )
        val store = DownloadStore(tmp.newFolder("downloads"))
        val service = serviceWith(dao, store)

        service.reconcile(hasLiveWork = { false }, reenqueue = {})

        verify(dao).updateState(
            eq(articleId),
            eq(ArticleDownload.STATE_FAILED),
            eq(DownloadErrorCause.STORAGE.value),
            any(),
            any(),
        )
        Unit
    }
}
