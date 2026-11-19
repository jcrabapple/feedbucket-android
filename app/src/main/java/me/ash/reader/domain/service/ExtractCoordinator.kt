package me.ash.reader.domain.service

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.UpdateDetection
import me.ash.reader.domain.model.download.DownloadHtmlSanitizer
import me.ash.reader.domain.model.extract.LengthGuard
import me.ash.reader.domain.model.extract.TeaserTrigger
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.query.TextNormalizer
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.infrastructure.html.ExtractCleaner
import me.ash.reader.infrastructure.html.Readability
import me.ash.reader.infrastructure.net.SsrfGuard
import timber.log.Timber

/** E2 — the mode of an extraction request; selects guard set + UX contract only. */
enum class ExtractMode {
    /** On-open adaptive extraction (E8/E10) — gated by the E6 teaser trigger. */
    AUTO,

    /** Explicit user extract from the reader (E11) — no trigger gate. */
    MANUAL,

    /** Explicit re-extract of an already-extracted article (E12). */
    RE_EXTRACT,

    /** Sync-time prefetch for `isFullContent` feeds (E9) — forced, best-effort. */
    PREFETCH,
}

/** E21 — typed failure reasons; every one is a silent no-op (no DB write). */
enum class ExtractFailureReason {
    /** E2 — the article has no usable link. */
    BLANK_LINK,

    /** E17 — a hop URL failed the SSRF guard (scheme/userinfo/non-global). */
    SSRF_REJECTED,

    /** E17/E21 — host resolution failed (guard reject or transport race). */
    DNS_FAILURE,

    /** E18/E21 — connect/read/call timeout. */
    TIMEOUT,

    /** E21 — other transport-level I/O failure. */
    NETWORK,

    /** E21 — final response was not 2xx. */
    NON_2XX,

    /** E17 — redirect chain exceeded the hop budget. */
    TOO_MANY_HOPS,

    /** E18 — response body exceeded the 5 MiB streaming cap. */
    OVERSIZE_RESPONSE,

    /** E16.1 — bot-challenge wall detected in the fetched document head. */
    CHALLENGE,

    /** E14 — Readability4J returned no article content (or crashed). */
    NULL_READABILITY,

    /** E15 — the deterministic post-cleaner threw. */
    CLEANER_ERROR,

    /**
     * E16.1 (amendment A1) — productive-text ratio below 5% of the SANITIZED
     * FRAGMENT (was: of the raw document — false-rejected heavy pages).
     */
    UNPRODUCTIVE,

    /** E16.2 — result not strictly longer than the mode's floor. */
    LENGTH_REJECTED,

    /** E16.3 — sanitized result exceeded the 2 MiB stored-text cap. */
    OVERSIZE_RESULT,

    /** E21 — the extraction write itself failed (or updated no row). */
    DAO_ERROR,

    /** The owning caller's coroutine was cancelled mid-extraction. */
    CANCELLED,

    /** Unexpected internal error; absorbed so nothing escapes (E21). */
    INTERNAL,
}

/** E21 — the coordinator's total result space; failures carry a typed reason. */
sealed interface ExtractOutcome {
    /** The extraction passed every guard and was written (E3/E4). */
    data class Stored(val articleId: String, val extractedAt: Date) : ExtractOutcome

    /** AUTO on a non-candidate (already extracted or not a teaser, E6) — no fetch. */
    data object Skipped : ExtractOutcome

    /** Silent no-op failure (E21): no DB write, no crash, no UI state. */
    data class Failure(val reason: ExtractFailureReason) : ExtractOutcome
}

/**
 * E2 — the ONE extraction pipeline. Every extraction (AUTO on open, MANUAL
 * extract, MANUAL re-extract, PREFETCH for `isFullContent` feeds) enters
 * through [extract]; the mode only selects the guard set (E16) and the
 * caller's UX contract (E10/E12). No second fetch-and-parse path exists
 * behind this coordinator.
 *
 * Pipeline order (fixed, E2): link check → SSRF/redirect guard (E17, inside
 * [ExtractFetch]) → bounded fetch (E18) → challenge check (E16.1) →
 * Readability4J parse (E14) → deterministic post-cleaner (E15) → sanitize
 * (E19) → productive-ratio guard (E16.1) → length/size guard (E16.2/E16.3)
 * → single DAO write (E4) with `extractedAt = now` (E3).
 *
 * Failure model (E21, binding): EVERY failure branch returns a typed
 * [ExtractOutcome.Failure] with ZERO DB writes and NO exception escaping;
 * logging is Timber-only, for diagnosis. Owner-cancellation is likewise
 * absorbed into [ExtractFailureReason.CANCELLED] so in-flight waiters always
 * receive an outcome instead of hanging (the extraction contract is
 * best-effort everywhere: E9c PREFETCH, E10 AUTO fallback).
 *
 * Concurrency: an in-flight per-article dedupe map guarded by a mutex means
 * concurrent [extract] calls for the SAME article share ONE fetch — later
 * callers join the running pipeline's deferred and receive its outcome.
 */
class ExtractCoordinator(
    private val fetch: ExtractFetch,
    private val articleDao: ArticleDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** E15 cleaner seam — production default; injectable for failure-path tests. */
    private val cleaner: (html: String, title: String) -> String = ExtractCleaner::clean,
    /**
     * E3 one-time hygiene seam — the retired legacy disk cache directory
     * (`cacheDir/readability`), deleted recursively on init. Null (default)
     * skips the cleanup; the temp-dir unit-test seam passes a fixture dir.
     */
    private val legacyCacheDir: File? = null,
) {

    private val inFlightMutex = Mutex()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ExtractOutcome>>()

    init {
        // E3 — the disk cache is retired (the canonical store is the article
        // row). One-time hygiene on first coordinator init after upgrade:
        // bounded recursive delete on the IO dispatcher, failure IGNORED
        // (Android may evict the cache anyway; no DB backfill — affected
        // feeds re-extract under their existing contracts). Idempotent:
        // once the dir is gone, every later init is a no-op.
        legacyCacheDir?.let { dir ->
            CoroutineScope(SupervisorJob() + ioDispatcher).launch {
                runCatching { dir.deleteRecursively() }
                    .onFailure {
                        Timber.tag(TAG).d(it, "legacy cache delete failed (ignored, E3)")
                    }
            }
        }
    }

    private class Registration(
        val deferred: CompletableDeferred<ExtractOutcome>,
        val owner: Boolean,
    )

    /**
     * Runs one extraction attempt. Never throws (E21); never writes on
     * failure; writes EXACTLY once via `applyExtraction` on success.
     */
    suspend fun extract(article: Article, feed: Feed, mode: ExtractMode): ExtractOutcome {
        // E8/E6 — AUTO fires only for teaser candidates; an already-extracted
        // row (E6 cond 2) or a non-teaser body skips immediately, no fetch.
        if (mode == ExtractMode.AUTO &&
            !TeaserTrigger.isTeaserCandidate(
                link = article.link,
                fullContent = article.fullContent,
                rawDescription = article.rawDescription,
                medianBodyLength = feed.medianBodyLength,
            )
        ) {
            Timber.tag(TAG).d("extract(${article.id}): AUTO skip — not a teaser candidate (E6)")
            return ExtractOutcome.Skipped
        }

        // In-flight per-article dedupe: the first caller owns the pipeline,
        // concurrent callers join its outcome (ONE fetch per article).
        val registration = inFlightMutex.withLock {
            val existing = inFlight[article.id]
            if (existing != null) {
                Registration(existing, owner = false)
            } else {
                val deferred = CompletableDeferred<ExtractOutcome>()
                inFlight[article.id] = deferred
                Registration(deferred, owner = true)
            }
        }
        if (!registration.owner) {
            Timber.tag(TAG).d("extract(${article.id}): joining in-flight extraction")
            return registration.deferred.await()
        }

        val outcome = try {
            withContext(ioDispatcher) { runPipeline(article, mode) }
        } catch (t: Throwable) {
            // E21 — nothing escapes, ever. Cancellation of the OWNER is
            // absorbed into a typed failure so joined waiters still get an
            // outcome (documented deviation from structured-concurrency
            // rethrow; extraction is best-effort by contract).
            Timber.tag(TAG).w(t, "extract(${article.id}): unexpected failure (mode=$mode)")
            if (t is CancellationException) {
                ExtractOutcome.Failure(ExtractFailureReason.CANCELLED)
            } else {
                ExtractOutcome.Failure(ExtractFailureReason.INTERNAL)
            }
        }
        // Non-suspending cleanup: safe even under cancellation.
        inFlight.remove(article.id)
        registration.deferred.complete(outcome)
        if (outcome is ExtractOutcome.Failure) {
            Timber.tag(TAG).d("extract(${article.id}): ${outcome.reason} — silent no-op (E21)")
        }
        return outcome
    }

    /** E2 fixed order, stage 1: link check → guarded bounded fetch. */
    private suspend fun runPipeline(article: Article, mode: ExtractMode): ExtractOutcome {
        if (article.link.isBlank()) {
            return ExtractOutcome.Failure(ExtractFailureReason.BLANK_LINK)
        }

        val fetched = try {
            fetch.fetch(article.link)
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "extract(${article.id}): transport failure")
            return ExtractOutcome.Failure(fetchFailureReason(t))
        }

        val body = when (fetched) {
            is ExtractFetch.FetchOutcome.Rejected -> return ExtractOutcome.Failure(
                if (fetched.reason == SsrfGuard.RejectReason.DNS_FAILURE) {
                    ExtractFailureReason.DNS_FAILURE
                } else {
                    ExtractFailureReason.SSRF_REJECTED
                },
            )

            is ExtractFetch.FetchOutcome.TooManyHops ->
                return ExtractOutcome.Failure(ExtractFailureReason.TOO_MANY_HOPS)

            is ExtractFetch.FetchOutcome.Oversize ->
                return ExtractOutcome.Failure(ExtractFailureReason.OVERSIZE_RESPONSE)

            is ExtractFetch.FetchOutcome.Non2xx ->
                return ExtractOutcome.Failure(ExtractFailureReason.NON_2XX)

            is ExtractFetch.FetchOutcome.Body -> fetched
        }
        return evaluateAndStore(article, mode, body)
    }

    /** E2 fixed order, stage 2: guards → the single E4 write. */
    private suspend fun evaluateAndStore(
        article: Article,
        mode: ExtractMode,
        body: ExtractFetch.FetchOutcome.Body,
    ): ExtractOutcome {
        // E16.1 — a bot-challenge wall is never content.
        if (DownloadHtmlSanitizer.isChallenge(body.html)) {
            return ExtractOutcome.Failure(ExtractFailureReason.CHALLENGE)
        }

        // E14 — Readability4J; a null result (or a parse crash) is typed.
        val parsed = try {
            Readability.parseToElement(body.html, body.finalUrl)
        } catch (t: Throwable) {
            Timber.tag(TAG).d(t, "extract(${article.id}): readability parse error")
            null
        }
        if (parsed == null) {
            return ExtractOutcome.Failure(ExtractFailureReason.NULL_READABILITY)
        }

        // E15 — deterministic post-cleaner (title dedupe, frontmatter, TOC,
        // dateline); any throw is a typed no-op.
        val cleaned = try {
            cleaner(parsed.toString(), article.title)
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "extract(${article.id}): cleaner failure")
            return ExtractOutcome.Failure(ExtractFailureReason.CLEANER_ERROR)
        }

        // E19 — sanitize BEFORE measurement and BEFORE the write.
        val sanitized = DownloadHtmlSanitizer.sanitize(cleaned)

        // E16.1 + amendment A1 (docs/research/SPEC-EXTRACT-AMENDMENT-A1.md) —
        // productive-ratio guard: fewer than 5% productive chars means
        // markup/junk won. A1 moves the basis from the RAW fetched document
        // to the SANITIZED FRAGMENT (the same string plainTextLength
        // measures): site chrome outside the extracted fragment no longer
        // counts against a complete article. The raw-document basis
        // false-rejected heavy pages (device-verified on 0.1.4: a 2,503-char
        // Engadget extract on a 61,559-byte page, 2,503 × 20 = 50,060 <
        // 61,559); a markup-dominant FRAGMENT (nav/menu/junk grab) still
        // fails here.
        val productiveTextLength = UpdateDetection.plainTextLength(sanitized).toLong()
        if (productiveTextLength * PRODUCTIVE_RATIO_DENOMINATOR < sanitized.length.toLong()) {
            return ExtractOutcome.Failure(ExtractFailureReason.UNPRODUCTIVE)
        }

        // E16.2/E16.3 — web-exact length guard + 2 MiB stored-size cap.
        when (LengthGuard.evaluate(sanitized, article.rawDescription, mode == ExtractMode.RE_EXTRACT)) {
            LengthGuard.Verdict.TooShort ->
                return ExtractOutcome.Failure(ExtractFailureReason.LENGTH_REJECTED)

            LengthGuard.Verdict.Oversize ->
                return ExtractOutcome.Failure(ExtractFailureReason.OVERSIZE_RESULT)

            LengthGuard.Verdict.Accept -> Unit
        }

        // E3/E4 — the ONE write; extractedAt = now on EVERY successful
        // extraction; fullLower shadow computed here (S11a write-path
        // invariant) so Room's FTS triggers carry the change (S16).
        val extractedAt = Date(clock())
        return try {
            val rows = articleDao.applyExtraction(
                id = article.id,
                fullContent = sanitized,
                fullLower = TextNormalizer.normalize(sanitized),
                extractedAt = extractedAt,
            )
            if (rows > 0) {
                ExtractOutcome.Stored(articleId = article.id, extractedAt = extractedAt)
            } else {
                Timber.tag(TAG).w("extract(${article.id}): applyExtraction updated 0 rows")
                ExtractOutcome.Failure(ExtractFailureReason.DAO_ERROR)
            }
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "extract(${article.id}): DAO write failure")
            if (t is CancellationException) {
                ExtractOutcome.Failure(ExtractFailureReason.CANCELLED)
            } else {
                ExtractOutcome.Failure(ExtractFailureReason.DAO_ERROR)
            }
        }
    }

    /** E21 mapping of transport-level exceptions to typed reasons. */
    private fun fetchFailureReason(t: Throwable): ExtractFailureReason = when (t) {
        is CancellationException -> ExtractFailureReason.CANCELLED
        is SocketTimeoutException -> ExtractFailureReason.TIMEOUT
        is InterruptedIOException -> ExtractFailureReason.TIMEOUT
        is UnknownHostException -> ExtractFailureReason.DNS_FAILURE
        is IOException -> ExtractFailureReason.NETWORK
        else -> ExtractFailureReason.INTERNAL
    }

    companion object {
        private const val TAG = "RLog"

        /**
         * E16.1 + amendment A1 — `plainTextLength × 20 < sanitizedFragment.length`
         * ⇒ under 5% productive (basis per SPEC-EXTRACT-AMENDMENT-A1.md).
         */
        private const val PRODUCTIVE_RATIO_DENOMINATOR = 20L

        /** E3 — name of the retired legacy disk-cache dir under `cacheDir`. */
        const val LEGACY_CACHE_DIR_NAME = "readability"
    }
}
