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

## Pending

- Wave C (reading reliability — read-state, positions, recently read, neighbors): card t_52fed684 (coder, idempotency `feedbucket-android-v1-wave-c`), created by Checkpoint C.
- Checkpoint D (t_6fac9a17, default) gated on Wave C; carries the continuation responsibility.
- Emulator-restore infra card t_d9844f3c (devops, host-only).
- Waves D–H strictly serial: Wave C reviewed + integrated before Wave D dispatches (enforced by the checkpoint card chain).
