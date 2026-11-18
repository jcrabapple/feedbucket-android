package me.ash.reader.domain.model.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E5/E6 — adaptive teaser trigger truth table, pinned against RECORDED
 * Python ground truth (D14, raw JSON in `build/recon/x2-parity-fixtures.json`,
 * generated read-only from the web venv — exact command recorded in
 * `build/recon/x2_parity_fixtures.py`): web `_can_fetch_full`
 * (`feedbucket/app.py:511–524`) evaluated as `length < 700 AND length <
 * max(200, int(0.4 * median))` for the full len × median matrix.
 *
 * Median 501 is an ADDITIONAL boundary pin beyond the plan's matrix: for
 * every other listed median the `max(200, …)` floor or an exact IEEE-754
 * product masks int-vs-ceil rounding (0.4×499 = 199.6 → floor-masked;
 * 0.4×1750 = 700.0 exactly), so ONLY a median like 501 (0.4×501 = 200.4 →
 * int 200, ceil 201) makes the mandated ceil REDCHECK observable at
 * len = 200. Recorded: `int_trunc(200.4) = 200`, threshold 200.
 *
 * REDCHECKs (recorded in the review handoff):
 *  - `.toInt()` → `ceil(...).toInt()`: the len=200/median=501 row and the
 *    `adaptiveThreshold(501) == 200` pin go red (ceil → 201 → candidate).
 *  - drop condition 2 (`fullContent != null → false`):
 *    `alreadyExtractedNeverTriggers` goes red.
 */
class TeaserTriggerTest {

    private val link = "https://example.com/post"

    private fun candidate(
        len: Int,
        median: Int,
        link: String? = this.link,
        fullContent: String? = null,
    ): Boolean = TeaserTrigger.isTeaserCandidate(
        link = link,
        fullContent = fullContent,
        // E5 body text: plain 'a' × len has plainTextLength == len (no tags,
        // no entities, no whitespace ends — deterministic for the matrix).
        rawDescription = "a".repeat(len),
        medianBodyLength = median,
    )

    // ---- E6 truth table: recorded Python `_can_fetch_full` parity ----

    @Test
    fun truthTableMatchesRecordedPythonMatrix() {
        // RECORDED PYTHON GROUND TRUTH (D14): candidate = (len < 700) &&
        // (len < max(200, int(0.4*median))); thresholds per median:
        // 0→200, 499→200, 500→200, 501→200, 1000→400, 1750→700, 2500→1000.
        val lens = listOf(0, 120, 199, 200, 201, 500, 699, 700, 701)
        val expectedByMedian = mapOf(
            // floor-200 rows (median 0/499/500/501 → threshold 200)
            0 to listOf(true, true, true, false, false, false, false, false, false),
            499 to listOf(true, true, true, false, false, false, false, false, false),
            500 to listOf(true, true, true, false, false, false, false, false, false),
            501 to listOf(true, true, true, false, false, false, false, false, false),
            // threshold 400
            1000 to listOf(true, true, true, true, true, false, false, false, false),
            // threshold exactly 700 → cond 3 (`len < 700`) fails first at 700
            1750 to listOf(true, true, true, true, true, true, true, false, false),
            // threshold 1000 → cond 3 dominates from 700 on
            2500 to listOf(true, true, true, true, true, true, true, false, false),
        )
        var rows = 0
        for ((median, expected) in expectedByMedian) {
            for ((i, len) in lens.withIndex()) {
                assertEquals(
                    "E6 matrix len=$len median=$median (recorded Python parity)",
                    expected[i], candidate(len, median),
                )
                rows++
            }
        }
        assertEquals("full matrix covered (9 lens × 7 medians)", 63, rows)
    }

    @Test
    fun adaptiveThresholdMatchesRecordedPythonIntTruncation() {
        // RECORDED (D14, `trigger_thresholds`): max(200, int(0.4×median)).
        assertEquals(200, TeaserTrigger.adaptiveThreshold(0))
        assertEquals("int(199.6)=199 → floor 200", 200, TeaserTrigger.adaptiveThreshold(499))
        assertEquals("0.4×500 = 200.0 exactly", 200, TeaserTrigger.adaptiveThreshold(500))
        assertEquals("int(200.4)=200 — ceil would give 201 (REDCHECK pin)",
            200, TeaserTrigger.adaptiveThreshold(501))
        assertEquals(400, TeaserTrigger.adaptiveThreshold(1000))
        assertEquals("IEEE-754: 0.4×1750 rounds to 700.0 exactly",
            700, TeaserTrigger.adaptiveThreshold(1750))
        assertEquals(1000, TeaserTrigger.adaptiveThreshold(2500))
    }

    @Test
    fun median1750CeilingFailsFirstAtLen700() {
        // Plan boundary pin: threshold is exactly 700, so `len < 700`
        // (cond 3) fails first — len 700 is NOT a candidate even though
        // cond 4 sits at the same value; 699 still is.
        assertFalse(candidate(700, 1750))
        assertTrue(candidate(699, 1750))
        // Cond 3 dominates even when cond 4's threshold is higher.
        assertFalse(candidate(700, 2500))
        assertFalse(candidate(701, 2500))
    }

    // ---- E6 conditions 1 & 2 ----

    @Test
    fun blankLinkRejects() {
        assertFalse(candidate(50, 1000, link = null))
        assertFalse(candidate(50, 1000, link = ""))
        assertFalse(candidate(50, 1000, link = "   "))
    }

    @Test
    fun alreadyExtractedNeverTriggers() {
        // E6 cond 2 — AUTO never re-extracts (E12: MANUAL-only). REDCHECK
        // target: dropping the fullContent check flips this red.
        assertFalse(candidate(50, 1000, fullContent = "<p>stored extraction</p>"))
        assertFalse(candidate(0, 0, fullContent = ""))
    }

    // ---- E6: no empty-body special case ----

    @Test
    fun emptyBodyWithLargeMedianIsCandidate() {
        // E6: len=0 satisfies cond 3 and cond 4 via the floor — genuinely
        // empty entries ARE candidates (web parity; E16 guards storage).
        assertTrue(candidate(0, 10_000))
        assertTrue(candidate(0, 0))
        // A rawDescription that strips to nothing behaves identically.
        assertTrue(
            TeaserTrigger.isTeaserCandidate(
                link = link, fullContent = null,
                rawDescription = "<div></div>", medianBodyLength = 5_000,
            )
        )
    }

    @Test
    fun bodyTextIsMeasuredThroughUpdateDetectionPlainText() {
        // E5 — tags do not count toward the trigger length: 600 chars of
        // markup around a 50-char body is a 50-length teaser, not a 600+
        // non-candidate.
        val tagged = "<p>" + "<b>x</b>".repeat(100) + "</p>" // plain length 100
        assertTrue(
            TeaserTrigger.isTeaserCandidate(
                link = link, fullContent = null,
                rawDescription = tagged, medianBodyLength = 1000,
            )
        )
        assertFalse(
            TeaserTrigger.isTeaserCandidate(
                link = link, fullContent = null,
                rawDescription = "<p>" + "a".repeat(700) + "</p>",
                medianBodyLength = 1000,
            )
        )
    }
}
