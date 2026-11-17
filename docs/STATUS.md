# FeedBucket Android — Build status (orchestrator STATUS)

Maintained by orchestrator checkpoint cards (Gate A / Gate B / Checkpoint C / ...). Last verified: Checkpoint C (t_c66202a8), 2026-11-17.

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

## Pending

- Wave D (query, rules, saved searches, mutes — D7): card t_9c290a7e (coder, idempotency `feedbucket-android-v1-wave-d`), created by Checkpoint D; carries reviewer advisory (destination-specific state/render smoke with failure witness), D17 verbatim, memory guard, no-phone/no-live-web.
- Checkpoint E (t_c0d13407, default) gated on Wave D; carries the continuation responsibility.
- Waves E–H strictly serial: Wave D reviewed + integrated before Wave E dispatches (enforced by the checkpoint card chain).
