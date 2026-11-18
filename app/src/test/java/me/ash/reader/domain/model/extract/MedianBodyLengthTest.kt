package me.ash.reader.domain.model.extract

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * E7 — median recompute parity, pinned against RECORDED Python
 * `feed_median_body_len` outputs (D14, raw JSON in
 * `build/recon/x2-parity-fixtures.json`, generated read-only from the web
 * venv with `feedbucket.security.plain_text` — exact command recorded in
 * `build/recon/x2_parity_fixtures.py`). Web expression (`db.py:830–836`):
 * sorted plain-text lengths of non-empty bodies → UPPER median
 * `lengths[len(lengths)//2]`; 0 when nothing survives.
 *
 * The ≤100 sample cap itself is enforced by the X1 DAO query
 * (`queryRecentRawDescriptionsForMedian … LIMIT`, pinned by the
 * instrumented `ArticleDaoExtractionTest`); [MedianBodyLength.recompute]
 * additionally clamps defensively when handed more — BOTH pins live here
 * per the plan (`defensiveClampToSampleCap`).
 *
 * REDCHECK (recorded in the review handoff): `size / 2` → `(size - 1) / 2`
 * (lower median) flips `upperMedianParityEvenCounts` red (even_4: 300→200,
 * even_2: 777→55).
 */
class MedianBodyLengthTest {

    private fun bodies(vararg lengths: Int): List<String> =
        lengths.map { "<p>" + "a".repeat(it) + "</p>" }

    @Test
    fun upperMedianParityOddCount() {
        // RECORDED (D14): bodies 100/300/200 → sorted [100,200,300] →
        // Python lengths[3//2=1] = 200.
        assertEquals(200, MedianBodyLength.recompute(bodies(100, 300, 200)))
        // RECORDED `with_duplicates`: [350,350,350,900] → index 2 → 350.
        assertEquals(350, MedianBodyLength.recompute(bodies(350, 350, 350, 900)))
    }

    @Test
    fun upperMedianParityEvenCounts() {
        // RECORDED (D14) `even_4`: plain lengths sorted [100,200,300,400] →
        // UPPER median lengths[4//2=2] = 300 (a LOWER median would give 200
        // — this is the REDCHECK target).
        assertEquals(300, MedianBodyLength.recompute(bodies(100, 200, 300, 400)))
        // RECORDED `even_2`: [55,777] → lengths[1] = 777 (lower would be 55).
        assertEquals(777, MedianBodyLength.recompute(bodies(55, 777)))
        // RECORDED `html_bodies` (mixed real markup — plain_text parity of
        // the E5 tag-strip across BOTH implementations): sorted
        // [11,61,240,485] → index 2 → 240.
        val htmlBodies = listOf(
            "<p>Short note.</p>",
            "<div><p>Word " + "one two three</p><p>More prose here to lengthen the body a bit.</p></div>",
            "<article><h2>Title</h2><p>" + "x".repeat(480) + "</p></article>",
            "<p>" + "y".repeat(240) + "</p>",
        )
        assertEquals(240, MedianBodyLength.recompute(htmlBodies))
    }

    @Test
    fun emptySampleListIsZero() {
        // RECORDED `empty` → 0 (E7: "unknown until the next recompute",
        // safe by E6's 200-char floor).
        assertEquals(0, MedianBodyLength.recompute(emptyList()))
    }

    @Test
    fun zerosAndEmptyStringsAreDropped() {
        // E7: drop zeros. Tag-only/empty/script-only rows strip to length 0
        // via the E5 plain-text and must not drag the median down.
        val samples = listOf(
            "<p>" + "a".repeat(150) + "</p>",
            "",
            "<div></div>",
            "<script>var hidden = 1;</script>",
            "<p>" + "b".repeat(500) + "</p>",
        )
        // Survivors [150,500] → UPPER median index 1 → 500 (a kept zero
        // would give [0,150,500] → 150).
        assertEquals(500, MedianBodyLength.recompute(samples))
    }

    @Test
    fun allZeroSamplesIsZero() {
        assertEquals(0, MedianBodyLength.recompute(listOf("", "<div></div>")))
    }

    @Test
    fun defensiveClampToSampleCap() {
        // The DAO LIMIT 100 is the enforcement point (pinned in the X1
        // instrumented test); recompute must ALSO clamp when handed more:
        // the first MEDIAN_SAMPLE_CAP samples are used, the rest ignored.
        // Samples 1..100 (length i) then 50 × length 1000: clamped sorted
        // lengths [1..100] → index 50 → 51; UNCLAMPED (150 samples) would
        // give index 75 → 76.
        val samples = (1..100).map { "a".repeat(it) } + (1..50).map { "b".repeat(1000) }
        assertEquals(150, samples.size)
        assertEquals(ExtractPolicy.MEDIAN_SAMPLE_CAP, 100)
        assertEquals(51, MedianBodyLength.recompute(samples))
    }
}
