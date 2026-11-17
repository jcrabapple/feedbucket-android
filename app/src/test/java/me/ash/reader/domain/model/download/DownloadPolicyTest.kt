package me.ash.reader.domain.model.download

import androidx.work.BackoffPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance #7 (S27a/S28/S28a) — download policy cap matrix. Each S28 cap is
 * pinned by its own test; the state machine, errorCause enum, retry rule and
 * the manual/bulk network split live here as pure functions so the worker and
 * service carry no second copy of the numbers.
 */
class DownloadPolicyTest {

    // --- S28 caps (exact values) -------------------------------------------

    @Test
    fun textCapIs2MiB() {
        val cap = DownloadPolicy.TEXT_MAX_BYTES
        assertEquals(2L * 1024 * 1024, cap)
        assertTrue(DownloadPolicy.textAccepted(cap))
        assertFalse(DownloadPolicy.textAccepted(cap + 1))
    }

    @Test
    fun perImageCapIs5MiB() {
        val cap = DownloadPolicy.PER_IMAGE_MAX_BYTES
        assertEquals(5L * 1024 * 1024, cap)
        assertTrue(DownloadPolicy.imageAccepted(cap))
        assertFalse(DownloadPolicy.imageAccepted(cap + 1))
    }

    @Test
    fun imageBudgetIs10MiBand50Assets() {
        assertEquals(10L * 1024 * 1024, DownloadPolicy.IMAGES_MAX_BYTES)
        assertEquals(50, DownloadPolicy.IMAGES_MAX_COUNT)

        // exactly at both caps -> still fetchable
        assertTrue(
            DownloadPolicy.imageFits(
                imageBytes = DownloadPolicy.PER_IMAGE_MAX_BYTES,
                bytesSoFar = DownloadPolicy.IMAGES_MAX_BYTES - DownloadPolicy.PER_IMAGE_MAX_BYTES,
                countSoFar = DownloadPolicy.IMAGES_MAX_COUNT - 1,
            )
        )
        // 51st asset rejected
        assertFalse(
            DownloadPolicy.imageFits(
                imageBytes = 100,
                bytesSoFar = 0,
                countSoFar = DownloadPolicy.IMAGES_MAX_COUNT,
            )
        )
        // bytes budget exceeded
        assertFalse(
            DownloadPolicy.imageFits(
                imageBytes = DownloadPolicy.PER_IMAGE_MAX_BYTES,
                bytesSoFar = DownloadPolicy.IMAGES_MAX_BYTES,
                countSoFar = 0,
            )
        )
    }

    @Test
    fun globalCapIs1GiBwith90PercentWarn() {
        assertEquals(1L * 1024 * 1024 * 1024, DownloadPolicy.GLOBAL_MAX_BYTES)
        assertEquals(0.9, DownloadPolicy.GLOBAL_WARN_FRACTION, 0.0)

        assertEquals(
            DownloadPolicy.GlobalStatus.OK,
            DownloadPolicy.globalStatus(DownloadPolicy.GLOBAL_MAX_BYTES / 2),
        )
        assertEquals(
            DownloadPolicy.GlobalStatus.WARN,
            DownloadPolicy.globalStatus((DownloadPolicy.GLOBAL_MAX_BYTES * 0.9).toLong()),
        )
        assertEquals(
            DownloadPolicy.GlobalStatus.EXCEEDED,
            DownloadPolicy.globalStatus(DownloadPolicy.GLOBAL_MAX_BYTES),
        )
    }

    @Test
    fun bulkCapIs200() {
        val ids = (1..250).map { "article-$it" }
        assertEquals(200, DownloadPolicy.truncateBulk(ids).size)
        assertEquals(ids.take(200), DownloadPolicy.truncateBulk(ids))
        assertEquals(3, DownloadPolicy.truncateBulk(ids.take(3)).size)
    }

    @Test
    fun timeoutsAre15_30_120() {
        assertEquals(15L, DownloadPolicy.CONNECT_TIMEOUT_SECS)
        assertEquals(30L, DownloadPolicy.READ_TIMEOUT_SECS)
        assertEquals(120L, DownloadPolicy.CALL_TIMEOUT_SECS)
    }

    // --- errorCause enum (S27a stable strings) ------------------------------

    @Test
    fun errorCauseStableStrings() {
        assertEquals("network", DownloadErrorCause.NETWORK.value)
        assertEquals("http_4xx", DownloadErrorCause.HTTP_4XX.value)
        assertEquals("http_5xx", DownloadErrorCause.HTTP_5XX.value)
        assertEquals("challenge", DownloadErrorCause.CHALLENGE.value)
        assertEquals("oversize", DownloadErrorCause.OVERSIZE.value)
        assertEquals("storage", DownloadErrorCause.STORAGE.value)
        assertEquals("cancelled", DownloadErrorCause.CANCELLED.value)
        assertEquals("unknown", DownloadErrorCause.UNKNOWN.value)
    }

    @Test
    fun httpCodeMapping() {
        assertEquals(DownloadErrorCause.HTTP_4XX, DownloadPolicy.errorCauseForHttpCode(404))
        assertEquals(DownloadErrorCause.HTTP_4XX, DownloadPolicy.errorCauseForHttpCode(403))
        assertEquals(DownloadErrorCause.HTTP_5XX, DownloadPolicy.errorCauseForHttpCode(503))
        assertEquals(DownloadErrorCause.UNKNOWN, DownloadPolicy.errorCauseForHttpCode(200))
    }

    // --- S27a state machine -------------------------------------------------

    @Test
    fun stateMachineLegalTransitions() {
        // happy path
        assertTrue(DownloadPolicy.isLegalTransition("pending", "downloading"))
        assertTrue(DownloadPolicy.isLegalTransition("downloading", "ready"))
        assertTrue(DownloadPolicy.isLegalTransition("downloading", "failed"))
        // manual retry: failed -> pending (resets attempts); pending may also
        // be re-attempted straight away
        assertTrue(DownloadPolicy.isLegalTransition("failed", "pending"))
        // terminal-ish guards
        assertFalse(DownloadPolicy.isLegalTransition("ready", "downloading"))
        assertFalse(DownloadPolicy.isLegalTransition("ready", "pending"))
        assertFalse(DownloadPolicy.isLegalTransition("ready", "failed"))
        assertFalse(DownloadPolicy.isLegalTransition("failed", "ready"))
        assertFalse(DownloadPolicy.isLegalTransition("pending", "ready"))
        assertFalse(DownloadPolicy.isLegalTransition("bogus", "ready"))
    }

    // --- S28a retry rule ----------------------------------------------------

    @Test
    fun retryRuleThreeAutomaticRetriesThenTerminal() {
        // runAttemptCount is 0-based on first execution (WorkManager 2.10.1).
        // attempts 0/1/2 -> retry; >= 3 -> terminal failed(network).
        assertEquals(DownloadPolicy.MAX_AUTO_ATTEMPTS, 3)
        for (attempt in 0..2) {
            assertEquals(
                DownloadPolicy.RetryOutcome.RETRY,
                DownloadPolicy.retryOutcome(runAttemptCount = attempt),
            )
        }
        assertEquals(
            DownloadPolicy.RetryOutcome.TERMINAL_NETWORK,
            DownloadPolicy.retryOutcome(runAttemptCount = 3),
        )
        assertEquals(
            DownloadPolicy.RetryOutcome.TERMINAL_NETWORK,
            DownloadPolicy.retryOutcome(runAttemptCount = 7),
        )
    }

    @Test
    fun backoffIsExponential30s() {
        assertEquals(BackoffPolicy.EXPONENTIAL, DownloadPolicy.BACKOFF_POLICY)
        assertEquals(30L, DownloadPolicy.BACKOFF_DELAY_SECS)
        // WorkManager 2.10.1 verified formula: delay x 2^(runAttemptCount-1),
        // clamped [10s, 5h]; these are FLOORS, never promised wake times.
        assertEquals(30L, DownloadPolicy.backoffFloorSecs(runAttemptCount = 0))
        assertEquals(60L, DownloadPolicy.backoffFloorSecs(runAttemptCount = 1))
        assertEquals(120L, DownloadPolicy.backoffFloorSecs(runAttemptCount = 2))
        // nonsense negative attempt counts clamp to the attempt-0 floor
        assertEquals(30L, DownloadPolicy.backoffFloorSecs(runAttemptCount = -5))
        assertEquals(5 * 3600L, DownloadPolicy.backoffFloorSecs(runAttemptCount = 30))
    }

    // --- S28a manual/bulk network split ------------------------------------

    @Test
    fun manualDownloadHasNoNetworkConstraint() {
        // Explicit user intent (R6): manual single-article downloads run with
        // NO network constraint; on airplane mode the attempt runs and fails
        // fast to failed(network).
        assertEquals(
            androidx.work.NetworkType.NOT_REQUIRED,
            DownloadPolicy.networkTypeFor(DownloadPolicy.NetworkPolicy.MANUAL),
        )
    }

    @Test
    fun bulkAndChainedDownloadsRequireUnmetered() {
        // Bulk/chained downloads gate on UNMETERED (+charging per account
        // prefs at enqueue time); constraint waiting is ENQUEUED, never failed.
        assertEquals(
            androidx.work.NetworkType.UNMETERED,
            DownloadPolicy.networkTypeFor(DownloadPolicy.NetworkPolicy.BULK),
        )
    }
}
