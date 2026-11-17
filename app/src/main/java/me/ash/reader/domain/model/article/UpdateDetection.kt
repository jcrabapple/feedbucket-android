package me.ash.reader.domain.model.article

import java.util.Date
import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * S20a (amended, R7) — refresh update-detection decision logic, the pure
 * port of the web `store_entries` state machine
 * (`feedbucket/db.py:519–575`). The DAO consumes [decide] inside the
 * per-feed sync transaction; this object carries NO DB access so the full
 * gate truth table is unit-testable (acceptance #4).
 *
 * Compare keys use `publishedAt` — NEVER `date` (R7): upstream re-mints
 * `date` from sync time for dateless entries on every refresh, so a
 * `date`-based key would flag every refresh of every dateless article as
 * "changed" (fake updates, snapshot churn, updated-queue spam).
 *
 * Bounded deviation (recorded in SPEC S20a / R7): web's
 * `ValueError → recent = False` branch (present-but-unparseable date
 * string) is UNREACHABLE on Android because Rome surfaces unparseable
 * dates as null — invalid ≡ missing → recent. Pinned by
 * `UpdateDetectionTest.romeInvalidDateIsRecentDeviation`, not silently
 * dropped.
 */
object UpdateDetection {

    /** Snapshot recency window (web db.py:535, `timedelta(days=7)`). */
    const val RECENCY_WINDOW_DAYS: Long = 7

    /** Snapshot significance threshold: plain-text delta must exceed this. */
    const val SIGNIFICANCE_THRESHOLD: Int = 50

    private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L

    /** What the S20a update path writes for a CHANGED row. */
    data class UpdatePlan(
        val title: String,
        val link: String,
        val author: String?,
        /** Existing value when the row is extracted (never clobbered). */
        val rawDescription: String,
        /** Existing value when the row is extracted (never clobbered). */
        val shortDescription: String,
        /** Existing value when the row is extracted (never clobbered). */
        val img: String?,
        val publishedAt: Date?,
        /**
         * Recomputed ONLY when the feed supplies a NEW non-null
         * `publishedAt`; otherwise the stored `date` is frozen (R7 — no
         * manufactured freshness for dateless rows).
         */
        val date: Date,
        /** True → write `originalContent` = OLD rawDescription (first version only). */
        val snapshotWrite: Boolean,
        /** Web parity: `isUpdated`/`contentChangedAt` are set ONLY when the feed shows updates. */
        val markUpdated: Boolean,
        val contentChangedAt: Date?,
        /** `updateAt = now` on EVERY changed row, even when showUpdates is off. */
        val updateAt: Date,
    )

    sealed interface Decision {
        /** Compare keys equal — row untouched, no `updateAt` bump, no snapshot. */
        data object Unchanged : Decision

        /** Compare keys differ — apply [plan] then S17a update effects. */
        data class Update(val plan: UpdatePlan) : Decision
    }

    /**
     * THREE-state recency (web-exact, db.py:526–537): `publishedAt == NULL
     * → recent = TRUE` (web assigns `recent = True` and only overrides it
     * inside `if existing["published"] is not None`; a missing publisher
     * date counts as recent). Present → within [windowDays] of [now].
     * (Rome future-guards future dates to NULL upstream, matching web
     * where a future date trivially satisfies `>= now - 7d`.)
     */
    fun isRecent(
        publishedAt: Date?,
        now: Date,
        windowDays: Long = RECENCY_WINDOW_DAYS,
    ): Boolean {
        if (publishedAt == null) return true
        return publishedAt.time >= now.time - windowDays * MILLIS_PER_DAY
    }

    /**
     * Web-exact significance (db.py:538–541): content actually differs
     * AND the plain-text length delta exceeds [threshold]. Plain text
     * mirrors web `plain_text` (security.py:168 — nh3 tag-strip) with
     * Jsoup, consistent with the app's existing HTML handling.
     */
    fun isSignificant(
        oldContent: String,
        newContent: String,
        threshold: Int = SIGNIFICANCE_THRESHOLD,
    ): Boolean {
        if (oldContent == newContent) return false
        return kotlin.math.abs(plainTextLength(newContent) - plainTextLength(oldContent)) > threshold
    }

    /**
     * S20a decision for one matched pair ([existing] row, [incoming] feed
     * article). [incoming] is the Rome-built candidate whose `publishedAt`
     * is the future-guarded publisher date (never re-minted). [showUpdates]
     * is the feed's `showUpdates` flag.
     */
    fun decide(
        existing: Article,
        incoming: Article,
        showUpdates: Boolean,
        now: Date,
        significanceThreshold: Int = SIGNIFICANCE_THRESHOLD,
    ): Decision {
        val extracted = existing.fullContent != null
        val keysEqual =
            if (extracted) {
                listOf(existing.title, existing.link, existing.author, existing.publishedAt) ==
                    listOf(incoming.title, incoming.link, incoming.author, incoming.publishedAt)
            } else {
                listOf(
                    existing.title, existing.link, existing.author,
                    existing.shortDescription, existing.rawDescription, existing.publishedAt,
                ) ==
                    listOf(
                        incoming.title, incoming.link, incoming.author,
                        incoming.shortDescription, incoming.rawDescription, incoming.publishedAt,
                    )
            }
        if (keysEqual) return Decision.Unchanged

        // Snapshot gate (web db.py:526–544): first version only, behind
        // showUpdates × !extracted × originalContent-null × recency ×
        // significance. Captures the OLD content BEFORE the field update.
        val snapshotWrite =
            showUpdates && !extracted && existing.originalContent == null &&
                isRecent(existing.publishedAt, now) &&
                isSignificant(existing.rawDescription, incoming.rawDescription, significanceThreshold)

        // R7: `date` recomputed ONLY when the feed supplies a NEW non-null
        // publisher date; dateless rows keep their stored `date` frozen.
        val publishedAtChanged =
            incoming.publishedAt != null && incoming.publishedAt != existing.publishedAt
        val newDate = if (publishedAtChanged) incoming.publishedAt!! else existing.date

        return Decision.Update(
            UpdatePlan(
                title = incoming.title,
                link = incoming.link,
                author = incoming.author,
                rawDescription = if (extracted) existing.rawDescription else incoming.rawDescription,
                shortDescription = if (extracted) existing.shortDescription else incoming.shortDescription,
                img = if (extracted) existing.img else incoming.img,
                publishedAt = incoming.publishedAt,
                date = newDate,
                snapshotWrite = snapshotWrite,
                markUpdated = showUpdates,
                contentChangedAt = if (showUpdates) now else null,
                updateAt = now,
            )
        )
    }

    /**
     * Plain-text mirror of web `plain_text` (security.py:168,
     * `nh3.clean(value, tags=set()).strip()`), pinned against recorded
     * Python ground truth (`UpdateDetectionTest.plainTextMatchesRecordedNh3Fixtures`,
     * raw JSON in build/recon/e4-plaintext-fixtures.json):
     *
     *  - tags are REMOVED with their text kept (verbatim — no whitespace
     *    collapsing, NO separator inserted between adjacent blocks), except
     *    script/style whose content ammonia always drops, and comments.
     *  - text is re-escaped as an HTML fragment: `&amp;`/`&lt;`/`&gt;`/
     *    `&nbsp;` stay literal entity refs while `&quot;`/`&#39;` decode to
     *    quotes — exactly Jsoup's base-mode text-node serialization.
     *  - ends are Python `str.strip()` (Unicode whitespace, incl. NBSP and
     *    U+001C–U+001F), not Java's narrower trim.
     */
    fun plainText(html: String): String {
        val doc = Jsoup.parse(html)
        doc.outputSettings().prettyPrint(false)
        // ammonia removes script/style WITH their content even when no tags
        // are allowed at all.
        doc.select("script,style").remove()
        val out = StringBuilder()
        fun walk(node: Node) {
            when (node) {
                is TextNode -> out.append(node.outerHtml())
                is Comment -> Unit
                is Element -> node.childNodes().forEach(::walk)
                else -> Unit
            }
        }
        walk(doc.body() ?: doc)
        return pythonStrip(out.toString())
    }

    /** Plain-text length mirror of web `plain_text` (nh3 tag-strip). */
    fun plainTextLength(html: String): Int = plainText(html).length

    /** Python `str.strip()`: Unicode whitespace, not just <= U+0020. */
    private fun pythonStrip(value: String): String =
        value.trim { it <= ' ' || Character.isSpaceChar(it) }
}
