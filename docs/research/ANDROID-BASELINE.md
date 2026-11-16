# FeedBucket Android: pristine ReadYou baseline (W0)

Measured 2026-11-16 on branch `feedbucket-native` at doc commit `7d431415`
(planning doc only) on top of upstream ReadYou `d2b979ccad9a3e54b9499929a9dc2824578b0fd9`.
Source was read-only for this measurement; no code, dependency, branding, or
history changes were made. The only workspace additions are this document,
gitignored `local.properties` (SDK pointer), and untracked build logs under
`docs/research/logs/` (kept for evidence, not committed).

## Toolchain (actual paths and versions)

| Component | Value |
|---|---|
| OS | Linux 7.3.3-300.fc45.x86_64 (Aurora/Fedora immutable), 62 GiB RAM, 36 GiB available at run time |
| JDK | `/home/jason/.local/share/jbr` (JetBrains Runtime 21.0.10), exported as `JAVA_HOME`; Gradle also resolved daemon JVM `/home/jason/.local/jdks/jdk-21.0.12.1+1` from `org.gradle.java.home` |
| Gradle | 8.13 (wrapper, downloaded on first run) |
| AGP | 8.13.0 (`gradle/libs.versions.toml`) |
| Kotlin | 2.2.0, KSP 2.2.0-2.0.2, Compose BOM 2025.10.01 |
| Android SDK | `/home/jason/Android/Sdk`; platforms incl. android-36; compileSdk 36, targetSdk 34, minSdk 26 |
| Room | 2.7.2, schema export on; `AndroidDatabase` version = 7 (schemas 5/6/7 exported) |
| Flavors | `channel` dimension: `github` (default), `fdroid`, `googlePlay` (`.google.play` id suffix) |
| Build types | `debug`, `release` (minify+shrinkResources) |
| applicationId / version | `me.ash.reader` / 0.16.2 (versionCode 47) |
| License | GPL-3.0 (`LICENSE`) |

Every Gradle invocation used `--no-daemon --no-parallel --max-workers=1`
(repo `gradle.properties` otherwise sets `org.gradle.parallel=true` and an 8 GiB
heap; one build owner at a time, `./gradlew --stop` run after).

## Commands and results (variant tasks discovered from `./gradlew :app:tasks --all`, not guessed)

| Gate | Exact command | Result |
|---|---|---|
| Wrapper | `./gradlew --version --no-daemon` | exit 0, Gradle 8.13 |
| Task discovery | `./gradlew :app:tasks --all --no-daemon --max-workers=1` | exit 0; confirmed `testGithubDebugUnitTest`, `assembleGithubDebug`, `lintGithubDebug` exist |
| Unit tests | `./gradlew :app:testGithubDebugUnitTest --no-daemon --no-parallel --max-workers=1` | exit 0, BUILD SUCCESSFUL in 2m 55s |
| Assemble | `./gradlew :app:assembleGithubDebug --no-daemon --no-parallel --max-workers=1` | exit 0, BUILD SUCCESSFUL in 1m 45s |
| Lint | `./gradlew :app:lintGithubDebug --no-daemon --no-parallel --max-workers=1` | BUILD FAILED in 1m 31s (lint errors are fatal by default); report written before failure |

Full raw logs: `docs/research/logs/{testGithubDebugUnitTest,assembleGithubDebug,lintGithubDebug}.log` (untracked).

### Unit test counts (from `app/build/test-results/testGithubDebugUnitTest/*.xml`)

- 5 test classes, 10 tests total, 0 failures, 0 errors, 0 skipped.
- Classes: `ExampleUnitTest` (1), `GoogleReaderIdTest` (2), `RssHelperTest` (3), `OPMLDataSourceTest` (3), `StringExtKtTest` (1).

### Assemble output

- APK: `app/build/outputs/apk/github/debug/ReadYou-0.16.2-7d431415.apk`
- `aapt dump badging`: package `me.ash.reader`, versionCode 47, versionName 0.16.2, minSdk 26, targetSdk 34, label "Read You".
- sha256: `5c867a7537fc691fd7b1d794403c87471751a55c5bca81c7a3d81235b9094461`

### Lint baseline (from `app/build/reports/lint-results-githubDebug.xml`)

232 issues: **28 Error, 200 Warning, 4 Hint**. Top counts:
`UnusedResources` 52W, `GradleDependency` 35W, `LogNotTimber` 32W,
`NewerVersionAvailable` 23W, `LocalContextGetResourceValueCall` 20E,
`UsingMaterialAndMaterial3Libraries` 17W, `MissingQuantity` 6E,
`FlowOperatorInvokedInComposition` 2E (ArticleCardWidget.kt:114,124).

Lint errors are inherited upstream findings (newer lint checks against older
code), not regressions from this fork. Any lint-delta gate must treat this as
the reference and must not be "fixed" by blanket suppressions.

## Signing (anticipated blocker: NOT present)

Upstream `app/build.gradle.kts` applies `signingConfigs.release` to **all**
buildTypes. It is fed by `signature/keystore.properties` (4 keys incl.
`storeFile`) which is **git-tracked upstream** together with
`signature/reader.keystore` (debug keystore). Because the debug keystore
properties exist in a pristine checkout, `validateSigningGithubDebug` passes
and both gates above ran without any keystore setup. No signing blocker was
observed, and no signing material was created or modified.

Inherited hygiene risk for the fork (per approved D2, independent signing):
all variants including release sign with the publicly committed debug keystore.
The foundation wave must swap in a private release keystore and stop signing
release builds with the public one, without committing secrets.

## Source consumer map (reader state / cache / search / rules gaps)

Verified against actual source at d2b979cc, not issue recitals.

- **Reader state.** `Article` (domain/model/article/Article.kt) carries
  `isUnread`, `isStarred`, `isReadLater`, `fullContent`, `rawDescription`,
  `img@` proxy fields; no update/diff snapshot fields, no GUID-vs-display-id
  separation. DAO in `domain/repository/ArticleDao.kt` (919 lines, paging
  queries). UI state in `ui/page/home/flow` (FlowPage, SearchBar, ArticleList)
  and `ui/page/home/reading` (ReadingPage list position via
  `rememberSaveable`+`LazyListState.Saver` only: survives process death via
  saved-state, not durable across app-data eviction/restart). 72 DataStore
  preference files under `infrastructure/preference/` (e.g.
  `MarkAsReadOnScrollPreference`). Read-on-scroll exists and must default OFF
  per D5.
- **Sync/refresh.** `domain/service/`: `RssService`, `AbstractRssRepository`
  (489 lines), `LocalRssService`, `FeverRssService`, `GoogleReaderRssService`,
  `SyncWorker` (WorkManager, Wi-Fi/UNMETERED constraint honored), `ReaderWorker`,
  `WidgetUpdateWorker`, `OpmlService`. Providers: local, Fever, Google Reader
  protocol (FreshRSS via greader). No FeedBucket API, none wanted (D1).
- **Cache/extraction.** `infrastructure/rss/ReaderCacheHelper` (disk cache),
  `infrastructure/html/Readability.kt` + `RYArticleGrabberExtended`
  (readability4j) writing `fullContent`; Coil for images. There is **no**
  durable user-controlled download store, no pending/failed/retry states, no
  retention/bounds management (D9 gap).
- **Search.** `ArticleDao` search is SQL `LIKE '%'||:text||'%'` over
  title/shortDescription/fullContent (many variants by flow filter). **No FTS**
  anywhere in source (verified); no body-only-match tests; no offline-index
  lifecycle (D10 gap).
- **Rules / saved searches / mutes.** No matches for saved-search/mute/rule
  infrastructure in domain or preference layers (D7 gap). `SearchBar.kt` is a
  transient query UI with no persistence.
- **History.** No recently-read log or update-diff pipeline (D8 gap); FeedBucket
  web repo (`/home/jason/projects/feedbucket`, read-only) is the behavioral
  authority for W1 to mine.

## Inherited blocker list

1. `lintGithubDebug` fails the build: 28 lint Errors on pristine upstream
   (counts above). Smallest foundation remedy: fix or locally scope the 20
   `LocalContextGetResourceValueCall`, 6 `MissingQuantity`, 2
   `FlowOperatorInvokedInComposition` errors in touched files during the
   foundation wave. A measured delta gate may track untouched inherited
   findings; do not disable abortOnError or add suppressions/baselines to hide
   new failures (approved execution constraints).
2. Release signing uses the publicly committed `signature/reader.keystore`
   for all buildTypes (see Signing).
3. Bundled font `app/src/main/res/font/google_sans_flex.ttf` (~4 MB, default
   typeface in `ui/theme/Type.kt` and referenced in `RYWebView.kt`) has **no
   license text file in the repo**. Orchestrator correction: the actual bundled
   font name table (version 3.007) explicitly declares SIL Open Font License
   1.1 in name ID 13 and links https://openfontlicense.org in ID 14. The
   official project also publishes OFL.txt:
   https://github.com/googlefonts/googlesans-flex/blob/main/OFL.txt .
   Include matching copyright/license notices; the earlier non-OFL claim
   was wrong and does not justify replacing the font.
4. `googlePlay` flavor requires its own source set/assets presence in future
   CI; not exercised here (out of scope for githubDebug gates).
5. Deprecated Gradle features warning (Gradle 9.0 incompatibility) from
   upstream build scripts; informational only.

## License and asset evidence

- Root `LICENSE` = GNU GPL v3. AboutLibraries plugin configured
  (`aboutLibraries { excludeFields = ... }`).
- `signature/reader.keystore` + `keystore.properties` are upstream debug
  signing material, intentionally public; no real keys in repo (verified
  `git ls-files signature/`).
- Fonts: only bundled font is `google_sans_flex.ttf` (see blocker 3); other
  fonts are user-imported TTFs copied to internal storage
  (`ui/ext/ExternalFonts.kt`, `basic_font.ttf`/`reading_font.ttf`) and carry
  their own user-supplied licensing.
- No other third-party asset directories found outside standard Android res.

## Specifier handoff

The pristine baseline builds and its unit tests pass (10/10, githubDebug) with
JDK 21/Gradle 8.13/AGP 8.13 on SDK 36 using only an SDK-pointer
`local.properties`; lint is the one red gate, failing on 28 inherited errors
(20 `LocalContextGetResourceValueCall`, 6 `MissingQuantity`, 2
`FlowOperatorInvokedInComposition`) which the foundation wave must own.
Signing is a non-issue for debug gates because upstream commits a debug
keystore, but release signing must be re-based to a private key per D2, and
the bundled Google Sans Flex font needs a license verdict before any release.
Behavioral gaps to spec against are confirmed in source: no FTS (LIKE-only
search), no saved searches/rules/mutes, no durable download store or
retry/failure states, no update-diff/recently-read history, no stable
GUID/display-id separation, and list/reading positions that are not durable
across restart. Room is at schema v7 with exported schemas 5-7, which anchors
the v1 migration starting point in `docs/SPEC-v1.md`.
