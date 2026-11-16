# FeedBucket Android: approved product direction

Status: approved by Jason on 2026-11-16. This is the scope authority for specification and implementation. Detailed specs may resolve engineering choices but must not silently drop these requirements.

## Goal and decisions

D1. Build a standalone native Android RSS reader on ReadYou's Kotlin/Compose foundation. It replaces FeedBucket as the user's primary reader. No server, login, subscription, FeedBucket API, or cloud account is required. Network requests fetch user-selected feeds, article pages, and images. Keep optional upstream providers working where practical; do not make them onboarding requirements or add new integrations.
D2. Working name: FeedBucket. Release application ID: us.snakepit.feedbucket; debug ID: us.snakepit.feedbucket.debug. Use a distinct debug label. Preserve upstream Kotlin namespace initially to minimize merge debt. Independent signing and update channel, GPL notices and attribution. No upstream/private signing keys.
D3. Use the current upstream main baseline d2b979ccad9a3e54b9499929a9dc2824578b0fd9. An existing older ReadYouAI fork was reused by GitHub under the new repo name feedbucket-android. Preserve its main/history and custom commits. New work is ONLY on feedbucket-native, created from upstream/main. Never force-push, overwrite old main, or silently import its AI features.
D4. Native design: quiet reading surfaces, restrained color, strong typography, configurable density and accessible thumb-reachable controls. Material You/dynamic color is optional. Carry over FeedBucket behavior and principles, not its HTML/CSS pixel-for-pixel.
D5. Browse unread articles, feeds/folders, saved searches, recently read, starred and updated articles. Preserve list scope, ordering, selected article, list position and article reading position across navigation/restart. Mark-read must not break previous/next navigation. Read-on-scroll is OFF by default and not required in v1.
D6. Full-text extraction has automatic/per-feed and explicit manual paths. Preserve valid feed text on extraction failure, reject bot/challenge pages, isolate per-article failures, honor network constraints. A failed extraction never claims success or replaces useful text with an error page.
D7. Port saved searches, local rules and keyword/query mutes with previews and apply-to-existing. Reuse one matching/query model for search, previews and ingest. Define supported syntax explicitly; do not pretend the web reader has regex/boolean/semantic search that it does not have. Rule effects initially mark-read and star; all effects must be reversible where applicable.
D8. Port recently read and updated-article snapshots/diffs. Diff rendering must be sanitized and accessible. Distinguish publisher updates from local extraction. Preserve user read/star state through refreshes. Keep arbitrary publisher GUIDs separate from display ordering and stable internal IDs.
D9. Offline storage is a user-controlled durable store, not an evictable HTTP/Coil cache masquerading as saved content. Provide explicit download action, text/image readiness, pending/failed/retry/remove-download states, bounded limits, retention rules and storage management. Do not promise every website can be downloaded. Saved downloads must survive process death and remain readable without network.
D10. Index full article text locally with SQLite/Room FTS. Search works offline against locally stored content; indicate incomplete local coverage if relevant. Search tests must include body-only matches, extracted content changes, deletion and migration.
D11. Import/export OPML, plus a separate versioned FeedBucket migration package for rules, saved searches, stars/history and article state. OPML carries subscriptions, not complete reader state. Migration works on a copied SQLite DB and through Android file-picker import, needs no live backend or credentials, uses transactional validation and import preview/counts. Never log or export login/session secrets. Maintain stable identity mapping and define duplicate import behavior.
D12. Android background refresh is best-effort WorkManager scheduling with Wi-Fi/charging controls and refresh-on-open/manual refresh. Explain this honestly. Constraints cover chained extraction/download work, not only initial feed fetch. Avoid concurrent/duplicate fetches and one failure stalling the batch.
D13. Defer AI summaries/ranking, podcasts/video player, inbound newsletters, social/team features and new cloud integrations. Do not claim duplicate collapsing is included unless separately specced and verified; destructive deduplication is forbidden.
D14. Do not stop/delete/reconfigure the existing FeedBucket web service or alter its live DB. Its repo /home/jason/projects/feedbucket is READ-ONLY input. Migration exports use SQLite backup/copies. Android replaces the product, not the running service until Jason explicitly authorizes retirement.
D15. No physical phone/tablet control, installation, screenshots, input, pull/push or adb shell against Jason's devices without fresh conversation consent. adb devices/version are allowed. Workers may use explicitly selected emulators only, never an ambiguous default transport. No physical installation is needed for code-card acceptance. Final deliverable is a verified APK plus emulator/runtime evidence where possible; if device evidence is unavailable, say so and request consent at the final user handoff.
D16. No secrets, personal feed corpus, real article exports or private screenshots in the public repo. Use synthetic fixtures. No telemetry by default. No external service/AI dependency for core reading. Protect article rendering and unsafe URI schemes.

## Known evidence and source inventory

Parent inspected current ReadYou source and FeedBucket source, and ran the web reader's suite: 153 tests passed (1 deprecation warning). This is NOT an Android baseline. ReadYou main is 7 commits ahead of 0.16.2. Root build/test evidence must be measured anew.

ReadYou actual providers: local, Fever, Google Reader and FreshRSS (Google Reader protocol). Feedly/Inoreader AccountType values map to LocalRssService; README promises exceed implementation. Widgets and TTS exist. Source baseline has Room/Paging/WorkManager/Hilt/DataStore, disk extraction cache and SQL LIKE search. Investigate exact consumers before extending preferences/models; existing state is not proof of behavior.

Primary research sources:
- https://github.com/ReadYouApp/ReadYou
- https://github.com/ReadYouApp/ReadYou/blob/main/app/src/main/java/me/ash/reader/domain/service/RssService.kt
- https://github.com/ReadYouApp/ReadYou/blob/main/app/src/main/java/me/ash/reader/domain/service/AbstractRssRepository.kt
- https://github.com/ReadYouApp/ReadYou/blob/main/app/src/main/java/me/ash/reader/domain/repository/ArticleDao.kt
- https://capyreader.com/faq/ (local + Feedbin/FreshRSS/Miniflux, full-text, free/open source)
- https://github.com/spacecowboy/Feeder (local/offline/OPML; no conventional backend sync)
- https://github.com/readrops/Readrops (local + self-hosted service integrations)
- https://feedbin.com/ (saved searches, actions, full-text, update diffs)
- https://github.com/samuelclay/NewsBlur (training/filtering and saved-story organization)
- https://www.inoreader.com/ (service-side rules/filtering benchmark)
- https://www.reddit.com/r/rss/comments/1kzuaid/ (parent read surviving comments: previews, offline text and subscription-value complaints; deleted main post, self-selected anecdotes)

ReadYou issue IDs surfaced by researchers and MUST be re-read before treating as verified defects: 1091 reading/list position, 1112 muted words, 957 folder order, 391 backup, 1271 read-state reconciliation, 1240 read-state delay, 121 feed filters, 1285 unread-state reset. These are demand/bug leads, not reproduced diagnoses. Avoid unsupported market exclusivity, quantitative prevalence, maintenance or license/font claims. Verify font licenses from actual files.

Existing FeedBucket inputs (READ ONLY):
- /home/jason/projects/feedbucket/feedbucket/{db.py,actions.py,extract.py,diff.py,opml.py,app.py}
- /home/jason/projects/feedbucket/docs/FEEDBUCKET-SPEC-v0.3.md
- /home/jason/projects/feedbucket/docs/creative/DIRECTION-v03.md
- /home/jason/projects/feedbucket/docs/creative/F8-QA-REPORT.md

## Execution order and review contract

Board: feedbucket-android. Workspace: /home/jason/projects/feedbucket-android. Branch: feedbucket-native. Pass board explicitly on every tool/CLI operation. Keep planning outputs committed and source slices independently testable.

1. Establish pristine upstream Android baseline and record commands, versions, test counts, lint findings, signing/build blockers.
2. Specifier writes numbered behavioral/data/state contracts and a detailed wave implementation plan with exact paths, schema/migration shape and tests. Define FINAL model composition before features widen it. Plan uses small TDD tasks inside serial waves. No invented paths/commands.
3. Creative director writes buildable direction; designer produces concrete native screen/state contract and self-contained mocks with measured accessibility/geometry.
4. Orchestrator validates docs against source and approved decisions before releasing foundation coder.
5. Foundation coder rebrands, fixes baseline blockers minimally, adds durable schema seams/CI/gate infrastructure; blind reviewer judges original requirements and raw diff. No feature build starts on an unreviewed foundation.
6. Orchestrator checkpoints merge/verify, creates NEXT serial feature wave only after preceding slice is reviewed and integrated. Suggested sequence: reading reliability; query/rules/saved searches; updates/history; offline/FTS; migration; visual implementation/polish. Spec may reorder for real dependencies, with reasons.
7. Final integration gate: two consecutive relevant full-suite runs, lint delta against baseline, exact-head CI, clean tree, APK metadata/signature checks and emulator exercise of add feed -> refresh -> read/star -> process restart -> offline reading/search -> migration. Never claim runtime from compile alone.

Every nontrivial source card hands off with kanban_request_review(reviewer='reviewer'), NOT direct complete. Implementation cards are classic/non-goal to avoid goal-judge/review deadlocks. Reviewer never changes source; fixes belong to coder. Review packets omit implementer self-assessments. Default checkpoints own independent verification and create the next cards. All workers can use explicitly permitted CLI fallback: hermes kanban --board feedbucket-android show|complete|request-review|block <their actual task id> (consult --help for flags).

Build memory/resource constraints: check available memory first (at least 8GiB, swap not near full); one Gradle owner/lock at a time; fully flavored scoped tasks; --no-daemon --no-parallel --max-workers=1 with bounded JVM heap and in-process Kotlin compilation where appropriate. Never bare multi-variant build/check/assemble/lint or whole-repo format. Do not weaken/delete tests or add suppressions/baselines to hide new issues. Use red-green and replayable production mutations. Keep signing keys outside repo, artifact output gitignored.
