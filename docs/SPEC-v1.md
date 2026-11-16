# FeedBucket Android — SPEC v1 (binding behavioral & data acceptance specification)

Status: v1, authored by specifier card t_2dfd27f0 on 2026-11-16.
Authority order: `docs/APPROVED-DIRECTION.md` (D1–D17, user-approved) > this spec > `docs/plans/IMPLEMENTATION-v1.md` > research docs (`docs/research/ANDROID-BASELINE.md`, including its committed corrections f0533707 / 95b1c72c). Every binding call below is a numbered decision (S#). Implementation cards cite `S#`; they must not re-decide it. Where this spec sets a value that upstream ReadYou or FeedBucket web does not have, the value is a DECISION, not a claim about existing code; claims about existing code carry file:line evidence gathered on 2026-11-16 at Android commit `95b1c72c` (upstream source `d2b979cc`) and web repo `/home/jason/projects/feedbucket` (read-only, live schema inspected via `sqlite3 'file:...?mode=ro'`).

## 0. Scope

In scope (D1, D5–D12, D17): standalone no-account local RSS reader on the ReadYou foundation; saved searches, rules, mutes with previews and apply-to-existing; recently read; updated-article snapshots and diffs; durable list/reading positions; bounded durable full-text+image downloads; offline FTS search; OPML; separate full-state FeedBucket migration import; Material 3 Expressive conformance gate.

Out of scope (D1, D13, D14, D15): server/API/AI/podcast/newsletter/social features, new sync services, retiring the live web service, physical-device use, destructive deduplication. Upstream service providers (Fever/Google Reader) are preserved where practical but never onboarding requirements (D1); all S# behavior below is specified for the LOCAL account type.

## 1. Verified source facts (evidence base)

Android (`/home/jason/projects/feedbucket-android`, paths under `app/src/main/java/me/ash/reader/`):

- Room `AndroidDatabase` version 7, identityHash `f5342dd1355611f8e4d6c980d85ba657`; exported schemas at `app/schemas/me.ash.reader.infrastructure.db.AndroidDatabase/{5,6,7}.json`. Tables: `account`, `feed`, `article`, `group`, `archived_article` (columns enumerated in 7.json; reproduced in §2).
- `article.id` is TEXT and generated RANDOM per insert: `id = accountId.spacerDollar(UUID.randomUUID().toString())` (`infrastructure/rss/RssHelper.kt:220`). Article identity/dedupe is `(feedId, accountId, link)` via `ArticleDao.insertListIfNotExist` (`domain/repository/ArticleDao.kt:908–919`): an existing link is SKIPPED ENTIRELY — upstream has no publisher-update detection.
- `Article.date` = `publishedDate ?: updatedDate` (if not future) else sync time; `Article.updateAt` = sync time (`RssHelper.buildArticleFromSyndEntry`, RssHelper.kt:198–235). `rawDescription` = feed `content ?: description`; `shortDescription` = plain text of `desc ?: content` truncated to 280 chars; `fullContent` carries a `@Deprecated` annotation in `domain/model/article/Article.kt` but is still a live search column.
- Search is `LIKE '%' || :text || '%'` over `title`, `shortDescription`, `fullContent` in many `ArticleDao` query variants (e.g. ArticleDao.kt:97–99, 124–126, 150–152, 171–173, 196–198, 220–222, 240–242). No FTS anywhere (W0-verified). No `%`/`_` escaping.
- Full-text extraction: `RssHelper.parseFullContent(link, title)` (RssHelper.kt:116) fetches via OkHttp, parses with readability4j (`infrastructure/html/Readability.kt`), removes a leading `h1` equal to the title; result written to EVICTABLE cache `cacheDir/readability/<accountId>/<sha256(articleId)>.html` by `infrastructure/rss/ReaderCacheHelper.kt` — not a durable store (D9 gap).
- Sync: `domain/service/LocalRssService.kt` — `supervisorScope` + per-feed `async(Dispatchers.IO)` under `Semaphore(16)`, per-feed failure isolated (`runCatching` → `Result.retry()` for the whole sync only if the scope fails); archived-link filtering against `archived_article`. `domain/service/SyncWorker.kt` chains `SyncWorker → ReaderWorker → WidgetUpdateWorker` with `ExistingWorkPolicy.KEEP` for the chain, `REPLACE` for one-time enqueues, `ExistingPeriodicWorkPolicy.UPDATE` for periodic; Wi-Fi/charging constraints come from `account.syncOnlyOnWiFi` / `syncOnlyWhenCharging`. `ReaderWorker` extracts full content for unread articles under `Semaphore(2)`, `Result.retry()` if any fetch fails.
- Feed entity has `isFullContent` (per-feed auto-extract flag), `isNotification`, `isBrowser`, `groupId` (single group per feed), `icon`, `url` (columns per 7.json). `account` has `syncInterval`, `syncOnStart`, `syncOnlyOnWiFi`, `syncOnlyWhenCharging`, `keepArchived`, `syncBlockList`, `securityKey`.
- Reading/flow UI: `ui/page/home/flow/{FlowPage,ArticleList,SearchBar,MarkAsReadBar,...}.kt`, `ui/page/home/reading/{ReadingPage,Content,BottomBar,TopBar,Metadata,ReaderImagePage}.kt`. List position survives process death only via `rememberSaveable`/`LazyListState.Saver` (W0-verified), not durable storage. 72 DataStore preference files under `infrastructure/preference/` including `MarkAsReadOnScrollPreference.kt`, `KeepArchivedPreference.kt`.
- OPML: `domain/service/OpmlService.kt` + `infrastructure/rss/OPMLDataSource` (be.ceau opml-parser 3.1.0); `OPMLDataSourceTest` exists (3 tests).
- Build: applicationId `me.ash.reader` 0.16.2 (47); flavors `github`/`fdroid`/`googlePlay`; release signing currently uses git-tracked `signature/reader.keystore` (public debug key) for ALL build types (W0-verified). Bundled font `app/src/main/res/font/google_sans_flex.ttf` is Google Sans Flex v3.007, **SIL OFL 1.1** per its name table ID13/ID14 and upstream `OFL.txt` (binding correction f0533707; the earlier "not OFL" claim is withdrawn). Lint baseline: 232 issues / 28 Errors inherited (W0 + orchestrator re-verification); hiding via `abortOnError=false` or baseline files is forbidden.
- Dependencies (`gradle/libs.versions.toml`): Room 2.7.2, WorkManager 2.10.1, Paging 3.3.6, DataStore 1.1.4, Compose BOM 2025.10.01 (stable channel), `material3Adaptive = 1.3.0-alpha06` (explicit alpha), OkHttp 5.0.0-alpha.12, Rome 2.1.0, Coil 2.5.0, compose-html 1.0.2, Hilt.

FeedBucket web (`/home/jason/projects/feedbucket`, read-only; live DB schema + code read 2026-11-16):

- Tables: `users(id=1, password_hash, starred_token)`, `subscriptions(feed_url UNIQUE, site_url, title, muted, last_fetched, etag, show_updates)`, `entries(feed_id, external_id, UNIQUE(feed_id, external_id), title, url, author, summary, content, published, created_at, original_content, extracted)`, `unread(entry_id)`, `starred(entry_id)`, `updated(entry_id, updated_at)`, `recently_read(entry_id, read_at)`, `tags(name UNIQUE)`, `subscription_tags(feed_id, tag_id)` (many-to-many), `user_prefs`, `saved_searches(name, query, created_at; non-unique index on (user_id,name))`, `actions(action_type IN ('standard','mute'), title, query, all_feeds, feed_ids CSV, tag_ids CSV, effects CSV default 'mark_read')`, `sharing_services`.
- Query grammar (`feedbucket/db.py:1047 parse_search`): `shlex.split` with fallback to whitespace split on quoting error; `sort:asc|desc|relevance` tokens (last wins); one `tag:NAME` (last wins, matched lowercased); all remaining tokens are AND-ed terms. `search_filters` (db.py:172) renders each term as `LIKE %term% ESCAPE '\'` over `e.title OR e.summary OR e.content` with `\`, `%`, `_` escaped (`like_term`, db.py:167).
- Listing (`db.py:588 list_entries`): views `unread` (unread join AND `s.muted = 0`), `starred`, `updated` (inner join `updated`, `s.muted = 0`), `recently_read`, `all`; query-mute exclusion (`NOT (feed in scope AND mute terms)`, db.py:652) applies to `unread` and `updated` views only; default order `COALESCE(e.published, e.created_at) DESC, e.id DESC`; `recently_read` orders by `rr.read_at DESC`; `sort:relevance` scores per term title=5, summary=2, content=1, ties by published DESC, id DESC; default limit 500.
- Ingest (`db.py:452 store_entries`, `feedbucket/actions.py`): per-feed single transaction. `external_id = entry.external_id or entry.url or sha256(joined field values)`. New entry: actions applied (`is_update=False`); entry inserted into `unread` only if no matching `mark_read` effect; `star` effect inserts into `starred` under a budget of 100 stars per `store_entries` run (decremented only on actual insert). Existing entry: compared keys `(title,url,author,published)` if `extracted` else `(title,url,author,summary,content,published)`; equal → untouched; changed → snapshot gate (only if `show_updates` AND NOT `extracted` AND `original_content IS NULL` AND published within 7 days (missing published counts as recent; unparseable counts as NOT recent) AND content changed with `abs(len(plain(new)) - len(plain(old))) > 50`) stores the OLD content into `original_content` once; then extracted entries update ONLY `title,url,author,published`, non-extracted update all content fields; `updated` row upserted when `show_updates`; actions re-applied with `is_update=True` where `mark_read` clears `unread` AND `updated`, and `star` is skipped.
- Rule matching (`actions.py entry_matches`): scope = all feeds, or `feed_ids ∪ feeds(tag_ids)` intersected with existing feeds; match = optional `tag:` must be among feed tags (casefold) AND every term `casefold`-substring of `"\n".join(title, summary, content).casefold()`. Note the deliberate asymmetry with SQL search: rule matching is Unicode-casefold substring in Python; listing/search uses SQLite LIKE (ASCII case-insensitive). This spec preserves BOTH behaviors exactly (S12).
- Preview/apply (`actions.py preview_action` / `apply_action_to_existing`): preview = count + first 20 rows `(id,title,feed_title,published)`; apply-to-existing runs in one transaction over the full scoped match set and returns the row count; `mark_read` also deletes `updated`.
- Read/star (`db.py:839 set_read`, `:856 toggle_star`, `:1002 mark_all_read`, `:867 clear_recently_read`): mark-read deletes `unread` and `updated`, upserts `recently_read(read_at=now)`; mark-unread re-inserts `unread` and deletes `recently_read`; mark-all-read is scoped to the current listing (view/feed/query/sort) and returns the count.
- Diff (`feedbucket/diff.py build_content_diff`): returns None if original empty or lengths equal; tokenizes SANITIZED html with regex `<[^>]+>|\S+|\s+`; `difflib.SequenceMatcher(autojunk=False)`; wraps removed/added token runs in `<del>`/`<ins>`; sanitizes output; any exception → None (no diff, never an error).
- OPML (`feedbucket/opml.py`): export OPML 2.0, one flat `<outline type="rss">` per feed with `category` = comma-joined tags; import walks NESTED outlines, inheriting and accumulating `category` attrs, requires `xmlUrl`, title = `title || text || feed_url`, order-preserving dedupe, insert via ON CONFLICT DO NOTHING (idempotent).
- URL safety: entry URLs stored only when scheme is http/https (`safe_entry_url`); titles/authors stored as PLAIN TEXT (`plain_entry` strips HTML); summary/content stored as sanitized HTML (nh3).
- Extraction trigger (web): entry considered teaser when stripped text `< max(200, 0.4 × feed median body length)` AND `< 700` chars; re-extraction length guard minimum 120 chars; extraction failures are silent no-ops that never replace existing content (`feedbucket/extract.py`, `db.py:820 feed_median_body_len`).

## 2. Final data shape (Room schema v8) and migration

**S1 — One migration, decided up front.** The FINAL v1 schema is Room version 8, reached by exactly one migration `MIGRATION_7_8` from upstream v7. Feature waves may NOT add columns/tables later in v1; they consume this shape (approved direction step 2: "Define FINAL model composition before features widen it"). Any v1 change to this section requires a spec amendment card, not a silent coder decision.

**S2 — Preserve upstream tables and ids.** `account`, `feed`, `group`, `article`, `archived_article` keep their names and primary keys. `article.id` stays TEXT. Existing rows keep their random UUID-based ids forever; determinism applies to NEW inserts only (S6). `account` is untouched; the standalone product uses exactly one Local account.

### 2.1 `article` — added columns (all with NOT NULL DEFAULT so ALTER TABLE is safe)

| Column | Type | Default | Purpose / web analogue |
|---|---|---|---|
| `guid` | TEXT | `''` | Publisher GUID (`SyndEntry.uri ?: link` at ingest). Web: `entries.external_id`. Kept SEPARATE from display ordering and from `id` (D8). |
| `siteUrl` is on feed, not article | — | — | see 2.2 |
| `originalContent` | TEXT | NULL | First-version publisher snapshot for diffs. Web: `entries.original_content`. Written ONLY by the snapshot gate (S20). |
| `contentChangedAt` | INTEGER | NULL | Epoch millis when a publisher change was last observed. Web: `updated.updated_at`. |
| `isUpdated` | INTEGER (Boolean) | `0` | Updated-queue membership. Web: row existence in `updated`. Denormalized onto `article` instead of a join table (S3). Cleared on mark-read and on mark-all-read (S21). |
| `readAt` | INTEGER | NULL | Recently-read timestamp. Web: `recently_read.read_at`. Denormalized (S3). Set on mark-read, cleared on mark-unread. |
| `extractedAt` | INTEGER | NULL | Epoch millis of successful local extraction. Together with non-null `fullContent` this is the "extracted" state (S19). Web: `entries.extracted`. |

`fullContent` (existing column, `@Deprecated` annotation REMOVED in wave B) is redefined: `fullContent` = locally extracted sanitized HTML, NULL when not extracted. It is never populated from feed data at ingest (upstream already leaves it commented out in `buildArticleFromSyndEntry`). `rawDescription` remains the feed-supplied body (web `content`), `shortDescription` remains the 280-char plain preview (web `summary` role for display), and `img`/`link`/`date`/`updateAt` keep upstream meaning (`updateAt` = last sync touch, NOT the publisher-change signal; `contentChangedAt` is).

### 2.2 `feed` — added columns

| Column | Type | Default | Purpose |
|---|---|---|---|
| `siteUrl` | TEXT | `''` | Web `subscriptions.site_url`; OPML `htmlUrl` parity; "View original" target metadata. |
| `muted` | INTEGER (Boolean) | `0` | Web `subscriptions.muted`; excluded from `unread` and `updated` views only (S15). |
| `showUpdates` | INTEGER (Boolean) | `1` | Web `subscriptions.show_updates`; gates snapshot + updated-queue (S20). |
| `tags` | TEXT | `''` | Comma-separated lowercase order-preserving-dedupe tag names (web `subscription_tags` many-to-many). ReadYou `groupId` stays the single DISPLAY folder; `tags` carries the full web tag set for `tag:` queries and rule scoping (S4). |
| `lastFetchedAt` | INTEGER | NULL | Web `subscriptions.last_fetched`. |
| `etag` | TEXT | NULL | Web `subscriptions.etag`; conditional GET support. |

### 2.3 New tables

```
saved_search   (id INTEGER PK AUTOINCREMENT, name TEXT NOT NULL, query TEXT NOT NULL,
                createdAt INTEGER NOT NULL)
  -- names are NOT unique (web index saved_searches_user is non-unique); identity = rowid.

action_rule    (id INTEGER PK AUTOINCREMENT,
                type TEXT NOT NULL DEFAULT 'standard' CHECK (type IN ('standard','mute')),
                title TEXT NOT NULL DEFAULT '', query TEXT NOT NULL DEFAULT '',
                allFeeds INTEGER NOT NULL DEFAULT 1,
                feedIds TEXT NOT NULL DEFAULT '',     -- CSV of feed.id (TEXT ids)
                groupIds TEXT NOT NULL DEFAULT '',    -- CSV of group.id (web tag_ids analogue)
                effects TEXT NOT NULL DEFAULT 'mark_read',  -- CSV subset of {mark_read,star}
                createdAt INTEGER NOT NULL)

article_fts    -- Room @Fts4(contentEntity = Article::class) over title, shortDescription,
               -- rawDescription, fullContent. Accelerator + coverage index (S10).

article_download (articleId TEXT PRIMARY KEY,                  -- FK article.id CASCADE
                state TEXT NOT NULL DEFAULT 'pending'
                      CHECK (state IN ('pending','downloading','ready','failed')),
                textBytes INTEGER NOT NULL DEFAULT 0,
                imageBytes INTEGER NOT NULL DEFAULT 0,
                imageCount INTEGER NOT NULL DEFAULT 0,
                errorCause TEXT,                -- stable enum string, see S27 table
                attempts INTEGER NOT NULL DEFAULT 0,
                requestedAt INTEGER NOT NULL, lastAttemptAt INTEGER, completedAt INTEGER)

download_asset (articleId TEXT NOT NULL, url TEXT NOT NULL, localPath TEXT NOT NULL,
                bytes INTEGER NOT NULL, sha256 TEXT NOT NULL,
                PRIMARY KEY (articleId, url))   -- FK article.id CASCADE; manifest for offline render

list_position  (scopeKey TEXT PRIMARY KEY,     -- canonical string, S24
                anchorArticleId TEXT, anchorItemIndex INTEGER NOT NULL DEFAULT 0,
                anchorOffsetPx INTEGER NOT NULL DEFAULT 0,
                updatedAt INTEGER NOT NULL)

reading_position (articleId TEXT PRIMARY KEY,  -- FK article.id CASCADE
                scrollRatioPx INTEGER NOT NULL DEFAULT 0,  -- numerator of scrollY/contentHeight*10000
                contentHeightPx INTEGER NOT NULL DEFAULT 0,
                updatedAt INTEGER NOT NULL)

import_run     (id INTEGER PK AUTOINCREMENT, kind TEXT NOT NULL CHECK (kind IN ('feedbucket','opml')),
                sourceFingerprint TEXT NOT NULL,   -- sha256 of imported file bytes
                state TEXT NOT NULL CHECK (state IN ('running','completed','failed','cancelled')),
                startedAt INTEGER NOT NULL, finishedAt INTEGER,
                countsJson TEXT NOT NULL DEFAULT '{}')

import_identity (source TEXT NOT NULL, sourceId TEXT NOT NULL,   -- e.g. 'feedbucket','entries:412'
                targetKind TEXT NOT NULL CHECK (targetKind IN ('article','feed','saved_search','action_rule')),
                targetId TEXT NOT NULL, importRunId INTEGER NOT NULL, importedAt INTEGER NOT NULL,
                PRIMARY KEY (source, sourceId, targetKind))
```

**S3 — Denormalize `updated`/`recently_read` onto `article`.** The web uses side tables keyed by entry_id with CASCADE deletes; Android uses `isUpdated`/`contentChangedAt`/`readAt` columns. Observable behavior is identical (queue membership, drain-on-read, ordering by readAt DESC); a column avoids two joins in every paging query and survives Room's identity-hash checking without extra entities. Acceptance tests pin the observable behavior, not the shape.

**S4 — Tags vs groups.** Web feeds carry multiple tags; ReadYou feeds carry one `groupId`. v1 keeps `groupId` as the single navigational folder and adds `feed.tags` (CSV) as the data-layer parity carrier used by `tag:` query tokens, rule `groupIds` scoping (which resolves against BOTH `groupId`-derived group ids and `feed.tags` names, see S13), OPML import (all folder/category names) and migration import (all web tags). UI for editing multiple tags per feed is deferred to the design contract; the data path must be complete in wave B.

**S5 — Migration `MIGRATION_7_8`.** Pure SQL, exported schema 8.json committed, no data loss:
1. `ALTER TABLE article ADD COLUMN` for each of §2.1 (defaults as tabled).
2. `ALTER TABLE feed ADD COLUMN` for each of §2.2.
3. `CREATE TABLE` for §2.3 entities (Room-exact DDL from 8.json), `CREATE INDEX` for `article_download.state`, `import_run.state`, `list_position.updatedAt`, `reading_position.updatedAt`, plus FK triggers Room expects.
4. FTS: create `article_fts` per Room's generated DDL; if (and only if) the Room-generated external-content setup does NOT auto-create sync triggers on `article`, add explicit `AFTER INSERT/UPDATE/DELETE` triggers on `article` mirroring Room's documented external-content pattern. Wave B verifies which path applies with an instrumented test that inserts via the DAO and queries the FTS table directly (red/green in plan).
5. Backfill: none required — all defaults are the correct historical state (no guid history exists upstream; `readAt`/`originalContent` genuinely unknown). `feed.tags` backfills from the feed's group name (`INSERT`-time: `tags = (SELECT name FROM group WHERE id = feed.groupId)` lowercased) so `tag:` queries work on pre-existing libraries.
6. Migration tests (instrumented, `room-testing` MigrationTestHelper): v7 fixture → migrate → assert exact table/column/index/trigger set against 8.json, assert fixture row values preserved, assert FTS round-trip (insert v7 article, migrate, search by body-only term). Downgrade is unsupported (Room default: crash) — S31 retention/backup story covers restore.

### 2.4 IDs and import identity

**S6 — Deterministic ids for new articles.** New inserts (sync, import) use `id = "<accountId>$" + sha256hex(feedUrl + "\n" + externalId)` where `externalId = guid.ifBlank { link }.ifBlank { sha256hex(title|url|author|published|contentFields) }` — the exact web fallback chain (`store_entries`, db.py:496). Rationale: idempotent re-sync, re-import and OPML-then-migration double imports converge on one row; the web already relies on `UNIQUE(feed_id, external_id)`. The `$` spacer convention is preserved so upstream code paths that split on it keep working. Existing UUID rows are NOT rewritten (rewriting PKs would cascade into every consumer for zero user-visible gain); identity resolution for old rows stays `(feedId, link)`.

**S7 — Dedupe on ingest.** An incoming article matches an existing row when `feedId` matches AND (`guid` non-empty AND equals existing `guid`) OR (`link` non-empty AND equals existing `link`). Match → update path (S20); no match → insert with S6 id. `INSERT` uses `OnConflictStrategy.IGNORE` on the PK as a last-resort race guard (two workers computing the same deterministic id).

**S8 — Import identity map.** Every migration/OPML-created entity records `(source, sourceId, targetKind, targetId)` in `import_identity`. FeedBucket sources use `'feedbucket'` with ids `'entries:<id>'`, `'subscriptions:<id>'`, `'saved_searches:<id>'`, `'actions:<id>'`, `'tags:<name>'`. Re-import consults the map first: mapped rows are SKIPPED for content and counted as `skipped`; state refresh (unread/starred/readAt/isUpdated) for already-mapped articles happens only when the import run is explicitly started in "refresh state" mode from the import UI (default OFF). Rationale: web `import_subscription` is ON CONFLICT DO NOTHING — idempotent, never destructive; D13 forbids destructive dedup.

**S9 — Migration article ids.** Imported FeedBucket entries get the S6 deterministic id computed from `(subscription.feed_url, entry.external_id)`, so importing the same DB copy twice — or importing after already having synced the same feed live — converges on the same row.

## 3. Query & rule grammar (shared across search, previews, ingest, mutes)

**S10 — One parser, one matcher module.** Create `domain/model/query/SearchQuery.kt` (parser) and `domain/model/query/QueryMatcher.kt` (in-memory matcher) as the ONLY implementations; search UI, saved searches, rule preview, rule ingest application and mute filtering all consume them. No second grammar may exist (D7).

Parser (exact port of `parse_search`, db.py:1047):
- Tokenize POSIX-shell-style: double quotes, single quotes, backslash escapes (a Kotlin port of `shlex.split` semantics; a tokenizer bug here silently changes every feature, so it is unit-tested against Python `shlex` outputs recorded as fixtures). On unbalanced quotes, fall back to plain whitespace split (web `except ValueError: query.split()`).
- `sort:asc|desc|relevance` (case-insensitive value; last valid token wins; invalid value ignored, leaving default).
- `tag:NAME` (NAME must be non-empty; last wins; comparisons lowercase).
- All other tokens are search terms, AND-ed, order preserved.
- Output: `SearchQuery(terms: List<String>, tag: String?, sort: Sort)` — pure data, no SQL.

**S11 — SQL rendering (search/list/mute filtering).** Each term renders as `(?field LIKE '%' || :t || '%' ESCAPE '\')` with `\`, `%`, `_` escaped exactly like `like_term` (db.py:167), OR-ed across fields, terms AND-ed. Matched fields: `title`, `shortDescription`, `rawDescription`, `fullContent`. Rationale for the 4-field set: web matches `title/summary/content` where `content` is the body that extraction REPLACES in place; Android keeps feed body (`rawDescription`) and extraction (`fullContent`) side by side, so matching both reproduces the web observable ("body-only matches work", "extracted content changes search results", D10) without losing pre-extraction text. `tag:` renders as `EXISTS (SELECT 1 FROM feed f2 WHERE f2.id = article.feedId AND (',' || f2.tags || ',' LIKE '%,<tag>,%'))` — CSV-safe containment, lowercase both sides.

**S12 — Two matchers, deliberately.** (a) SQL LIKE path (S11) for search/list/mutes: ASCII case-insensitive, exactly like SQLite on the web. (b) In-memory rule matcher (port of `entry_matches`, actions.py:63): `term.lowercase()`-substring (Kotlin `casefold` NOT used — Python `casefold` differs from SQLite LIKE and from Kotlin `lowercase` on edge cases; the web rule engine uses `casefold`, so the Kotlin matcher uses `java.lang.String.lowercase()` with ROOT locale and this deviation is documented: for the ASCII/CJK corpora in scope, casefold≡lowercase; acceptance fixtures include a Turkish-I case pinning ROOT-locale behavior). Matched text = `"$title\n$shortDescription\n$body"` where `body = fullContent?.takeIf{it.isNotBlank()} ?: rawDescription` at match time. `tag:` in a rule matches when the lowercased tag is contained in `feed.tags` CSV or equals the lowercased group name. This asymmetry (LIKE for queries, substring-lowercase for rules) is the REAL web behavior and is pinned by tests on both paths.

**S13 — Rule scope resolution.** Port of `resolve_scope` (actions.py:33): `allFeeds=1` → every feed of the account; else union of `feedIds` and feeds whose `groupId ∈ groupIds` OR whose `tags` CSV contains any group NAME resolved from `groupIds` (web resolves tag_ids → subscription_tags; Android group ids map to names, then to both `groupId` and `tags` per S4), intersected with existing feeds. Empty scope → rule never matches (web `_query_mute_filters` skips empty-scope mutes).

**S14 — Relevance sort.** Port of list_entries relevance (db.py:632): per term, `CASE WHEN title LIKE … THEN 5 ELSE 0 END + CASE WHEN shortDescription LIKE … THEN 2 ELSE 0 END + CASE WHEN rawDescription LIKE … OR fullContent LIKE … THEN 1 ELSE 0 END`, summed; `ORDER BY score DESC, date DESC, rowid DESC`. `sort:asc` → `date ASC, rowid ASC`; `sort:desc`/default → `date DESC, rowid DESC`. Web ties on integer `e.id`; Android ties on `rowid` (the implicit rowid of the TEXT-PK table) — same observable: stable insertion-order tiebreak.

**S15 — Views and mute application.** Flow views: `unread` (isUnread=1 AND feed.muted=0 AND query-mutes excluded), `starred` (isStarred=1; mutes NOT applied; muted feeds included), `updated` (isUpdated=1 AND feed.muted=0 AND query-mutes excluded), `recentlyRead` (readAt NOT NULL, ordered readAt DESC, rowid DESC; mutes NOT applied), `all` (no state filter; mutes NOT applied), plus feed scope, group scope and saved-search scope combinable with any view. This is the exact web matrix (list_entries + `_query_mute_filters` applied only to unread/updated). Feed-scope and group-scope filters AND with the view filter.

**S16 — FTS role.** `article_fts` is an accelerator and coverage index, NEVER a semantic authority: the canonical predicate for "article matches query" is S11/S12 (substring). Implementation may use FTS to shortlist candidates only where it cannot change results (e.g. `sort:relevance` ranking prefilter must still re-verify with LIKE; if a code path cannot guarantee identical result sets, it must not use FTS). Coverage indicator (D10): the search screen shows "index covers N of M articles" when `COUNT(article_fts.docid) != COUNT(article.id)` for the account, plus a stale-sync notice when `account.updateAt` is older than 24 h. Body-only-match, mid-word-substring (FTS cannot find these; LIKE must), extracted-content-change, deletion and migration search tests are mandatory (D10).

## 4. Rules, saved searches, mutes (D7)

**S17 — Rule effects and ingest application.** Effects CSV ⊆ {`mark_read`, `star`}. On new-article insert inside the same DB transaction as the insert (port of store_entries): a matching `mark_read` inserts the article with `isUnread=0`; a matching `star` sets `isStarred=1`, subject to a budget of 100 stars per per-feed refresh run (shared across rules within that run, decremented only on actual flips — exact web semantics; rationale: prevents a bad rule from starring an entire backlog in one sync). On publisher UPDATE (S20): `mark_read` clears `isUnread` AND `isUpdated`; `star` is skipped entirely (web: `star = not is_update and …`). Rules of type `mute` never have effects at ingest; they filter the `unread`/`updated` views at query time (S15) and are evaluated with S12(b)+S13.

**S18 — Preview and apply-to-existing.** Rule editor shows a live preview: total match count over the current library plus the first 20 matches `(articleId, title, feed name, date)` (port of `preview_action` cap 20). Apply-to-existing runs in ONE transaction over the full scoped match set, applies `mark_read` (clear isUnread + isUpdated) and `star` (set isStarred; NO budget on apply-to-existing — web has none), returns the exact affected count for display. Reversibility (D7): apply-to-existing shows the count and a "undo last apply" affordance backed by storing the flipped article ids in memory of the rules screen session (bounded 10 000 ids); star toggles and mark-unread remain individually reversible always. `mark_read` applied at ingest is reversible via normal mark-unread.

Saved searches: persisted `(name, query)` rows, listed in the flow filter bar, executable as scopes (view=all + query, S15), renamable/deletable; duplicate names allowed (web parity, S: saved_search). Creating a saved search captures the current query string verbatim including `sort:`/`tag:` tokens.

## 5. Updated-vs-extracted semantics (D6, D8)

**S19 — Extraction contract.** Extraction (auto via `feed.isFullContent` + ReaderWorker, or manual per-article) writes `fullContent` (sanitized HTML, S30) and `extractedAt`; it NEVER modifies `rawDescription`, `originalContent`, `isUpdated`, `contentChangedAt`, `isUnread`, `isStarred`, `readAt`. Productivity guard (port of web thresholds): an extraction result is accepted only if its plain-text length ≥ 200 chars OR ≥ 40% of the feed's median stored body length (median computed like `feed_median_body_len` over `rawDescription`/`shortDescription` plain-text lengths of the feed's articles); re-extraction of an already-extracted article additionally requires ≥ 120 plain-text chars. Rejected results leave prior content untouched and record NO success (D6: a failed extraction never claims success). Bot/challenge rejection: if the fetched document's `<title>` or first 500 chars match known challenge markers (Cloudflare/"Just a moment"/"Access Denied"/captcha iframes) OR the productive-text ratio is < 5% of document size, treat as failure. Failures are isolated per article (web rescue-everything; upstream `runCatching`), surfaced in UI as a transient "extraction unavailable" state on the article only — never a dialog, never a crash, never content replacement.
**S20 — Refresh update detection (port of store_entries).** On sync, for an existing match (S7): compute old/new compare keys — extracted (`fullContent != null`): `(title, link, author, date)`; else `(title, link, author, shortDescription-source, rawDescription, date)`. Equal → row untouched (no `updateAt` bump). Changed → snapshot gate: if `feed.showUpdates` AND NOT extracted AND `originalContent IS NULL` AND recency holds (`date` within 7 days of now; a `date` that came from the sync-time fallback counts as recent; unparseable/absent publisher date → NOT recent, mirroring the web `ValueError → recent=False` branch as closely as the S6 date model allows) AND significance holds (plain-text length delta > 50 chars AND content actually differs) → `originalContent = OLD rawDescription` (once, first version only, never overwritten). Then: extracted → update only `title, link, author, date`; else → update `title, link, author, rawDescription, shortDescription, img, date`. If `feed.showUpdates`: `isUpdated = 1`, `contentChangedAt = now`. Then S17 update-effects. `updateAt = now` always on a changed row. Rationale for every threshold: exact web parity (7-day recency db.py:559, >50 significance db.py:568, first-version-only db.py:552).
**S21 — Updated queue drain.** Mark-read (single, on-open, or scoped mark-all-read) sets `isUpdated = 0` in the same write (web deletes the `updated` row in set_read/mark_all_read). Opening an article does NOT silently clear `isUpdated` unless mark-read-on-open (S25) fires. The `updated` view shows exactly `isUpdated=1` rows, newest `contentChangedAt` first.
**S22 — Diff rendering.** "View changes" computes a word-level diff between sanitized `originalContent` and sanitized current `rawDescription` using the web tokenization (`<[^>]+>|\S+|\s+`) and a longest-common-subsequence differ (java-diff-utils 4.x, Apache-2.0 — new dependency, isolated to the diff module); no diff when `originalContent` is null/blank or token sequences are equal (web returns None on equal LENGTH; Android compares token lists, a strict improvement pinned by test: equal-length-but-different text must still diff). Rendered as Compose annotated text (NOT WebView): deletions struck-through with `deleted` semantics, insertions underlined with `inserted` semantics, TalkBack announces "deleted:"/"inserted:" runs, colors meet AA in light/dark and never rely on color alone (strikethrough/underline are the redundant channel). Diff computation failures → feature silently unavailable for that article (web exception→None parity).

## 6. List scope, position and read lifecycle (D5)

**S23 — Mark-read-on-open.** Default ON (web `mark_read_on_open` default ON): opening an article sets `isUnread=0`, `readAt=now`, `isUpdated=0` (S21) in one transaction. `MarkAsReadOnScrollPreference` stays available, default OFF (D5). Manual mark-unread clears `readAt` (web set_read(False) parity).
**S24 — Durable list positions.** Canonical scope key: `"<view>|<feedId?>|<groupId?>|<savedSearchId?>|<query>|<sort>"` (empty slots as `-`, query verbatim). On scroll-idle (debounced 300 ms) and on `ON_PAUSE`/navigation-away, persist `(anchorArticleId, anchorItemIndex, anchorOffsetPx)` for the active scope to `list_position`. Restore order: exact anchorArticleId if still in scope → else anchorItemIndex clamped to list size → else top. Restoration happens after process death, after theme/locale change and after back-navigation. Retention: rows older than 30 days pruned on app start (bounded table, no user-visible effect).
**S25 — Sticky scope & neighbors (mark-read must not break prev/next).** The flow list does NOT re-query on read-state change of an item already loaded; a marked-read article remains visible (badged read) until the user changes scope, pulls to refresh, or restarts the app (web parity: the list is server-rendered once per navigation; ReadYou's live Paging invalidation is the KNOWN regression source, W0-cited issues 1091/1271/1240/1285 are demand leads only — the behavior contract here stands on its own). Prev/next in the reading pager resolves over the CURRENT scope's ordering (S14/S15) with a position fallback ported from web `db.neighbors`: when the current article no longer satisfies the scope predicate (e.g. just marked read in an unread scope), neighbors are found by sort-position comparison — previous = max row with `(sortKey, rowid) < (current.sortKey, current.rowid)` in scope order, next = min row with `>` — using SQL row-value comparison, so navigation survives the article leaving the scope. Mark-all-read is scoped to the current list scope (view/feed/group/saved-search/query/sort) and reports the exact count (web mark_all_read parity); confirmation default ON (web `mark_as_read_confirmation` default 1).
**S26 — Reading position.** Per-article scroll position (`scrollRatioPx`, `contentHeightPx`) persisted on pause/scroll-idle and restored on reopen, including after process death. Reading pager index (which article of the scope is open) is part of `list_position.anchorArticleId`. Positions are best-effort UI state: a restore that lands within ±1 screen of the truth passes acceptance; missing/corrupt rows fall back to top-of-article, never a crash.

## 7. Durable downloads & offline (D9)

**S27 — Download store.** Explicit user action "Download" per article (and bulk "download all in scope" capped per S28) enqueues `article_download(state='pending')` + WorkManager one-time work chained after current sync. A download = sanitized self-contained HTML of the article body (`fullContent ?: rawDescription`, rewritten so every `<img src>` in the allowed set points at a local `download_asset` path) + images fetched under caps. Storage location: `filesDir/downloads/<accountId>/<sha256(articleId)>/` (`index.html`, `assets/`, `manifest.json`) — internal files dir, NOT cacheDir: survives cache-clear, survives process death, excluded from auto-eviction (D9). States: `pending → downloading → ready | failed(errorCause, attempts)`; user actions: retry (always available on failed, resets attempts), remove (deletes row + files). UI shows text-ready vs images-pending granularity: `ready` requires text written; images may complete after (row flips to `ready` when text is stored, `imageCount`/`imageBytes` update as assets land; a partially-imaged ready article renders offline with missing-image placeholders).
Failure causes (stable enum strings, surfaced as human text): `network` (DNS/TLS/timeout/connection), `http_4xx`, `http_5xx`, `challenge` (bot wall detected, S19 markers), `oversize` (cap exceeded → text still stored if under text cap, images skipped), `storage` (insufficient space), `cancelled`, `unknown`. D6 applies: a failed download never claims success and never corrupts existing content — download writes are staged to a temp dir and moved atomically on completion.
**S28 — Caps and defaults (exact values, rationale).**

| Limit | Default | Rationale |
|---|---|---|
| Per-article HTML text | 2 MiB | full-text articles rarely exceed 500 KiB sanitized; 2 MiB leaves headroom for data-heavy posts while bounding worst case. |
| Per-article images | 10 MiB AND 50 assets | photo essays stay downloadable; hard asset count prevents 1000-thumbnail galleries from consuming the store. |
| Per-image | 5 MiB | single hero images/retina screenshots fit; larger is almost never article content. |
| Global download store | 1 GiB | ~500 fully-downloaded articles; visible usage bar in Settings → Downloads. On reaching 90% the UI warns; on 100% new downloads fail with `storage` and the manage screen offers bulk remove (oldest-first suggestion). NEVER silent eviction (D9: durable store). |
| Auto retries | 3 per download, exponential backoff (1/5/25 min, WorkManager defaults) | transient network recovery; manual retry always allowed and resets the counter. |
| Bulk download scope cap | 200 articles per invocation | bounds a single user action; UI states the cap. |
| Network policy | Manual downloads run on any network (explicit user intent); bulk and background-chained downloads require unmetered (Wi-Fi) by default, honoring `account.syncOnlyOnWiFi`/`syncOnlyWhenCharging` for the chained work (D12: constraints cover chained extraction/download work). | honest, matches web "user asked for it" semantics without surprise metered burn. |
| HTTP timeouts | connect 15 s, read 30 s, call 120 s for downloads | bounded failure detection; 120 s call cap covers slow large images. Applied via a dedicated OkHttpClient for downloads; feed/extraction client timeouts pinned to the same connect/read values in wave B config (verify current upstream defaults first; do not lengthen silently). |

**S29 — Retention & storage management.** Downloads persist until removed by the user, by article deletion (FK CASCADE + file cleanup hook), or by "Remove all downloads" in Settings → Downloads (with count confirmation). Removing an article that has a download deletes its files in the same user action. `archived_article`/`keepArchived` upstream behavior unchanged. No time-based expiry in v1 (rationale: D9 "durable"; retention rules = user-controlled management, not silent TTL).

## 8. Offline behavior, FTS and search (D10)

Search, browsing, reading (synced or downloaded), starring, mark-read, rules, saved searches, diffs (snapshot present) all work with zero connectivity — every path reads Room/filesDir only. Offline state table (observable):

| Scenario | Observable behavior |
|---|---|
| Airplane mode, open app | Last-synced content fully browsable; sync attempt (manual pull) fails within timeout → non-blocking banner "Offline — showing saved articles", no dialog, no crash; positions restore (S24/S26). |
| Airplane mode, search | Results from local LIKE/FTS over stored text; coverage indicator (S16) shows index size; body-only and mid-word matches return identical sets to online. |
| Airplane mode, open downloaded article | Renders fully offline from `index.html` + local assets; no network requests issued (test: MockWebServer/OkHttp interceptor asserts zero calls). |
| Airplane mode, open non-downloaded article with images | Text renders; remote images show placeholder, no retry storm (Coil request skipped when connectivity absent). |
| Airplane mode, tap Download | Row → `failed(network)` after connect timeout; retry available. |
| Network lost MID-download | WorkManager retry per S28; after 3 auto-failures row stays `failed(network)`; partial staged files discarded (atomic move, S27). |
| Process death during download | On next app start, rows in `downloading` with no live work are reset to `pending` and re-enqueued once (recovery rule); staged temp dirs garbage-collected. |
| Process death during sync | Upstream behavior: partial per-feed inserts already committed remain (each feed's insert is its own transaction); next sync re-fetches and dedupes via S7. No corruption path. |
| Process death during migration import | See S33 (resume via import_run + import_identity). |
| Storage full during download | `failed(storage)`; existing `ready` downloads unaffected. |

## 9. Migration from FeedBucket & OPML (D11)

**S30 — Sanitization.** All stored HTML (feed-supplied `rawDescription`, extracted `fullContent`, snapshots `originalContent`, diff output, downloaded `index.html`) is sanitized at WRITE time with Jsoup `Safelist.relaxed()` extended to allow `class` attributes and `http/https/mailto` protocols only; `<script>`, event-handler attributes, `javascript:`/`data:`/`file:` URIs are stripped (D16). Rendering additionally runs through the existing RYWebView pipeline with JavaScript disabled, no file access, no external asset loading for downloaded content (acceptance tests assert the WebSettings and a scheme-block test feeds `javascript:`, `data:`, `file:`, `intent:` URIs and asserts non-navigation).

**S31 — Backup & privacy.** Room DB + downloads live in internal storage; Android auto-backup posture: DB excluded from cloud backup in v1 (`allowBackup` decision belongs to foundation; spec requires: no secrets and no article corpus leave the device by default, D16). No telemetry (D16). The web `users` table (password_hash, starred_token) is NEVER read by the importer (S32). Migration artifacts (imported DB copies, preview dumps) live in app cache and are purged after import; developer-side test fixtures are synthetic only (D16). Private local artifacts (`docs/research/logs/`, keystores, `keystore.properties`, `.plan/`, any `*.db` copies) are gitignored — foundation wave adds the ignore entries and a CI check that no `*.db`/keystore file is tracked.

**S32 — FeedBucket migration import.** Input: a SQLite COPY of the web DB produced by the user (`sqlite3 data/feedbucket.db ".backup feedbucket-copy.db"`; in-app instructions + copy-step inside the importer: the picked file is first copied to app cache and opened read-only there — the live service DB is never opened, D14). Pipeline:
1. Fingerprint (sha256 of picked file) + open read-only; schema check: required tables/columns exist (`subscriptions`, `entries`, `unread`, `starred`, `updated`, `recently_read`, `tags`, `subscription_tags`, `saved_searches`, `actions`; `user_prefs` optional). Unknown/missing → import rejected with a precise message, nothing written.
2. Secret guard: the importer's column allowlist excludes `users.*` entirely; a test asserts the string `password_hash` and `starred_token` appear in NO query the importer issues (query-log assertion) and that no imported value matches a session-token shape.
3. Preview phase (no writes): counts table — subscriptions, tags, entries, unread, starred, updated-queue, recently-read, saved searches, standard actions, mute actions, entries with snapshots, extracted entries — plus first 10 subscription titles. Shown to the user with Import/Cancel.
4. Apply phase: `import_run(kind='feedbucket', state='running')`; iterate feeds in `subscriptions.id` order; per feed ONE Room transaction creating the feed (S6-style deterministic feed id = `"<accountId>$" + sha256hex("feed\n"+feed_url)`; groups from tags: first tag → group, all tags → `feed.tags` CSV), its entries (ids per S9; `isUnread` from `unread`; `isStarred` from `starred`; `readAt` from `recently_read`; `isUpdated`/`contentChangedAt` from `updated`; `originalContent` from `original_content`; `fullContent`+`extractedAt` from `content` where `extracted=1`, with `rawDescription` = `original_content ?: content` mirroring web in-place replacement as closely as the two-column model allows: for extracted entries web `content` IS the extraction and the pre-extraction body survives only in `original_content` when a snapshot exists — so `rawDescription = original_content ?: summary-as-body fallback (content)`; documented deviation, pinned by fixture test), saved searches and action rules (web `tag_ids` resolve against imported tag names → Android group ids). Then per-entry `import_identity` rows. Commit per feed.
5. Counts ledger: `imported`, `skipped` (identity-map hits), `failed` (per-row errors, itemized) written to `import_run.countsJson`; success screen shows the exact ledger. A row-level failure never aborts the feed transaction unless it is a schema/constraint error (then the feed chunk rolls back, counted in `failed`, import continues with the next feed — bounded blast radius).
6. Restart/resume: app start finds `import_run(state='running')` → resume mode: continue from the first feed lacking identity-map coverage; already-imported feeds count as `skipped`. User-visible: import is resumable, never duplicated (S8), never silently half-applied without a ledger.
7. Rollback: "Cancel" before apply → nothing written. Failure mid-apply → completed feed chunks REMAIN (each was transactional and identity-mapped) and the ledger says so; a "Retry import" resumes. A "Remove imported data" action on the result screen deletes exactly the rows whose ids appear in `import_identity` for that run (bounded, non-destructive to other data).
**S33 — OPML.** Export: OPML 2.0 flat outlines with `category` = `feed.tags` (web export parity) AND folder nesting from `groupId` (ReadYou parity) — both consumers round-trip. Import: existing `OpmlService` path extended: nested folders → group from first folder level, all folder/category names → `tags` CSV (web walk parity), `htmlUrl` → `siteUrl`, duplicate `xmlUrl` → skip with count (ON CONFLICT DO NOTHING parity), report imported/skipped counts. OPML carries subscriptions only, never reader state (D11).

## 10. Sync, refresh and background work (D12)

Refresh triggers: periodic WorkManager (account.syncInterval, existing), sync-on-start (existing pref), manual pull-to-refresh, per-feed/per-group refresh (existing). Honest description in UI: "Background refresh is best-effort; Android may defer it. Pull to refresh for immediate updates." Chained work: `SyncWorker → ReaderWorker (auto-extraction) → DownloadWorker (pending downloads) → WidgetUpdateWorker`, `ExistingWorkPolicy.KEEP` for the chain (upstream pattern preserved); per-feed concurrency 16, extraction concurrency 2 (upstream semaphores preserved); one feed's failure never stalls others (supervisorScope preserved); duplicate concurrent syncs prevented by KEEP/REPLACE policies (existing) plus a deterministic-work-name assertion test. Update detection (S20) runs inside the per-feed sync path.

## 11. Product identity, licensing, design (D2, D4, D17)

**S34 — Identity.** applicationId `us.snakepit.feedbucket`, debug suffix `.debug`, debug label "FeedBucket D", release label "FeedBucket". Kotlin namespace stays `me.ash.reader` (D2 merge-debt minimization). versionName resets to `0.1.0`, versionCode `1`. All three flavors kept initially; CI gates `githubDebug`.
**S35 — Signing.** Release builds sign ONLY with a private keystore referenced by a gitignored `keystore.properties` (path via `FEEDBUCKET_KEYSTORE_PROPERTIES` env or repo-root file); a release assemble without it FAILS the build loudly (no silent fallback). The upstream public `signature/reader.keystore` is deleted from the tree in the foundation wave; debug builds use the standard Gradle debug keystore. CI never has signing material; CI assembles debug only.
**S36 — Font & licenses (corrected).** `google_sans_flex.ttf` (v3.007) is SIL OFL 1.1 (name-table ID13/ID14 + upstream OFL.txt, binding correction f0533707). KEEP the font; foundation adds `app/src/main/res/raw/ofl_google_sans_flex.txt` (full OFL text) and an attribution entry (About screen / AboutLibraries). GPL-3.0 preserved; NOTICE/attribution for Rome, readability4j, Jsoup, be.ceau opml-parser, compose-html, java-diff-utils (new, Apache-2.0) maintained via AboutLibraries. Any NEW dependency requires a license check recorded in the wave's review packet.
**S37 — Material 3 Expressive conformance (D17).** The design contract (creative director/designer cards, per approved direction steps 3) owns the dated conformance matrix (screen/component → official guideline → actual Compose API/version → justified adaptation → verification) against https://m3.material.io/ (incl. applying-m-3-expressive) and https://developer.android.com/develop/ui/compose/designsystems/material3 (updated 2026-11-10) with versions cross-checked at https://developer.android.com/jetpack/androidx/releases/compose-material3 (table at parent verification 2026-11-16: 1.4.0 stable, 1.5.0-rc01 RC). This spec pins the dependency posture: Compose BOM stable channel; material3 resolved version must be ≥ 1.4.0 stable and verified via `./gradlew :app:dependencies` output committed in the foundation review packet; the pre-existing explicit alpha `material3Adaptive = 1.3.0-alpha06` must be replaced by the BOM-managed stable adaptive artifacts or justified+documented as a bounded `@OptIn` experimental usage; no blind alpha chasing. Mandatory properties for every screen (Gate A and final visual review test these against rendered UI): semantic color/surface/state tokens (no hardcoded hex in feature code), current type & shape scales, purposeful motion honoring reduced-motion, component-correct buttons/chips/search/bars/sheets/dialogs, adaptive navigation & list-detail on larger windows, edge-to-edge insets + predictive Back where supported, ≥48dp actionable targets, TalkBack/RTL/font-scaling support, measured light/dark/dynamic-color contrast (AA text). Quiet reading surfaces remain (D4): no gratuitous list bouncing, no decorative shape morphs in reading views; the old flat web grammar (CSS radii/button styles) is NOT carried over.

## 12. Defaults & preferences summary (exact values)

| Setting | Default | Source/rationale |
|---|---|---|
| mark-read-on-open | ON | web `mark_read_on_open` default ON (S23) |
| mark-as-read-on-scroll | OFF | D5 (upstream pref kept) |
| mark-all-read confirmation | ON | web `mark_as_read_confirmation` default 1 |
| auto full-text extraction (global) | OFF for new installs; per-feed `isFullContent` governs | upstream model; web `auto_fetch_full` default ON is a WEB-extraction-on-open behavior Android replaces with explicit + per-feed paths (D6); decision recorded to avoid "porting" a hidden network-on-open default |
| per-feed show updates | ON | web `show_updates` default 1 |
| feed muted | OFF | web `muted` default 0 |
| hide recently-read view | OFF | web `hide_recently_read` default 0 |
| star rule budget | 100 / feed-refresh run | web star_budget (S17) |
| snapshot recency window | 7 days | web (S20) |
| snapshot significance | > 50 stripped chars delta | web (S20) |
| shortDescription length | 280 | upstream (kept) |
| list default limit | Paging 20/page, unbounded scroll | Android paging replaces web limit 500; ordering identical |
| preview sample | 20 | web preview_action |
| download caps | S28 table | decisions with rationale |
| position-table retention | 30 days | bounded UI-state table (S24) |
| sync concurrency | 16 feeds, 2 extractions | upstream semaphores preserved |

## 13. Acceptance tests (mandatory, named)

Unit (`app/src/test/java/me/ash/reader/…`, JVM):
1. `SearchQueryParserTest` — shlex-parity fixtures (quotes, escapes, unbalanced-quote fallback), `sort:`/`tag:` last-wins, term order; recorded from Python `shlex.split` outputs.
2. `QueryMatcherTest` — S12(b) substring-lowercase matching incl. Turkish-I ROOT-locale fixture; tag matching via feed.tags and group name.
3. `RuleEngineTest` — scope resolution (all/feedIds/groupIds∩tags/empty), ingest effects (mark_read suppresses unread; star budget 100 with 101st unmatched; update-path star skipped; update mark_read clears isUpdated), preview cap 20, apply-to-existing count + no-budget.
4. `UpdateDetectionTest` — compare-key matrices extracted/non-extracted, snapshot gate truth table (showUpdates × extracted × originalContent × recency × significance), first-version-only, extracted rows never clobbered.
5. `DiffTokenizerTest` — token regex parity, equal-token-lists → no diff, equal-length-different-text → diff (S22 improvement), failure → null.
6. `ExtractionGuardTest` — productivity thresholds (200/40%-median/120), challenge-marker rejection, failure preserves prior content, per-article isolation.
7. `DownloadPolicyTest` — caps enforcement (per-article/per-image/global 90%/100% behavior), state machine transitions, `downloading`-orphan reset-to-pending recovery, network policy split manual/bulk.
8. `MigrationImporterTest` — schema fingerprint accept/reject, secret-guard query allowlist (asserts `password_hash`/`starred_token` never queried), preview counts exactness on synthetic fixture DB, per-feed chunk rollback, resume-from-identity-map, duplicate re-import all-skipped, ledger counts, Remove-imported-data deletes exactly ledgered rows.
9. `OpmlParityTest` (extend existing `OPMLDataSourceTest`) — nested-folder tag inheritance, category CSV export, htmlUrl→siteUrl, duplicate skip counts.
10. `IdentityTest` — S6/S9 deterministic ids stable across runs; S7 guid-then-link matching; collision guard IGNORE.
Instrumented (`app/src/androidTest/java/me/ash/reader/…`, explicitly selected emulator only, D15):
11. `Migration7To8Test` — MigrationTestHelper v7 fixture → v8: exact schema match, data preserved, triggers/indices present, FTS round-trip.
12. `ArticleDaoSearchTest` — S11 SQL rendering incl. ESCAPE (`%`,`_`,`\` fixtures), body-only match, mid-word substring (LIKE must find what FTS cannot), extracted-content changes results, 4-field OR semantics, relevance ordering fixture, rowid tiebreak.
13. `PositionPersistenceTest` — scope-key canonicalization, restore after state clear (process-death surrogate), ±1-screen tolerance, corrupt-row fallback.
14. `OfflineRenderTest` — downloaded article renders with zero network calls (OkHttp interceptor assertion), scheme-block test (S30).
Each test list item maps to spec sections; a wave may not delete/weaken any of them (approved direction: no test weakening). REDCHECK discipline: every behavior-carrying change ships with a mutation check recorded in the wave review packet (break behavior → test red → restore → green).

## 14. Observable state tables (condensed; full offline table in §8)

Sync: `idle | syncing(progress n/m feeds) | success(updateAt bump) | partial(per-feed errors logged, others committed) | failed-all(retry available) | offline-banner`. Extraction: `idle | fetching | stored(fullContent,extractedAt) | rejected-unproductive(no state change) | failed(isolated, transient badge)`. Download: S27 machine. Import: `pick → validating(schema) → preview(counts) → applying(run ledger, resumable) → completed(ledger) | failed(chunk ledger) | cancelled(nothing written)`. Rule apply: `preview(count,20 samples) → applying(tx) → done(count, undo affordance) | error(no partial state)`. Positions: `active-scope persisted | restored-exact | restored-clamped | fallback-top`.

## 15. Amendment process

Changes to S# decisions require a spec-amendment commit on `feedbucket-native` referencing the requesting card, before any coder implements the change. Silent divergence is a review-blocking defect.
