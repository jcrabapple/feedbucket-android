package me.ash.reader.domain.model.extract

import me.ash.reader.domain.model.article.UpdateDetection
import me.ash.reader.domain.model.download.DownloadPolicy

/**
 * E16.2 + E16.3 — quality guards on the SANITIZED extraction result
 * (web-exact; AMENDS S19's wording per E26.2). Pipeline position (E19):
 * the cleaner output is sanitized FIRST, then measured here; rejected
 * results write NOTHING — prior content untouched, no `extractedAt`
 * (D6: a failed extraction never claims success).
 *
 *  - E16.2 fresh (AUTO/MANUAL/PREFETCH of a NOT-yet-extracted article):
 *    accept iff `resultLen > max(currentLen, 120)` — web
 *    `min_len = max(current_len, 120)` with STRICT `>`
 *    (`app.py:581–584`); `currentLen` = [UpdateDetection.plainTextLength]
 *    of `rawDescription` (E5).
 *  - E16.2 re-extract (RE_EXTRACT of an already-extracted article):
 *    accept iff `resultLen > 120` (web `min_len = 120`; a SHORTER-than-
 *    stored clean result is acceptable — junk removal may legitimately
 *    shrink it).
 *  - E16.3 stored-size cap: sanitized result > [DownloadPolicy.TEXT_MAX_BYTES]
 *    (2 MiB, value REUSED not re-decided) → [Verdict.Oversize]; HTML is
 *    never truncated. Measured in UTF-8 bytes (storage cost), checked
 *    before the length rule so an oversize result can never be "accepted".
 *
 * E16.4: `extract_article`'s <120 floor is NOT separately ported — guard
 * E16.2 subsumes it for every mode (`max(x,120) ≥ 120`, strict `>`).
 * E16.1 (challenge + productive-ratio) lives in the X3 coordinator
 * (`DownloadHtmlSanitizer.isChallenge`), not here.
 */
object LengthGuard {

    sealed interface Verdict {
        /** Passed E16.2 and E16.3 — the coordinator may write (E4). */
        data object Accept : Verdict

        /** E16.2 — result not STRICTLY longer than the mode's floor. */
        data object TooShort : Verdict

        /** E16.3 — sanitized result exceeds the 2 MiB stored-text cap. */
        data object Oversize : Verdict
    }

    /**
     * @param sanitizedResult the cleaned extraction AFTER
     *   `DownloadHtmlSanitizer.sanitize` (E19 ordering).
     * @param rawDescription the article's feed-supplied body; its E5
     *   plain-text length is the fresh-extraction `current` baseline.
     *   Ignored when [isReExtract] (web `min_len = 120 if extracted`).
     */
    fun evaluate(
        sanitizedResult: String,
        rawDescription: String,
        isReExtract: Boolean,
    ): Verdict {
        val bytes = sanitizedResult.toByteArray(Charsets.UTF_8).size.toLong()
        if (bytes > DownloadPolicy.TEXT_MAX_BYTES) return Verdict.Oversize
        val resultLen = UpdateDetection.plainTextLength(sanitizedResult)
        val minLen =
            if (isReExtract) {
                ExtractPolicy.RE_EXTRACT_MIN
            } else {
                maxOf(
                    UpdateDetection.plainTextLength(rawDescription),
                    ExtractPolicy.RE_EXTRACT_MIN,
                )
            }
        return if (resultLen > minLen) Verdict.Accept else Verdict.TooShort
    }
}
