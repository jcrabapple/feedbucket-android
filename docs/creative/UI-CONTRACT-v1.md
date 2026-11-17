# FeedBucket Android native UI contract v1

Status: BUILDABLE DESIGN CONTRACT for Gate A and Wave H
Date: 2026-11-16
Authority: `docs/APPROVED-DIRECTION.md` D1–D17 > amended `docs/SPEC-v1.md` v1.1 > `docs/plans/IMPLEMENTATION-v1.md` > this contract.
Direction: **Margin Notes**, with **Reading Desk** adaptive continuity from `docs/creative/DIRECTION-v1.md`.
Reference mocks: `docs/creative/mocks/index.html`. Measured browser evidence: `docs/creative/QA-v1.md`.

This is a Compose/Material contract and an HTML reference artifact. The HTML is not a WebView implementation, a new feature specification, or runtime evidence. All fixture people, feeds, titles, URLs, counts and articles are synthetic.

## 1. Product and taste decisions

### 1.1 One reading axis

Every surface has one primary object:

- **Read:** the article index. Scope controls stay above it and never become a dashboard.
- **Reader:** the article. Metadata uses the same feed → author/date → state order as the index.
- **Preview:** the evidence. Count first, samples second, consequential action last.
- **Manage:** the stored object. State and recovery text precede secondary actions.

Flat rows, start alignment, 4dp spacing increments and restrained color replace ReadYou's rounded article cards. Material 3 Expressive supplies component anatomy, state layers, connected shapes and adaptive navigation. It does not supply decorative motion, a magazine grid, filled unread rows or a permanent wordmark.

### 1.2 Reader font is an explicit compatibility choice

The existing source has `ReadingFontsPreference.default = GoogleSans` (`ReadingFontsPreference.kt:69–72`), not sans-serif generically and not serif. That default remains **Google Sans** for v1 compatibility. **Serif** is the Margin Notes editorial recommendation and is shown in the mocks as an opt-in taste preview, not silently made the migration default.

The appearance sheet presents adjacent choices:

- **Google Sans — current default**, 18/28sp design body setting.
- **Serif — editorial preview**, same size and line height.
- System, Sans-Serif, Monospace, Cursive and External remain available in the full Reading settings. External font behavior remains upstream; failures fall back to `FontFamily.Default`.

Changing the v1 default to Serif requires a product decision, not a theme-side data migration.

### 1.3 Static identity first

Default appearance is **System theme + static FeedBucket palette + dynamic color OFF**. Dynamic color is optional on API 31+, falls back to the complete static schemes below, and never changes the meaning of Updated, unread, starred, download or error states.

## 2. Navigation topology and continuity

### 2.1 Top-level destinations

| Destination | Existing route to retain | Initial content | Navigation treatment |
|---|---|---|---|
| **Read** | `Route.Reading(null)` | Unread, one pane compact; list-detail when allowed | Navigation bar compact, rail medium/expanded |
| **Feeds** | `Route.Feeds` | Folder/feed index | Same suite; feed row opens its Read scope |
| **Settings** | `Route.Settings` | Setting groups | Same suite; subpages have direct Back to Settings |

Use `NavigationSuiteScaffold` around these three roots. Search is a mode of Read, not a fourth destination. There is no Home, statistics, AI or Query Bench destination.

Read keeps a per-root back stack and saved state. Top-level reselection uses single-top + restore-state behavior; it does not reset scope, query, selected article, list anchor or reading position. Switching roots does not destroy the Read generation.

### 2.2 Nested routes

| Route | Parent | Purpose |
|---|---|---|
| `FeedDetail(feedId)` / `GroupDetail(groupId)` | Feeds | Full management and displaced drawer actions |
| `Rules` | Settings | Standard rules and mutes |
| `RuleEdit(ruleId: Long?)` | Rules | Create/edit one standard rule or mute |
| `Downloads` | Settings | Stored articles, storage cap, retry/remove |
| `Migration` | Settings | FeedBucket package/raw-copy import and result |
| `Opml` | Settings | OPML import/export and count result |
| Existing Accounts / ColorAndStyle / Interaction / Languages / Troubleshooting / TipsAndSupport descendants | Settings | Preserve upstream capabilities and licenses |

Rule Preview, appearance and small confirmations are sheets/dialogs, not routes. Import Preview and Result are pages in the Migration back stack because they must survive process death and expose a durable retry/resume path.

### 2.3 Back hierarchy

Back is handled in this exact priority:

1. IME or an exposed dropdown closes.
2. Modal sheet/dialog/menu dismisses and focus returns to its launcher.
3. Search collapses to the originating Read scope; query draft follows S24a scope state.
4. Reader Current/Changes does not intercept Back. Compact Reader returns to its originating list and anchor.
5. Import Preview cancels without writes. Applying cannot be dismissed into a false cancellation; Back opens the visible run status. Failed Result keeps its retained source while Retry/Resume is offered.
6. Rule/feed/settings subpage pops to its direct parent.
7. On a two-pane Read, `PopUntilScaffoldValueChange` is retained: changing the selected article does not create artificial Back steps. Compact list→detail does create a scaffold-value change.
8. At Feeds or Settings root, Back selects Read. At Read root, Back belongs to the system. No legacy `moveTaskToBack` shortcut bypasses this hierarchy.

Navigation3's predictive transitions and the adaptive navigator provide predictive Back visuals. API 36 behavior is mandatory per S38; no `onBackPressed` or `KEYCODE_BACK` fallback is introduced.

### 2.4 Displaced ReadYou entry points

| Existing entry | FeedBucket destination |
|---|---|
| Initial Feeds screen | Read / Unread |
| Feeds top-left Settings icon | Settings top-level destination |
| Shared bottom `FilterBar` | Read scope chip row; Feeds management and Settings are separate roots |
| FeedsBanner view shortcuts | Unread / Updated / Starred / Recently read / All chips |
| Feed/group long-press drawer | Row overflow plus full Feed/GroupDetail page; long-press remains only as a duplicate shortcut |
| Feed URL, rename, folder, notifications, full text, browser, clear and unsubscribe | FeedDetail, in that order; destructive actions remain confirmed |
| Flow large 172dp app bar and auto-shrinking title | 64dp collapsed app bar, natural wrapping, no auto-size |
| Search in Flow | Scoped Search mode inside Read |
| Reader palette route | Appearance modal sheet; full controls remain Settings → Reading |
| Reader full-content and image actions | Reader More menu; Read aloud also moves here rather than disappearing |
| Reader 40dp bottom buttons | 48dp Previous / Read / Star / Next / More targets in a 64dp bottom app bar |
| Swipe and long-press triage | Duplicate gestures only; every command also appears in the row More menu |
| Saved searches | Read scope chips and Search save/rename/delete actions |
| Rules/mutes | Settings → Rules & mutes |
| Durable downloads | Reader More and Settings → Downloads |
| FeedBucket migration and OPML | Settings → Import & export, with separate package and OPML flows |

## 3. Window geometry and adaptive behavior

One HTML CSS pixel represents one design dp in the reference mocks. Native implementation uses `dp`/`sp`, never browser pixels.

### 3.1 Width classes

| Window | Chrome | Read panes | List width | Reader measure |
|---|---|---|---|---|
| 320–599dp compact | Navigation bar, 16dp list gutters | One pane | Full width | 20dp gutters; 16dp at ≤360dp |
| 600–839dp medium | 80dp navigation rail | List + detail by default | 320dp | Remaining detail, 20dp gutters |
| 840–1199dp expanded | 80dp navigation rail | List + detail | 360dp | Remaining detail, 24dp gutters, centered, max 680dp |
| ≥1200dp | 80dp navigation rail | List + detail | 400dp | Centered, max 680dp |

At **768dp**, the mock geometry is rail 80 + list 320 + detail 368. The selected article, scope, list anchor and article scroll ratio are identical to the 390dp fixture.

When font scale is **≥1.6**, available detail width is **<320dp**, a display cutout reduces usable width below these values, or a fold crosses content, Read uses one-pane list-detail even at 768dp. The rail remains. This is a content-availability decision, not a device-name decision. No draggable splitter is exposed; the existing 2dp drag handle is removed from the design.

### 3.2 Insets and fixed chrome

- `Scaffold` consumes `safeDrawing` for chrome. The article body uses edge-to-edge windows but applies top/bottom content padding so first/last text never sits under system bars or the triage bar.
- Compact app bar: **64dp** + status inset. Scope title is `titleLarge`; no 172dp expanded app bar.
- Navigation bar: **80dp** + navigation inset; each item at least **64dp** high and **48dp** wide. Navigation rail: **80dp** wide + start inset.
- Reader bottom app bar: **64dp** + navigation inset. Five 48dp actions fit at 320dp with 8dp outer padding.
- Mock-only system fixtures: status inset 24 and gesture inset 16. Native values come from `WindowInsets`, never these constants.
- Keyboard: search/editor actions use `imePadding`; focused input is brought above the keyboard without changing route. Sheets scroll internally.
- Bottom fixed bars never cover the final row. Content bottom padding equals bar height + inset + 16dp. Hit-testing must verify this numerically.

### 3.3 Component geometry

| Component | Exact geometry |
|---|---|
| Base grid | 4dp; 8dp between related controls, 16dp between unrelated groups, 24dp between major sections |
| Top app bar action | 48×48dp target; 24dp glyph; 8dp target separation maximum at 320dp |
| Scope chips | Minimum 48dp high, horizontal padding 16dp, 8dp gap, 12dp vertical row padding; horizontal scroll, no label shrinking |
| Search input container | Minimum 56dp high, 16dp horizontal margins, full shape; 48dp Clear and Back targets |
| Comfortable article row | 16dp horizontal and 12dp vertical padding; metadata→title gap 4dp; title→summary gap 8dp; divider 1dp `outlineVariant` |
| Compact article row | Same horizontal padding, 8dp vertical padding, no summary; type and targets do not shrink |
| Article title | Max 3 lines in either density; full title in Reader and accessible label |
| Comfortable summary | Max 2 lines; omitted if blank; no reserved empty height |
| Thumbnail | 80×80dp, small 8dp shape, 12dp start gap; absent image does not change title start edge |
| Unread indicator | 8dp dot + 8dp gap, decorative; unread is also weight 600 and semantics text |
| Metadata/status | 12/16sp, 4dp between inline items, wraps to additional lines at large font |
| Reader title | Compact 24/32sp, expanded 28/36sp; 12dp below metadata, 16dp above proof/body |
| Reader body | 18/28sp default design setting; 16dp paragraph spacing; 680dp maximum measure |
| Current/Changes | 48dp minimum height, two equal-width options, connected outer shape, 4dp inner gap |
| Reader triage | 64dp bar + inset; Previous, Read/Unread, Star, Next, More; each 48×48dp; Next is the only tonal emphasis |
| Settings/feed/rule rows | Minimum 64dp, 16dp horizontal and 12dp vertical padding; 24dp leading glyph and 16dp gap; text determines final height |
| Count/evidence rows | Minimum 48dp, 16dp horizontal padding; labels wrap, numeric values use tabular figures |
| Primary/tonal/text buttons | Minimum 48dp high, 24dp horizontal padding; icon+text gap 8dp |
| Modal sheet | Full width compact, 360dp end-anchored wide; top corners 28dp; handle 32×4dp; 24dp content padding; max 85% available height |
| Alert dialog | 28dp shape, 24dp padding, 280dp min/560dp max width; action row wraps at 200% font |
| Snackbar/result line | Minimum 48dp, 16dp margin, above bottom chrome; Undo target 48dp; durable apply result also remains on RuleEdit after process recreation |
| Loading indicator | 4dp linear track plus visible text; no wavy track and no spinner-only initial state |
| Empty/error block | 24dp horizontal and 32dp vertical padding; title, explanation, then one 48dp action; no illustration required |

## 4. Typography

### 4.1 Chrome family and license

Chrome uses bundled **Google Sans Flex v3.007**, weights 400/500/600/700. Existing binary: `app/src/main/res/font/google_sans_flex.ttf`. License: **SIL Open Font License 1.1**, copyright 2022 The Google Sans Flex Project Authors. Official notice: <https://github.com/googlefonts/googlesans-flex/blob/main/OFL.txt>. S36 requires the complete license and attribution in app notices. The self-contained mock embeds the unmodified font and the OFL notice.

Use the full Material 15-role scale. Emphasized variants use weight 600 selectively for unread titles, the current scope and primary action labels. No all-caps metadata and no auto-sizing text.

| Role | Size / line height | Weight | Letter spacing | FeedBucket use |
|---|---:|---:|---:|---|
| displayLarge | 57/64sp | 400 | −0.25sp | Reserved; not used in routine chrome |
| displayMedium | 45/52sp | 400 | 0sp | Reserved |
| displaySmall | 36/44sp | 400 | 0sp | Reserved |
| headlineLarge | 32/40sp | 400 | 0sp | Reserved |
| headlineMedium | 28/36sp | 400 | 0sp | Expanded reader title |
| headlineSmall | 24/32sp | 400 | 0sp | Compact reader title, empty-state title |
| titleLarge | 22/28sp | 500 | 0sp | Scope/page title |
| titleMedium | 16/24sp | 500; unread 600 | 0.15sp | Article/settings/feed title |
| titleSmall | 14/20sp | 500 | 0.1sp | Section heading |
| bodyLarge | 16/24sp | 400 | 0.5sp | Search input, dialogs |
| bodyMedium | 14/20sp | 400 | 0.25sp | Summary, supporting text |
| bodySmall | 12/16sp | 400 | 0.4sp | Supplementary detail only |
| labelLarge | 14/20sp | 500 | 0.1sp | Buttons |
| labelMedium | 12/16sp | 500 | 0.5sp | Metadata and chips |
| labelSmall | 11/16sp | 500 | 0.5sp | Badge only; never long explanatory text |

Reader body is **18/28sp**, line-height multiplier 1.556 at default. Existing 17sp size remains available as a preference; the design default is a deliberate reading-comfort change. Existing font size, line height 0.8–2.0 multiplier, letter spacing, horizontal padding, alignment, bold and external font controls remain in full settings.

Article titles, summaries and body use `TextDirection.Content`; navigation and controls follow `LocalLayoutDirection`. At 200% font scale, `sp` grows while minimum dp targets remain 48dp, bars and rows grow vertically, metadata wraps, and no label is made smaller.

## 5. Color, shape and state tokens

### 5.1 Complete static and representative dynamic palettes

The dynamic columns are a **representative Material dynamic scheme for numerical QA**, not a hardcoded wallpaper palette. Production dynamic roles come from `dynamicLightColorScheme` / `dynamicDarkColorScheme` on API 31+.

| Role | Static light | Static dark | Dynamic light sample | Dynamic dark sample |
|---|---|---|---|---|
| primary | `#145B60` | `#86D4D2` | `#6750A4` | `#D0BCFF` |
| onPrimary | `#FFFFFF` | `#003638` | `#FFFFFF` | `#381E72` |
| primaryContainer | `#B8E4E2` | `#24575A` | `#EADDFF` | `#4F378B` |
| onPrimaryContainer | `#062F32` | `#B8E4E2` | `#21005D` | `#EADDFF` |
| primaryFixed | `#B8E4E2` | `#B8E4E2` | `#EADDFF` | `#EADDFF` |
| primaryFixedDim | `#9ACFCD` | `#9ACFCD` | `#D0BCFF` | `#D0BCFF` |
| onPrimaryFixed | `#062F32` | `#062F32` | `#21005D` | `#21005D` |
| onPrimaryFixedVariant | `#1A4A4D` | `#1A4A4D` | `#4F378B` | `#4F378B` |
| secondary | `#496361` | `#A9CCC7` | `#625B71` | `#CCC2DC` |
| onSecondary | `#FFFFFF` | `#173634` | `#FFFFFF` | `#332D41` |
| secondaryContainer | `#CBE6E2` | `#35504D` | `#E8DEF8` | `#4A4458` |
| onSecondaryContainer | `#123331` | `#CBE6E2` | `#1D192B` | `#E8DEF8` |
| secondaryFixed | `#CBE6E2` | `#CBE6E2` | `#E8DEF8` | `#E8DEF8` |
| secondaryFixedDim | `#AFCBC7` | `#AFCBC7` | `#CCC2DC` | `#CCC2DC` |
| onSecondaryFixed | `#123331` | `#123331` | `#1D192B` | `#1D192B` |
| onSecondaryFixedVariant | `#2A4A47` | `#2A4A47` | `#4A4458` | `#4A4458` |
| tertiary | `#4B5F7D` | `#B3C5E2` | `#7D5260` | `#EFB8C8` |
| onTertiary | `#FFFFFF` | `#1D304A` | `#FFFFFF` | `#492532` |
| tertiaryContainer | `#D3E0F5` | `#33465F` | `#FFD8E4` | `#633B48` |
| onTertiaryContainer | `#16283F` | `#D3E0F5` | `#31111D` | `#FFD8E4` |
| tertiaryFixed | `#D3E0F5` | `#D3E0F5` | `#FFD8E4` | `#FFD8E4` |
| tertiaryFixedDim | `#B7C9E2` | `#B7C9E2` | `#EFB8C8` | `#EFB8C8` |
| onTertiaryFixed | `#16283F` | `#16283F` | `#31111D` | `#31111D` |
| onTertiaryFixedVariant | `#33465F` | `#33465F` | `#633B48` | `#633B48` |
| error | `#BA1A1A` | `#FFB4AB` | `#B3261E` | `#F2B8B5` |
| onError | `#FFFFFF` | `#690005` | `#FFFFFF` | `#601410` |
| errorContainer | `#FFDAD6` | `#93000A` | `#F9DEDC` | `#8C1D18` |
| onErrorContainer | `#410002` | `#FFDAD6` | `#410E0B` | `#F9DEDC` |
| background / surface | `#FAFDFC` | `#101514` | `#FFFBFE` | `#141218` |
| onBackground / onSurface | `#171D1C` | `#DDE4E2` | `#1C1B1F` | `#E6E0E9` |
| surfaceDim | `#D6DAD8` | `#101514` | `#DED8E1` | `#141218` |
| surfaceBright | `#FAFDFC` | `#363C3A` | `#FFFBFE` | `#3B383E` |
| surfaceContainerLowest | `#FFFFFF` | `#0B100F` | `#FFFFFF` | `#0F0D13` |
| surfaceContainerLow | `#F4F7F6` | `#181E1C` | `#F7F2FA` | `#1D1B20` |
| surfaceContainer | `#EEF2F1` | `#1C2220` | `#F3EDF7` | `#211F26` |
| surfaceContainerHigh | `#E8EDEB` | `#262D2B` | `#ECE6F0` | `#2B2930` |
| surfaceContainerHighest / surfaceVariant | `#E2E8E6` | `#313836` / `#3F4947` | `#E6E0E9` / `#E7E0EC` | `#36343B` / `#49454F` |
| onSurfaceVariant | `#3F4947` | `#BEC9C6` | `#49454F` | `#CAC4D0` |
| outline | `#6F7977` | `#899391` | `#79747E` | `#938F99` |
| outlineVariant | `#BEC9C6` | `#3F4947` | `#CAC4D0` | `#49454F` |
| inverseSurface | `#2C3130` | `#DDE4E2` | `#313033` | `#E6E0E9` |
| inverseOnSurface | `#F1F4F2` | `#2C3130` | `#F4EFF4` | `#313033` |
| inversePrimary | `#86D4D2` | `#145B60` | `#D0BCFF` | `#6750A4` |
| surfaceTint | primary | primary | primary | primary |
| scrim / shadow | `#000000` | `#000000` | `#000000` | `#000000` |

Black is restricted to scrim/shadow. White is a surface/on-accent token, not decorative glow.

### 5.2 Semantic mapping

| Meaning | Color and redundant channel |
|---|---|
| Reading ground | `surface` + `onSurface`; no tinted article card |
| Browse chrome | `surfaceContainerLow` where separation is needed |
| Feed/date metadata | `onSurfaceVariant`, never tertiary |
| Unread | Primary 8dp dot + weight 600 + “Unread” semantics; no row fill |
| Read | Weight 400 + `onSurfaceVariant`; never blanket alpha |
| Updated | `secondaryContainer` label + `onSecondaryContainer` + visible “Updated” |
| Starred | Filled star + visible “Starred” + toggle state; `onSurface` |
| Selected navigation/chip | Material selected container + check/selected semantics + label |
| Primary action / link / focus | `primary` on actual ground; Next uses `primaryContainer` + `onPrimaryContainer` |
| Pending / downloading / waiting | `onSurfaceVariant` plus literal state text and determinate progress when known |
| Text saved, images incomplete | `secondaryContainer` evidence label + “Text saved · images N/M” + Retry images |
| Saved | `secondaryContainer` evidence label + “Saved”; never color alone |
| Error | `error`/`errorContainer` pairs + icon + cause + recovery action |
| Diff deletion | Strike-through + `onSurfaceVariant` + “deleted” semantics |
| Diff insertion | Underline + `primary` + optional `primaryContainer` wash + “inserted” semantics |

Normal text is ≥4.5:1, large text ≥3:1, meaningful icons/boundaries/focus ≥3:1 against every actual adjacent surface. Numeric results are in `QA-v1.md`; dynamic wallpaper schemes still need instrumentation against resolved production colors.

### 5.3 Shape

Use component defaults from `Shapes(extraSmall=4, small=8, medium=12, large=16, extraLarge=28)`. Article rows have 0dp outer shape. Thumbnails/chips use small shapes; sheets/dialogs use 28dp; Current/Changes uses connected start/end shapes. Do not use `Shape20` on every row, morphing polygons, web 6px buttons, glass, gradients or decorative splitters.

## 6. Screen contracts

### 6.1 Read / article index

**Order:** 64dp scope bar → non-blocking sync/preparation banner when present → horizontally scrollable scope chips → article generation.

Scope bar contains current scope, Search and Mark all read. Mark all read is absent only where the action has no meaning; it never shifts Search. Scope chips: Unread (default), Updated, Starred, Recently read, All, then saved searches in creation order. Feed/group/saved-search scopes preserve the base view and sort. Recently read can be hidden by the existing preference, default visible.

Each row exposes one Open action and one More action. More contains Mark read/unread, Star/unstar, Share, Download, and existing mark-above/below commands where upstream provides them. Swipe duplicates only the configured read/star actions. Read-state writes update the current generation in place: title weight and state text change, but the row does not disappear or reorder until scope change, refresh or restart (S25a).

Mark all read asks “Mark N articles as read in [scope]?” by default, then reports the exact affected count. Previous/next continues to work after the current article leaves Unread (S25a neighbor fallback).

Optional date headers use the existing sticky-header preference, **default OFF** (`FlowArticleListDateStickyHeaderPreference.kt:29–32`). At 200% font they are non-sticky section labels to avoid consuming the viewport.

### 6.2 Read state distinctions

| State | Required rendering |
|---|---|
| Initial loading | Scope remains stable; 4dp linear indicator, “Loading articles”, row placeholders; no fake counts |
| Refreshing | Existing generation stays interactive; inline `syncing n/m feeds`; no layout jump |
| Empty scope | “No unread articles” / scope-specific equivalent, one relevant action such as View all or Refresh; no celebration |
| No subscriptions | “Add a feed to start reading”, Add feed action linking to Feeds subscribe flow |
| Offline with content | Non-blocking “Offline — showing saved articles”; content and local actions remain available |
| Partial sync | “Updated N of M feeds · K failed”; View errors disclosure and Retry; successful rows remain |
| Failed initial load | Error title, cause in plain language, Retry; no empty-state wording |
| Search preparation | “Preparing search · N of M articles. Browsing is ready.” Determinate when N/M known; search/rule execution gated as below |
| Long/mixed-direction content | Three-line list clamp only, `dir=auto` content, full title in Reader, no horizontal page scroll |

### 6.3 Feeds and FeedDetail

Feeds shows folder rows with expand/collapse and unread count, nested feed rows with title, host and state, and Add feed. Refresh is per folder/feed or global pull-to-refresh. Empty folders remain navigable and can be renamed/deleted through GroupDetail.

FeedDetail order:

1. Feed title and favicon; editable Rename action.
2. Full feed URL and site URL, wrapped with `overflow-wrap:anywhere`; Open original and Change URL.
3. Folder (single `groupId`) and **Tags** editor (comma-separated, lowercase, order-preserving dedupe). This resolves S4's deferred multi-tag UI without merging tags and folders.
4. Muted (default OFF): “Hide this feed from Unread and Updated. Starred, Recently read and All still include it.”
5. Show updates (default ON).
6. Automatic full-text extraction (per-feed `isFullContent`), Notifications and Open in browser upstream options.
7. Refresh now, Clear articles and Unsubscribe. Destructive actions name the feed and consequence in an AlertDialog.

Loading preserves the list and shows a linear indicator. Feed failure is inline on that feed; other feeds remain usable. Empty Feeds shows Add feed and Import OPML. FeedDetail validation errors remain beside the edited field and do not discard valid edits.

### 6.4 Search and saved searches

Search uses the same field anatomy as RuleEdit. The app bar Back collapses Search. `SearchBar` contains the query, Clear and a 48dp Save search action when the query is nonblank. Grammar help is an inline disclosure, not a separate command palette.

Help copy must state:

- Unquoted terms are AND-ed; each is a substring in title, summary, feed body or extracted body.
- Quotes group words into one substring term; shell-style escapes are supported. Unbalanced quotes fall back to whitespace splitting.
- `tag:NAME` filters one tag; last valid `sort:asc`, `sort:desc` or `sort:relevance` wins.
- Unicode simple lowercase is used. `straße` does not match `strasse`; final Greek sigma and ligatures are not fully folded.
- `%`, `_` and `\` are literal escaped query characters. No regex, Boolean operators or semantic search.

Initial Search shows saved searches and grammar help, not fabricated recent/trending queries. Results show “N results” when countable and “Index covers N of M articles”. A stale-sync notice appears if account update time is older than 24h. Result rows reuse the article index and open Reader with Back returning to the same query/results anchor.

Save search captures the verbatim query including `tag:` and `sort:`. Duplicate names are allowed. Saved-search chips execute `view=all + query`; rename/delete act on row identity, not name.

**Preparation gate:** while S5 step 7 normalization is incomplete, the query draft and name editor may be edited, but Run, saved-search execution, Preview and Apply are disabled with “Preparing search · N of M”. Existing results are removed rather than presented as complete. Browsing remains available. No rule evaluation or ingest application runs against a half-normalized existing library.

Search loading keeps the query visible. Zero results says “No matches for [query]” and offers Edit query. Error preserves the query and offers Retry. Offline results use the same local predicate and coverage text; no online-search affordance is invented.

### 6.5 Reader, extraction and download evidence

Top app bar: Back/list toggle, feed context, Reading appearance, Share and overflow. Full article title lives in the article, not a shrinking app bar.

Article order: metadata → title → Current/Changes when a snapshot exists → body → source URL → end padding. State text distinguishes publisher Updated from local extraction. A locally extracted article says “Full text extracted”; it never invents a publisher update.

Bottom bar: Previous / Read or Unread / Star / Next / More. First/last boundaries disable the corresponding action with semantics “Previous article, unavailable” and leave positions stable. More contains Download or download state, Extract full text, Open original, Read aloud, and existing state-specific actions.

Extraction states: idle action → “Extracting full text” inline → stored evidence → transient article-only “Extraction unavailable; showing feed text” or “Extracted text was not useful; showing feed text”. No dialog and no success claim on rejection. Existing valid feed/full text never gets replaced by a challenge/error page.

Remote images unavailable offline show fixed-aspect placeholders with “Image not saved”. Downloaded incomplete articles show missing-image placeholders and retain readable saved text. Offline downloaded links open the external browser intent; they never navigate inside the hardened renderer. The offline JS image bridge is absent, so image zoom is either native renderer behavior or unavailable without an inert fake control.

### 6.6 Current / Changes diff

The control appears only when `originalContent` is present and diff computation succeeds. Current is the default whenever the article opens; the selected tab may be remembered for the existing composition but is not a new global preference.

Visual runs come from S22 sanitized word-level tokens. Deletion is struck through, insertion underlined; neither uses red/green. There are no side-by-side compact panes, gutters or line numbers. Tables/code/images remain inside the reading measure.

For TalkBack, a single merged `DiffText` semantics node exposes a content description generated from the same run list: `“deleted: [run] … inserted: [run]”`. Visual spans and the accessible description cannot diverge because both consume the identical model. The group label is “Article version”; each option exposes selected state. Switching is immediate and preserves article scroll ratio where meaningful.

### 6.7 Recently read and Updated history

Recently read orders by `readAt DESC, rowid DESC` and includes read date/feed/title. Its overflow contains Clear recently read with a count confirmation; clearing does not mark articles unread or remove downloads/stars. Empty state: “Articles you read will appear here.” Loading/error preserve the existing list when available.

Updated orders by the default scope order `date DESC, rowid DESC`, never `contentChangedAt`. Opening does not clear Updated unless mark-read-on-open fires. Updated rows offer Current and Changes through the same Reader. Empty state: “No publisher updates”; local extraction does not populate this queue.

### 6.8 Rules, mutes, preview, apply and Undo

Rules has two filter choices: **Standard** and **Mutes**. Rows show title, verbatim query, scope and effects/behavior before Edit.

Standard editor fields: title, query, All feeds or selected feeds/folders/tags, effects Mark read and/or Star, Preview. New-article behavior help states the 100-star per-feed-refresh budget; publisher-update Star is skipped. Mute editor has title, query and scope only, with “Filters Unread and Updated only. It never changes article state during sync.” Mutes do not show Preview or Apply-to-existing.

Preview sheet:

1. Exact match count and plain-language scope/effects.
2. Loading indicator or up to 20 article rows with title/feed/date.
3. “First 20 of N” when N>20.
4. Zero-match text with Edit query, or failure text with Retry.
5. Apply-to-existing action for Standard only.

Apply asks for exact-count confirmation before a single transaction. Result says “Applied to N articles” and offers **Undo**. Undo is durable for the most recent completed, not-yet-undone run within the 10-run retention bound; it is not presented as a transient snackbar-only promise. Completion and restoration results are persistent on the editor and announced politely.

Undo result uses exact service counts: “Restored N · skipped M (changed after apply)”. It never claims to restore edited rows or clear pre-existing stars. No Undo action appears for ingest-time effects; normal mark-unread/unstar remains available.

### 6.9 Downloads and storage

Reader More and Settings → Downloads expose the same state model. A row includes title/feed, state, size when known and the relevant action.

| Service condition | Visible copy and action |
|---|---|
| pending | “Pending” + Cancel/remove request |
| pending under bulk/chained UNMETERED constraint | “Waiting for Wi-Fi”; never Failed |
| downloading text | “Downloading text” + progress when measurable |
| ready, assets in flight | “Text saved · images N/M”; saved text opens offline now |
| ready, terminal asset failures/skips | “Text saved · images incomplete N/M” + **Retry images**; never collapse to Saved |
| ready + assetsComplete | “Saved” + Remove download |
| failed(network/http_4xx/http_5xx/challenge/storage/unknown) | Human cause + Retry; existing ready text is not corrupted |
| oversize | “Text saved · some images exceeded the download limit” + incomplete evidence; if text itself exceeds cap, Failed with cause |
| orphan recovery | “Pending” after restart; no false in-progress animation |

Retrying images is a manual action and keeps the committed text/read state. Initial attempt plus three automatic retries is the service ceiling; UI says “Automatic retries exhausted” only after retry 3 fails. It never promises an exact wake time or displays the withdrawn 1/5/25-minute schedule.

Storage bar shows bytes and percent of the 1GiB cap. At ≥90% a persistent warning links to Manage. At 100%, new downloads fail with “Storage full” and existing downloads remain readable. Remove all asks for the exact count and bytes. Oldest-first is a selection suggestion, never silent eviction. Bulk download states the 200-article invocation cap. No time-based expiry UI is shown.

### 6.10 FeedBucket import, result and OPML

Migration page offers **Choose FeedBucket package** (preferred v1 secret-free package or compatible raw copy), explains device-local processing and shows any resumable run. It never asks for server credentials.

State sequence and visible copy:

- **Retaining copy:** “Making a private working copy on this device.” Indeterminate until byte count is known.
- **Validating:** schema/package version, required tables and secret guard. Invalid input names the precise problem and states “Nothing was imported.”
- **Preview:** counts table for subscriptions, tags, entries, unread, starred, updated, recently read, saved searches, standard actions, mute actions, snapshots and extracted entries; first 10 subscription titles; optional Refresh state for already-mapped articles, default OFF; Import and Cancel.
- **Applying:** current feed, imported/skipped/failed ledger, resumable indicator. Leaving shows Run status rather than pretending rollback.
- **Completed:** exact imported/skipped/failed counts and itemized row errors; Remove imported data and Done.
- **Failed:** completed chunks remain truthfully listed; Retry import resumes. “Source kept on this device for retry” remains visible, so terminal-on-leave cleanup must not delete the source while this action exists.
- **Cancelled before apply:** nothing written and working copy purged.

Apply aborts if the retained copy fingerprint differs from Preview. Remove imported data reports “Removed N · skipped M (edited after import or retained to protect other articles)”. Gate A must resolve the feed/article cascade guard below; the UI must never imply that protected feeds/articles were removed.

OPML has separate Import OPML and Export OPML actions. Preview/result counts are subscriptions imported/skipped only. Text explicitly says OPML does not carry read/star/history/rule/download state.

### 6.11 Settings

Root groups, in order:

1. **Library:** Feeds, Rules & mutes, Downloads, Import & export.
2. **Appearance:** System/Light/Dark, Dynamic color, Comfortable/Compact density, existing list display options and sticky dates.
3. **Reading:** family choice including current Google Sans default and Serif preview, font size, line height, spacing, padding, alignment, bold, images and external fonts.
4. **Interaction:** Mark read on open ON, Mark read on scroll OFF, Mark-all confirmation ON, sort and existing gestures.
5. **Sync & offline:** best-effort explanation, Wi-Fi/charging, sync-on-open, global auto-extraction OFF, storage link.
6. **Accounts, Language, Troubleshooting, About & licenses.** No mandatory account onboarding, telemetry or AI setting.

Root Settings never becomes a blank page. Its loading state keeps group headings with placeholders. Its error state says “Some preferences could not be read” with Retry and preserves available groups. Its fresh/empty state keeps all setting groups and shows “No optional accounts or imports yet” only in the relevant summary. This avoids treating a permanent settings catalog as removable content.

## 7. Shared state and feedback rules

| Aspect | Rule |
|---|---|
| Loading vs empty | Loading never says “No articles”; empty is rendered only after a successful known-empty load |
| Error vs offline | Offline with cached content is a non-blocking banner; failed initial load is an error block with Retry |
| Disabled vs failed | Waiting for Wi-Fi, preparation and boundary Previous/Next are disabled/pending, never errors |
| Success | Exact count or durable evidence; no color-only success, celebration or false atomicity |
| Partial | Name successful and failed parts separately; never turn partial into Saved/Complete |
| Feedback timing | Read/star/diff changes immediate; apply/import results persistent; transient extraction failure uses article-local status |
| Motion | `MotionScheme.standard()` product-wide; built-in component effects, no row bounce, shape morph, wavy progress or auto-scroll |
| Reduced motion | Animator duration scale 0 removes custom spatial transitions, shared elements and auto-scroll while preserving Back, focus and state |
| Snackbar | Supplemental only; Undo/apply/import results also have durable inline representation |
| Dialogs | Reserved for destructive/count confirmation; not extraction failure, offline or routine progress |

## 8. Accessibility, localization and state preservation

### 8.1 Compose semantics contract

- Every page exposes one heading with the visible page title. Section labels use `Modifier.semantics { heading() }`.
- Icon-only buttons have action names, not icon names: “Back to Unread”, “Reading appearance”, “More actions for [article]”.
- Article row accessible order: title → feed/date → Unread/Updated/Starred/download states → Open action. The row's More action is separately focusable. Decorative dots, stars duplicated by text and thumbnails have null descriptions.
- Toggle controls expose `toggleableState` and state description (“Starred”/“Not starred”). Scope and Current/Changes expose selected state. Disabled boundary/preparation controls expose disabled state and reason in nearby text.
- Progress exposes determinate value N/M where known and a polite status update at meaningful milestones, not every asset byte.
- Dynamic result lines use `LiveRegion.Mode.Polite`; applying does not repeatedly steal focus.
- Alert dialogs start focus on the safest meaningful action/content, trap it while modal, support Back, and restore it to the launcher. Sheets do the same.
- Diff uses the merged accessible run description in §6.6.
- Full feed URLs and long titles are available to semantics even when visually clamped in list/app-bar contexts.

### 8.2 Focus, keyboard and D-pad

Order follows visual start→end/top→bottom: app bar → banner → scopes → list → bottom navigation; on wide Read, list pane → detail pane → root navigation. Use `focusGroup()` and explicit traversal only where pane order needs correction.

Opening Reader focuses the article heading without scrolling past it. Back restores the originating article row and anchor. Search focuses its input and opens IME; closing restores Search launcher. Retry keeps focus on Retry or moves to the resulting status heading only when the old node disappears. No positive tabindex analogue, pointer-only command or gesture-only exit.

### 8.3 RTL, fonts and long content

Use start/end padding and auto-mirrored Back/Previous/Next/Open icons. Pane order and connected shapes mirror. Test an English/Arabic or Hebrew mixed fixture: article text uses content direction; chrome uses layout direction. Numbers and URLs remain isolates so they do not reorder surrounding labels.

At 200% font and 320dp: top-bar feed context may ellipsize but remains fully labeled; scope chips scroll; app-bar action labels never shrink; settings rows and dialog actions wrap; one-pane detail replaces cramped two-pane layouts. At 320dp no document-level horizontal scroll is allowed. Article tables/code may scroll inside their own labeled container. URLs use break opportunities; they never widen the page.

### 8.4 Durable UI state

Implement S24a/S25a/S26 directly:

- Canonical versioned percent-encoded scope key over `[view,feedId,groupId,savedSearchId,query,sort]`.
- Save anchor article/id/index/offset after 300ms scroll idle and on pause/navigation away; exact anchor → clamped index → top.
- Save article scroll ratio/content height on pause/idle; restore on reopen with ±1-screen acceptance and top fallback.
- Current generation updates read/star state in place; refresh/sync starts a new generation. No animated catch-up scroll after restore.
- Search stores query draft/results anchor by scope. Reader stores selected article and Current/Changes for the composition; durable requirements are article and positions, not a new tab preference.
- Wide resize preserves selected article, list anchor and article ratio. Font-driven pane collapse follows the same Back hierarchy.

## 9. Compose implementation map and API posture

### 9.1 Stable dependencies and bounded opt-ins

- `androidx.compose.material3:material3` **1.4.0 stable** via Compose BOM 2025.10.01. Do not substitute 1.5.0-rc01.
- `material3-adaptive-navigation-suite` **1.4.0 stable** via BOM.
- `androidx.compose.material3.adaptive:{adaptive,adaptive-layout,adaptive-navigation,adaptive-navigation3}` **1.3.0 stable explicit pins** per amended S37. Wave A must attach resolved dependency evidence; current source has alpha pins that are not accepted.
- Existing Navigation3 1.0.0 and `NavDisplay` predictive transitions remain.
- `ExperimentalMaterial3ExpressiveApi`: bounded to theme emphasized typography and `MotionScheme.standard()` call site as required by the resolved artifact.
- `ExperimentalMaterial3Api`: bounded to TopAppBar, slot-based SearchBar/SearchBarState and ModalBottomSheet signatures that require it.
- `ExperimentalMaterial3AdaptiveApi`: bounded to adaptive scaffold/directive/navigator call sites required by resolved 1.3.0 KDoc.
- `ExperimentalFoundationApi`: sticky headers only when enabled. `ExperimentalSharedTransitionApi`: remove with article shared-element transitions rather than carrying it into calm reader motion.

**Verified 1.4.0 API correction:** local `material3-android-1.4.0` classes contain **zero public `androidx/compose/material3/ButtonGroup*` classes**. Do not implement the creative-direction prose as a nonexistent `ButtonGroup` or `ButtonGroupState` call. Current/Changes uses public `SingleChoiceSegmentedButtonRow` + two `SegmentedButton` children with 48dp minimum height and connected shapes. The hidden binary-compatibility overloads are not used. This is a bounded stable fallback to the guideline's deprecated component, forced by the stable API inventory; migrate to connected ButtonGroup only when the pinned stable release actually provides it.

`SearchBarState` exists in 1.4.0 and carries `ExperimentalMaterial3Api`; use the slot-based `SearchBar(state,inputField,...)` or `DockedSearchBar` with that opt-in. Do not claim it is stable because it appears in current unversioned reference docs.

### 9.2 Source map for Wave H and feature waves

| Surface | Existing source to adapt | New source allowed by implementation plan/this contract |
|---|---|---|
| Theme | `ui/theme/{Type,Theme,Shapes}.kt`, palette package | Static schemes and standard motion in owned theme files |
| Root navigation | `ui/page/nav3/AppEntry.kt` | NavigationSuiteScaffold wiring and nested routes |
| Adaptive Read | `ui/page/adaptive/{ArticleListReadingPage,PaneMotion,TopBar}.kt` | No drag splitter; font-aware one-pane directive |
| Index/search | `ui/page/home/flow/{FlowPage,ArticleList,ArticleItem,SearchBar,MarkAsReadBar}.kt` | SavedSearchChip within filter files, preparation/evidence states |
| Reader/diff | `ui/page/home/reading/{ReadingPage,Content,TopBar,BottomBar,Metadata}.kt` | `DiffPane.kt`, `OfflineArticleWebView.kt`, appearance sheet |
| Feeds | `ui/page/home/feeds/**` | `detail/FeedDetailPage.kt` and group detail equivalent |
| Rules | Existing Settings navigation | `ui/page/settings/rules/{RulesPage,RuleEditPage,RulePreview}.kt` |
| Downloads | Reader More, Settings | `ui/page/settings/downloads/{DownloadsPage,StorageBar}.kt` |
| Migration | Settings | `ui/page/settings/migration/{MigrationPage,ImportPreviewSheet,ImportResultSheet}.kt`; sheet names in plan may become pages to satisfy durable Back |
| Settings | `ui/page/settings/**` | New group rows; retain existing preference pages |

No feature semantics are added by this file map. Data behavior remains S1–S38.

## 10. Dated Material 3 Expressive conformance matrix

Sources retrieved 2026-11-16:

- **M1:** [Applying M3 Expressive usability tactics](https://m3.material.io/foundations/usability/applying-m-3-expressive): clear scale/placement, consistent roles, calm balanced layouts.
- **M2:** [Material Design 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3), updated 2026-11-10: theme, roles, type, dynamic color and accessibility.
- **M3:** [Compose Material 3 releases](https://developer.android.com/jetpack/androidx/releases/compose-material3): 1.4.0 stable; 1.5.0-rc01 is not used.
- **M4:** [Adaptive navigation](https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation), updated 2026-10-22.
- **M5:** [List-detail](https://developer.android.com/develop/ui/compose/layouts/adaptive/list-detail), updated 2026-10-28; [adaptive releases](https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive): 1.3.0 stable.
- **M6:** [Motion overview](https://m3.material.io/styles/motion/overview): standard scheme for utilitarian products.
- **M7:** [Search](https://m3.material.io/components/search/overview), [Button groups](https://m3.material.io/components/button-groups/overview), [type](https://m3.material.io/styles/typography/applying-type).
- **M8:** [Compose accessibility](https://developer.android.com/develop/ui/compose/accessibility), [interaction states](https://m3.material.io/foundations/interaction/states/overview).

| Source | Screen/component | Actual API/version posture | Adaptation | Verification |
|---|---|---|---|---|
| M1 M2 M3 | Global theme | `MaterialTheme`, `ColorScheme`, `Typography`, `Shapes`, `MotionScheme.standard()`; material3 1.4.0 stable | Static petrol/ink first, optional dynamic, calm standard motion | Resolved deps; numeric static/dynamic-sample contrast; dark/light/RTL renders |
| M1 M2 M4 | Root navigation | `NavigationSuiteScaffold`; navigation-suite 1.4.0 stable | Three roots; bar/rail; no dashboard | 320/390/768 targets, selection semantics, Back/root state |
| M5 | Read list-detail | `NavigableListDetailPaneScaffold`, navigator, `AnimatedPane`; adaptive 1.3.0 stable target with bounded opt-in | Stable two-pane where content fits, one-pane at large font | Same article/anchors across 390/768, rotation, font 200%, predictive Back |
| M1 M2 | App bars | `TopAppBar`, `BottomAppBar`, 48dp `IconButton`/`IconToggleButton`; 1.4.0 | 64dp bars, no shrinking title or 40dp actions | Bounds, hit tests, inset occlusion, long context |
| M1 M2 M7 | Article index | `LazyColumn`, `Surface`, `Text`, `HorizontalDivider`; stable | Flat Margin Notes rows, no cards/fill/alpha wash | Long title/feed/URL, mixed RTL, comfortable/compact, font 200% |
| M2 M7 | Scopes/saved searches | `FilterChip`, `LazyRow`; 1.4.0 | 48dp chips, horizontal overflow, labels+selection | Grayscale selection, TalkBack order, 320dp hit tests |
| M2 M3 M7 | Search | Slot `SearchBar`, `SearchBarDefaults.InputField`, `SearchBarState`; 1.4.0, `ExperimentalMaterial3Api` | Contained scoped search, draft retained during preparation | Actual signature/opt-in check, IME, clear/Back, zero/error/offline/preparing |
| M1 M2 M7 | Current/Changes | **Stable fallback** `SingleChoiceSegmentedButtonRow`; 1.4.0; no public ButtonGroup exists there | Connected two-option stable control, no fabricated API | Jar inventory; selected semantics, 48dp, focus, immediate switch |
| M1 M2 M3 | Reader actions | `BottomAppBar`, icon buttons, `FilledTonalIconButton`, `DropdownMenu`; 1.4.0 | Previous/Read/Star/Next/More, single tonal Next | All targets ≥48dp, TalkBack, boundaries, More parity |
| M2 M8 | Diff | Compose text/spans + merged semantics; no WebView | Strike/underline + accessible run labels, inline measure | DiffA11yTest, light/dark contrast, long/mixed text |
| M1 M2 M3 | Appearance/previews | `ModalBottomSheet`, 1.4.0 with bounded opt-in | Reading controls/evidence; internal scroll, focus return | Back/Escape, IME, 200% font, reduced motion |
| M1 M2 M3 | Confirmations/results | `AlertDialog`, `SnackbarHost`, persistent result `ListItem`; 1.4.0 | Dialog only for consequential confirmation; durable Undo/result | Exact count, safe initial focus, restoration, process recreation |
| M1 M2 M6 | Loading/progress | `LinearProgressIndicator`, placeholders; 1.4.0 | Straight 4dp track + literal state, no wavy/shimmer dependency | Loading/empty/error distinction, animator scale 0 |
| M1 M2 | Feeds/settings/rules | `ListItem`, `Switch`, `RadioButton`, text fields; 1.4.0 | Flat rows, labels before values, tags editor, no nested cards | Long values, validation recovery, switch semantics |
| M1 M2 M3 | Import evidence | `ListItem`, table-like rows, `LinearProgressIndicator`, dialogs | Counts first, samples second, action last; honest retained source | Fingerprint failure, failed/retry, result/cascade skip copy |
| M6 M3 | Motion | `MotionScheme.standard()`; 1.4.0, bounded expressive opt-in | Expressive anatomy without gratuitous bounce/morph | Normal + animator 0, no custom spring in list/read deltas |
| M2 M5 M8 | Edge/insets/Back/accessibility | `WindowInsets`, Navigation3 predictive transitions, adaptive navigator, Compose semantics | API 36 behavior, 48dp floor, content-direction text | API 36 emulator later; mock geometry now; no physical-device claim |

## 11. Gate A contract clarifications required before foundation release

These are not new features and this contract does not silently amend S#. Gate A must make the data wording consistent with these UI truths:

1. **Undo version guard:** ledger stores BEFORE read/star/update/readAt values but the POST-apply `stateVersion` used for optimistic comparison. Otherwise every changed row is skipped or the guard is meaningless. UI copy remains “skipped M (changed after apply)”.
2. **Import cascade guard:** removing an imported feed must be blocked when deleting it would FK-cascade a pre-existing, new or edited retained article. Per-row `stateStamp` alone cannot protect that graph. Result copy reserves “retained to protect other articles”.
3. **Failed-import source:** retained source survives while Retry/Resume is offered, including when the user leaves Failed Result. Purge only after successful completion/cancel semantics or explicit user discard, plus orphan GC policy.
4. **Post-text asset recovery:** text-ready with in-flight or failed assets is visibly incomplete and has manual Retry images. `assetsComplete=1` must distinguish terminal skips/failures from “every image saved” before UI can say Saved without qualification.
5. **Retry arithmetic:** initial attempt 0 + automatic retries 1/2/3, with delay floors 30/60/120s, not three total attempts and never a promised wake time.

The mock renders these states as design requirements; it does not assert the unresolved database implementation exists.

## 12. Mock and acceptance inventory

`docs/creative/mocks/index.html` is a single self-contained interactive artifact with embedded CSS, JS, SVG icons, Google Sans Flex and OFL notice. It has no network, build, external font, external image or runtime service dependency. `README.md` documents controls and regeneration/QA boundaries. `rendered/` is gitignored evidence output.

Mock selectors cover:

- Read populated/loading/empty/error/offline/preparing and comfortable/compact densities.
- Feeds populated/loading/empty/error and FeedDetail.
- Search initial/results/loading/zero/error/preparing and saved searches.
- Reader Current/Changes, overflow, appearance, Serif preview, extraction error, incomplete download and Previous/Next boundary.
- Recently read and Updated empty/populated states.
- Rules list/editor/preparation, Preview loading/zero/20+/failure, Apply result and Undo result.
- Downloads pending/Waiting for Wi-Fi/downloading/text-ready incomplete/Saved/failed/storage warning/full/empty/loading/error.
- Migration retaining/validating/preview/applying/completed/failed/invalid and OPML preview/result.
- Settings normal/loading/error/fresh.
- 320/390/768 widths, light/dark/dynamic samples, 100/200% font, LTR/RTL and reduced motion.

Browser QA records actual rectangles, minimum actionable target, document overflow, fixed-bar hit-testing, focus/Back behavior, accessible names, screenshots and numeric contrast. It does not claim Compose runtime, TalkBack, emulator or physical-device verification; those remain implementation gates.
