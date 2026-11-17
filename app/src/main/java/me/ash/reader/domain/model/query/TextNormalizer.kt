package me.ash.reader.domain.model.query

import java.util.Locale

/**
 * S12a — the SINGLE canonical case-normalization implementation (D7).
 *
 * `normalize(s) = s?.lowercase(Locale.ROOT) ?: ""` — Unicode SIMPLE
 * lowercase. Used identically for shadow-column writes (S11a), query-term
 * prep (S11a) and the in-memory [QueryMatcher].
 *
 * Deliberate deviation from Python `casefold`, pinned by fixtures
 * (TextNormalizerTest): simple lowercase does NOT full-fold — `ß` does not
 * match `ss`, ligatures do not expand. Chosen because SQLite LIKE offers no
 * full-fold mode, so a full-folding contract could not be implemented
 * identically in SQL and Kotlin. Known limitation, documented in the search
 * grammar help.
 */
object TextNormalizer {

    fun normalize(s: String?): String = s?.lowercase(Locale.ROOT) ?: ""
}
