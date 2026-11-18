package me.ash.reader.domain.model.extract

import me.ash.reader.domain.model.article.UpdateDetection

/**
 * E5/E6 — the adaptive teaser trigger, an exact port of web
 * `_can_fetch_full` (`feedbucket/app.py:511–524`). An article is a TEASER
 * candidate iff ALL of:
 *
 *  1. `link` is non-blank (web `if not entry["url"]: return False`);
 *  2. `fullContent == null` (AUTO never re-extracts; re-extraction is
 *      MANUAL-only, E12 — documented deviation from web);
 *  3. `len < 700` ([ExtractPolicy.TRIGGER_MAX_ABS]), where `len` is the
 *      E5 body-text length: [UpdateDetection.plainTextLength] of
 *      `rawDescription` — the single canonical nh3-parity tag-strip, NEVER
 *      a second plain-text implementation;
 *  4. `len < max(200, (0.4 × medianBodyLength).toInt())` — the web's exact
 *      expression INCLUDING `int()` truncation toward zero (pinned by
 *      recorded Python fixtures in `TeaserTriggerTest`).
 *
 * Empty/near-empty bodies are NOT special-cased (web parity, E6): `len = 0`
 * satisfies 3 and 4 via the 200-char floor; the guard that prevents garbage
 * storage is E16 ([LengthGuard]), not the trigger. `medianBodyLength == 0`
 * (unknown / all-empty feed) degrades exactly like web's
 * `feed_median_body_len → 0`: threshold = `max(200, 0)` = 200.
 */
object TeaserTrigger {

    /**
     * E6 truth table. [rawDescription] is the article's feed-supplied body
     * (the Android analogue of web `content or summary`, E5);
     * [medianBodyLength] is the feed's stored E7 estimate.
     */
    fun isTeaserCandidate(
        link: String?,
        fullContent: String?,
        rawDescription: String,
        medianBodyLength: Int,
    ): Boolean {
        if (link.isNullOrBlank()) return false // cond 1
        if (fullContent != null) return false // cond 2
        val len = UpdateDetection.plainTextLength(rawDescription) // E5
        if (len >= ExtractPolicy.TRIGGER_MAX_ABS) return false // cond 3 (`length >= 700 → False`)
        return len < adaptiveThreshold(medianBodyLength) // cond 4
    }

    /**
     * E6 cond 4 threshold: `max(200, int(0.4 × median))` with `int()`
     * truncation toward zero (Double.toInt) — web `app.py:524` verbatim.
     * Median 499 → int(199.6) = 199 → floor 200; median 1750 → exactly
     * 700 (IEEE-754: 0.4 × 1750 rounds to 700.0) → `len < 700`, which
     * cond 3 fails first at len 700.
     */
    fun adaptiveThreshold(medianBodyLength: Int): Int =
        maxOf(
            ExtractPolicy.TRIGGER_FLOOR,
            (ExtractPolicy.TRIGGER_MEDIAN_FRACTION * medianBodyLength).toInt(),
        )
}
