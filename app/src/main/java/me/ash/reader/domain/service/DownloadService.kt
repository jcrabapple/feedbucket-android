package me.ash.reader.domain.service

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.download.ArticleDownload
import me.ash.reader.domain.model.download.DownloadAsset
import me.ash.reader.domain.model.download.DownloadErrorCause
import me.ash.reader.domain.model.download.DownloadHtmlSanitizer
import me.ash.reader.domain.model.download.DownloadManifest
import me.ash.reader.domain.model.download.DownloadPolicy
import me.ash.reader.domain.model.download.DownloadStore
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.DownloadDao
import me.ash.reader.infrastructure.di.DownloadHttpClient
import me.ash.reader.infrastructure.net.RedirectWalker
import me.ash.reader.infrastructure.net.SsrfGuard
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource
import timber.log.Timber

/**
 * S27a/S28/S28a/S29 — durable download orchestration: the state machine, the
 * staging/atomic-rename/manifest commit contract, image fetching under caps,
 * enqueue (manual = NO constraint, bulk/chained = UNMETERED), removal and
 * startup orphan recovery.
 *
 * Failure honesty (D6): a failed download never claims success and never
 * corrupts existing content — all writes are staged and moved atomically.
 */
@Singleton
class DownloadService
@Inject
constructor(
    private val downloadDao: DownloadDao,
    private val articleDao: ArticleDao,
    private val downloadStore: DownloadStore,
    private val workManager: WorkManager,
    @DownloadHttpClient private val http: OkHttpClient,
    private val ssrfGuard: SsrfGuard,
) {

    /** Thrown on any attempt-level failure; [errorCause] feeds the retry rule. */
    class AttemptFailed(val errorCause: DownloadErrorCause) :
        RuntimeException("download attempt failed: ${errorCause.value}")

    // --- enqueue --------------------------------------------------------------

    /** Manual single-article download: NO network constraint (R6). */
    suspend fun enqueueManual(articleId: String) {
        val now = System.currentTimeMillis()
        val existing = downloadDao.queryByArticleId(articleId)
        downloadDao.upsert(
            ArticleDownload(
                articleId = articleId,
                state = ArticleDownload.STATE_PENDING,
                requestedAt = existing?.requestedAt ?: now,
            )
        )
        workManager.enqueueUniqueWork(
            DownloadWorker.uniqueName(articleId),
            ExistingWorkPolicy.REPLACE,
            DownloadWorker.manualRequest(articleId),
        )
    }

    /**
     * Bulk download (S28 bulk cap 200 per invocation; the UI states the cap).
     * Rows that already exist keep their state; one UNMETERED bulk worker
     * drains the pending batch.
     */
    suspend fun enqueueBulk(articleIds: List<String>) {
        val now = System.currentTimeMillis()
        for (articleId in DownloadPolicy.truncateBulk(articleIds)) {
            if (downloadDao.queryByArticleId(articleId) == null) {
                downloadDao.upsert(
                    ArticleDownload(articleId = articleId, state = ArticleDownload.STATE_PENDING, requestedAt = now)
                )
            }
        }
        workManager.enqueueUniqueWork(
            DownloadWorker.BULK_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            DownloadWorker.bulkRequest(),
        )
    }

    // --- one download ------------------------------------------------------------

    /**
     * Runs one download attempt for [articleId] (idempotent — safe to re-run
     * after a retry or process death). Throws [AttemptFailed] with the
     * retryability-relevant cause; the worker applies the S28a retry rule.
     *
     * Text readiness (S27a): sanitized self-contained HTML + manifest are
     * staged; the manifest is written LAST; the commit is a single atomic
     * directory rename; ONLY after the rename does the row flip to `ready`.
     * Images land afterwards with atomic manifest rewrites.
     */
    suspend fun download(articleId: String): Unit = withContext(Dispatchers.IO) {
        val row =
            downloadDao.queryByArticleId(articleId)
                ?: throw AttemptFailed(DownloadErrorCause.UNKNOWN)
        when (row.state) {
            // Re-entry after text commit: resume the missing asset set.
            ArticleDownload.STATE_READY -> {
                resumeAssets(articleId)
                return@withContext
            }
            ArticleDownload.STATE_DOWNLOADING -> Unit // retry within the same attempt window
            else -> {
                if (row.state == ArticleDownload.STATE_FAILED) {
                    downloadDao.resetForRetry(articleId)
                }
                downloadDao.markDownloading(articleId, System.currentTimeMillis())
            }
        }

        val articleWithFeed =
            articleDao.queryById(articleId) ?: throw AttemptFailed(DownloadErrorCause.UNKNOWN)
        val article = articleWithFeed.article
        val accountId = article.accountId

        // S28 global cap: 100% -> failed(storage), never silent eviction.
        if (DownloadPolicy.globalStatus(downloadStore.usageBytes()) ==
            DownloadPolicy.GlobalStatus.EXCEEDED
        ) {
            markFailed(articleId, DownloadErrorCause.STORAGE)
            throw AttemptFailed(DownloadErrorCause.STORAGE)
        }

        // Text: sanitized, self-contained, img srcs pointed at deterministic
        // local asset paths (fetch happens after the text commit).
        val body = article.fullContent ?: article.rawDescription
        val (rewrittenHtml, candidates) =
            DownloadHtmlSanitizer.rewriteImages(body) { url ->
                "assets/${DownloadHtmlSanitizer.assetFileName(url)}"
            }
        if (!DownloadPolicy.textAccepted(rewrittenHtml.toByteArray(Charsets.UTF_8).size.toLong())) {
            markFailed(articleId, DownloadErrorCause.OVERSIZE)
            throw AttemptFailed(DownloadErrorCause.OVERSIZE)
        }

        // Stage: index.html first, manifest LAST, then the single atomic rename.
        val staging = downloadStore.prepareStaging(accountId, articleId)
        downloadStore.writeText(staging, rewrittenHtml)
        downloadStore.writeManifestAtomic(
            staging,
            DownloadManifest(
                schemaVersion = DownloadManifest.CURRENT_SCHEMA_VERSION,
                textComplete = true,
                assets = emptyList(),
                assetsComplete = candidates.isEmpty(),
                expectedImageCount = candidates.size,
            ),
        )
        if (!downloadStore.commit(accountId, articleId)) {
            markFailed(articleId, DownloadErrorCause.STORAGE)
            throw AttemptFailed(DownloadErrorCause.STORAGE)
        }
        downloadDao.markReady(
            articleId,
            textBytes = rewrittenHtml.toByteArray(Charsets.UTF_8).size.toLong(),
            expectedImageCount = candidates.size,
            now = System.currentTimeMillis(),
        )
        downloadDao.setAssetsComplete(articleId, candidates.isEmpty())

        fetchAssets(accountId, article, candidates)
    }

    /**
     * S27a asset phase. Per-asset HTTP errors and cap skips are TERMINAL for
     * that asset (the row stays `ready`, visibly incomplete); an IOException
     * (DNS/TLS/timeout — e.g. network lost mid-download) fails the whole
     * attempt so WorkManager retries per S28a. Partial asset files live in
     * the committed dir and are overwritten on the next attempt.
     */
    private suspend fun fetchAssets(
        accountId: Int,
        article: Article,
        candidates: List<String>,
    ) {
        val articleId = article.id
        for (url in currentManifest(accountId, articleId)?.let { landed ->
            candidates - landed.assets.map { it.url }.toSet()
        } ?: candidates) {
            val manifest = currentManifest(accountId, articleId) ?: return
            // Budget pre-check ONLY: stop fetching once the article's image
            // count/byte budget is exhausted. Per-image size is enforced in
            // fetchOneAsset against the ACTUAL downloaded bytes — the old
            // `imageFits(imageBytes = Long.MAX_VALUE)` sentinel here failed
            // unconditionally, so no image was ever fetched (dead code).
            if (manifest.assets.size >= DownloadPolicy.IMAGES_MAX_COUNT ||
                manifest.assets.sumOf { it.bytes } >= DownloadPolicy.IMAGES_MAX_BYTES
            ) {
                continue // cap-exceeded assets are terminally skipped
            }
            runCatching { fetchOneAsset(accountId, articleId, url) }
                .onFailure { throwable ->
                    when (throwable) {
                        is AssetSkipped -> Unit // terminal per-asset skip
                        is CancellationException -> throw throwable
                        else -> throw (throwable as? AttemptFailed) ?: AttemptFailed(DownloadErrorCause.NETWORK)
                    }
                }
        }
        finalizeAssets(accountId, articleId)
    }

    /** Sealed per-asset terminal skip (HTTP error / cap / challenge / SSRF). */
    private class AssetSkipped : Exception()

    private fun currentManifest(accountId: Int, articleId: String): DownloadManifest? =
        downloadStore.readManifest(downloadStore.articleDir(accountId, articleId))

    /**
     * Fetches ONE asset under the E17 [SsrfGuard] (the start URL AND every
     * redirect hop — `@DownloadHttpClient` does not follow redirects itself,
     * hops are walked by [RedirectWalker] exactly like the extract fetch) and
     * the S28 streaming byte cap `min(per-image, remaining article budget)`:
     * a hostile multi-GB body is aborted mid-stream and never lands in
     * memory. Guard rejects, exhausted redirect budgets, non-2xx, oversize
     * bodies and challenge pages are TERMINAL per-asset skips; an IOException
     * fails the whole attempt (S28a retry via the caller).
     */
    private suspend fun fetchOneAsset(accountId: Int, articleId: String, url: String) {
        val dir = downloadStore.articleDir(accountId, articleId)
        val budget = currentManifest(accountId, articleId)
            ?: throw AttemptFailed(DownloadErrorCause.STORAGE)
        val maxBytes = minOf(
            DownloadPolicy.PER_IMAGE_MAX_BYTES,
            DownloadPolicy.IMAGES_MAX_BYTES - budget.assets.sumOf { it.bytes },
        )

        val responses = LinkedHashMap<String, Response>()
        val bytes: ByteArray
        try {
            val walker = RedirectWalker(
                guard = { candidate -> ssrfGuard.check(candidate) },
                transport = { current ->
                    val response = responses.getOrPut(current) {
                        http.newCall(Request.Builder().url(current).build()).execute()
                    }
                    if (response.code in REDIRECT_CODES) response.header("Location") else null
                },
            )
            val finalUrl = when (val walked = walker.walk(url)) {
                is RedirectWalker.WalkOutcome.Succeeded -> walked.finalUrl
                // SSRF-rejected hop or runaway redirect chain: terminal skip.
                is RedirectWalker.WalkOutcome.Rejected -> throw AssetSkipped()
                is RedirectWalker.WalkOutcome.TooManyHops -> throw AssetSkipped()
            }
            val resp = responses.getValue(finalUrl)
            if (!resp.isSuccessful) throw AssetSkipped()
            // Streaming cap: abort the moment the body exceeds the budget.
            bytes = readAtMost(resp.body.source(), maxBytes) ?: throw AssetSkipped()
        } catch (e: IOException) {
            throw AttemptFailed(DownloadErrorCause.NETWORK)
        } finally {
            responses.values.forEach { runCatching { it.close() } }
        }
        // Challenge sniff on the first ~1 KiB only — the body may be binary,
        // decoding all of it to UTF-8 just to sniff markers is wasted work.
        if (bytes.isNotEmpty() &&
            DownloadHtmlSanitizer.isChallenge(
                String(bytes, 0, minOf(bytes.size, CHALLENGE_SNIFF_BYTES), Charsets.UTF_8)
            )
        ) {
            throw AssetSkipped()
        }
        val manifest = currentManifest(accountId, articleId) ?: throw AttemptFailed(DownloadErrorCause.STORAGE)
        if (!DownloadPolicy.imageFits(
                imageBytes = bytes.size.toLong(),
                bytesSoFar = manifest.assets.sumOf { it.bytes },
                countSoFar = manifest.assets.size,
            )
        ) {
            throw AssetSkipped() // over per-image/bytes/count caps: terminal skip
        }
        val fileName = DownloadHtmlSanitizer.assetFileName(url)
        val assetFile = java.io.File(dir, "assets/$fileName")
        assetFile.parentFile?.mkdirs()
        assetFile.writeBytes(bytes)
        val sha256 =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
        val updated =
            manifest.copy(
                assets = manifest.assets + DownloadManifest.Asset(url, "assets/$fileName", bytes.size.toLong(), sha256),
            )
        downloadStore.writeManifestAtomic(dir, updated)
        downloadDao.upsertAssets(
            listOf(DownloadAsset(articleId = articleId, url = url, localPath = "assets/$fileName", bytes = bytes.size.toLong(), sha256 = sha256))
        )
        downloadDao.bumpImageProgress(articleId, bytes.size.toLong(), assetsComplete = false)
    }

    /**
     * S28 streaming cap (mirrors `ExtractFetch.readAtMost`): reads in fixed
     * chunks and stops the moment the total EXCEEDS [maxBytes] (a body of
     * exactly maxBytes is allowed). Returns null when the cap was exceeded —
     * the caller closes the response in [fetchOneAsset]'s finally block.
     */
    private fun readAtMost(source: BufferedSource, maxBytes: Long): ByteArray? {
        val buffer = Buffer()
        var total = 0L
        while (total <= maxBytes) {
            val read = source.read(buffer, READ_CHUNK_BYTES)
            if (read == -1L) return buffer.readByteArray()
            total += read
        }
        return null
    }

    /** All expected assets resolved (landed or terminally skipped). */
    private suspend fun finalizeAssets(accountId: Int, articleId: String) {
        val manifest = currentManifest(accountId, articleId) ?: return
        downloadStore.writeManifestAtomic(
            downloadStore.articleDir(accountId, articleId),
            manifest.copy(assetsComplete = true),
        )
        downloadDao.setAssetsComplete(articleId, true)
    }

    /** S27a — "Retry images": re-open the missing set on a ready row. */
    suspend fun resumeAssets(articleId: String) {
        val articleWithFeed = articleDao.queryById(articleId) ?: return
        val article = articleWithFeed.article
        val body = article.fullContent ?: article.rawDescription
        val (_, candidates) = DownloadHtmlSanitizer.rewriteImages(body) { url ->
            "assets/${DownloadHtmlSanitizer.assetFileName(url)}"
        }
        val manifest = currentManifest(article.accountId, articleId) ?: return
        val missing = candidates - manifest.assets.map { it.url }.toSet()
        if (missing.isEmpty()) {
            if (!manifest.assetsComplete) finalizeAssets(article.accountId, articleId)
            return
        }
        // Re-opening the missing set takes assetsComplete back to 0 (S27a).
        downloadDao.setAssetsComplete(articleId, false)
        downloadStore.writeManifestAtomic(
            downloadStore.articleDir(article.accountId, articleId),
            manifest.copy(assetsComplete = false),
        )
        // AttemptFailed PROPAGATES: the STATE_READY re-entry in
        // download(articleId) runs under DownloadWorker, whose S28a retry
        // rule must see transient image-phase network failures instead of
        // them being silently swallowed. UI callers guard themselves.
        fetchAssets(article.accountId, article, candidates)
    }

    /** Chained/bulk worker entry: drains up to [DownloadPolicy.BULK_MAX_ARTICLES] pending rows. */
    suspend fun downloadPendingBatch() {
        val pending = downloadDao.queryByState(ArticleDownload.STATE_PENDING)
        for (row in DownloadPolicy.truncateBulk(pending)) {
            try {
                download(row.articleId)
            } catch (e: AttemptFailed) {
                // Batch path: no WorkManager retry for the batch itself; the
                // row-level attempt counter provides the same max-3 bound and
                // the next sync chain re-drains surviving pending rows.
                val current = downloadDao.queryByArticleId(row.articleId) ?: continue
                if (e.errorCause == DownloadErrorCause.STORAGE ||
                    e.errorCause == DownloadErrorCause.OVERSIZE ||
                    current.attempts >= DownloadPolicy.MAX_AUTO_ATTEMPTS
                ) {
                    markFailed(row.articleId, e.errorCause)
                } else if (current.state == ArticleDownload.STATE_DOWNLOADING) {
                    // Non-terminal: put the row BACK to pending (attempts
                    // PRESERVED — updateState never touches the counter) so
                    // the next batch drain re-picks it instead of stranding
                    // it in `downloading` until app restart.
                    downloadDao.updateState(
                        articleId = row.articleId,
                        state = ArticleDownload.STATE_PENDING,
                        errorCause = e.errorCause.value,
                        lastAttemptAt = System.currentTimeMillis(),
                        completedAt = null,
                    )
                }
                // A row that raced to READY keeps its committed text: an
                // image-phase failure leaves it honestly text-only.
            }
        }
    }

    /**
     * Attempt-failure write. NEVER demotes a committed READY row (D6/state
     * machine: `ready -> failed` is not an attempt outcome — the text is
     * durably committed and an image-phase failure leaves it text-only).
     * The corrupt-dir demotion in [reconcile] is the ONE deliberate
     * ready->failed writer and goes through [writeFailed] directly.
     */
    suspend fun markFailed(articleId: String, cause: DownloadErrorCause) {
        val current = downloadDao.queryByArticleId(articleId)
        if (current?.state == ArticleDownload.STATE_READY) {
            Timber.w(
                "download $articleId: attempt failed (${cause.value}) but text is committed; staying ready (text-only)"
            )
            return
        }
        writeFailed(articleId, cause)
    }

    private suspend fun writeFailed(articleId: String, cause: DownloadErrorCause) {
        downloadDao.updateState(
            articleId = articleId,
            state = ArticleDownload.STATE_FAILED,
            errorCause = cause.value,
            lastAttemptAt = System.currentTimeMillis(),
            completedAt = System.currentTimeMillis(),
        )
    }

    // --- removal (S29) ----------------------------------------------------------

    /** User removal: deletes the row AND its files INCLUDING staging. */
    suspend fun remove(articleId: String) {
        downloadDao.queryByArticleId(articleId)?.let { row -> deleteFilesFor(row) }
        downloadDao.deleteAssetsByArticleId(articleId)
        downloadDao.deleteByArticleId(articleId)
    }

    /** "Remove all downloads" (count-confirmed by the caller). */
    suspend fun removeAll() {
        for (row in downloadDao.queryAll()) deleteFilesFor(row)
        downloadDao.deleteAllAssets()
        downloadDao.deleteAllDownloads()
    }

    /**
     * S29 — article deletion cascades files. [articleIds] use the upstream
     * `<accountId>$<uuid>` shape, so the account dir derives from the id.
     */
    suspend fun deleteFilesFor(articleIds: List<String>) {
        if (articleIds.isEmpty()) return
        // SQLite binds at most 999 host parameters (API 26-29): chunk the IN
        // query so >999-article feed/account deletes never hit the limit.
        for (chunk in articleIds.chunked(QUERY_CHUNK_SIZE)) {
            val withDownloads = downloadDao.queryByArticleIds(chunk)
            for (row in withDownloads) deleteFilesFor(row)
            withDownloads.forEach { downloadDao.deleteAssetsByArticleId(it.articleId) }
        }
    }

    private fun deleteFilesFor(row: ArticleDownload) {
        val accountId = row.articleId.substringBefore('$').toIntOrNull() ?: return
        downloadStore.delete(accountId, row.articleId)
    }

    // --- startup reconciliation (S27a) ------------------------------------------

    suspend fun reconcileOnStart() {
        val liveArticles = liveDownloadArticles()
        reconcile(
            hasLiveWork = { it in liveArticles },
            reenqueue = { articleId ->
                workManager.enqueueUniqueWork(
                    DownloadWorker.uniqueName(articleId),
                    ExistingWorkPolicy.KEEP,
                    DownloadWorker.manualRequest(articleId),
                )
            },
        )
    }

    private suspend fun liveDownloadArticles(): Set<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                workManager
                    .getWorkInfosByTag(DownloadWorker.DOWNLOAD_TAG)
                    .get(10, TimeUnit.SECONDS)
                    .filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
                    .flatMap { it.tags }
                    .filter { it.startsWith(DownloadWorker.ARTICLE_TAG_PREFIX) }
                    .map { it.removePrefix(DownloadWorker.ARTICLE_TAG_PREFIX) }
                    .toSet()
            }.getOrDefault(emptySet())
        }

    /**
     * Recovery rule (S27a/§8): on app start, `downloading` rows with no live
     * work reset to `pending` and re-enqueue ONCE; orphan `*.staging/` dirs
     * are garbage-collected (keeping in-flight keys); a `ready` row whose
     * committed dir fails manifest validation is demoted to
     * `failed(storage)` — never rendered from a corrupt dir.
     */
    internal suspend fun reconcile(
        hasLiveWork: (String) -> Boolean,
        reenqueue: (String) -> Unit,
    ) {
        val downloading = downloadDao.queryByState(ArticleDownload.STATE_DOWNLOADING)
        val inFlightKeys = mutableSetOf<String>()
        for (row in downloading) {
            if (hasLiveWork(row.articleId)) {
                inFlightKeys.add(DownloadStore.articleKey(row.articleId))
            } else {
                downloadDao.resetForRetry(row.articleId)
                reenqueue(row.articleId)
            }
        }
        downloadStore.gcOrphanStaging(inFlightKeys)

        for (row in downloadDao.queryByState(ArticleDownload.STATE_READY)) {
            val accountId = row.articleId.substringBefore('$').toIntOrNull() ?: continue
            if (!downloadStore.exists(accountId, row.articleId)) {
                Timber.w("download ${row.articleId}: committed dir missing/invalid; demoting to failed(storage)")
                // Deliberate ready->failed demotion (corrupt dir is never
                // rendered): bypasses the READY guard in markFailed.
                writeFailed(row.articleId, DownloadErrorCause.STORAGE)
            }
        }
    }

    private companion object {
        /** Response body is pulled in fixed chunks under the streaming cap. */
        const val READ_CHUNK_BYTES = 8_192L

        /** Challenge markers are sniffed from the first ~1 KiB only. */
        const val CHALLENGE_SNIFF_BYTES = 1_024

        /** Same redirect-code convention as the extract fetch walker. */
        val REDIRECT_CODES = 300..399

        /** SQLite host-parameter limit is 999 (API 26-29); 500 stays clear. */
        const val QUERY_CHUNK_SIZE = 500
    }
}
