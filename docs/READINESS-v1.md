# FeedBucket Android — READINESS v1 (Gate A decision map)

Status: FINAL — GO. Authored by orchestrator card `t_a0ff683d` on 2026-11-16 (Gate A). This is a document-readiness checkpoint; no source was written. It records what was independently verified, the decisions taken on the contained contract clarifications, the baseline facts the foundation wave inherits, and the build blockers foundation must handle. Authority: `docs/APPROVED-DIRECTION.md` (D1–D17) > `docs/SPEC-v1.md` (v1.1) > `docs/plans/IMPLEMENTATION-v1.md` > `docs/research/SPEC-RECONCILIATION-v1.md` > `docs/creative/{DIRECTION-v1,UI-CONTRACT-v1,QA-v1}.md` > this document.

## 1. Verdict

**GO.** Every binding S# decision has a documented source/premise that was re-derived or spot-verified against the actual repo, dependency artifacts, or official docs at this gate; the five contract clarifications that Gate A was required to resolve (UI-CONTRACT §11, orchestrator relay 2026-11-16 20:06) have been resolved directly in the spec (S18a / S27a / S32a) with no replanning round and no new design cards. The foundation card `t_92e517cf` (Wave A, assignee coder) is released to build. Trivial claims (UI text, fixture labels) that cannot affect behavior are not gate blockers.

NO-GO conditions that would have blocked (none met): missing/misleading committed artifacts; source-path claims that did not exist; framework/arithmetic claims that contradicted bytecode or official docs; a D1–D17 decision silently dropped; unresolved §11 contradictions; physical-device or web-service-retirement prohibitions violated.

## 2. Independent verification performed (this gate)

| Item | Verified result | Method |
|---|---|---|
| Git state | Branch `feedbucket-native` clean (`git status` empty), HEAD = `origin/feedbucket-native` = `030e6c70`; upstream `main` (`d2b979cc`) is an ancestor; 12 commits since base are docs-only (verified per-commit `git diff-tree` file lists) | `git status/log/remote/rev-list` |
| Source paths cited in SPEC §1 | All cited files exist: `RssHelper.kt` (:116 `parseFullContent`, :220 UUID id minting, :224–225 date logic), `ArticleDao.kt` (:909 `insertListIfNotExist`, 27 LIKE variants), `WebViewLayout.kt` (:36 allowFileAccess, :42–43 domStorage/JS, :44 JavascriptInterface), `WebViewClient.kt` (:46/:49 raw `HttpURLConnection`), `ui/page/adaptive/{ArticleListReaderViewModel,ArticleListReadingPage,PaneMotion,TopBar}.kt`, `ui/page/nav3/AppEntry.kt`, `domain/service/{LocalRssService,SyncWorker,ReaderWorker,OpmlService}.kt`, `infrastructure/{html/Readability,rss/ReaderCacheHelper,rss/OPMLDataSource}.kt`, `ui/page/home/{flow,reading}/*`, `ui/theme/{Type,Theme,Shapes}.kt`, `ReadingFontsPreference.kt` (default GoogleSans), `FlowArticleListDateStickyHeaderPreference.kt` (default OFF), `MarkAsReadOnScrollPreference.kt`, `Article.kt:34–35` (`@Deprecated` fullContent), `OPMLDataSourceTest.kt` | `ls`/`grep`/`sed` |
| Room schema baseline | `AndroidDatabase.kt:23` version = 7; exported schemas `app/schemas/me.ash.reader.infrastructure.db.AndroidDatabase/{5,6,7}.json` present; 7.json identityHash `f5342dd1355611f8e4d6c980d85ba657` | file read + grep |
| Version pins | `libs.versions.toml`: room 2.7.2, work 2.10.1, paging 3.3.6, datastore 1.1.4, BOM 2025.10.01, kotlin 2.2.0, okhttp 5.0.0-alpha.12, rome 2.1.0, opml-parser 3.1.0, `material3Adaptive = 1.3.0-alpha06` (:58), `nav3 = 1.0.0` (:55) | file read |
| Build identity baseline | `app/build.gradle.kts`: applicationId `me.ash.reader`, minSdk 26 / targetSdk 34 / compileSdk 36, versionCode 47 / versionName 0.16.2, `googlePlay` suffix `.google.play`, upstream `all { signingConfig = release }` block present (:93) — all match SPEC §1 / ANDROID-BASELINE | file read |
| WorkManager retry arithmetic | Re-derived from `work-runtime-2.10.1.aar` bytecode: LINEAR = `delay × runAttemptCount`; EXPONENTIAL = `Math.scalb(delay, runAttemptCount - 1)` (instructions: lload/l2f/iload/iconst_1/ISUB/scalb); result clamped via `coerceAtMost(18_000_000)`. Floors for initial 30 s = 30/60/120 s. Confirms amended S28a (744df21d) | `javap -c` |
| Font license | Bundled `google_sans_flex.ttf` name table: ID13 = SIL OFL 1.1, ID14 = https://openfontlicense.org, ID0 = "Copyright 2015 Google LLC. All Rights Reserved." Confirms f0533707 and corrects the UI contract's "2022 project authors"-only line (patched to include the binary notice) | TTF name-table parse |
| Mock QA evidence | Raw `qa-summary.json`: 101 cases, 0 failures, 776 targets, min 48×48, 0 small targets / hit failures / overflow failures / external resources, font loaded in all; viewport split 390×844×77 / 768×1024×11 / 320×800×8 / 320×844×5; interactions JSON confirms 24px list + 96px article exact restoration, focus return | JSON aggregation |
| Contrast | Independently recomputed WCAG relative luminance from UI-CONTRACT §5.1 tokens: representative pairs reproduce QA exactly (onPrimary/primary 7.79/7.80/6.44/7.71; onPrimaryContainer/primaryContainer 10.43/5.89/13.32/7.23; etc.); text pairs ≥ 5.89, component/focus ≥ 3:1 | Python WCAG |
| D1–D17 preservation | See §3 mapping; D17 carried verbatim into direction, contract §10 matrix, spec S37, and the foundation card thread | doc cross-read |
| Design/QA evidence boundary | QA-v1.md states plainly: measured HTML reference only; NOT Compose runtime / TalkBack / emulator / device proof; production dynamic palettes, native semantics, API 36 remain implementation gates — preserved, not overstated | doc read |

## 3. Approved D1–D17 preservation map

| D | Where preserved | Status |
|---|---|---|
| D1 standalone no-account local reader | SPEC §0 scope; plan architecture | ✓ |
| D2 identity / applicationId / debug suffix / labels / GPL / independent signing | SPEC S34, S35a; plan Wave A tasks 3/4 | ✓ |
| D3 baseline d2b979cc, feedbucket-native only, no force-push/AI import | ANDROID-BASELINE; D3 in APPROVED-DIRECTION; git verified | ✓ |
| D4 current Material 3 design | DIRECTION-v1; UI-CONTRACT §5/§10 | ✓ |
| D5 browse/unread/positions/mark-read prev-next | SPEC S23–S26; UI contract §6.1/§8.4 | ✓ |
| D6 extraction honesty | SPEC S19; UI contract §6.5 | ✓ |
| D7 saved searches/rules/mutes one model, reversible | SPEC S10–S18a (S12a canonical, S18a durable undo); UI contract §6.8 | ✓ |
| D8 recently read/update diffs, GUID separation | SPEC S20a–S22; guid column S2.1; UI contract §6.6/§6.7 | ✓ |
| D9 durable downloads, honest states | SPEC S27a–S29; UI contract §6.9 | ✓ |
| D10 local FTS search, coverage honesty | SPEC S16; S5 step 4/7; UI contract §6.4 | ✓ |
| D11 OPML + versioned secret-free migration | SPEC S32a–S33; UI contract §6.10 | ✓ |
| D12 best-effort WorkManager, honest | SPEC §10, S28a; UI copy rule | ✓ |
| D13 deferrals; no destructive dedup | SPEC §0; S8/S32a non-destructive provenance | ✓ |
| D14 web service untouched, read-only | SPEC S32a (export tool outside web repo, live DB never written); plan global constraints | ✓ |
| D15 no physical devices without consent | Plan global constraints; S38 emulator-only; D15 in spec §0 | ✓ |
| D16 no secrets/corpus/telemetry; sanitization | SPEC S30a, S31; synthetic fixtures; gitignore list | ✓ |
| D17 Material 3 Expressive gate | D17 verbatim in APPROVED-DIRECTION; S37; DIRECTION-v1 matrix; UI-CONTRACT §10 (dated, official-source → component → API/version → adaptation → verification); plan Gate A step 4 + Wave H | ✓ |

## 4. Gate A decision map (UI-CONTRACT §11 clarifications)

| # | Clarification | Decision (committed) | Owner wave |
|---|---|---|---|
| 1 | Undo optimistic guard | `stateVersionAtApply` = POST-apply (bumped) version from the same apply transaction — only later edits change it and are detected (SPEC S18a) | Wave D |
| 2 | Import feed cascade guard | Feed removal blocked when deleting it would FK-cascade any retained article (pre-existing/new/edited); per-row stateStamp insufficient; walk FK before deletion; skipped+counted ("retained to protect other articles") (SPEC S32a step 7) | Wave G |
| 3 | Failed-import source retention | Retained copy purged on leaving result surface only when no longer resumable (completed success / cancelled before apply); FAILED runs retain while Retry/Resume offered, including after leaving Failed Result; orphan GC 7 days (SPEC S32a step 8) | Wave G |
| 4 | Post-text asset recovery | `assetsComplete=1` = manifest asset set FINAL only; UI "Saved" additionally requires all expected images landed (imageCount == expectedImageCount, zero terminal failures); terminal skips/failures stay visibly incomplete with manual Retry images (SPEC S27a) | Wave F |
| 5 | Retry arithmetic | Initial attempt 0 + automatic retries 1/2/3 (worker-enforced `runAttemptCount >= 3` → terminal), floors 30/60/120 s; already aligned everywhere; recorded here as verified | Wave F |

## 5. Actual baseline status (what foundation builds on)

- **Unit tests:** `:app:testGithubDebugUnitTest` 10/10 green (5 classes: ExampleUnitTest 1, GoogleReaderIdTest 2, RssHelperTest 3, OPMLDataSourceTest 3, StringExtKtTest 1) — per ANDROID-BASELINE, XML-verified by prior orchestrator pass.
- **Assemble:** `:app:assembleGithubDebug` green; APK `ReadYou-0.16.2-7d431415.apk`, sha256 `5c867a75…94461` (verified by prior orchestrator pass); badging package `me.ash.reader`, label "Read You", versionCode 47.
- **Lint:** `:app:lintGithubDebug` RED — 28 Errors / 232 issues inherited (20 `LocalContextGetResourceValueCall`, 6 `MissingQuantity`, 2 `FlowOperatorInvokedInComposition` at ArticleCardWidget.kt:114,124; plus 200W/4H). Reference fingerprint preserved; lint-delta gate required; suppression/baseline/abortOnError changes forbidden.
- **Signing:** upstream commits a debug keystore (`signature/reader.keystore` + `keystore.properties`) used for ALL build types; foundation must re-base release signing to a private keystore and delete the tracked keystore (record fingerprint before deletion) per S35a.
- **Font:** `google_sans_flex.ttf` v3.007, OFL 1.1, binary notice "Copyright 2015 Google LLC" (see §2). Keep; add OFL raw resource + attribution (Wave A task 5).
- **Schema:** Room v7, schemas 5/6/7 exported, identityHash `f5342dd1…` — v8 migration start point per S1/S5.
- **Evidence boundary:** W0 build is compile/test baseline, NOT runtime emulator evidence; no emulator/device runtime claim exists anywhere in committed docs.

## 6. Build blockers foundation must handle (Wave A scope)

1. Lint: fix the 28 inherited Errors in owned files; commit `lint-baseline.xml` + `scripts/lint_delta.py` (0 new errors gate). No suppressions/baselines/abortOnError.
2. Signing: remove `all { signingConfig = release }` block; release reads private keystore props from env/repo-root gitignored file; configuration phase never throws; loud failure scoped to release tasks; delete `signature/` with fingerprint recorded.
3. Identity: applicationId `us.snakepit.feedbucket`, `.debug` suffix + label "FeedBucket D", release "FeedBucket", versionName 0.1.0 / versionCode 1, keep flavors + namespace `me.ash.reader`.
4. targetSdk 34 → 36 with Android 16 behavior checklist (edge-to-edge enforced, predictive back audit, no resizability opt-out, WorkManager fixed-rate note) per S38.
5. Font notices (OFL text + attribution incl. 2015 binary notice) per S36.
6. Material 3 Expressive dependency posture per S37/R10: resolve `1.3.0-alpha06`/`nav3 1.0.0` pins to explicit stable adaptive-* 1.3.0 with `:app:dependencies` evidence; material3 ≥ 1.4.0 stable (BOM 2025.10.01 pom-managed); record every `@OptIn`.
7. CI gate `.github/workflows/feedbucket-ci.yml` + `scripts/gate.sh`; exact-head CI check at Wave H; debug-only assemble, no signing material in CI.
8. Migration harness: room-testing + `MigrationTestHarness.kt` (instrumented, explicit emulator only); RED is runtime `IllegalStateException` per R4.
9. Gitignore additions: keystore properties/keystores, `docs/research/logs/`, `.plan/`, `*.db`, `build/recon/`.
10. Local-account/no-login entry point; verify brands/update endpoints/workmanager names/intents/flavor resources across tree per foundation card.

## 7. Prohibitions carried forward (binding)

- D14: web FeedBucket service and live DB untouched; `/home/jason/projects/feedbucket` read-only input; no retirement without Jason's explicit authorization.
- D15: no physical phone/tablet control, install, screenshots, input, pull/push, or adb shell without fresh conversation consent; `adb devices`/`version` read-only allowed; explicitly selected emulators only.
- D16: no secrets, personal feed corpus, real article exports, or private screenshots in repo; synthetic fixtures only; no telemetry.
- No profile config edits, no fallback-lane changes, no source edits by this gate.
