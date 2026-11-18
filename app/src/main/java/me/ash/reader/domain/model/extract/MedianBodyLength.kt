package me.ash.reader.domain.model.extract

import me.ash.reader.domain.model.article.UpdateDetection

/**
 * E7 — per-feed median body-length recompute (single implementation). The
 * X1 DAO query (`ArticleDao.queryRecentRawDescriptionsForMedian`) supplies
 * the ≤[ExtractPolicy.MEDIAN_SAMPLE_CAP] most recent non-empty
 * `rawDescription` rows (`ORDER BY rowid DESC LIMIT 100` — the cap is
 * pinned there by `ArticleDaoExtractionTest`); this object turns the
 * samples into the stored estimate:
 *
 *  - per-row length = [UpdateDetection.plainTextLength] (E5 — the single
 *    canonical nh3-parity tag-strip, NEVER a second plain-text impl);
 *  - zero-length results are dropped (web keeps only non-empty bodies,
 *    `db.py:830–833`);
 *  - UPPER median `lengths[size / 2]` of the sorted list — exact parity
 *    with web `feed_median_body_len` (`db.py:834–836`,
 *    `lengths[len(lengths)//2]`), pinned against recorded Python outputs;
 *  - 0 when no samples survive ("unknown until the next recompute",
 *    safe by E6's 200-char floor).
 *
 * The defensive [ExtractPolicy.MEDIAN_SAMPLE_CAP] clamp mirrors the DAO
 * `LIMIT`: the query is the enforcement point, but a caller handing more
 * samples still gets the bounded recompute (both pinned in
 * `MedianBodyLengthTest`). Recorded deviation (E7): web scans ALL entries
 * server-side; Android bounds cost inside a mobile sync transaction.
 */
object MedianBodyLength {

    fun recompute(samples: List<String>): Int {
        val lengths = samples
            .take(ExtractPolicy.MEDIAN_SAMPLE_CAP)
            .map { UpdateDetection.plainTextLength(it) }
            .filter { it > 0 }
            .sorted()
        if (lengths.isEmpty()) return 0
        return lengths[lengths.size / 2] // UPPER median (web len//2 parity)
    }
}
