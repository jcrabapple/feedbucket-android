# FeedBucket Android — Build status (orchestrator STATUS)

Maintained by orchestrator checkpoint cards (Gate A / Gate B / Checkpoint C / ...). Last verified: Gate B (t_122a1840), 2026-11-16.

## Verified foundation (Wave A / W4)

- Branch: `feedbucket-native`. Verified commit: `940522566140994eb7f358fdb7524d08676627e1` (tree clean, exact head).
- Review path (t_92e517cf): coder run 7 handoff -> review; reviewer run 8 approved (done). No direct-complete by coder.
- CI: FeedBucket CI run 43999393794 SUCCESS at exact head. Inherited `Build Commit` / `Build Pull Request` workflows STILL RED on every push: they run `assembleGithubRelease` in CI with no private keystore, hitting the S35a expected-red guard ("Release signing requires private keystore"). Fix is scheduled in Wave B as a bounded CI-wiring slice.
- Unit: 10/10, 0 failures (5 classes), re-parsed from `app/build/test-results/**/*.xml` at HEAD.
- Lint: fresh `lint-results-githubDebug.xml` = 0 Errors vs committed baseline 28 Errors; `scripts/lint_delta.py` PASS (no new errors, total <= baseline).
- APK (debug, github flavor): package `us.snakepit.feedbucket.debug`, label "FeedBucket D", versionCode 1, versionName 0.1.0, targetSdk 36, minSdk 26; sha256 `9697bb3e28545a496490dcd80619a69a8894325e51d42313caca241bc80c13fa`; signer = Android debug key (cert SHA-256 `bbd90a9a...`); upstream public keystore fingerprint recorded before deletion (`715696914a...`).
- Instrumented on emulator test_api36 (API 36): `AppLaunchSmokeTest.launchAndBackDoesNotCrash`, `ExampleInstrumentedTest.useAppContext`, `MigrationTestHarness.v7SchemaOpens` — 3/3 PASS.
- S37 dependency posture RESOLVED: see SPEC S37 (amended by Gate B) and `build/recon/s37-dependency-posture.txt`. App compile classpath keeps `compose-bom-alpha 2025.10.01` -> material3 1.5.0-alpha07, adaptive* 1.3.0-alpha06, glance-material3 1.2.0-beta01; androidTest classpath uses the stable BOM -> material3 1.4.0. Stable-swap evidence: material3 1.4.0 stable lacks the Expressive API surface upstream uses (compile failure across 18 files); adaptive 1.3.0 stable AAR hard-requires SDK 37 / AGP 9.1.0. Authorized decision: retain tested bounded alpha pins; no downgrade; no unbounded alpha chasing; re-verify at Wave H.

## Pending

- Wave B (schema v8, identity, FTS — data layer only): card created by Gate B, idempotency key `feedbucket-android-v1-wave-b`.
- Waves C–H strictly serial: Wave B reviewed + integrated before Wave C dispatches (enforced by the checkpoint card chain).
