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
import java.io.IOException
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.download.ArticleDownload
import me.ash.reader.domain.model.download.DownloadErrorCause
import me.ash.reader.domain.model.download.DownloadHtmlSanitizer
import me.ash.reader.domain.model.download.DownloadManifest
import me.ash.reader.domain.model.download.DownloadPolicy
import me.ash.reader.domain.model.download.DownloadStore
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.DownloadDao
import me.ash.reader.infrastructure.html.READER_TABLE_FIXTURE_HTML
import me.ash.reader.infrastructure.html.ReaderTables
import me.ash.reader.infrastructure.net.SsrfGuard
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.notNull
import org.mockito.kotlin.times
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
        articleDao: ArticleDao = mock(),
        http: OkHttpClient = mock(),
    ): DownloadService =
        DownloadService(
            downloadDao = dao,
            articleDao = articleDao,
            downloadStore = store,
            workManager = mock(),
            http = http,
            ssrfGuard = SsrfGuard(),
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

    // --- review fix lane 1: asset fetching, SSRF, caps, batch retry, chunking ---

    /** Public literal IPv4 (example.com's address): guard-allowed WITHOUT DNS. */
    private val publicImageUrl = "http://93.184.216.34/a.png"

    private fun pendingRow(attempts: Int = 0) =
        ArticleDownload(
            articleId = articleId,
            state = ArticleDownload.STATE_PENDING,
            requestedAt = 1L,
            attempts = attempts,
        )

    private fun articleWith(html: String): ArticleWithFeed {
        val article =
            Article(
                id = articleId,
                date = Date(0),
                title = "title",
                rawDescription = html,
                shortDescription = "short",
                link = "https://example.com/post",
                feedId = "1\$feed",
                accountId = 1,
            )
        return ArticleWithFeed(article = article, feed = mock())
    }

    private fun responseFor(
        request: Request,
        code: Int = 200,
        body: ResponseBody = ByteArray(0).toResponseBody(),
        location: String? = null,
    ): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("msg-$code")
            .apply { location?.let { header("Location", it) } }
            .body(body)
            .build()

    /** Mock client routing every request through [handler] (no HTTP server). */
    private fun httpClient(handler: (Request) -> Response): OkHttpClient {
        val client = mock<OkHttpClient>()
        whenever(client.newCall(any())).thenAnswer { invocation ->
            val call = mock<Call>()
            whenever(call.execute()).thenReturn(handler(invocation.getArgument(0)))
            call
        }
        return client
    }

    private fun throwingHttpClient(e: IOException): OkHttpClient {
        val client = mock<OkHttpClient>()
        whenever(client.newCall(any())).thenAnswer {
            val call = mock<Call>()
            whenever(call.execute()).thenThrow(e)
            call
        }
        return client
    }

    @Test
    fun imageDownloadLandsAssetAndBumpsProgress() = runBlocking {
        val dao = mock<DownloadDao>()
        whenever(dao.queryByArticleId(articleId)).thenReturn(pendingRow())
        val articleDao = mock<ArticleDao>()
        whenever(articleDao.queryById(articleId))
            .thenReturn(articleWith("<p>text</p><img src=\"$publicImageUrl\">"))
        val store = DownloadStore(tmp.newFolder("downloads"))
        val payload = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4, 5, 6)
        val http = httpClient { req ->
            responseFor(req, 200, payload.toResponseBody("image/png".toMediaTypeOrNull()))
        }
        val service = serviceWith(dao, store, articleDao, http)

        service.download(articleId)

        // the asset file landed with the exact bytes at the deterministic name
        val assetName = DownloadHtmlSanitizer.assetFileName(publicImageUrl)
        val assetFile = File(store.articleDir(1, articleId), "assets/$assetName")
        assertTrue("asset file must exist", assetFile.isFile)
        assertArrayEquals(payload, assetFile.readBytes())
        // manifest records the landed asset and completion
        val manifest = store.readManifest(store.articleDir(1, articleId))!!
        assertEquals(1, manifest.assets.size)
        assertEquals(publicImageUrl, manifest.assets[0].url)
        assertEquals(payload.size.toLong(), manifest.assets[0].bytes)
        assertEquals(64, manifest.assets[0].sha256.length)
        assertTrue(manifest.assetsComplete)
        assertEquals(1, manifest.expectedImageCount)
        // row progress: imageCount/imageBytes bumped, assetsComplete set
        verify(dao).bumpImageProgress(articleId, payload.size.toLong(), assetsComplete = false)
        verify(dao).setAssetsComplete(articleId, true)
        verify(dao).markReady(eq(articleId), any(), eq(1), any())
        Unit
    }

    @Test
    fun downloadWritesSelfContainedOfflinePageWithResponsiveTables() = runBlocking {
        // Card t_1ed5c518: the committed index.html is a FULL self-contained
        // document — shared responsive table CSS inline, data-label column
        // labels derived in Kotlin (the hardened offline WebView runs no
        // JavaScript), scroll wrappers in place, zero network references.
        val dao = mock<DownloadDao>()
        whenever(dao.queryByArticleId(articleId)).thenReturn(pendingRow())
        val articleDao = mock<ArticleDao>()
        whenever(articleDao.queryById(articleId))
            .thenReturn(articleWith(READER_TABLE_FIXTURE_HTML))
        val store = DownloadStore(tmp.newFolder("downloads"))
        val service = serviceWith(dao, store, articleDao)

        service.download(articleId)

        val page = File(store.articleDir(1, articleId), "index.html").readText()
        assertTrue("offline page must be a full document", page.startsWith("<!DOCTYPE html>"))
        assertTrue("viewport meta drives the phone breakpoint", page.contains("width=device-width"))
        assertTrue("shared table CSS embedded", page.contains(ReaderTables.TABLE_CSS))
        assertTrue("labels derived without JavaScript", page.contains("data-label=\"Tier\""))
        assertTrue(
            "scroll wrapper present",
            page.contains("class=\"${ReaderTables.SCROLL_WRAPPER_CLASS}\""),
        )
        assertFalse("offline page makes zero network calls", page.contains("https://"))
        Unit
    }

    /** Lazily generated body that counts how many bytes were actually pulled. */
    private fun countingBody(total: Long, emitted: AtomicLong): ResponseBody =
        object : ResponseBody() {
            override fun contentType(): MediaType? = "image/png".toMediaTypeOrNull()

            override fun contentLength(): Long = total

            override fun source(): BufferedSource {
                val src =
                    object : Source {
                        private var remaining = total

                        override fun read(sink: Buffer, byteCount: Long): Long {
                            if (remaining <= 0L) return -1L
                            val n = minOf(byteCount, remaining)
                            sink.write(ByteArray(n.toInt()))
                            remaining -= n
                            emitted.addAndGet(n)
                            return n
                        }

                        override fun timeout(): Timeout = Timeout.NONE

                        override fun close() {}
                    }
                return src.buffer()
            }
        }

    @Test
    fun oversizeImageIsRejectedByStreamingCapWithoutFullBuffering() = runBlocking {
        val dao = mock<DownloadDao>()
        whenever(dao.queryByArticleId(articleId)).thenReturn(pendingRow())
        val articleDao = mock<ArticleDao>()
        whenever(articleDao.queryById(articleId))
            .thenReturn(articleWith("<img src=\"$publicImageUrl\">"))
        val store = DownloadStore(tmp.newFolder("downloads"))
        val totalBytes = DownloadPolicy.PER_IMAGE_MAX_BYTES + 1024 * 1024 // 6 MiB
        val emitted = AtomicLong(0)
        val http = httpClient { req -> responseFor(req, 200, countingBody(totalBytes, emitted)) }
        val service = serviceWith(dao, store, articleDao, http)

        service.download(articleId)

        // The stream was aborted at the 5 MiB per-image cap (+ one read
        // chunk): the full 6 MiB body was NEVER pulled into memory.
        assertTrue(
            "emitted=${emitted.get()} must stay near the cap",
            emitted.get() <= DownloadPolicy.PER_IMAGE_MAX_BYTES + 64 * 1024,
        )
        assertTrue(emitted.get() < totalBytes)
        // terminal per-asset skip: nothing landed, the row stays ready text-only
        val dir = store.articleDir(1, articleId)
        assertEquals(0, store.readManifest(dir)!!.assets.size)
        assertTrue(store.readManifest(dir)!!.assetsComplete)
        verify(dao, never()).bumpImageProgress(any(), any(), any())
        verify(dao).setAssetsComplete(articleId, true)
        Unit
    }

    @Test
    fun ssrfGuardRejectsLoopbackAndPrivateImageUrlsWithoutAnyRequest() = runBlocking {
        val dao = mock<DownloadDao>()
        whenever(dao.queryByArticleId(articleId)).thenReturn(pendingRow())
        val articleDao = mock<ArticleDao>()
        val blocked =
            listOf(
                "http://127.0.0.1:8080/loopback.png",
                "http://192.168.1.1/lan.png",
                "http://10.0.0.5/internal.png",
                "http://169.254.169.254/metadata.png",
                "http://[::1]/v6loop.png",
            )
        whenever(articleDao.queryById(articleId))
            .thenReturn(articleWith(blocked.joinToString("") { "<img src=\"$it\">" }))
        val store = DownloadStore(tmp.newFolder("downloads"))
        val http = mock<OkHttpClient>()
        val service = serviceWith(dao, store, articleDao, http)

        service.download(articleId)

        // every candidate rejected by the E17 guard BEFORE touching the network
        verify(http, never()).newCall(any())
        val dir = store.articleDir(1, articleId)
        assertEquals(0, store.readManifest(dir)!!.assets.size)
        assertTrue(store.readManifest(dir)!!.assetsComplete)
        verify(dao, never()).bumpImageProgress(any(), any(), any())
        Unit
    }

    @Test
    fun redirectHopIntoPrivateRangeIsSkippedWithoutFetchingIt() = runBlocking {
        val dao = mock<DownloadDao>()
        whenever(dao.queryByArticleId(articleId)).thenReturn(pendingRow())
        val articleDao = mock<ArticleDao>()
        whenever(articleDao.queryById(articleId))
            .thenReturn(articleWith("<img src=\"$publicImageUrl\">"))
        val store = DownloadStore(tmp.newFolder("downloads"))
        val http = httpClient { req ->
            responseFor(req, 302, location = "http://127.0.0.1/secret.png")
        }
        val service = serviceWith(dao, store, articleDao, http)

        service.download(articleId)

        // ONE request: the public start URL. The private hop is rejected by
        // the guard before it is ever fetched (client follows no redirects).
        verify(http, times(1)).newCall(any())
        val dir = store.articleDir(1, articleId)
        assertEquals(0, store.readManifest(dir)!!.assets.size)
        verify(dao, never()).bumpImageProgress(any(), any(), any())
        Unit
    }

    @Test
    fun batchNonTerminalFailureResetsRowToPendingKeepingAttempts() = runBlocking {
        val dao = mock<DownloadDao>()
        val pending = pendingRow(attempts = 1)
        val downloading =
            ArticleDownload(
                articleId = articleId,
                state = ArticleDownload.STATE_DOWNLOADING,
                requestedAt = 1L,
                attempts = 2,
                lastAttemptAt = 5L,
            )
        whenever(dao.queryByState(ArticleDownload.STATE_PENDING)).thenReturn(listOf(pending))
        // download() re-reads the row (pending); the batch catch re-reads it
        // (downloading, attempts bumped by markDownloading).
        whenever(dao.queryByArticleId(articleId)).thenReturn(pending, downloading)
        val articleDao = mock<ArticleDao>()
        whenever(articleDao.queryById(articleId)).thenReturn(null) // -> AttemptFailed(UNKNOWN)
        val store = DownloadStore(tmp.newFolder("downloads"))
        val service = serviceWith(dao, store, articleDao)

        service.downloadPendingBatch()

        // Non-terminal (UNKNOWN, attempts 2 < 3): back to PENDING via
        // updateState (which never touches `attempts`), NOT the
        // attempts-zeroing resetForRetry, and NOT stranded in `downloading`.
        verify(dao).updateState(
            eq(articleId),
            eq(ArticleDownload.STATE_PENDING),
            eq(DownloadErrorCause.UNKNOWN.value),
            notNull(),
            isNull(),
        )
        verify(dao, never()).resetForRetry(any())
        Unit
    }

    @Test
    fun batchTerminalFailureMarksFailed() = runBlocking {
        val dao = mock<DownloadDao>()
        val pending = pendingRow(attempts = 2)
        val downloading =
            ArticleDownload(
                articleId = articleId,
                state = ArticleDownload.STATE_DOWNLOADING,
                requestedAt = 1L,
                attempts = 3,
                lastAttemptAt = 5L,
            )
        whenever(dao.queryByState(ArticleDownload.STATE_PENDING)).thenReturn(listOf(pending))
        // download() entry, batch catch, and markFailed's READY guard read.
        whenever(dao.queryByArticleId(articleId)).thenReturn(pending, downloading, downloading)
        val articleDao = mock<ArticleDao>()
        whenever(articleDao.queryById(articleId)).thenReturn(null) // -> AttemptFailed(UNKNOWN)
        val store = DownloadStore(tmp.newFolder("downloads"))
        val service = serviceWith(dao, store, articleDao)

        service.downloadPendingBatch()

        // attempts 3 >= MAX_AUTO_ATTEMPTS -> terminal failed, never re-pended
        verify(dao).updateState(
            eq(articleId),
            eq(ArticleDownload.STATE_FAILED),
            eq(DownloadErrorCause.UNKNOWN.value),
            any(),
            any(),
        )
        verify(dao, never())
            .updateState(any(), eq(ArticleDownload.STATE_PENDING), any(), any(), any())
        Unit
    }

    @Test
    fun deleteFilesForChunksOversizedIdLists() = runBlocking {
        val dao = mock<DownloadDao>()
        val ids = (0 until 1200).map { "1\$article-$it" }
        whenever(dao.queryByArticleIds(any())).thenReturn(emptyList())
        val store = DownloadStore(tmp.newFolder("downloads"))
        val service = serviceWith(dao, store)

        service.deleteFilesFor(ids)

        // 1200 ids -> three IN queries of at most 500 bound parameters each
        val captor = argumentCaptor<List<String>>()
        verify(dao, times(3)).queryByArticleIds(captor.capture())
        val chunks = captor.allValues
        assertEquals(listOf(500, 500, 200), chunks.map { it.size })
        assertEquals(ids, chunks.flatten())
        Unit
    }

    @Test
    fun resumeAssetsPropagatesNetworkFailureForWorkerRetry() = runBlocking {
        val dao = mock<DownloadDao>()
        val articleDao = mock<ArticleDao>()
        whenever(articleDao.queryById(articleId))
            .thenReturn(articleWith("<img src=\"$publicImageUrl\">"))
        val store = DownloadStore(tmp.newFolder("downloads"))
        // committed text-only dir with one outstanding image
        val staging = store.prepareStaging(1, articleId)
        store.writeText(staging, "<p>text</p>")
        store.writeManifestAtomic(
            staging,
            DownloadManifest(assets = emptyList(), assetsComplete = false, expectedImageCount = 1),
        )
        assertTrue(store.commit(1, articleId))
        val http = throwingHttpClient(IOException("network down"))
        val service = serviceWith(dao, store, articleDao, http)

        // AttemptFailed must ESCAPE resumeAssets so the DownloadWorker's
        // STATE_READY re-entry applies the S28a retry rule.
        val failure =
            assertThrows(DownloadService.AttemptFailed::class.java) {
                runBlocking { service.resumeAssets(articleId) }
            }
        assertEquals(DownloadErrorCause.NETWORK, failure.errorCause)
        Unit
    }

    @Test
    fun markFailedNeverDemotesCommittedReadyRow() = runBlocking {
        val dao = mock<DownloadDao>()
        whenever(dao.queryByArticleId(articleId))
            .thenReturn(
                ArticleDownload(articleId = articleId, state = ArticleDownload.STATE_READY, requestedAt = 1L)
            )
        val store = DownloadStore(tmp.newFolder("downloads"))
        val service = serviceWith(dao, store)

        service.markFailed(articleId, DownloadErrorCause.NETWORK)

        // ready -> failed is not an attempt outcome: the committed text stays
        verify(dao, never()).updateState(any(), any(), any(), any(), any())
        Unit
    }
}
