package me.ash.reader.domain.model.extract

import me.ash.reader.domain.model.download.DownloadPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * E16.2 + E16.3 — quality-guard verdicts on the SANITIZED result
 * (web-exact, `app.py:578–584`: fresh `min_len = max(current_len, 120)`,
 * re-extract `min_len = 120`, reject when `result <= min_len` — STRICT `>`
 * to accept; 2 MiB stored cap reused from [DownloadPolicy.TEXT_MAX_BYTES],
 * never re-decided). Rejected results write NOTHING (D6) — the verdict IS
 * the contract; the coordinator (X3) maps it to `ExtractOutcome.Failure`.
 *
 * REDCHECK (recorded in the review handoff): strict `>` → `>=` in
 * `LengthGuard.evaluate` flips `freshEqualLengthIsRejected` (and the
 * floor-equal case in `freshFloorIs120`) red.
 */
class LengthGuardTest {

    private fun fresh(result: String, current: String) =
        LengthGuard.evaluate(
            sanitizedResult = result,
            rawDescription = current,
            isReExtract = false,
        )

    private fun reExtract(result: String, current: String = "a".repeat(10_000)) =
        LengthGuard.evaluate(
            sanitizedResult = result,
            rawDescription = current,
            isReExtract = true,
        )

    // ---- E16.2 fresh: result > max(current, 120), STRICT ----

    @Test
    fun freshEqualLengthIsRejected() {
        // Web parity: `<= min_len` rejects — EQUAL is not longer.
        assertEquals(LengthGuard.Verdict.TooShort, fresh("b".repeat(500), "a".repeat(500)))
        // One char longer accepts.
        assertEquals(LengthGuard.Verdict.Accept, fresh("b".repeat(501), "a".repeat(500)))
        // Plan pin: current 5000 → reject 4999 (and equal, and accept 5001).
        assertEquals(LengthGuard.Verdict.TooShort, fresh("b".repeat(4999), "a".repeat(5000)))
        assertEquals(LengthGuard.Verdict.TooShort, fresh("b".repeat(5000), "a".repeat(5000)))
        assertEquals(LengthGuard.Verdict.Accept, fresh("b".repeat(5001), "a".repeat(5000)))
    }

    @Test
    fun freshCurrentZeroAcceptsAt121Only() {
        // max(0, 120) = 120 → accept ≥ 121.
        assertEquals(LengthGuard.Verdict.TooShort, fresh("a".repeat(120), ""))
        assertEquals(LengthGuard.Verdict.Accept, fresh("a".repeat(121), ""))
        assertEquals(LengthGuard.Verdict.TooShort, fresh("", ""))
    }

    @Test
    fun freshFloorIs120EvenForSmallCurrent() {
        // max(50, 120) = 120: the RE_EXTRACT_MIN floor anchors fresh too.
        assertEquals(ExtractPolicy.RE_EXTRACT_MIN, 120)
        assertEquals(LengthGuard.Verdict.TooShort, fresh("b".repeat(120), "a".repeat(50)))
        assertEquals(LengthGuard.Verdict.Accept, fresh("b".repeat(121), "a".repeat(50)))
    }

    @Test
    fun freshMeasuresThroughPlainTextNotMarkup() {
        // E5/E16.2: the guard measures PLAIN TEXT — 500 chars of markup
        // around a 130-char body still passes the 120 floor, and a current
        // of markup-only text doesn't inflate the bar.
        val result = "<p>" + "b".repeat(130) + "</p>"
        assertEquals(LengthGuard.Verdict.Accept, fresh(result, "<div><span>short</span></div>"))
    }

    // ---- E16.2 re-extract: result > 120, shorter-than-stored OK ----

    @Test
    fun reExtractAcceptsShorterResultAboveFloor() {
        // The stored/feed baseline is huge; a SHORT clean result is still
        // accepted (junk removal may legitimately shrink it, web
        // `min_len = 120 if entry["extracted"]`).
        assertEquals(LengthGuard.Verdict.Accept, reExtract("b".repeat(121)))
        assertEquals(LengthGuard.Verdict.Accept, reExtract("b".repeat(200)))
    }

    @Test
    fun reExtractRejectsAtOrBelowFloor() {
        assertEquals(LengthGuard.Verdict.TooShort, reExtract("b".repeat(120)))
        assertEquals(LengthGuard.Verdict.TooShort, reExtract(""))
    }

    // ---- E16.3 stored-size cap (2 MiB, sanitized UTF-8 bytes) ----

    @Test
    fun oversizeResultRejected() {
        val twoMiB = DownloadPolicy.TEXT_MAX_BYTES.toInt() // 2 MiB (S28, reused)
        assertEquals(2 * 1024 * 1024, twoMiB)
        // Strictly ABOVE the cap → Oversize (fresh AND re-extract).
        assertEquals(
            LengthGuard.Verdict.Oversize,
            fresh("a".repeat(twoMiB + 1), ""),
        )
        assertEquals(LengthGuard.Verdict.Oversize, reExtract("a".repeat(twoMiB + 1)))
        // Exactly AT the cap → NOT oversize (length rule passes) — and the
        // HTML is never truncated (E16.3).
        assertEquals(LengthGuard.Verdict.Accept, fresh("a".repeat(twoMiB), ""))
    }

    @Test
    fun oversizeIsMeasuredInUtf8BytesNotChars() {
        // 1.1 M chars × 2 UTF-8 bytes = 2.2 MB > 2 MiB cap, while the char
        // count alone would pass — pins BYTE measurement.
        assertEquals(LengthGuard.Verdict.Oversize, fresh("é".repeat(1_100_000), ""))
    }
}
