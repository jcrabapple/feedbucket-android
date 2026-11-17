# FeedBucket Android — Build status (orchestrator STATUS)

Maintained by orchestrator checkpoint cards (Gate A / Gate B / Checkpoint C / ...). Last verified: Checkpoint E (t_c0d13407), 2026-11-17.

## Verified foundation (Wave A / W4)

- Branch: `feedbucket-native`. Verified commit: `940522566140994eb7f358fdb7524d08676627e1` (tree clean, exact head).
- Review path (t_92e517cf): coder run 7 handoff -> review; reviewer run 8 approved (done). No direct-complete by coder.
- CI: FeedBucket CI run 43999393794 SUCCESS at exact head. Inherited `Build Commit` / `Build Pull Request` workflows STILL RED on every push: they run `assembleGithubRelease` in CI with no private keystore, hitting the S35a expected-red guard ("Release signing requires private keystore"). Fix is scheduled in Wave B as a bounded CI-wiring slice.
- Unit: 10/10, 0 failures (5 classes), re-parsed from `app/build/test-results/**/*.xml` at HEAD.
- Lint: fresh `lint-results-githubDebug.xml` = 0 Errors vs committed baseline 28 Errors; `scripts/lint_delta.py` PASS (no new errors, total <= baseline).
- APK (debug, github flavor): package `us.snakepit.feedbucket.debug`, label "FeedBucket D", versionCode 1, versionName 0.1.0, targetSdk 36, minSdk 26; sha256 `9697bb3e28545a496490dcd80619a69a8894325e51d42313caca241bc80c13fa`; signer = Android debug key (cert SHA-256 `bbd90a9a...`); upstream public keystore fingerprint recorded before deletion (`715696914a...`).
- Instrumented on emulator test_api36 (API 36): `AppLaunchSmokeTest.launchAndBackDoesNotCrash`, `ExampleInstrumentedTest.useAppContext`, `MigrationTestHarness.v7SchemaOpens` — 3/3 PASS.
- S37 dependency posture RESOLVED: see SPEC S37 (amended by Gate B) and `build/recon/s37-dependency-posture.txt`. App compile classpath keeps `compose-bom-alpha 2025.10.01` -> material3 1.5.0-alpha07, adaptive* 1.3.0-alpha06, glance-material3 1.2.0-beta01; androidTest classpath uses the stable BOM -> material3 1.4.0. Stable-swap evidence: material3 1.4.0 stable lacks the Expressive API surface upstream uses (compile failure across 18 files); adaptive 1.3.0 stable AAR hard-requires SDK 37 / AGP 9.1.0. Authorized decision: retain tested bounded alpha pins; no downgrade; no unbounded alpha chasing; re-verify at Wave H.

## Verified Wave B (schema v8, identity, FTS — data layer only)

- Verified commit: `f526432ecb72d01a91354f1dde772e62afbb8d1c` (pushed to origin/feedbucket-native; tree clean).
- Review path (t_83e9eaaf): coder run 10 handoff -> review; reviewer run 12 approved (done). No direct-complete by coder.
- CI at exact head f526432e: FeedBucket CI run 44032352651 SUCCESS; inherited `Build Commit` run 44032352665 SUCCESS (Wave B's CI fix — `assembleGithubRelease` -> `assembleGithubDebug` — closed the S35a red workflow).
- Unit: 50/50, 0 failures / 0 errors (10 classes), re-parsed from `app/build/test-results/**/*.xml` at HEAD.
- Lint: fresh lint = 0 Errors vs committed baseline 28 Errors; `scripts/lint_delta.py` PASS.
- Gate B re-run by Checkpoint C: `bash scripts/gate.sh B` PASS (unit 50/50, assembleGithubDebug green, lintGithubDebug green, lint delta PASS).
- Diff scope 32d31139..f526432e: 44 files, +4074/-131 — exactly the Wave B owned-files list (schema 8.json committed; UUID.randomUUID gone from RssHelper.kt; no dependency/toml/build-file changes → S37/D17 posture unchanged).
- Instrumented: DEFERRED (integration blocker) — 17 tests executed earlier on test_api36 (12 green pre-fix); post-fix re-run blocked by host emulator regression (qemu SwiftShader SIGSEGV on cold boot; `-gpu off` reaches adb-unauthorized then drops; host-GPU needs a physical desktop click — off-limits). Emulator-restore infra card t_d9844f3c dispatched; deferral must close before runtime-critical waves promote.

## Verified Wave C (reading reliability — read-state, positions, recently read, neighbors)

- Verified commit: `b646b5025adb5fe020fe524c2cff3acc4ed8596c` (pushed to origin/feedbucket-native; tree clean, no artificial merge).
- Review path (t_52fed684): coder run 15 handoff -> review rounds 16-17 -> reviewer run 18 approved (done). No direct-complete by coder.
- Fixture-fix path (t_cfc2686e): coder run 24 -> reviewer run 25 approved (done) at b646b502; 2 instrumented fixture defects corrected (absent-anchor clamp expectation, Article-FK seed) + new out-of-range clamp @Test; API36 promoted to required hosted gate.
- Unit: 82/82, 0 failures / 0 errors (14 classes), re-parsed from `app/build/test-results/**/*.xml` at HEAD.
- Lint: fresh lint = 0 Errors vs committed baseline 28 Errors; `scripts/lint_delta.py` PASS.
- Instrumented (hosted GitHub CI, exact head b646b502, run 44091492105): independently downloaded artifact XMLs and parsed — API35 30/30 (PositionPersistenceTest 13/13, ArticleDaoSearchTest 9/9, Migration7To8Test 5/5, AppLaunchSmokeTest 1, MigrationTestHarness 1, ExampleInstrumentedTest 1), API36 identical 30/30; 0 failures/errors/skips on both. Runtime deferrals from Wave C review (7 PositionPersistenceTest behaviors + post-fix tag test) CLOSED.
- CI at exact head b646b502: build, gate, api35, api36 check-runs all success.
- Diff scope 94052256..b646b502: Wave C owned files + test-fixture fixes (PositionPersistenceTest/ArticleDaoSearchTest) + hosted-instrumented CI workflow + scripts/verify_instrumented_results.py + STATUS docs; no dependency/toml/build-file changes -> S37/D17 posture unchanged.
- D5 manual-free assertion: MarkAsReadOnScrollPreference.kt:38 `val default = OFF` unchanged.
- REDCHECK: 4 triples recorded in Wave C handoff (scope-key injectivity, neighbor sort fallback, sticky generation half 1, mark-read-on-open policy), each with mutation -> red lines -> green lines.
- Local emulator infra t_d9844f3c / t_90b494be: superseded by hosted-CI emulator gate (t_e8fae1d9); not a remaining blocker.

## Verified Wave D (query, rules, saved searches, mutes — D7)

- Verified commit: `fe9c9a9231f3ab6d6aec3db3edb985c5502c7a81` (pushed to origin/feedbucket-native; tree clean, no artificial merge).
- Review path (t_9c290a7e): coder run 29 handoff -> review -> reviewer run 30 CHANGES-REQUIRED -> coder run 31 fix round 2 -> reviewer run 32 approved (done). No direct-complete by coder.
- Unit: 125/125, 0 failures/errors/skips (20 classes), re-parsed from `app/build/test-results/**/*.xml` at HEAD (fresh forced `--rerun-tasks` run by reviewer 15:48Z; checkpoint re-parsed same XML + independent Gate D re-run PASS).
- Lint: fresh lint = 0 Errors vs committed baseline 28 Errors; `scripts/lint_delta.py` PASS.
- Gate D re-run by Checkpoint E: `bash scripts/gate.sh D` PASS (exit 0; 125/125 unit, assembleGithubDebug green, lintGithubDebug green, lint delta PASS).
- LIKE audit (Gate D): `grep -c "LIKE"` = 6 in ArticleDao.kt; four text predicates `'%' || :text` all carry `ESCAPE '\'` with SearchSql.likeTerm-prep params; one tag-CSV containment uses exact-match framing; zero unescaped remnants (legacy searchArticle* variants deleted).
- REDCHECK: 9 triples recorded (6 initial in run 29: star budget 100, R1 mute-no-ingest-effect, S15 view matrix, S18a stateVersionAtApply, retention prune 10, verbatim saved-search persistence; 3 fix-round in run 31: S18a optimistic-lock version, silent empty-match SQL, appliedCount), each with mutation -> red output line -> green output line.
- Diff scope 6f3c3fc7..fe9c9a92: 36 files, +3516/-467 — Wave D owned files plus justified repoints/ripples (PositionPersistenceTest repointed to the raw ScopeSql path; FEVER/GoogleReaderRssService constructor ripple; strings.xml UI labels; no dependency/toml/build-file/CI changes -> S37/D17 posture unchanged).
- Instrumented (hosted GitHub CI, exact head fe9c9a92, run 44119741751): independently downloaded artifacts and parsed — API35 32/32 and API36 32/32 across 7 classes, 0 failures/errors/skips on both. New StateApplyLedgerPersistenceTest executed 2/2 on both API levels (hosted deferral from Wave D review CLOSED). PositionPersistenceTest 13/13, ArticleDaoSearchTest 9/9, Migration7To8Test 5/5, smoke/harness 3/3.
- CI at exact head fe9c9a92: Build Commit 44119741746 success, FeedBucket CI (gate) 44119741936 success, FeedBucket Instrumented 44119741751 success (api35 + api36 jobs).
- Local emulator infra t_90b494be remains BLOCKED (paused, not resolved; host emulator gfxstream crash + device-consent hook) — superseded by the hosted-CI emulator gate (t_e8fae1d9/t_cfc2686e); not a remaining blocker.

## Verified Wave E (updates & diffs — D8)

- Verified commit: `c1319ec7dcfcc54ccdfc1f8d05d8e294e0cc0b54` (pushed to origin/feedbucket-native; tree clean, no artificial merge).
- Review path (t_0dad71fa): coder run 34 handoff -> review -> reviewer run 35 CHANGES-REQUIRED (5 blocking findings) -> coder run 36 fix round 2 -> reviewer run 37 approved (done). No direct-complete by coder.
- Unit: 169/169, 0 failures/errors/skips (23 classes), re-parsed from fresh gate-run XML + independent re-parse by Checkpoint F.
- Lint: fresh lint = 0 Errors vs committed baseline 28 Errors (28 removed); `scripts/lint_delta.py` PASS.
- Gate E re-run by Checkpoint F: `bash scripts/gate.sh E` PASS (exit 0; 169/169 unit, assembleGithubDebug green, lintGithubDebug green, lint delta PASS).
- Diff scope 1d71ff61..c1319ec7: 14 files, +2053/-93 — Wave E owned set + justified ripples (ReadingPage.kt S22 originalContent/contentChangedAt wiring; strings.xml UI labels); only dependency change = planned java-diff-utils 4.12 (io.github coordinate, Apache-2.0 recorded) per S22/S36; no other toml/build-file/CI changes -> S37/D17 posture unchanged.
- Truth-table verified in code by Checkpoint F: compare keys use `publishedAt` never `date`; dateless rows keep frozen `date`; three-state recency; snapshot gate >50 first-version-only; markUpdated only when showUpdates; updated view ordered `date DESC, rowid DESC` via the ScopeSql default branch, `contentChangedAt` never a sort key; scoped mark-all-read drains already-read updated rows (account/group/feed).
- REDCHECK: 6 initial triples (run 34) + 5 fix-round triples (run 36: threshold-0 flip, date-vs-publishedAt compare keys, token-length parity, display-token identity, separator removal, regex whitespace parity), each mutation -> red -> green; logs `build/recon/e1/e2/e3/e4-*.log`.
- CI at exact head c1319ec7 (run 44148524014): Build success, FeedBucket CI gate success. Hosted instrumented api35 AND api36 RED: 44 tests each, 1 failure each — identical deterministic NPE in `ArticleDaoUpdatedViewTest.drainOnReadClearsIsUpdated` at `row(ArticleDaoUpdatedViewTest.kt:117)` (test-harness seed bug: `update()` without insert; production mark-read drain logic verified correct). Fix card t_3287a7d9 dispatched (coder); Wave F gated on it. The instrumented gate must be green at exact head before Wave F promotes.

## Pending

- Wave E hosted-instrumented fix (t_3287a7d9, coder): seed `ArticleDaoUpdatedViewTest.drainOnReadClearsIsUpdated`; require api35/api36 green at exact head (44/44 instrumented).
- Wave F (durable downloads & offline — D9/D10): card t_cbccc792 (coder, idempotency `feedbucket-android-v1-wave-f`), gated on the fix card; carries reviewer advisory (destination-specific state/render smoke with failure witness), D17 verbatim, memory guard, no-phone/no-live-web.
- Checkpoint G (t_754ea072, default) gated on Wave F; carries the continuation responsibility.
- Waves F–H strictly serial: Wave F reviewed + integrated before Wave G dispatches (enforced by the checkpoint card chain).
