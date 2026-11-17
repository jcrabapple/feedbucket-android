package me.ash.reader.domain.model.article

import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S20a (amended, R7) — refresh update detection, acceptance #4. The full
 * gate truth table for [UpdateDetection.decide], including the THREE-state
 * recency (`publishedAt NULL → recent TRUE`, present-within-7d → TRUE,
 * present-older → FALSE, web db.py:526–537), the snapshot significance
 * gate (>50 stripped chars, db.py:538–541), first-version-only snapshots,
 * extracted rows never clobbered, and the R7 dateless fake-update
 * regression pin (compare keys use `publishedAt`, NEVER `date`).
 *
 * RED protocol: this suite was written FIRST and run against a
 * pass-through `decide` (always Unchanged) — every changed-row test was
 * red (see wave review packet). The dateless fake-update pin additionally
 * REDCHECKs the R7 compare-key contract: pointing the keys at `date`
 * instead of `publishedAt` flips `datelessRowsNeverFakeUpdateAcrossRefreshes`
 * red.
 */
class UpdateDetectionTest {

    private val now = Date(1_800_000_000_000L) // 2027-01-15, arbitrary fixed
    private val recent = Date(now.time - 3 * DAY)
    private val old = Date(now.time - 10 * DAY)

    private fun existing(
        title: String = "Title",
        link: String = "https://example.com/1",
        author: String? = "Author",
        raw: String = "<p>body one</p>",
        short: String = "body one",
        publishedAt: Date? = recent,
        date: Date = publishedAt ?: Date(now.time - 30 * DAY),
        fullContent: String? = null,
        originalContent: String? = null,
        img: String? = null,
    ) = Article(
        id = "a1",
        date = date,
        title = title,
        author = author,
        rawDescription = raw,
        shortDescription = short,
        fullContent = fullContent,
        img = img,
        link = link,
        feedId = "feed1",
        accountId = 1,
        publishedAt = publishedAt,
        originalContent = originalContent,
    )

    private fun incoming(
        title: String = "Title",
        link: String = "https://example.com/1",
        author: String? = "Author",
        raw: String = "<p>body one</p>",
        short: String = "body one",
        publishedAt: Date? = recent,
        img: String? = null,
    ) = existing(
        title = title, link = link, author = author, raw = raw, short = short,
        publishedAt = publishedAt, img = img,
    ).copy(id = "a1-incoming", originalContent = null)

    private fun plan(decision: UpdateDetection.Decision) =
        (decision as UpdateDetection.Decision.Update).plan

    // ---- Compare-key matrices (extracted vs non-extracted) ----

    @Test
    fun equalKeysNonExtractedUnchanged() {
        val d = UpdateDetection.decide(existing(), incoming(), showUpdates = true, now = now)
        assertEquals(UpdateDetection.Decision.Unchanged, d)
    }

    @Test
    fun equalKeysExtractedUnchanged() {
        val d = UpdateDetection.decide(
            existing(fullContent = "<p>extracted</p>"),
            incoming(raw = "<p>feed changed but extracted ignores content keys</p>"),
            showUpdates = true, now = now,
        )
        assertEquals(UpdateDetection.Decision.Unchanged, d)
    }

    @Test
    fun extractedChangedContentKeyTriggersUpdate() {
        val d = UpdateDetection.decide(
            existing(fullContent = "<p>extracted</p>"),
            incoming(title = "New title"),
            showUpdates = true, now = now,
        )
        val p = plan(d)
        // Extracted rows update ONLY title/link/author/publishedAt(+date) —
        // feed-provided content never clobbers the extraction.
        assertEquals("New title", p.title)
        assertEquals("<p>body one</p>", p.rawDescription)
        assertEquals("body one", p.shortDescription)
        assertFalse(p.snapshotWrite)
    }

    @Test
    fun nonExtractedRawChangeTriggersUpdateWithContentFields() {
        val d = UpdateDetection.decide(
            existing(),
            incoming(raw = "<p>body two</p>", short = "body two"),
            showUpdates = true, now = now,
        )
        val p = plan(d)
        assertEquals("<p>body two</p>", p.rawDescription)
        assertEquals("body two", p.shortDescription)
    }

    @Test
    fun publishedAtChangeAloneTriggersUpdateAndMovesDate() {
        val newPub = Date(recent.time + DAY)
        val d = UpdateDetection.decide(
            existing(publishedAt = recent, date = recent),
            incoming(publishedAt = newPub),
            showUpdates = false, now = now,
        )
        val p = plan(d)
        assertEquals(newPub, p.publishedAt)
        assertEquals(newPub, p.date)
        // showUpdates off → no updated-queue membership, but updateAt always bumps.
        assertFalse(p.markUpdated)
        assertNull(p.contentChangedAt)
        assertEquals(now, p.updateAt)
    }

    @Test
    fun compareKeysUsePublishedAtNeverDate() {
        // Identical publisher date, wildly different display `date` (as
        // upstream re-mints for dateless rows): NOT changed.
        val d = UpdateDetection.decide(
            existing(publishedAt = recent, date = Date(now.time - 90 * DAY)),
            incoming(publishedAt = recent),
            showUpdates = true, now = now,
        )
        assertEquals(UpdateDetection.Decision.Unchanged, d)
    }

    // ---- R7 dateless fake-update regression pin ----

    @Test
    fun datelessRowsNeverFakeUpdateAcrossRefreshes() {
        // First sync: no publisher date → date = sync time at insert.
        val firstSync = Date(now.time - 2 * DAY)
        val inserted = existing(publishedAt = null, date = firstSync, raw = "<p>body</p>", short = "body")
        // Second sync, identical feed data, no publisher date: upstream
        // would mint a NEW `date`; the S20a compare keys ignore `date`
        // entirely → row untouched, no updateAt bump, no snapshot.
        val secondSync = Date(now.time - DAY)
        val d = UpdateDetection.decide(
            inserted,
            incoming(publishedAt = null, raw = "<p>body</p>", short = "body"),
            showUpdates = true, now = secondSync,
        )
        assertEquals(
            "dateless row must not fake-update when only sync time moved",
            UpdateDetection.Decision.Unchanged, d,
        )
    }

    @Test
    fun datelessChangedRowKeepsFrozenDate() {
        val storedDate = Date(now.time - 30 * DAY)
        val d = UpdateDetection.decide(
            existing(publishedAt = null, date = storedDate),
            incoming(publishedAt = null, title = "Edited title"),
            showUpdates = true, now = now,
        )
        val p = plan(d)
        assertNull(p.publishedAt)
        assertEquals("date frozen for dateless rows — no manufactured freshness", storedDate, p.date)
    }

    @Test
    fun incomingNullPublishedAtKeepsExistingStoredDate() {
        // Feed stops supplying a date it once supplied: publishedAt is
        // written (web writes published=NULL), but `date` is NOT touched.
        val storedDate = Date(recent.time - 5 * DAY)
        val d = UpdateDetection.decide(
            existing(publishedAt = recent, date = storedDate),
            incoming(publishedAt = null),
            showUpdates = true, now = now,
        )
        val p = plan(d)
        assertNull(p.publishedAt)
        assertEquals(storedDate, p.date)
    }

    // ---- Snapshot gate truth table ----
    //
    // Gate: showUpdates AND !extracted AND originalContent==null AND
    // recent AND significant (>50 plain-text chars delta, content differs).
    // Fixture: old/new bodies chosen so the plain-text delta is 60 chars (> 50).

    private val oldBody = "<p>${"o".repeat(200)}</p>"
    private val newBody = "<p>${"o".repeat(60)}${"n".repeat(80)}</p>" // plain delta = 60 > 50

    private fun gateDecision(
        showUpdates: Boolean = true,
        extracted: Boolean = false,
        hasOriginal: Boolean = false,
        publishedAt: Date? = recent,
        newRaw: String = newBody,
        title: String = "Title",
        threshold: Int = UpdateDetection.SIGNIFICANCE_THRESHOLD,
    ) = UpdateDetection.decide(
        existing(
            publishedAt = publishedAt,
            raw = oldBody, short = "old",
            fullContent = if (extracted) "<p>extracted</p>" else null,
            originalContent = if (hasOriginal) "<p>first version</p>" else null,
        ),
        incoming(title = title, raw = newRaw, short = "new"),
        showUpdates = showUpdates, now = now,
        significanceThreshold = threshold,
    )

    @Test
    fun snapshotGateAllOnTakesSnapshot() {
        assertTrue(plan(gateDecision()).snapshotWrite)
    }

    @Test
    fun snapshotGateShowUpdatesOffBlocks() {
        assertFalse(plan(gateDecision(showUpdates = false)).snapshotWrite)
    }

    @Test
    fun snapshotGateExtractedBlocks() {
        // Extracted row with a changed title (content keys don't apply).
        assertFalse(plan(gateDecision(extracted = true, title = "Edited title")).snapshotWrite)
    }

    @Test
    fun snapshotGateFirstVersionOnly() {
        assertFalse(plan(gateDecision(hasOriginal = true)).snapshotWrite)
    }

    @Test
    fun snapshotGateRecentWithinSevenDays() {
        assertTrue(plan(gateDecision(publishedAt = Date(now.time - 7 * DAY + 1000))).snapshotWrite)
    }

    @Test
    fun snapshotGateOldPublishedBlocks() {
        assertFalse(plan(gateDecision(publishedAt = old)).snapshotWrite)
    }

    @Test
    fun snapshotGateExactlySevenDaysBoundary() {
        // published == now - 7d exactly: web `published >= now - 7d` → recent.
        assertTrue(plan(gateDecision(publishedAt = Date(now.time - 7 * DAY))).snapshotWrite)
    }

    @Test
    fun snapshotGateDatelessIsRecent() {
        // db.py:527 parity: publishedAt NULL → recent = TRUE.
        assertTrue(plan(gateDecision(publishedAt = null)).snapshotWrite)
    }

    @Test
    fun snapshotGateInsignificantDeltaBlocks() {
        // Delta <= 50 plain chars: not significant.
        val barely = "<p>${"o".repeat(200)}x</p>" // +1 char, differs
        assertFalse(plan(gateDecision(newRaw = barely)).snapshotWrite)
    }

    @Test
    fun snapshotGateExactlyFiftyDeltaBlocks() {
        // Web threshold is STRICTLY greater than 50 (db.py:538–541).
        val exactly = "<p>${"o".repeat(130)}${"n".repeat(20)}</p>" // delta = 50
        assertFalse(plan(gateDecision(newRaw = exactly)).snapshotWrite)
    }

    @Test
    fun snapshotGateFiftyOneDeltaTakes() {
        val over = "<p>${"o".repeat(130)}${"n".repeat(19)}</p>" // length 149, delta = 51
        assertTrue(plan(gateDecision(newRaw = over)).snapshotWrite)
    }

    @Test
    fun snapshotGateIdenticalContentBlocks() {
        // Content equal → significance false even with huge key change.
        assertFalse(plan(gateDecision(newRaw = oldBody)).snapshotWrite)
    }

    @Test
    fun romeInvalidDateIsRecentDeviation() {
        // Bounded deviation (SPEC S20a/R7): web's `ValueError → recent=False`
        // branch (present-but-unparseable date STRING) is unreachable on
        // Android — Rome surfaces unparseable dates as null, and
        // invalid ≡ missing → recent. This test pins the DEVIATION NOTE:
        // the three-state recency has no "present-but-invalid" fourth state.
        assertTrue(
            "Rome-invalid ≡ missing → recent (recorded deviation, not silently dropped)",
            UpdateDetection.isRecent(null, now),
        )
    }

    @Test
    fun isRecentThreeStates() {
        assertTrue(UpdateDetection.isRecent(null, now))
        assertTrue(UpdateDetection.isRecent(recent, now))
        assertFalse(UpdateDetection.isRecent(old, now))
        assertTrue(
            "future dates are future-guarded to NULL upstream; a raw future date still passes web parity",
            UpdateDetection.isRecent(Date(now.time + DAY), now),
        )
    }

    // ---- Updated-queue flags ----

    @Test
    fun isUpdatedAndContentChangedAtOnlyWhenShowUpdates() {
        val on = plan(gateDecision(showUpdates = true, newRaw = newBody))
        assertTrue(on.markUpdated)
        assertEquals(now, on.contentChangedAt)
        val off = plan(gateDecision(showUpdates = false, newRaw = newBody))
        assertFalse(off.markUpdated)
        assertNull(off.contentChangedAt)
        assertEquals(now, off.updateAt)
    }

    @Test
    fun constantsMatchWeb() {
        assertEquals(7L, UpdateDetection.RECENCY_WINDOW_DAYS)
        assertEquals(50, UpdateDetection.SIGNIFICANCE_THRESHOLD)
    }

    companion object {
        private const val DAY = 24 * 60 * 60 * 1000L
    }
}
