# SPEC-RECONCILIATION v1 — numbered dispositions for the W1 spec/plan amendment

Status: FINAL. Authored by specifier card `t_8df74936` on 2026-11-16.
Authority: `docs/APPROVED-DIRECTION.md` (D1–D17) > amended `docs/SPEC-v1.md` > amended `docs/plans/IMPLEMENTATION-v1.md` > this document's evidence record.
Method: every finding was re-derived from primary sources (web repo code at `/home/jason/projects/feedbucket`, read-only; Android repo at `24fb08cb`; framework bytecode in `~/.gradle/caches/modules-2/files-2.1`; official docs retrieved 2026-11-16), not from document authority alone. Amended sections in `SPEC-v1.md` carry the marker `(amended by t_8df74936)`; amended plan steps carry `(amended)`.

Disposition summary: R1 ACCEPTED, R2 ACCEPTED, R3 ACCEPTED, R4 ACCEPTED, R5 ACCEPTED, R6 ACCEPTED, R7 ACCEPTED, R8 ACCEPTED, R9 ACCEPTED, R10 ACCEPTED IN PART (premise corrected by evidence, conclusion stands). No scope cuts; every acceptance lists the amended S#/plan sections.

Schema impact: all schema changes are folded into the ONE v8 migration now (S1 preserved: no later v1 schema changes). New/changed columns and tables are listed in §11 below.

---

## R1 — One canonical matching contract (amends S10, S11, S12, S13, S14, S15, S17; adds fixtures to §13)

**Verdict: ACCEPTED.** The committed spec violated D7 ("reuse one matching/query model for search, previews and ingest") and misstated web behavior.

Evidence:
- Web rule matcher is Unicode-casefold over a CONCATENATED string: `actions.py:77–78` — `searchable = "\n".join((title or "", summary or "", content or "")).casefold()`, `all(term.casefold() in searchable ...)`.
- Web SQL search is ASCII-case-insensitive LIKE over three columns: `db.py:167–169` (`like_term` escaping), `db.py:172–192` (`search_filters`: `e.title OR e.summary OR e.content`).
- Web query-mutes at LIST time are evaluated with the SQL path (`db.py:652–676` calls `search_filters`), while the SAME mute rows at INGEST time are evaluated with the casefold path (`actions.py:113` via `apply_actions_for_entry`). One rule, two match semantics — the web inconsistency the old S12 enshrined ("This asymmetry … is the REAL web behavior").
- The old S17 claim "Rules of type `mute` never have effects at ingest" is FALSE for web: `actions.py:105–123` iterates ALL action rows with NO `action_type` guard, and effects come from `action_row["effects"]` (default `'mark_read'`), so a web mute with default effects suppresses `unread` insertion at ingest (`actions.py:132–133`).
- Android status quo: unescaped `LIKE '%' || :text || '%'` over `title/shortDescription/fullContent` (`ArticleDao.kt:97–99` and six more variants; W0-verified).

Amendment (SPEC S12 rewritten as S12a "One canonical match contract"):
- Canonical normalization: `TextNormalizer.normalize(s) = s?.lowercase(Locale.ROOT) ?: ""` — ONE implementation (`domain/model/query/TextNormalizer.kt`), consumed by every write path (maintaining the new `titleLower/shortLower/rawLower/fullLower` shadow columns, §11), by `SearchSql` term prep, and by `QueryMatcher` (ingest/preview in-memory path).
- Canonical predicate: a term matches an article iff `normalize(term)` is a substring of `normalize(field)` for ANY of `title`, `shortDescription`, `rawDescription`, `fullContent` (per-field OR; terms AND-ed). SQL rendering: `(<lowerCol> LIKE '%' || :normTerm || '%' ESCAPE '\')` OR-ed across the four shadow columns, with the same `like_term` escaping port. Search, saved searches, previews, apply-to-existing, ingest rules and query mutes ALL use this one predicate — D7 satisfied.
- Deliberate, documented improvements over web: (a) SQL path becomes Unicode case-insensitive (web LIKE is ASCII-only); (b) mutes and rules evaluate with identical semantics at query time and ingest time (web splits LIKE vs casefold); (c) per-field OR replaces web's `"\n".join` concatenation, removing cross-boundary false matches.
- Deliberate, documented deviations from Python `casefold`: Kotlin `lowercase(Locale.ROOT)` performs SIMPLE case mapping, not full folding — `ß` does not match `ss`, ligatures do not expand. Chosen because SQLite LIKE has no full-fold mode, so any full-folding contract would be unimplementable identically in SQL and Kotlin. Pinned by fixtures.
- Mute/rule split (S17 amended): ONLY `type='standard'` rules have ingest effects; `type='mute'` rules are pure query-time filters on `unread`/`updated` (S15). This is a deliberate divergence from `actions.py:105–139` (recorded in S17), justified by D7 reversibility: a mute can be removed and the original unread state is intact, whereas web's ingest-time mute effect permanently consumes unread state.
- Regex/boolean/semantic search remain OUT (D7 honesty clause unchanged).
- New mandatory fixtures/tests (§13 items 1a, 2 amended): Unicode set (Greek `Σ/σ/ς`, Cyrillic, Turkish `İ/ı` — `"İ".lowercase()` = `"i̇"` U+0069 U+0307, so term `i` matches text containing `İ`; term `i` does NOT match dotless `ı`), non-ASCII casefold pins (`straße` does not match `strasse`), and a body-after-280chars fixture (term occurring only beyond char 280 of the body must match via `rawLower`/`fullLower` even though the 280-char `shortDescription` lacks it).
- Migration/backfill: shadow columns default `''`; SQL `lower()` is ASCII-only so the normalization backfill for pre-existing rows runs as a one-shot resumable Kotlin pass on first DB open after migration (S5 step 7). Write-path invariant: every writer of the four text columns recomputes the matching Lower column in the same transaction (unit-tested).

Rejected alternative: a custom SQL function (Room 2.7 supports custom functions) was rejected — per-row JNI lowercasing of full HTML bodies on every query is a performance hazard; shadow columns trade ~2× article-text storage (measured proxy: live web corpus 3 067 entries ≈ 26 MB text, read-only query 2026-11-16) for C-speed LIKE scans.

## R2 — Honest, durable Undo for apply-to-existing (amends S18; adds tables to §2.3/§11)

**Verdict: ACCEPTED.** Old S18 stored only flipped article IDS in the rules-screen session (bounded 10 000, in memory): it could not restore previous `readAt`/`isUpdated`/star state, did not survive process death, and could silently clear PRE-EXISTING stars on "undo" (violating D7 "all effects must be reversible" and "no clearing pre-existing stars").

Amendment: a durable before-state ledger, included in schema v8 NOW (S1 freeze respected):
- New tables `state_apply_run` and `state_apply_before` (§11) capture, inside the SAME transaction as apply-to-existing: per-article `wasUnread`, `wasStarred`, `wasUpdated`, `readAtBefore`, and `stateVersionAtApply`.
- New column `article.stateVersion INTEGER NOT NULL DEFAULT 0`, bumped by every write that changes `isUnread/isStarred/isUpdated/readAt` (single DAO choke point, unit-tested).
- Undo contract: available for the most recent completed, not-yet-undone apply run; restores only articles whose `stateVersion` is unchanged since the ledger row was written; changed articles are SKIPPED and reported honestly ("Restored N · skipped M (changed after apply)"). `wasStarred=1` restores to starred — pre-existing stars are never cleared. Survives process death (Room-durable). Bounded: all but the last 10 runs pruned on each new apply.
- UI keeps the exact count confirmation BEFORE apply (old S18 behavior retained) — the ledger makes the Undo claim true instead of removing it.
- Ingest-time effects remain WITHOUT a ledger (individually reversible via mark-unread/unstar; star budget 100/run bounds blast radius) — explicitly documented, not a silent cut.

## R3 — Secret-free versioned export, durable resume, exact-provenance removal (amends S32; adds columns to §2.3/§11)

**Verdict: ACCEPTED.** Old S32 had the user hand the app a raw `.backup` copy of the live DB — which physically contains `users.password_hash` and `users.starred_token` (web schema, `db.py` SCHEMA; SPEC §1). "The importer never queries users" is not a secret-free EXPORT package under D11. The old resume story relied on app-cache purge with no durable locator, and "Remove imported data" keyed off `import_identity` alone could delete pre-existing rows re-matched by a later import.

Amendments:
1. **Export tool, outside the live web repo**: `scripts/export_feedbucket_package.py` (versioned in THIS repo, Wave G). It opens the source DB read-only (`file:...?mode=ro` URI + `PRAGMA query_only=ON`), snapshots via the SQLite Online Backup API to a temp file, and writes `feedbucket-export-v1.sqlite` containing ONLY allowlisted tables (`subscriptions, entries, unread, starred, updated, recently_read, tags, subscription_tags, saved_searches, actions`, optional `user_prefs`) copied via explicit column lists — `users` is never opened or referenced — plus a `package_meta` table (`schemaVersion=1, createdAt, sourceSha256, per-table counts`). Output gates: `PRAGMA integrity_check`, allowlist assertion (no table/column outside the list), secret scan (no `password_hash`/`starred_token` column names; no itsdangerous-shaped dotted-token values). The live web DB is never written (D14 preserved).
2. **Importer accepts** the v1 package (preferred; `package_meta.schemaVersion` validated, unknown version → precise rejection) OR a raw DB copy (compat path; existing in-app column-allowlist + query-log secret guard retained). Preview→apply identity: the apply phase re-fingerprints (sha256) the picked file and ABORTS unless it matches the preview fingerprint recorded in `import_run.sourceFingerprint`.
3. **Durable resume**: new columns `import_run.sourceUri` (provenance display) and `import_run.sourcePath` — the picked file is copied to `filesDir/imports/<runId>.db` (durable internal storage, NOT cacheDir) before any work starts; retained until the run reaches a terminal state AND the user leaves the result surface; orphaned copies GC'd on app start after 7 days. Resume after process death reads the retained copy, never the original SAF URI (persisted grants can lapse).
4. **Exact-provenance removal**: `import_identity` gains `createdByRunId INTEGER NULL` (set ONLY when that run actually created the target row; identity-map skip hits record NULL) and `stateStamp TEXT NOT NULL` (articles: `stateVersion` at import; feeds: sha256 of user-editable fields `title,tags,groupId,muted,showUpdates,isFullContent`; saved_search/action_rule: sha256 of content fields). "Remove imported data" deletes ONLY rows with `createdByRunId = <run>` AND matching current `stateStamp`; mismatches are skipped and reported ("Removed N · skipped M (edited after import)"). Pre-existing matched rows and records edited since import are NEVER deleted (D13 non-destructive).

## R4 — Real FTS external-content mechanics; runtime (not compile) migration RED (amends S5, S16; amends plan Wave B task 5)

**Verdict: ACCEPTED.** Evidence, verified from Room 2.7.2 artifacts and official docs (retrieved 2026-11-16):
- `room-compiler-2.7.2.jar` → `androidx.room.vo.FtsEntity` class constants: FTS DDL `CREATE VIRTUAL TABLE IF NOT EXISTS …`; sync-trigger templates `CREATE TRIGGER IF NOT EXISTS … BEGIN DELETE FROM <fts> WHERE \`docid\`=OLD.\`rowid\`; END` and the insert counterpart; trigger-name prefix `room_fts_content_sync_`. So the FTS `docid` maps directly to the content table's implicit `rowid` — a TEXT-PK `article` table is a rowid table and needs NO hand-built mapping table; Room owns the triggers.
- Room `Fts4` KDoc (developer.android.com/reference/androidx/room/Fts4, updated 2026-10-26): external-content mode — "Room will create the necessary triggers to keep the tables in sync"; "The content sync triggers created by Room will be removed before migrations are executed and are re-created once migrations are complete." The INTEGER-affinity PK requirement applies to the FTS entity's own `rowid` field, NOT to the content entity's PK.
- SQLite FTS3/4 doc §6.2.2 (sqlite.org/fts3.html): creating an external-content FTS table does NOT index existing rows; the `INSERT INTO <fts>(<fts>) VALUES('rebuild')` command reindexes from the content table.
- Room does NOT fail at compile time for a version bump without a migration: `room-runtime-android-2.7.2.aar` → `androidx.room.RoomOpenHelper` carries the runtime message "A migration from … was required but not found. Please provide the necessary Migration path via RoomDatabase.Builder.addMigration(Migration …)". Plan Wave B task 5's "Compile RED first: Room processor fails on version bump without migration" is FALSE and was rewritten.

Amendments: S5 step 4 replaced — `MIGRATION_7_8` creates `article_fts` with Room's exact generated DDL from `8.json` and then runs `INSERT INTO article_fts(article_fts) VALUES('rebuild')` to backfill the existing v7 corpus; it must NOT hand-create sync triggers (Room drops/recreates `room_fts_content_sync_*` around migrations; duplicates would corrupt sync). `Migration7To8Test` verifies behaviorally: post-migration DAO insert → MATCH finds it; update → index follows; delete → gone. Plan Wave B: RED is now runtime-red (migration test fails with the not-found IllegalStateException before the migration is added), green after; exported-schema validation (8.json) enforces the exact model. S16 coverage indicator amended to be account-scoped: `COUNT(article_fts ⋈ article ON article.rowid = article_fts.docid WHERE accountId=:id)` vs `COUNT(article WHERE accountId=:id)` (old `COUNT(article_fts.docid)` counted ALL accounts).

## R5 — Coherent download readiness, atomic manifests, safe local origin, real WebView offline tests (amends S27, S30, §8 table, plan Wave F)

**Verdict: ACCEPTED.** Old S27 called staging "atomic at completion" while flipping rows to `ready` before images landed, and old S30 demanded "no file access" while offline rendering inherently needs local file reads — with a blanket-settings guess instead of an origin-scoped design. Evidence from upstream: `WebViewLayout.kt:36` sets `allowFileAccess = true` (External-font branch), `:42–43` `domStorageEnabled = true; javaScriptEnabled = true` unconditionally, `:44–54` registers a JavascriptInterface; `WebViewClient.kt:25–58` `shouldInterceptRequest` performs RAW `HttpURLConnection` network fetches (lines 44–53) that never pass through OkHttp — so an "OkHttp interceptor asserts zero calls" test (old §13 item 14) proves nothing about the WebView path.

Amendments:
- **Readiness model (S27 amended)**: `ready` means TEXT committed — sanitized `index.html` + `manifest.json` staged in `<hash>.staging/` and committed by a single atomic directory rename; the DB row flips to `ready` only after the rename succeeds. New columns `article_download.expectedImageCount`, `article_download.assetsComplete`. Images continue after `ready`; each asset landing rewrites the manifest atomically (`manifest.tmp` → rename) and bumps `imageCount/imageBytes`; `assetsComplete=1` when the manifest's asset set is final (all fetched or terminally skipped under caps). UI states: "Text saved · images N/M" vs "Saved" — honest granularity per D9. Recovery unchanged+extended: startup reconciliation resets orphan `downloading` → `pending` and garbage-collects orphan staging dirs.
- **Safe local origin (S30 amended)**: the downloaded-content renderer is a hardened configuration, distinct from the upstream reading pipeline: `javaScriptEnabled=false`, `domStorageEnabled=false`, `allowFileAccess=false`, `allowContentAccess=false`, `allowFileAccessFromFileURLs=false`, `allowUniversalAccessFromFileURLs=false`, no JavascriptInterface. Local assets are served through `androidx.webkit.WebViewAssetLoader` (webkit 1.18.0 verified in the local Gradle cache; `InternalStoragePathHandler` present) rooted at `filesDir/downloads/<accountId>/<sha256(articleId)>/` under the virtual origin `https://appassets.androidplatform.net/…`. This restricts file exposure to ONE local renderer origin instead of a blanket file-access toggle. `shouldInterceptRequest` delegates ONLY to the asset loader; every non-appassets request returns an empty `WebResourceResponse` (blocked); link taps route to the external-browser intent, never in-view navigation. Scheme-block test (`javascript:`, `data:`, `file:`, `intent:`, `content:`) asserts non-load and non-navigation on THIS path. The upstream JS image-click bridge does not run offline; image interaction on downloaded articles degrades to the native renderer or no-zoom — designer owns the UX, D16 safety wins.
- **Zero-network tests on the actual WebView request path (§13 item 14 amended, plan Wave F)**: instrumented test with device connectivity disabled renders a fixture-downloaded article through the real reading entry point and records every `shouldInterceptRequest`/`onLoadResource` callback: all served URLs must be appassets-origin, total non-local requests must be ZERO. The OkHttp-interceptor assertion is retained only as a secondary check and is no longer accepted as primary evidence (it cannot see `HttpURLConnection` fetches, per WebViewClient.kt:44–53).
- **§8 offline table aligned**: "Airplane mode, open downloaded article" now cites the WebView-path test; "Network lost MID-download" cites the amended S28a retry semantics.

## R6 — Truthful WorkManager retry/backoff semantics (amends S28, §8 table, plan Wave F)

**Verdict: ACCEPTED.** Old S28 claimed "3 retries, exponential backoff (1/5/25 min, WorkManager defaults)" — false on every element: WorkManager has no ×5 multiplier and no minute-scale default. Evidence from `work-runtime-2.10.1.aar` bytecode (javap, 2026-11-16): `WorkSpec.setBackoffDelayDuration` clamps to `[10 000 ms, 18 000 000 ms]` ("Backoff delay duration less than minimum value"/"exceeds maximum value"); `WorkSpec$Companion.calculateNextRunTime` computes LINEAR as `backoffDelayDuration × runAttemptCount` and EXPONENTIAL as `Math.scalb(backoffDelayDuration, runAttemptCount - 1)` = `delay × 2^(runAttemptCount-1)`, capped at 5 h; the public API is `setBackoffCriteria(BackoffPolicy.LINEAR|EXPONENTIAL, delay, unit)` (OneTimeWorkRequest.Builder reference, retrieved 2026-11-16). `ListenableWorker.getRunAttemptCount()` is 0-based on first execution.

Amendment (S28 row replaced, S28a): max 3 automatic retries enforced IN THE WORKER (`runAttemptCount >= 3` → terminal `failed(network)` + row state; else `Result.retry()`); `setBackoffCriteria(EXPONENTIAL, 30, SECONDS)` → retry-delay floors of 30/60/120 seconds per the verified formula, clamped to [10 s, 5 h]. All delays are FLOORS: constraints, Doze and battery optimization may defer execution; no exact wake time is promised anywhere in UI or docs (D12 honesty). Metered/connectivity state table aligned: manual single-article downloads run with NO network constraint (explicit user intent; on airplane mode the attempt fails fast to `failed(network)`); bulk and chained downloads gate on `UNMETERED` (+ charging per account prefs) and while gated show a distinct honest state "Waiting for Wi-Fi" (row stays `pending`, WorkInfo ENQUEUED) — never `failed`. `DownloadPolicyTest` (unit, TestListenableWorkerBuilder) pins the attempt-count terminal rule and the manual/bulk constraint split.

## R7 — Publisher-date provenance and one ordering authority (amends S20, S21; adds `article.publishedAt`)

**Verdict: ACCEPTED.** Two real defects:
- Old S20 stated "unparseable/absent publisher date → NOT recent", but web defaults `recent = True` when `published IS NULL` (`db.py:526–537`: `recent = True` is assigned first and only overwritten inside `if existing["published"] is not None`; `ValueError → recent=False` applies solely to PRESENT-but-unparseable strings). The spec contradicted its own cited source.
- Upstream `RssHelper.buildArticleFromSyndEntry` (`RssHelper.kt:223–225`) sets `date = (publishedDate ?: updatedDate)?.takeIf { !it.isFuture(preDate) } ?: preDate` — for a dateless entry `date` is re-minted from sync time on EVERY refresh. Had `date` stayed in the S20 compare keys, every refresh of every dateless article would look "changed": fake updates, snapshot churn, updated-queue spam.

Amendments:
- New column `article.publishedAt INTEGER NULL` — the publisher-provided date (`publishedDate ?: updatedDate`, future-guarded), NULL when absent/future/unparseable. Set at insert; on update it changes ONLY when the feed supplies a different publisher date. Compare keys (S20 amended) use `publishedAt`, never `date`.
- Display/sort `date` keeps upstream meaning but is frozen for dateless rows: `date = publishedAt ?: syncTimeAtInsert`, and on updates with `publishedAt == NULL` the stored `date` is NOT touched — no manufactured freshness, no fake updates.
- Snapshot recency gate (S20 amended): `publishedAt == NULL → recent = true` (exact web parity, db.py:527); present → within 7 days of now. Documented bounded deviation: Rome surfaces unparseable dates as null, so web's `ValueError → recent=False` branch is UNREACHABLE on Android; invalid≡missing→recent. Pinned by an UpdateDetectionTest note, not silently dropped.
- Ordering conflict resolved in favor of S15 as the single authority (web parity): the `updated` view uses the DEFAULT order `date DESC, rowid DESC` — `db.py:627` shows `list_entries(view='updated')` only adds the join, never an `updated_at` order. S21 amended: `contentChangedAt` is provenance/badge data and is NEVER a sort key.

## R8 — Collision-free scope keys and a list-generation model (amends S24, S25)

**Verdict: ACCEPTED.**
- Old S24's key `"<view>|<feedId?>|<groupId?>|<savedSearchId?>|<query>|<sort>"` with `-` empty placeholders is not injective: `query` may contain `|`, and a literal `-` value is indistinguishable from an empty slot (e.g. feedId `-` vs absent), so two distinct scopes can collide and overwrite each other's saved position.
- Amendment (S24a): `scopeKey = "v1/" + components.map { pctEncode(it ?: "") }.joinToString("/")` over `[view, feedId, groupId, savedSearchId, query, sort]`, where `pctEncode` percent-encodes everything outside RFC 3986 unreserved (`A-Za-z0-9-._~`). The encoding is injective → collision-free; the `v1/` prefix versions the scheme for future changes. Exact-restore tests mandated: queries containing `/`, `%`, `|`, `-`, Unicode and empty strings; slot-shift pairs (empty vs `-`) must produce DIFFERENT keys; round-trip parse equality.
- Old S25's "the flow list does NOT re-query" over-blocked: taken literally it would also suppress legitimate NEW articles arriving via sync. Amendment (S25a) — snapshot/generation model: the visible list for a scope is a GENERATION. Writes that change read-state flags (`isUnread/isStarred/isUpdated/readAt`) of articles already in the generation update those items IN PLACE (badge/weight) and must not remove, reorder or invalidate the generation — mark-read never breaks prev/next or loses rows (D5). Only sync completion or explicit user refresh rolls to a NEW generation, which may legitimately add incoming content. Paging invalidation triggered by read-state writes is suppressed per this contract; tests pin both halves (sticky retention AND new-content-after-refresh).

## R9 — Identity badging, release-scoped signing failure, targetSdk 36 (amends S35, plan Wave A tasks 3/4, Wave H item 5; adds S38)

**Verdict: ACCEPTED.**
- Plan Wave H item 5 expected `aapt dump badging` of the CI/debug artifact to show package `us.snakepit.feedbucket` — impossible: with `applicationIdSuffix ".debug"` (S34/D2) the debug package is `us.snakepit.feedbucket.debug` with label `FeedBucket D`. Amended: badging expectations are stated PER BUILD TYPE (debug → `us.snakepit.feedbucket.debug` / "FeedBucket D"; locally built release → `us.snakepit.feedbucket` / "FeedBucket"); the `googlePlay` flavor's existing `.google.play` suffix (`app/build.gradle.kts:71`) is noted so no check ever assumes a suffix-free debug id.
- Old plan Wave A task 4 wired `throw GradleException` at CONFIGURATION time — that would break EVERY task including all debug builds. Amended (S35a): configuration phase never throws on missing signing material; the loud failure is scoped to release execution via `gradle.taskGraph.whenReady` (fail iff scheduled tasks match release assembly/packaging and keystore props are absent), and the upstream `all { signingConfig = signingConfigs.getByName("release") }` block (`app/build.gradle.kts`, buildTypes) is removed so debug builds use the standard debug keystore. Expected-red check unchanged: `assembleGithubRelease` fails loudly with no material; `assembleGithubDebug` is green.
- targetSdk (new S38): current tree is `compileSdk 36 / targetSdk 34` (`app/build.gradle.kts:39,44`). Current guidance (developer.android.com/about/versions/16/behavior-changes-16, retrieved 2026-11-16; Play target-API-36 deadline 2026-08-31, extension 2026-11-01): v1 raises `targetSdk` to 36 in Wave A with a behavior-change checklist — edge-to-edge enforcement with the opt-out attribute disabled (aligns with S37 insets requirement); predictive back enabled by default with `onBackPressed`/`KEYCODE_BACK` no longer dispatched (audit upstream back handling; Navigation3 baseline must be verified on emulator); orientation/resizability locks ignored on ≥600dp-sw screens (aligns with the adaptive direction; no opt-out property used); fixed-rate scheduling change (WorkManager unaffected). The app ships sideloaded/F-Droid (not Play), so this is guidance alignment, not store compliance — recorded honestly.

## R10 — Gate A duplication, adaptive-artifact posture, command/path hygiene (amends plan Gate A, S37, Wave A task 6)

**Verdict: ACCEPTED IN PART** — the required outcomes stand, but the finding's premise ("adaptive artifacts are not necessarily BOM-managed") is corrected by direct evidence: they ARE BOM-managed, at older versions than the repo pins.

Evidence (all retrieved/inspected 2026-11-16):
- `compose-bom-2025.10.01.pom` (local Gradle cache): manages `androidx.compose.material3:material3` → **1.4.0**, `material3-adaptive-navigation-suite` → **1.4.0**, and `androidx.compose.material3.adaptive:{adaptive, adaptive-layout, adaptive-navigation}` → **1.2.0**. It does NOT contain `adaptive-navigation3`.
- Repo pins (`gradle/libs.versions.toml:58,137–139`): `adaptive-navigation`/`adaptive-layout` at `material3Adaptive = 1.3.0-alpha06` (explicit alpha, overriding the BOM) and `adaptive-navigation3` at `nav3 = 1.0.0`.
- Official releases page (developer.android.com/jetpack/androidx/releases/compose-material3-adaptive): stable **1.3.0** (2026-08-12; `adaptive-navigation3` announced in the 1.3.0 train), alpha 1.4.0-alpha03 (2026-10-07); 1.3.0-beta01 moved scaffold-order APIs BACK to experimental — opt-in annotations must be read from the resolved artifacts, not assumed.

Amendments:
- S37 amended: replace `1.3.0-alpha06` with explicit STABLE pins `androidx.compose.material3.adaptive:* = 1.3.0` (separate from the BOM by necessity — BOM 2025.10.01 only manages 1.2.0 and omits `adaptive-navigation3`; mixed BOM-1.2.0/pinned-navigation3 resolution is a compatibility hazard), verify the `adaptive-navigation3` version that actually resolves (the `1.0.0` pin must be reconciled against the 1.3.0 stable train) via committed `:app:dependencies` evidence in the Wave A review packet; record every `@OptIn` (e.g. `ExperimentalMaterial3AdaptiveApi`) from the RESOLVED KDoc into the honest D17 matrix. `material3 ≥ 1.4.0` is already satisfied by BOM 2025.10.01 (pom evidence) — the plan's "bump the BOM" branch is now conditional-only. No alphas, no RCs.
- Plan Gate A amended: the actual board graph is the authority. The creative-director card (t_1abc9a6b, DIRECTION-v1.md committed at `24fb08cb`) is COMPLETE and the designer card (t_b548f84e) EXISTS, gated on this reconciliation card. Gate A must NOT create duplicate design cards or claim parallel design/Wave A–B scheduling; it validates docs-vs-source and passes D17 + the amended spec + `DIRECTION-v1.md` + this reconciliation into the designer card's context. The design contract is still required before Wave G starts.
- Command/path hygiene: plan Wave A task 6's `> /tmp/deps.txt` redirected probe output to a world-readable, unmanaged path — amended to write under the kanban workspace (`build/recon/`, gitignored) or the managed scratch dir; all fixture-generation one-liners keep using the web repo venv READ-ONLY (D14) with the exact command recorded. Unsupported command claims in the plan were re-checked against the tree: `ui/page/adaptive/{ArticleListReaderViewModel,ArticleListReadingPage,PaneMotion,TopBar}.kt` and `ui/page/nav3/AppEntry.kt` exist (verified 2026-11-16); `ArticleDao.insertListIfNotExist` is at :909 (spec said 908–919 — within range); `RssHelper.parseFullContent` at :116 and id minting at :220 verified; `Article.fullContent` `@Deprecated` at `Article.kt:34–35` verified.

---

## 11. Consolidated schema v8 amendments (all inside the ONE `MIGRATION_7_8`, S1 preserved)

`article` — columns ADDED relative to the original SPEC §2.1 (on top of `guid`, `originalContent`, `contentChangedAt`, `isUpdated`, `readAt`, `extractedAt`):

| Column | Type | Default | Decision |
|---|---|---|---|
| `publishedAt` | INTEGER | NULL | R7/S20a — publisher-date provenance, never re-minted |
| `stateVersion` | INTEGER NOT NULL | 0 | R2/S18a — bumped on every isUnread/isStarred/isUpdated/readAt write |
| `titleLower` | TEXT NOT NULL | `''` | R1/S12a — normalize(title) shadow |
| `shortLower` | TEXT NOT NULL | `''` | R1/S12a — normalize(shortDescription) shadow |
| `rawLower` | TEXT NOT NULL | `''` | R1/S12a — normalize(rawDescription) shadow |
| `fullLower` | TEXT NOT NULL | `''` | R1/S12a — normalize(fullContent) shadow |

`article_download` — ADDED: `expectedImageCount INTEGER NOT NULL DEFAULT 0`, `assetsComplete INTEGER NOT NULL DEFAULT 0` (R5/S27a).

`import_run` — ADDED: `sourceUri TEXT NULL`, `sourcePath TEXT NULL` (R3/S32a durable resume).

`import_identity` — ADDED: `createdByRunId INTEGER NULL`, `stateStamp TEXT NOT NULL DEFAULT ''` (R3/S32a exact-provenance removal).

NEW tables:

```
state_apply_run   (id INTEGER PK AUTOINCREMENT, ruleId INTEGER NULL,
                   kind TEXT NOT NULL DEFAULT 'apply_to_existing'
                        CHECK (kind IN ('apply_to_existing')),
                   startedAt INTEGER NOT NULL, finishedAt INTEGER NULL,
                   appliedCount INTEGER NOT NULL DEFAULT 0,
                   undoneAt INTEGER NULL,
                   undoRestoredCount INTEGER NOT NULL DEFAULT 0,
                   undoSkippedCount INTEGER NOT NULL DEFAULT 0)

state_apply_before (runId INTEGER NOT NULL, articleId TEXT NOT NULL,
                   wasUnread INTEGER NOT NULL, wasStarred INTEGER NOT NULL,
                   wasUpdated INTEGER NOT NULL, readAtBefore INTEGER NULL,
                   stateVersionAtApply INTEGER NOT NULL,
                   PRIMARY KEY (runId, articleId))   -- FK state_apply_run.id CASCADE
```

Unchanged from the original §2: `saved_search`, `action_rule`, `article_fts` (Room FTS4 external-content over the four text columns; docid = article implicit rowid), `download_asset`, `list_position`, `reading_position`, plus all `feed` columns.

## 12. Amendment→S# index (every accepted change, no silent cuts)

| Finding | Amended spec sections | Amended plan sections |
|---|---|---|
| R1 | S10, S11→S11a, S12→S12a, S13 (unchanged semantics, matcher source), S14 (lower cols), S17 (mute/standard split), §2.1, §13 items 1a/2/3/12 | Wave B (TextNormalizer, shadow cols, backfill, tests), Wave D (mutes never ingest) |
| R2 | S18→S18a, §2.1 (stateVersion), §2.3 (ledger tables), §12, §13 item 3a | Wave D (ledger write in apply tx, UndoLedgerTest) |
| R3 | S32→S32a (export tool, package v1, retention, provenance), §2.3 (import_run/import_identity cols), §13 item 8 | Wave G (export tool + tests, filesDir/imports, remove-imported stamp check) |
| R4 | S5 steps 4/6→S5a (+ step 7 backfill), S16 (account-scoped count), §13 item 11 | Wave B task 5 (runtime RED, rebuild, no hand triggers), task 6 |
| R5 | S27→S27a (readiness/manifest/recovery), S30→S30a (hardened local-origin renderer), §8 table, §13 item 14 | Wave F (WebViewAssetLoader path, WebView-level zero-network test) |
| R6 | S28 retry row→S28a, network-policy row, §8 table (waiting-wifi), §12 | Wave F (worker attempt-count rule, constraints split) |
| R7 | S20→S20a (publishedAt, recency, frozen date), S21→S21a (ordering authority = S15), §2.1, §13 item 4 | Wave E (compare keys, truth-table three-state recency) |
| R8 | S24→S24a (percent-encoded versioned key), S25→S25a (generation model), §13 item 13 | Wave C (scope-key round-trip tests, sticky+refresh tests) |
| R9 | S35→S35a (release-scoped failure), NEW S38 (targetSdk 36) | Wave A tasks 3/4 + new task 10, Wave H item 5 (per-build-type badging) |
| R10 | S37→S37a (adaptive stable pins, BOM evidence, opt-ins) | Gate A (no duplicate cards), Wave A task 6 (workspace probe path, conditional BOM branch) |
