package me.ash.reader.domain.model.download

import androidx.work.BackoffPolicy
import androidx.work.NetworkType

/**
 * S28/S28a — the download policy as ONE pure policy object. The caps are
 * DECISIONS (exact values, rationale in SPEC-v1 §7); the service and worker
 * must not re-declare or re-tune these numbers anywhere else.
 *
 * Retry/backoff semantics are the WorkManager 2.10.1-verified ones (R6):
 * exponential delay = `initialDelay x 2^(runAttemptCount-1)` clamped
 * [10 s, 5 h]; `runAttemptCount` is 0-based on first execution. All delays
 * are FLOORS — constraints, Doze and battery optimization may defer
 * execution; no exact wake time is ever promised in UI (D12).
 */
object DownloadPolicy {

    // --- S28 caps (exact) ---------------------------------------------------

    /** Per-article sanitized HTML text. */
    const val TEXT_MAX_BYTES: Long = 2L * 1024 * 1024

    /** Per-article images: bytes AND asset count. */
    const val IMAGES_MAX_BYTES: Long = 10L * 1024 * 1024
    const val IMAGES_MAX_COUNT: Int = 50

    /** Per single image. */
    const val PER_IMAGE_MAX_BYTES: Long = 5L * 1024 * 1024

    /** Global store; warn at 90%, new downloads fail at 100%. */
    const val GLOBAL_MAX_BYTES: Long = 1L * 1024 * 1024 * 1024
    const val GLOBAL_WARN_FRACTION: Double = 0.9

    /** Bulk download scope cap per invocation. */
    const val BULK_MAX_ARTICLES: Int = 200

    // --- S28 HTTP timeouts (dedicated download client) ----------------------

    const val CONNECT_TIMEOUT_SECS: Long = 15L
    const val READ_TIMEOUT_SECS: Long = 30L
    const val CALL_TIMEOUT_SECS: Long = 120L

    // --- S28a retry rule ----------------------------------------------------

    /** Max 3 automatic retries, enforced in the worker via runAttemptCount. */
    const val MAX_AUTO_ATTEMPTS: Int = 3

    val BACKOFF_POLICY: BackoffPolicy = BackoffPolicy.EXPONENTIAL
    const val BACKOFF_DELAY_SECS: Long = 30L
    const val BACKOFF_MIN_SECS: Long = 10L
    const val BACKOFF_MAX_SECS: Long = 5 * 3600L

    // --- decisions ------------------------------------------------------------

    fun textAccepted(bytes: Long): Boolean = bytes <= TEXT_MAX_BYTES

    fun imageAccepted(bytes: Long): Boolean = bytes <= PER_IMAGE_MAX_BYTES

    /** One more image fits when per-image, bytes-budget AND count-budget pass. */
    fun imageFits(imageBytes: Long, bytesSoFar: Long, countSoFar: Int): Boolean =
        imageAccepted(imageBytes) &&
            bytesSoFar + imageBytes <= IMAGES_MAX_BYTES &&
            countSoFar < IMAGES_MAX_COUNT

    enum class GlobalStatus { OK, WARN, EXCEEDED }

    /** 90% warn, 100% `failed(storage)` (never silent eviction, D9). */
    fun globalStatus(totalBytes: Long): GlobalStatus =
        when {
            totalBytes >= GLOBAL_MAX_BYTES -> GlobalStatus.EXCEEDED
            totalBytes >= (GLOBAL_MAX_BYTES * GLOBAL_WARN_FRACTION).toLong() -> GlobalStatus.WARN
            else -> GlobalStatus.OK
        }

    /** Bulk scope cap; the UI states the cap (S28). */
    fun <T> truncateBulk(items: List<T>): List<T> = items.take(BULK_MAX_ARTICLES)

    // --- errorCause (S27a stable enum strings) --------------------------------

    fun errorCauseForHttpCode(code: Int): DownloadErrorCause =
        when {
            code in 400..499 -> DownloadErrorCause.HTTP_4XX
            code in 500..599 -> DownloadErrorCause.HTTP_5XX
            else -> DownloadErrorCause.UNKNOWN
        }

    // --- S27a state machine ---------------------------------------------------

    private val LEGAL_TRANSITIONS: Map<String, Set<String>> =
        mapOf(
            ArticleDownload.STATE_PENDING to
                setOf(ArticleDownload.STATE_DOWNLOADING),
            ArticleDownload.STATE_DOWNLOADING to
                setOf(
                    ArticleDownload.STATE_READY,
                    ArticleDownload.STATE_FAILED,
                    // batch requeue: a non-terminal bulk failure resets the
                    // row to pending with attempts PRESERVED (the row-level
                    // max-3 bound still applies)
                    ArticleDownload.STATE_PENDING,
                ),
            // manual retry: failed -> pending, resetting the attempt counter
            ArticleDownload.STATE_FAILED to
                setOf(ArticleDownload.STATE_PENDING),
        )

    fun isLegalTransition(from: String, to: String): Boolean =
        LEGAL_TRANSITIONS[from]?.contains(to) == true

    // --- S28a retry outcome ---------------------------------------------------

    enum class RetryOutcome { RETRY, TERMINAL_NETWORK }

    /**
     * Worker-enforced max attempts: `runAttemptCount >= 3` → terminal
     * `failed(network)`; else `Result.retry()`.
     */
    fun retryOutcome(runAttemptCount: Int): RetryOutcome =
        if (runAttemptCount >= MAX_AUTO_ATTEMPTS) RetryOutcome.TERMINAL_NETWORK
        else RetryOutcome.RETRY

    /** Retry-delay FLOOR for display math only; never a promised wake time. */
    fun backoffFloorSecs(runAttemptCount: Int): Long {
        // Delay before the run with this attempt count: retry 1 (attempt 0
        // failed) waits 30 s, retry 2 waits 60 s, retry 3 waits 120 s.
        val exp = BACKOFF_DELAY_SECS shl runAttemptCount.coerceIn(0, 20)
        return exp.coerceIn(BACKOFF_MIN_SECS, BACKOFF_MAX_SECS)
    }

    // --- S28a network split ---------------------------------------------------

    enum class NetworkPolicy { MANUAL, BULK }

    /**
     * (amended, R6) Manual = NO constraint (airplane tap fails fast to
     * `failed(network)`); bulk/chained = UNMETERED (+charging per account
     * prefs at enqueue time). Constraint waiting renders as ENQUEUED
     * ("Waiting for Wi-Fi"), never `failed`.
     */
    fun networkTypeFor(policy: NetworkPolicy): NetworkType =
        when (policy) {
            NetworkPolicy.MANUAL -> NetworkType.NOT_REQUIRED
            NetworkPolicy.BULK -> NetworkType.UNMETERED
        }
}

/** S27a — stable enum strings surfaced as human text; stored in `errorCause`. */
enum class DownloadErrorCause(val value: String) {
    NETWORK("network"),
    HTTP_4XX("http_4xx"),
    HTTP_5XX("http_5xx"),
    CHALLENGE("challenge"),
    OVERSIZE("oversize"),
    STORAGE("storage"),
    CANCELLED("cancelled"),
    UNKNOWN("unknown"),
}
