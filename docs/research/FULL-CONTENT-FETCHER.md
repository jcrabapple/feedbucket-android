# Full-content fetcher — research and build decision (2026-11-18)

Requested: (1) full-content fetching for feeds that truncate content, (2) a paywall
bypasser, possibly via unwall.app or periscope.corsfix.com.

## Decision

- BUILD: upgrade the existing full-content extraction into an adaptive, Feedbin-style
  system (auto-detect teasers, manual re-extract, quality guards). This is standard
  reader functionality (Feedbin, Reeder, Read You upstream all do it) and covers the
  actual problem: feeds that truncate content which is publicly readable on the site.
- DO NOT BUILD: paywall-circumvention integration (unwall.app, Periscope/Ladder,
  12ft-style proxies). Rationale below. This is an orchestrator decision; Jason can
  override the product direction, but the circumvention pipeline itself is out of
  scope for this project.

## What the fork already has (verified at 68b534d5)

- `Feed.isFullContent` per-feed flag + group-level toggle
  (`FeedDao.updateIsFullContentByGroupId`, `AbstractRssRepository.groupParseFullContent`).
- `RssHelper.parseFullContent(link, title)` (RssHelper.kt:150): OkHttp GET of the
  article URL, charset detection (header + meta), `Readability.parseToElement`
  (Readability4JExtended with a custom RYArticleGrabberExtended), removes an h1 that
  duplicates the entry title, returns article HTML. Throws IOException on failure.
- `ReaderCacheHelper`: per-article disk cache of extracted HTML;
  `readOrFetchFullContent`, `checkOrFetchFullContent`.
- `ReaderWorker` prefetches full content at sync time for flagged feeds
  (semaphore-bounded); `ArticleListReaderViewModel` (~:558) reads-or-fetches on open.
- Offline-download sanitizer exists (`DownloadHtmlSanitizer`, Wave F) and the
  update-diff system keeps `originalContent` snapshots (Wave E).

## Gaps vs the proven FeedBucket web extract system

The web app's extract system (feedbucket/extract.py, shipped 2026-11-14) is the
reference design. Gaps in the Android fork:

1. No adaptive teaser detection. Web auto-extracts when entry text is
   < max(200, 0.4 x feed median body length) AND < 700 chars. Empty content alone is
   NOT a signal (many feeds put full posts in summary). Android only extracts when
   the user flags the feed manually.
2. No manual per-article extract/re-extract action in the reader. Web has an
   always-visible extract control that works on any entry.
3. Cleanup rules not ported (web lessons, Wolfram bug): drop head/title subtrees,
   unwrap html/body, strip YAML-frontmatter paragraphs, drop first h1/h2 matching the
   title incl. site-suffix variants (Android does exact h1 match only), drop TOC
   chrome and page-level time.date, void-tag stack safety. Readability4J covers much
   of this but not the site-chrome cases.
4. Failure model: web is silent no-op (keep feed content on any failure). Android
   throws -> Result.failure; verify the reader falls back cleanly and never shows an
   error wall for a failed extraction.
5. Length guard: re-extraction must not clobber good feed-supplied content with a
   shorter result (web: min 120 chars when already extracted; guard protects
   feed-supplied content only).
6. Sanitization: extracted HTML must pass the same sanitizer as downloaded content
   before rendering/storage.
7. Diff protection: extracted entries must never be diffed or clobbered by feed
   refresh (web rule; Android Wave E snapshots need the same exclusion).
8. Fetch safety: public-IP guard on the article URL (web security.require_public_url
   equivalent), bounded timeouts, no redirect into private ranges.

## Proposed shape (for the spec card)

- Keep Readability4J as the extractor; add an `ExtractCoordinator` mirroring web's
  `_run_extraction`: one shared path for AUTO (on open/sync, adaptive trigger) and
  MANUAL (reader action, any entry, re-extract allowed).
- Per-feed median body length tracked incrementally (cheap running estimate).
- Store extracted content separately from feed content (existing fullContent column
  + extractedAt already exist); never overwrite originalContent snapshots.
- Reader UI: extract action in the article toolbar (icon-only, consistent with the
  existing toolbar grammar), busy/failed states quiet.
- Settings: global auto-extract toggle (default ON like web), per-feed override later.
- Tests: teaser-trigger truth table, cleanup fixtures (Wolfram-style frontmatter,
  TOC chrome, title-suffix h1), length guard, failure no-op, sanitizer pass-through,
  diff exclusion. REDCHECK per behavior.

## The two suggested services (researched 2026-11-18)

- unwall.app: explicit paywall-removal service ("Read Full Articles Without
  Paywalls", counters for "walls cleared" / "dollars saved").
- periscope.corsfix.com: open-source hosted version of Ladder (everywall/ladder),
  self-described alternative to 12ft.io. Ladder's per-domain rulesets exist to defeat
  paywalls: site-specific request headers (crawler-UA spoofing), overlay removal,
  alternate-version fetching. Fetches route through the Corsfix proxy.

How this class of tool works: (a) spoof search-crawler identity against soft paywalls
that trust crawlers, (b) serve archive snapshots (archive.today etc.), (c) strip
client-side paywall overlays, (d) fetch AMP/JSON-LD versions. Hard paywalls gate
content server-side: no unauthenticated fetch returns the article, so only archived
or leaked copies "work".

Why not integrate:

1. Legal exposure: circumventing access controls on copyrighted works implicates
   DMCA-style anti-circumvention rules and publisher ToS. Shipping it in a
   distributed app is materially different from a personal browser bookmark.
2. Fragile and wrong-content risk: crawler spoofing and archives break constantly;
   archives serve stale or unrelated snapshots. An RSS reader silently substituting
   a stale archive copy for today's article is a correctness bug, not a feature.
3. Privacy: routing article fetches through a third-party proxy hands that service
   the user's full reading list.
4. It does not solve the stated problem better: truncated-but-public feeds are fully
   solved by extraction. For genuinely paywalled sources the honest paths are the
   publisher's own full-text feed (some provide it) or reading on the publisher's
   site with a subscription; "View original" already exists.

## Sources

- Repo inspection at 68b534d5 (files above).
- FeedBucket web extract design: feedbucket skill + ~/projects/feedbucket
  (extract.py, security.require_public_url), shipped and battle-tested 2026-11-14.
- https://unwall.app/ (landing page, fetched 2026-11-18).
- https://github.com/corsfix/periscope -> reynaldichernando/periscope README
  ("Hosted version of ladder and alternative to 12ft.io", fetched 2026-11-18).
- https://github.com/everywall/ladder (ruleset system referenced by Periscope).
