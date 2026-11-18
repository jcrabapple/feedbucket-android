package me.ash.reader.domain.model.extract

/**
 * SPEC-EXTRACT-v1 — the SINGLE source for every extraction constant (X2).
 * No other extraction file may re-declare or re-tune these numbers; tests
 * cite this object, never literals. Values are decisions, not suggestions:
 *
 *  - [TRIGGER_MAX_ABS], [TRIGGER_FLOOR], [TRIGGER_MEDIAN_FRACTION] — E6,
 *    the exact web `_can_fetch_full` formula (`feedbucket/app.py:511–524`).
 *  - [RE_EXTRACT_MIN] — E16.2, web `min_len = 120` (`app.py:581`); the same
 *    120 floor anchors the fresh guard's `max(current, 120)`.
 *  - [MAX_HOPS] — E17 manual redirect budget (web's httpx client never
 *    silently follows into private ranges either).
 *  - [MAX_RESPONSE_BYTES] — E18 streamed response cap: page-HTML headroom
 *    above the 2 MiB stored-text cap (`DownloadPolicy.TEXT_MAX_BYTES`,
 *    reused not re-decided), a hard bound against hostile multi-GB
 *    responses on metered links.
 *  - [MEDIAN_SAMPLE_CAP] — E7; enforced by the X1 DAO query
 *    (`queryRecentRawDescriptionsForMedian LIMIT`, pinned in
 *    `ArticleDaoExtractionTest`) AND defensively by `MedianBodyLength`.
 *  - The timeout trio — E18 `@ExtractHttpClient` (connect/read deliberately
 *    equal the S28 download-client row; the 60 s call cap bounds a single
 *    page fetch vs 120 s for image downloads).
 */
object ExtractPolicy {

    /** E6 cond 3 — absolute teaser ceiling in plain-text chars (web `length >= 700 → False`). */
    const val TRIGGER_MAX_ABS: Int = 700

    /** E6 cond 4 — adaptive threshold floor (web `max(200, …)`). */
    const val TRIGGER_FLOOR: Int = 200

    /** E6 cond 4 — median fraction (web `int(0.4 * median)`, truncation toward zero). */
    const val TRIGGER_MEDIAN_FRACTION: Double = 0.4

    /** E16.2 — result floor; fresh: `result > max(current, 120)`, re-extract: `result > 120`. */
    const val RE_EXTRACT_MIN: Int = 120

    /** E17 — max manual redirect hops; every hop URL runs the full guard. */
    const val MAX_HOPS: Int = 5

    /** E18 — streamed response byte cap (exceeded → close + failure `oversize`). */
    const val MAX_RESPONSE_BYTES: Long = 5L * 1024 * 1024

    /** E7 — median recompute sample cap (≤100 most recent non-empty rawDescription rows). */
    const val MEDIAN_SAMPLE_CAP: Int = 100

    // --- E18 @ExtractHttpClient timeouts ---

    const val CONNECT_TIMEOUT_SECS: Long = 15L
    const val READ_TIMEOUT_SECS: Long = 30L
    const val CALL_TIMEOUT_SECS: Long = 60L
}
