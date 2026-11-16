# FeedBucket Android creative direction v1

Status: RECOMMENDED FOR THE NATIVE UI CONTRACT
Authority: `docs/APPROVED-DIRECTION.md` D1-D17 and `docs/SPEC-v1.md` S34-S37
Visual baseline reviewed: `e40064a5` on `feedbucket-native`

Technical behavior is outside this document. The designer must re-read the amended `SPEC-v1.md`, `IMPLEMENTATION-v1.md`, and `docs/research/SPEC-RECONCILIATION-v1.md` from reconciliation card `t_8df74936` before resolving disputed rule, mute, migration, FTS, offline, WorkManager, identity, or gate states. Those states remain provisional here.

**Reading the brief:** Make FeedBucket feel like a purpose-built reading instrument, with the speed and depth of the web reader and a native Android identity that survives dynamic color, large text, offline use, and a tablet.

**Method and constraint:** SCAMPER, Bob Eberle's 1971 development of Alex Osborn's checklist, applied to stock ReadYou. The strongest operators are Eliminate and Rearrange: remove card-like row containers and scattered controls, then arrange every signal around one reading axis. The imposed constraint is **one primary thing per surface**: scope in Browse, article in Reader, evidence in Preview. Expressive Material supplies hierarchy and interaction anatomy, not decoration.

## What stock ReadYou is currently saying

ReadYou already has useful native bones: Navigation3, an adaptive list-detail scaffold, Google Sans Flex, reader preferences, swipe actions, and a combined list/reader ViewModel. Its visual emphasis is diffuse. The flow uses a 172dp large app bar, rounded `Shape20` article rows and thumbnails, tertiary-colored feed names, automatic title shrinking, animated scope changes, and `MotionScheme.expressive()`. Reader actions are 40dp icon buttons in a custom 60dp row. That is a configurable feed client, not yet a coherent FeedBucket.

Keep the adaptive state machinery and typography asset. Change the emphasis: titles before containers, reading before theme effects, explicit state before gesture novelty. FeedBucket web contributes its discipline: quiet metadata, no unread row fills, readable diffs, and machinery that appears when it explains the next action. Its CSS radii, flat buttons, and page layout stay behind.

## Directions

### 1. Margin Notes

**Pitch:** A start-aligned editorial axis carries feed, time, update, and offline annotations while titles and article text stay unobstructed.

How it works: Browse is a flat typographic index with a small metadata line above each title. Reader repeats the same metadata order above the article, then places Current/Changes and download evidence in that margin. Compact screens keep the margin inline. Wide screens may move secondary article metadata into a narrow start column that reflows inline for large text or RTL constraints. Material navigation, connected button groups, sheets, and toolbars provide the native frame.

Precedent: Feedbin's first-version update diffs, Instapaper's reading-appearance panel, and GitHub's word-level rich diff. The mechanism is borrowed without paper costumes or code-review gutters.

Risk: The margin can become a second dashboard. Keep it to one metadata line in Browse and one status cluster in Reader. Counts, storage detail, rule results, and sync diagnostics open on demand.

Buildable first step: Produce one 390dp Unread list and one Reader state with Updated, downloaded, starred, and long-title fixtures. Prove that no status needs a filled row, icon-only meaning, or extra destination.

### 2. Reading Desk

**Pitch:** Treat the phone as a single reading surface and larger windows as a fixed desk where the index and article never trade places.

How it works: Compact navigation is Read / Feeds / Settings. Medium and expanded windows use a rail, then a persistent list-detail scaffold. Selecting an article changes only the detail pane. Search, filters, and sheets occupy the list side so the article remains stable. The phone gets the same order through one-pane navigation and predictive Back.

Precedent: Material's Reply sample and Apple Mail's list-detail continuity.

Risk: This is excellent adaptive hygiene but weak brand identity by itself. It can become an email client with RSS styling. It needs the Margin Notes metadata grammar to become FeedBucket.

Buildable first step: Mock 390dp and 768dp windows with the same selected article, list anchor, and scope. Verify Back, rotation, RTL, and font scale without losing either position.

### 3. Query Bench

**Pitch:** Make the shared query language the product's signature: one composer drives search, saved searches, mutes, rules, and previews.

How it works: A persistent Material search field sits below the scope bar. Saving a query turns it into a labeled chip. Rule creation reuses the same field, then shows match count and the first 20 results in a bottom sheet before Apply. Reader and article lists remain the Margin Notes design; the identity is strongest in Search and Rules.

Precedent: Feedbin saved searches/actions and Linear's command-oriented editing flow, with ordinary accessible fields instead of a command palette.

Risk: Search-first navigation can make casual browsing feel like database work. It also exposes grammar complexity before the user needs it. Keep syntax help inline and optional; never replace the Unread home with a blank search screen.

Buildable first step: Design one query composer in three states: search, saved search, and rule preview. Use a quoted phrase, `tag:news`, long terms, zero matches, and more than 20 matches.

### 4. Daily Proof

**Pitch:** Give chronology an editorial rhythm through restrained date sections and visible publisher corrections.

How it works: Sticky date headers divide the existing ordered list. Updated articles carry a consistent text label and open the same inline proof view. There is no synthetic daily edition, reading score, or completion celebration. Saved searches and feed scopes retain their own ordering.

Precedent: The Economist Espresso's dated reading rhythm and Feedbin's update queue.

Risk: Date theater can imply a finite daily product that RSS is not. Sticky headers also consume space at large font sizes and can add paging cost. This direction is a useful list treatment, not a full product identity.

Buildable first step: Add two date groups and an Updated article to the existing list mock. Test 320dp, 200% font, and an empty Updated view without celebrations or illustrations.

**The safe one I'm refusing:** Stock ReadYou with dynamic color, larger corner radii, an oversized app-bar title, wavy progress, shape morphs, and a Material 3 version bump. That is the safe one. It would satisfy a screenshot checklist while leaving the same scattered hierarchy and tiny reader controls. A second safe option, a magazine grid of image cards, is also refused: it penalizes text-only feeds and turns scanning into visual sorting.

**Pick:** **Margin Notes**, using Reading Desk's adaptive continuity as infrastructure. It gives FeedBucket a recognizable editorial signature, preserves the approved quiet-reading behavior, and makes updates, offline state, rules, and downloads legible without a dashboard or compulsory AI.

---

## Recommended direction contract

### 1. Identity and navigation

- Brand remains **FeedBucket**. Release application ID is `us.snakepit.feedbucket`; debug application ID is `us.snakepit.feedbucket.debug`; debug label is **FeedBucket D**. Kotlin namespace remains upstream as approved.
- Top-level destinations are **Read**, **Feeds**, and **Settings**. Read opens Unread. There is no Home dashboard, statistics destination, AI destination, or permanent brand wordmark on every screen.
- Compact windows use a Material navigation bar. Medium and expanded windows use a navigation rail. Read uses list-detail on larger windows; Feeds and Settings may use list-detail where their content benefits, without forcing a third permanent pane.
- Search is a scoped action inside Read, not a fourth destination. Unread, Updated, Starred, Recently read, All, and saved searches are scopes. Rules, Downloads, Migration, and OPML live under Settings with direct Back paths.
- The wide Read layout retains list scope, selected article, list anchor, and article position. Compact predictive Back returns from article to the same list. No animated list scroll is required to prove restoration.

### 2. Information hierarchy

#### Read / article list

1. **Scope bar:** current scope title, search, and scoped mark-all-read action. A collapsed title uses `titleLarge`; the expanded title may use `headlineSmall` only where space allows. No auto-sizing text and no repeated FeedBucket wordmark.
2. **Scope selector:** Material filter chips for the five views and saved searches. Horizontal scrolling is acceptable; labels never shrink. The selected chip has text, shape, check/selection semantics, and color.
3. **Article index:** flat full-width rows, not cards. Each row is one 48dp-minimum touch target with an accessible long-press or overflow alternative.
4. **Row order:** metadata, title, optional summary, optional thumbnail. Metadata contains feed name, human date, and only the states present: Updated, starred, downloaded/downloading/failed. Unread uses a dot plus title weight. Read rows use normal weight and `onSurfaceVariant`; never a low-opacity wash or filled background.
5. **Quick triage:** optional swipe actions duplicate visible actions and are never the only path. Long-press opens a labeled menu. Mark-read changes the badge immediately without removing the sticky-scope row or animating it away.
6. **Date headers:** quiet sticky labels when enabled. Loading, empty, offline, partial, and error states use plain text and one relevant action, not illustrations or dashboard cards.

Comfortable rows show a two-line summary. Compact rows remove the summary before reducing type or touch size. Thumbnails remain optional and use a small Material shape; a missing image never changes the title column's start edge.

#### Reader

1. **Top app bar:** Back/list toggle, feed context, Reading appearance, Share, and overflow. It collapses without hiding Back or creating an inaccessible gesture-only exit.
2. **Article margin:** feed, author/date, and state labels in the same order as the list. Updated is a text label. Download evidence names text versus image readiness when that distinction exists; the reconciliation-approved state model owns the final copy.
3. **Article title:** `headlineSmall` on compact and `headlineMedium` on expanded, wrapping naturally. Long titles never auto-shrink.
4. **Proof control:** when a snapshot exists, a connected, single-select **Current / Changes** button group sits directly above the body. The diff stays inline in reader typography. Deletion uses strike-through plus `onSurfaceVariant`; insertion uses underline plus an optional primary-container wash. No red/green encoding, side-by-side mobile panes, line numbers, or code gutters.
5. **Body:** a centered reading measure with edge-to-edge window insets and no bottom fade. Images, tables, and code do not widen the page.
6. **Triage bar:** a Material bottom app bar above navigation insets. Previous, Read/Unread, Star/Unstar, Next, and More are the compact set. More contains Download, Extract full text, Open original, and state-specific actions. Next is the single tonal emphasis. Every icon has a content description, state description, tooltip where appropriate, and a 48dp target.
7. **Reading appearance:** a modal bottom sheet on compact and an anchored/supporting surface where appropriate on large windows. It contains only reading controls and never navigates away to a theme showcase.

#### Search, rules, previews, and settings

- Use one query field anatomy across Search and Rule editor. Saved searches are labeled chips, not mysterious icons.
- Rules and mutes are stacked rows with title, query, plain-language scope, and effects visible before Edit. The editor is a page or inline expansion, not a wizard.
- Preview uses a bottom sheet with a real count, at most 20 article rows, and explicit loading/zero/failure states. Apply-to-existing names the consequence. Completion uses a snackbar or persistent result line with the exact count and Undo where approved.
- Import preview and result use the same evidence pattern: counts first, samples second, explicit action last. Settings rows use Material list anatomy rather than nested cards.
- Offline and sync information is inline and non-blocking. Persistent banners appear only when the amended behavioral spec requires them. No feature may claim readiness or success through color alone.

### 3. Typography

- **Chrome:** bundled Google Sans Flex v3.007, retained under SIL OFL 1.1 with the required notices. Use Material's 15-role scale and emphasized variants selectively. Do not add a second UI font.
- **Reader:** default to the platform serif family for long-form body text, with the existing reader font choices retained. Platform sans and serif require no downloaded asset. Any future bundled face needs a recorded license.
- **List title:** `titleMedium`, 16/24sp. Unread uses weight 600; read uses 400. **Summary:** `bodyMedium`, 14/20sp. **Metadata:** `labelMedium`, 12/16sp with no all-caps transformation.
- **Reader body default:** 18/28sp, adjustable through existing reading preferences. Target roughly 58-72 characters per line and a maximum text measure near 680dp, then verify with real font metrics rather than treating the number as a fixed width.
- **Hierarchy rule:** weight and spacing carry emphasis before color. Emphasized type is for scope titles, article titles, and primary actions, not every label.
- Android text uses baselines and `sp`. At 200% font scale, controls grow or wrap, metadata reflows, and no essential label truncates. Article titles and summaries may clamp only in the list; full text remains available in Reader. Mixed-direction article text follows content direction while chrome follows layout direction.

### 4. Density and geometry

- Use a 4dp base grid. Default screen gutters are 16dp on compact and 24dp on expanded. Reader text may use 20dp compact gutters when that improves measure.
- Comfortable list rows use 12dp vertical padding and 8dp between title and summary. Compact rows use 8dp vertical padding and omit summary. Text determines height; do not force fixed card heights.
- Keep 16dp between unrelated groups and 4-8dp between related metadata. Dividers are `outlineVariant` and supplementary; whitespace and alignment do most of the separation.
- Every actionable target is at least 48x48dp, including top-bar icons, chip hit areas, bottom-bar actions, sheet close controls, and inline diff controls. Visual glyphs may stay 24dp inside those targets.
- Preserve the M3 component's default height and padding unless a measured accessibility requirement calls for more. Do not recreate the current 40dp reader buttons.
- No decorative splitters, draggable panes, glass panels, gradients, or one-off shadow recipes. On wide screens the reading measure stays bounded even when the window is not.

### 5. Color and shape

- Ship complete static light and dark schemes before dynamic color. Use a restrained ink/petrol primary derived from FeedBucket's existing blue temperament, with neutral surfaces and measured on-color pairs. The designer owns exact tonal values and numeric ratios.
- Dynamic color is optional and may be enabled on supported Android versions. It never supplies the only FeedBucket identity and never changes status meaning.
- `surface` is the reading ground. `surfaceContainerLow` may distinguish browse chrome. Primary marks active navigation, focus, links, and the single emphasized reader action. Feed names use `onSurfaceVariant`, not the current tertiary accent.
- Unread rows have no fill. Updated uses a small labeled outline or secondary-container treatment with correct on-color. Star uses both filled icon shape and state text. Errors use error roles plus copy and iconography.
- Normal text meets 4.5:1, large text 3:1, and meaningful component/focus indicators 3:1 against every actual surface. Measure static light, static dark, and representative dynamic schemes. Do not lower read text with blanket alpha.
- Use the Material shape scale through component defaults. Small shapes belong on thumbnails and chips; larger shapes belong on sheets and connected controls. No inherited web 6px buttons, universal `Shape20` rows, decorative morphing polygons, or card containers around every list item.

### 6. Motion and feedback

- Set the product-level scheme to `MotionScheme.standard()` as the documented M3 adaptation for a utilitarian reader. Retain current Expressive component anatomy, state layers, connected shapes, and interaction feedback. This is a deliberate choice supported by Material's standard-scheme guidance, not a fallback to old Material You.
- Use built-in component motion for buttons, chips, search, sheets, navigation, and predictive Back. Use fast effects for state color/opacity and default spatial motion only for pane or sheet transitions.
- No bouncing article rows, wavy sync indicators, spring-driven read/star toggles, shape-morphing article containers, or auto-scroll animation after mark-read. Pull-to-refresh must not leave the list bouncing.
- Diff switching is immediate. Read/star/download state changes are immediate. Sync uses a conventional linear or contained indicator with text when duration matters.
- Honor reduced motion and animator duration scale: remove custom spatial transitions and auto-scroll, preserve focus and state, and make Back/sheet behavior equivalent. Never require animation to understand a result.

### 7. Native accessibility floor

- Compose semantics expose headings, button roles, selected/toggle states, disabled states, value text, and traversal order. Article rows announce unread, updated, starred, and download state in a deliberate order without reading decorative icons.
- TalkBack can perform every swipe and long-press action through labeled controls. Current/Changes announces selection and deleted/inserted runs. Bottom sheets return focus to their launcher and Back dismisses them before leaving the underlying scope.
- Support RTL with start/end placement, auto-mirrored directional icons, and content-direction article text. Test mixed English/Arabic or Hebrew fixtures rather than mirroring the article's script by force.
- Support light/dark, optional dynamic color, 200% font, narrow 320dp layouts, and display-size changes without horizontal page overflow. Edge-to-edge content respects status, navigation, keyboard, and display-cutout insets.
- Keyboard and D-pad focus follows visual order on large windows. Focus remains visible at 3:1 and does not rely on a ripple alone.

## Dated Material 3 Expressive conformance matrix

Sources retrieved 2026-11-16. This is the creative-level matrix. The designer must expand it to every screen/state with exact dp values, and Gate A/Wave A must attach resolved dependency output. Experimental annotations are bounded to the component that needs them; no 1.5.0 RC or 1.4 alpha is introduced to make an annotation disappear.

**Official source key**

- **M1:** [Applying M3 Expressive usability tactics](https://m3.material.io/foundations/usability/applying-m-3-expressive): clear scale/placement, consistent color roles, calm balanced layouts.
- **M2:** [Material Design 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3), updated 2026-11-10: `MaterialTheme`, color/type/shape, components, dynamic color, accessibility.
- **M3:** [Compose Material 3 releases](https://developer.android.com/jetpack/androidx/releases/compose-material3): 1.4.0 stable, 1.5.0-rc01 RC at review date; [Material 3 API summary](https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary).
- **M4:** [Adaptive navigation](https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation), updated 2026-10-22: `NavigationSuiteScaffold` and window-adaptive bar/rail.
- **M5:** [List-detail layout](https://developer.android.com/develop/ui/compose/layouts/adaptive/list-detail), updated 2026-10-28; [Compose Material 3 Adaptive releases](https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive): adaptive 1.3.0 stable, 1.4.0-alpha03 alpha at review date.
- **M6:** [Motion overview](https://m3.material.io/styles/motion/overview): standard scheme for utilitarian products, spatial/effect tokens, reduced overshoot through standard motion.
- **M7:** [Button groups](https://m3.material.io/components/button-groups/overview), [Search](https://m3.material.io/components/search/overview), and [Applying type](https://m3.material.io/styles/typography/applying-type): connected groups replace legacy segmented buttons in Expressive; contained search; body/label hierarchy.
- **M8:** [Compose accessibility](https://developer.android.com/develop/ui/compose/accessibility), updated 2026-06-13, and [Interaction states](https://m3.material.io/foundations/interaction/states/overview): semantics, scalable content, dual state indicators.

| Official source | Screen / component | Actual Compose API and version posture | FeedBucket adaptation | Verification |
|---|---|---|---|---|
| M1, M2, M3 | Global theme | `MaterialTheme`, `ColorScheme`, `Typography`, `Shapes`, `motionScheme`; `androidx.compose.material3:material3` **1.4.0 stable minimum**, resolved in Wave A | Static light/dark first, optional dynamic color, Google Sans Flex chrome, standard motion, no hardcoded feature colors | Resolved dependency report; token audit; rendered light/dark/dynamic contrast matrix |
| M1, M2, M4 | Read / Feeds / Settings navigation | `NavigationSuiteScaffold`, `NavigationBar`, `NavigationRail`; stable Material/adaptive artifact resolved by Gate A, with no RC substitution | Three destinations, compact bar and wider rail, no dashboard | 320/390/768dp geometry; Back/rotation/RTL; selected-state semantics |
| M5 | Read list-detail | `NavigableListDetailPaneScaffold`, `rememberListDetailPaneScaffoldNavigator`, `AnimatedPane`; `androidx.compose.material3.adaptive:adaptive-*` **1.3.0 stable target**, replacing 1.3.0-alpha06 | One pane compact, stable list + reader on large windows, bounded reading measure | Selected article and both positions survive resize, Back, process recreation; predictive Back where supported |
| M1, M2, M7 | Article index | `LazyColumn`, `Surface`, `Text`, `HorizontalDivider`; Compose stable BOM plus Material 3 1.4.0. `ListItem` for conventional feed/rule/settings rows | Custom flat article index for metadata + title + summary; no cards or unread fills | Long title/feed, no-image, comfortable/compact, 200% font, RTL; no overflow or clipped 48dp row target |
| M2, M3, M7 | Scoped search | `SearchBar`, `SearchBarDefaults.InputField`, `SearchBarState` where present in the resolved 1.4.0 signature; bounded `ExperimentalMaterial3Api` only if that signature requires it | Search opens within current scope; saved searches become chips; no blank home | Keyboard/IME, focus/traversal, process restore, long query, offline results, clear/Back behavior |
| M1, M2 | View and saved-search filters | `FilterChip`, `AssistChip`, `LazyRow`; Material 3 1.4.0 | Text labels, selected check/semantics, horizontal overflow without shrinking | Selected state in grayscale, 48dp hit areas, TalkBack order, 320dp and 200% font |
| M1, M3, M7 | Current / Changes and connected triage choices | `ButtonGroup` with connected toggle children; Material 3 1.4.0, bounded `ExperimentalMaterial3ExpressiveApi` if required by resolved API | Single-select Current/Changes above body; no deprecated legacy segmented-button grammar | Selection semantics, 48dp targets, focus retention, immediate pane switch, insertion/deletion announcement |
| M1, M2, M3 | Reader actions | `BottomAppBar`, `IconButton`, `IconToggleButton`, `FilledTonalIconButton`, `DropdownMenu`; Material 3 1.4.0 | Previous / Read / Star / Next / More, one tonal Next, Download and extraction in More | All actions keyboard/TalkBack reachable; 48dp measurement; insets; state labels; no icon-only ambiguity |
| M1, M2, M3 | Appearance, previews, confirmations | `ModalBottomSheet`, `BasicAlertDialog` or `AlertDialog`, `SnackbarHost`; Material 3 1.4.0 with only required opt-ins | Sheets for inspection/adjustment, dialogs only for consequential confirmation, exact-count feedback | Focus return, Back dismissal, keyboard insets, internal scroll, error persistence, reduced motion |
| M6, M3 | Motion | `MaterialTheme(motionScheme = MotionScheme.standard())`; Material 3 1.4.0 stable, built-in component spring tokens | Expressive anatomy with utilitarian motion; no list bounce or decorative morph | Animator scale 0 and normal; no custom spring specs in reading/list deltas; equivalent state and Back behavior |
| M2, M5, M8 | Edge-to-edge, predictive Back, accessibility | `WindowInsets`, `NavDisplay` predictive transitions already in Navigation3 baseline, adaptive navigator, Compose `semantics`, `Role`, `selected`, `toggleableState`, heading and traversal APIs | Insets around chrome and reader, semantic state text, 48dp minimum, content-direction article text | Manifest/target behavior verified on supported API; TalkBack/RTL/font-scale matrix; measured contrast and hit tests |

## Handoff acceptance for the designer

The UI contract and mocks must make these states visible rather than leaving them to interpretation:

1. Compact Unread list with read/unread, Updated, starred, downloaded, no-image, long-title, and mixed-direction fixtures; comfortable and compact densities.
2. Reader current and diff states, offline/download evidence, overflow open, appearance sheet open, and previous/next boundaries.
3. Search with saved searches and grammar help; rule editor with preview loading, zero, 20-plus, failure, apply result, and Undo.
4. Feeds/folders, import preview/result, download management, and Settings error/empty/loading states using the same list and evidence grammar.
5. 320, 390, and 768dp widths; light/dark and optional dynamic color; RTL; 200% font; reduced motion. Record actual target dimensions, text/UI contrast, overflow, focus return, and Back hierarchy numerically.

A visual gate passes only when the article remains primary in every state and the Material matrix is visible in component choices. A rounded stock ReadYou reskin does not pass.

## PLAN CONFLICT

None from the creative direction. The approved D4/D17 shape is buildable within the existing ReadYou navigation and Compose foundation. The separately opened technical reconciliation remains mandatory downstream; this document does not amend or pre-empt it.
