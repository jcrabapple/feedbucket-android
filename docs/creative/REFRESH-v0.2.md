# FeedBucket design refresh v0.2 — subtle polish vs stock ReadYou

Status: BUILDABLE SPEC for `coder` (docs-and-mocks deliverable; no app source touched by this card)
Date: 2026-11-20
Base: branch `feedbucket-native`, HEAD `a832b079` (verified clean tree at authoring time)
Reference mock: `docs/creative/mocks/refresh-v02.html` (self-contained, before/after × light/dark)
Measured contrast data: generated with the WCAG 2.2 relative-luminance formula; every ratio below is computed from the exact hex values in `ui/theme/palette/FeedBucketStatic.kt`, including alpha composites (src-over blend onto the stated opaque ground, rounded to 8-bit channels before measurement).

## 0. Scope and relationship to UI-CONTRACT-v1

This is a **minor, high-signal polish** of the current ReadYou-derived screens. It is not the Margin Notes rebuild: navigation (`Read` / `Feeds` / `Settings`), the `FilterBar`, gestures/swipes, toolbar icon order, the 60dp reader bottom bar, and every user preference stay exactly as they are. `UI-CONTRACT-v1.md` remains the long-term approved baseline; this refresh moves the current code toward it (no blanket alpha wash, primary-toned feed metadata, 12dp row shape, 48dp targets, tabular numerals) without reopening the overall direction.

Preserved unchanged (hard constraints):

- `FeedBucketColorSchemes` petrol/ink tokens — **no palette file edits**. Every color below is an existing role.
- Dynamic color (API 31+), legacy ReadYou palette selection, custom themes (`Theme.kt` `resolveColorScheme` precedence untouched).
- Reading fonts/sizes, title bold/uppercase/alignment, density and tonal-elevation preferences, list display toggles (feed icon/name/image/desc/date/read-indicator).
- `MotionScheme.standard()` product-wide (`Theme.kt:85`). **No new motion is introduced.** Existing `AnimatedVisibility` spring in `BottomBar.kt:46` and the TTS `AnimatedContent` transition are retained as-is.
- Swipe actions (`tertiaryContainer` background / `tertiary` icon), long-press menus, mark-above/below, share.
- `Shapes.kt` definitions are not edited; `Shape20`/`Shape32` stay available (after this refresh their only call sites are removed, but the tokens remain for compatibility).

Decision ownership: all choices below are settled here. `coder` implements against the tables; deviations need a comment on the card, not a silent reinterpretation.

---

## 1. Article list — `ui/page/home/flow/ArticleItem.kt` (+ `ArticleList.kt`)

### 1.1 Read-state rendering: replace the alpha wash

Current: the whole row (text **and** thumbnail) gets `.alpha(0.5f)` when read (`ArticleItem.kt:143–155`, preference `AllRead` / `ExcludingStarred`; default is `ExcludingStarred`, `FlowArticleReadIndicatorPreference.kt:44`). Measured result: read titles fail WCAG AA in **both** schemes (3.29:1 light, 4.34:1 dark — see §9 B1/B2).

After: delete the `.alpha(...)` modifier entirely. Hierarchy comes from weight + color role only. Thumbnails are never dimmed.

Truth table (drives every conditional below):

| `articleListReadIndicator` | article state | `emphasized` | dot visible |
|---|---|---|---|
| `None` | any | `true` | no (slot absent) |
| `AllRead` | unread | `true` | yes |
| `AllRead` | read | `false` | no |
| `ExcludingStarred` | unread | `true` | yes |
| `ExcludingStarred` | read + starred | `true` | no |
| `ExcludingStarred` | read, not starred | `false` | no |

```kotlin
val emphasized = when (articleListReadIndicator) {
    FlowArticleReadIndicatorPreference.None -> true
    FlowArticleReadIndicatorPreference.AllRead -> isUnread
    FlowArticleReadIndicatorPreference.ExcludingStarred -> isUnread || isStarred
}
val showDot = articleListReadIndicator != FlowArticleReadIndicatorPreference.None && isUnread
```

The dot strictly means *unread*; a starred-read row under `ExcludingStarred` gets unread-level typography but no dot (the filled star already carries that state).

### 1.2 Before/after token table — ArticleItem.kt

| Element | Before (file:line) | After |
|---|---|---|
| Row container shape | `.clip(Shape20)` = 20dp (`:140`) | `.clip(MaterialTheme.shapes.medium)` = **12dp** |
| Row read dimming | `.alpha(0.5f)` on whole row (`:143–155`) | **removed**; weight + color per §1.1 |
| Row paddings | outer h12, inner h12/v12 (`:139,142`) | unchanged |
| Title color | `onSurface` always (`:234`) | `if (emphasized) onSurface else onSurfaceVariant` |
| Title weight | `titleMedium` default 500 (`:236`) | `titleMedium.merge(fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal)` (600 / 400) |
| Title line height / clamp | `lineHeight = 22.sp`, maxLines 2 (desc shown) / 4 (`:238–240`) | unchanged |
| `feedName` color | `tertiary` (`:173`) | `if (emphasized) primary else onSurfaceVariant` |
| `feedName` style | `labelMedium`, maxLines 1 ellipsis (`:174–176`) | unchanged |
| `feedName` start padding | `30.dp` if feed icon shown else `0.dp` (`:169`) | `(if (articleListFeedIcon.value) 34.dp else 0.dp) + (if (articleListReadIndicator != None) 16.dp else 0.dp)` — keeps alignment with the title edge after the 24dp favicon frame (§7) and the reserved dot slot (§1.3) |
| Hidden-feedName spacer | `Spacer(width 30/0)` (`:197`) | same formula as `feedName` start padding |
| Unread dot | **does not exist** | new 8dp `primary` dot, §1.3 |
| Time / description | `onSurfaceVariant`, `labelMedium` / `bodySmall` (`:190,261`) | unchanged |
| Starred icon | `onSurface @0.7f` (`:303`) | unchanged (measured 6.25 / 7.45, passes) |
| Thumbnail | 80dp, `.clip(Shape20)`, full alpha only when unread (`:280`) | 80dp, `.clip(MaterialTheme.shapes.small)` = **8dp**, **always full alpha** |
| Swipe background/icons | `tertiaryContainer` / `tertiary` (`:409,446,470`) | unchanged |

### 1.3 Unread dot placement (jitter-free, alignment-preserving)

- When `articleListReadIndicator != None`, the **title row** (`:231`) gains a fixed leading slot on every row: `Box(Modifier.size(8.dp).clip(CircleShape).background(if (showDot) primary else Color.Transparent))` + `Spacer(Modifier.width(8.dp))`, before the title `Text`. The slot is reserved on read rows too, so read/unread transitions never shift layout.
- When the preference is `None`, no slot and no padding compensation — zero visual change for users who opted out of read indicators.
- The `feedName` start-padding compensation in §1.2 keeps `feedName` and title on the same vertical edge in every configuration (with/without favicon × indicator on/off).
- Semantics: the dot is decorative (`Modifier.clearAndSetSemantics {}`); the row exposes `stateDescription` = "Unread" / "Read" (reuse `R.string.unread`; add a `read_state` string resource if no suitable "Read" string exists). Weight 600 + color remain the redundant visual channels.

### 1.4 Hairline dividers — `ui/page/home/flow/ArticleList.kt`

After each `SwipeableArticleItem` **whose following item is also an article**, emit:

```kotlin
HorizontalDivider(
    modifier = Modifier.padding(horizontal = 24.dp),
    thickness = 0.5.dp,
    color = MaterialTheme.colorScheme.outlineVariant,
)
```

- Both branches: the `items(...)` branch (~`:36–66`) and the sticky-header `for` loop (~`:67–105`). Next-item check: `pagingItems.peek(index + 1) is ArticleFlowItem.Article` (peek returns null past the end → no trailing divider).
- No divider before a `ArticleFlowItem.Date` sticky header or the trailing spacer; the check handles both.
- The divider lives **outside** `SwipeableArticleItem`'s swipe box, so it stays put while a row is swiped.
- 24dp inset aligns with the row content edge (outer 12 + inner 12). Decorative separator: exempt from the 3:1 UI-boundary rule (rows are identified by spacing and typography); measured 1.66 / 1.98 for the record.

---

## 2. Flow top bar — `ui/page/home/flow/FlowPage.kt`

| Element | Before (file:line) | After |
|---|---|---|
| `expandedHeight` | `172.dp` (`:431`) | **`140.dp`** (within the approved 136–144 band; collapsed 64dp + 76dp title zone) |
| Expanded title role | `headlineMedium = displaySmall` override → 36/44sp (`:381`) | **remove the override**; expanded title uses `headlineMedium` 28/36sp |
| Collapsed title | `titleLarge.merge(fontSize = 18.sp, fontWeight = Medium)` (`:382–386`) | unchanged |
| Auto-size floor | `minFontSize = 28.sp` (`:419`) | **`22.sp`** (max stays `textStyle.fontSize` = 28sp; `StepBased`, maxLines 2, ellipsis — mechanism preserved) |
| Title padding | start 34/8dp, end 24dp (`:408–411`) | unchanged |
| Scroll behavior | `exitUntilCollapsedScrollBehavior` (`:276`) | unchanged |
| Icon order | Back → DoneAll → Search (`:433–505`) | unchanged |
| Active action tint | `primary` when `markAsRead` / `onSearch` (`:455,481`) | unchanged |
| Scrolled container | `surfaceContainer` if elevated else `surface` (`:179–180`) | unchanged |

Geometry check: 2 × 36sp lines = 72dp ≤ 76dp title zone at 100% font. At 200% font the title ellipsizes to one line inside the fixed zone (56sp glyph, 72dp line box); the full scope name remains in semantics. No overlap with the action row is possible because `LargeTopAppBar` reserves the collapsed 64dp row independently of `expandedHeight`.

---

## 3. Reader header — `ui/page/home/reading/Metadata.kt`

Current order: date → title → author → feedName, all metadata in `outline.copy(alpha = .7f)` (`:47`) — measured **2.58:1 light / 3.47:1 dark, fails AA** (§9 B6). The refresh groups source + date into an eyebrow above the title and moves every metadata label to full-opacity roles.

New structure (inside the existing `Column(Modifier.fillMaxWidth().padding(12.dp))`; the `Content.kt:96–105` headline wrapper and its `roundClick` are untouched):

1. **Eyebrow** — `FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically)` (stable `androidx.compose.foundation.layout.FlowRow` on the pinned foundation train), alignment mapped from `titleAlign` (Start/Center/End):
   - `Text(feedName, color = primary, style = labelMedium.merge(fontFamily = fontFamily), maxLines = 1, overflow = Ellipsis)` — unweighted: it ellipsizes only when the name alone exceeds the line.
   - `Row { Text(" · ", color = onSurfaceVariant, style = labelMedium); Text(dateString, color = onSurfaceVariant, style = labelMedium.merge(fontFamily = fontFamily), maxLines = 1) }` — the separator travels with the date as one nowrap unit. When name + date do not fit (320dp at 200% font), the date group **wraps to a second line** instead of crushing the feed name to zero width (UI-CONTRACT-v1 §3.3: metadata wraps at large font).
   - `dateString` keeps `formatAsString(context, atHourMinute = true)` (`:41–42`).
2. `Spacer(Modifier.height(8.dp))`
3. **Title** — `headlineLarge.merge(fontFamily = fontFamily, fontWeight = if (titleBold.value) Bold else Medium, lineHeight = 38.sp)` (line height refined 40 → **38sp**; size stays 32sp), `color = onSurface`, `textAlign = titleAlign`, uppercase transform per `titleUpperCase`, `applyTextDirection(title.requiresBidi())` — all existing preference behavior preserved. Add `Modifier.semantics { heading() }`.
4. **Author** (only when non-null and non-empty): `Spacer(6.dp)` + `Text(author, color = onSurfaceVariant, style = labelMedium.merge(fontFamily), textAlign = titleAlign, maxLines = 2, overflow = Ellipsis)`. No reserved height when absent.
5. `Spacer(Modifier.height(12.dp))`
6. **Accent rule** — `Box(Modifier.fillMaxWidth(), contentAlignment = alignment mapped from titleAlign) { Box(Modifier.height(2.dp).width(32.dp).clip(RoundedCornerShape(1.dp)).background(primary)) }`

Removed: `labelColor` (`:47`), the bottom `feedName` text (`:88–94`), the old date-first ordering. The rule is the header/body separator; `DiffUpdateToggle` (when present) and the article body follow it exactly as they follow Metadata today.

Why a 32×2dp primary rule instead of a full-width divider: it is the one place the reader carries the petrol accent as a brand gesture, it survives center/right title alignment, and it costs zero horizontal space at 320dp. Measured 7.61 / 10.85 against `surface` (§9 A10).

---

## 4. Reader bottom bar — `BottomBar.kt` + `tts/TtsButton.kt` + `base/CanBeDisabledIconButton.kt`

The bar itself is untouched: 60dp row, `SpaceAround`, `navigationBarsPadding`, `surfaceContainer` ground (or `surface` + 0.5dp `surfaceContainerHighest` divider in `Outlined` mode), six 48dp targets in the existing order **Unread · Starred · Next · TTS · AI Summary · Full Content** (`BottomBar.kt:99–190`). 6 × 48 = 288dp fits 320dp.

### 4.1 Active-state pill

`CanBeDisabledIconButton` gains one optional parameter:

```kotlin
active: Boolean = false
```

When `active && !disabled`, the icon is centered inside `Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer))` and the effective content color becomes `onPrimaryContainer` (overriding the passed `tint`); otherwise behavior is byte-for-byte identical (including `disabledContentColor = outline`). The `IconButton` touch target stays 48dp; the 36dp circle is visual-only.

| Button | `active` condition | Active icon tint | Inactive tint |
|---|---|---|---|
| Unread (`:107–124`) | `isUnread` | `onPrimaryContainer` (filled dot) | `outline` (outlined dot) — unchanged |
| Starred (`:125–142`) | `isStarred` | `onPrimaryContainer` (filled star) | `outline` — unchanged |
| Next (`:143–152`) | never | — | `outline` — unchanged |
| AI Summary (`:157–166`) | never | — | `outline` — unchanged |
| Full Content (`:172–189`) | `isFullContent && !extractFailed` | `onPrimaryContainer` (filled article) | `outline`; `extractFailed` keeps the transient `error` tint **without** pill (E13 semantics preserved) |

Before, active states were tint-only (`onSecondaryContainer`, no container) — visually quiet against the bar. The pill makes toggled state readable at a glance. The pill ground vs bar ground is deliberately low-contrast (1.22–2.27, supplementary): state is redundantly encoded by the filled-vs-outlined icon swap, the tint change, and TalkBack toggle semantics, so the container is not the required state identifier (WCAG 1.4.11). Icon-on-pill contrast is 10.43 / 5.89 (§9 A16). No new animation: the state flip is immediate, exactly like today's tint flip.

### 4.2 TTS button — `tts/TtsButton.kt`

| Element | Before (file:line) | After |
|---|---|---|
| Touch target (both states) | `Box.size(40.dp)` (`:65,89`) — below the 48dp floor | **`48.dp`** |
| Reading-state highlight | none | **36dp `primaryContainer` circle** behind ring + stop (same grammar as §4.1) |
| Stop icon tint | `tertiary` (`:81`) | `onPrimaryContainer` |
| Progress ring | 24dp, stroke 3dp, `trackColor = outlineVariant`, default indicator color (`:130–137`) | unchanged geometry; pin `color = MaterialTheme.colorScheme.primary` explicitly |
| Idle icon | `Headphones` outlined, `outline` tint (`:110–114`) | unchanged (now inside 48dp target) |
| Long-press → TTS settings | `:94–107` | unchanged |
| Haptics / `AnimatedContent` | `:52–59,68,92` | unchanged |

`Preparing` renders the idle branch (no pill — playback has not started); `Error` keeps the existing toast path. Ring-on-pill 5.65 / 4.78, stop-on-pill 10.43 / 5.89 (§9 A18/A19).

---

## 5. Feeds screen — `GroupItem.kt`, `FeedItem.kt`

### 5.1 Group container and header — `GroupItem.kt`

| Element | Before (file:line) | After |
|---|---|---|
| `GroupWithFeedsContainer` shape | `.clip(Shape32)` = 32dp (`:121`) | **`RoundedCornerShape(16.dp)`** (= `shapes.large`) |
| Container ground | `surfaceContainerLow` (`:122`) | unchanged |
| Container border | none | **`.border(0.5.dp, outlineVariant, RoundedCornerShape(16.dp))`** after `.background(...)` |
| Container paddings | top 16, horizontal 16 (`:119–120`) | unchanged |
| `GroupItem` top padding | `22.dp` (`:60`) | **`16.dp`** |
| `GroupItem` bottom spacer | `22.dp` (`:111`) | **`16.dp`** |
| Group name | `titleMedium`, `onSecondaryContainer`, h28 padding (`:67–76`) | unchanged (28dp still aligns with feed-name edge: FeedItem 14 + 14) |
| More button | 48dp, `onSecondaryContainer` (`:84–94`) | unchanged |
| Expand/collapse visual | 24dp circle, `surfaceContainerHigh`, `onSecondaryContainer` icon (`:95–108`) | **visual unchanged** |
| Expand/collapse tap target | the same 24dp Box (below floor) | **48dp wrapper**: `Box(Modifier.size(48.dp).clip(CircleShape).clickable { onExpanded() }, contentAlignment = Center) { /* existing 24dp circle + icon */ }` — ripple bounded to the 48dp circle; `contentDescription` unchanged |

### 5.2 Feed rows — `FeedItem.kt`

| Element | Before (file:line) | After |
|---|---|---|
| Unread badge | `Badge(containerColor = surfaceContainerHigh, contentColor = outline)` + `labelSmall` (`:96–105`) — measured **3.79 / 4.45, fails AA** (§9 B7) | **`Surface(shape = CircleShape, color = secondaryContainer, contentColor = onSecondaryContainer)`** wrapping `Text(feed.important.toString(), style = labelSmall.merge(fontFeatureSettings = "tnum"), textAlign = Center, maxLines = 1, modifier = Modifier.defaultMinSize(minWidth = 20.dp, minHeight = 20.dp).padding(horizontal = 8.dp, vertical = 2.dp))` |
| Badge numerals | proportional | **tabular** (`"tnum"`; `fontFeatureSettings` is API 26+, minSdk is 26 — safe) |
| Favicon | `FeedIcon` 20dp circle, no ground | framed per §7 (24dp outer) |
| Feed name | `labelLarge` lh20 trim-both, `onSurfaceVariant` (`:80–93`) | unchanged |
| Row paddings | 14dp; last item bottom 22 (`:39–41`), inner start 14 / end 6 (`:72`) | unchanged |
| More button | 48dp, `onSurfaceVariant` (`:109–119`) | unchanged |

Badge text contrast becomes 10.34 / 6.63 (§9 A11). The pill ground vs container is supplementary (1.22 / 1.94): the count text is the required information and passes on its own. Optional a11y nicety (not blocking): `semantics { contentDescription = "$count unread" }` using a localized string if the coder adds one.

### 5.3 FeedsBanner — `FeedsBanner.kt` + `base/Banner.kt`

Problem: `Banner` defaults to `primaryFixed` (`Banner.kt:44`), which is `#B8E4E2` in **both** schemes. A `primary` icon tint on that fixed light ground measures **1.23:1 in dark** — rejected. The accent must sit on a scheme-adaptive container.

`Banner.kt` (shared with 4 settings pages + AccountDetailsPage — **defaults must not change**): add one optional parameter to the slot overload (`:73–82`):

```kotlin
descColor: Color = contentColor.copy(alpha = .7f)
```

and use it for the desc `Text` color (`:122`). Every existing caller keeps today's rendering.

`FeedsBanner.kt` switches to the slot overload:

| Param | Value |
|---|---|
| `backgroundColor` | `MaterialTheme.colorScheme.primaryContainer` |
| `contentColor` | `MaterialTheme.colorScheme.onPrimaryContainer` |
| `descColor` | `MaterialTheme.colorScheme.onPrimaryContainer` (**full opacity** — the 70% blend fails dark at 3.80, §9 A23; full opacity measures 10.43 / 5.89, A23f) |
| `icon` slot | `Icon(filter.iconOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary)` + `Spacer(Modifier.width(16.dp))` |
| `action` slot | unchanged `KeyboardArrowRight` (inherits `onPrimaryContainer`) |
| shape / height / paddings | 32dp radius, min 88dp, h16/v20 — **unchanged** (out of scope) |

Result: light banner is visually near-identical (`primaryContainer` = `primaryFixed` = `#B8E4E2` in light); dark banner becomes a proper dark petrol container instead of a floating light block. Icon 5.65 / 4.78, title 10.43 / 5.89 (§9 A21/A22). `FeedsPage.kt` itself needs **no edits** (DisplayText, "Feeds" label, unfold button, group wiring all unchanged).

---

## 6. Top bar / reader chrome not in scope

`FlowPage` search bar, mark-as-read bar, saved-search chips, `FilterBar` (icon-only 64dp default, `primaryContainer` indicator light / `secondaryContainer` dark), `FeedsPage` top bar, `DisplayText`, navigation suite: untouched. Existing sub-48dp targets outside the approved scope (FeedsPage unfold button 28dp, `TtsButton` was 40dp — the latter **is** fixed in §4.2) are recorded as follow-up candidates, not changed here except where the card explicitly asks.

---

## 7. Favicon frame — `ui/component/FeedIcon.kt` (shared)

Current: image/base64 favicons are clipped to `CircleShape` with **no ground** (`:41–58`); transparent icons with dark content vanish on dark surfaces. `FontIcon` fallback is a `primary` circle with a 10sp bold `onPrimary` letter (`:71–88`).

After:

- **Image + base64 paths**: wrap in `Box(Modifier.size(size + 4.dp).clip(RoundedCornerShape(6.dp)).background(Color.White).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)), contentAlignment = Center)`; the image itself becomes `Modifier.size(size).clip(RoundedCornerShape(4.dp))` (was `CircleShape`). `contentDescription = feedName` preserved.
- **`FontIcon` fallback**: outer `size + 4.dp` for uniform geometry; inner `primary` box `size`, shape `RoundedCornerShape(6.dp)` (was circle); letter style unchanged.
- **`placeholderIcon` path** (`ImageIcon`): unchanged (tinted vector, no frame needed).
- Default `size = 20.dp` stays the *content* size → **24dp outer**.

Why a constant white ground in both schemes: favicons are third-party content overwhelmingly designed for light grounds. A scheme-following dark frame would keep dark-content transparent icons invisible in dark mode — the exact defect being fixed. White is already a palette surface token (`surfaceContainerLowest` light, `onPrimary`); using it as a content ground is the same principle as article thumbnails rendering arbitrary imagery. The frame is decorative (feed name text adjacent carries identity): in dark the white ground is an 18.43:1 boundary; in light it is 1.02:1 against `surface` and the `outlineVariant` hairline defines the edge (1.70:1, decorative exemption). Documented, deliberate, and the only choice that fixes the dark-mode legibility case.

Caller impact (all inherit the frame; +4dp outer in row/list contexts, no fixed-width clipping risk): `ArticleItem.kt`, `FeedItem.kt`, `FeedOptionDrawer.kt`, `SubscribeDialog.kt`, `SubscribeCandidateList.kt`, `FeedDetailPage.kt`, `DiscoverFeedsPage.kt`. `ArticleItem` padding compensation is specified in §1.2; `FeedItem` rows grow from 48dp to ~52dp minimum (still ≥48dp).

---

## 8. Implementation notes for `coder`

1. **Files touched**: `flow/ArticleItem.kt`, `flow/ArticleList.kt`, `flow/FlowPage.kt`, `reading/Metadata.kt`, `reading/BottomBar.kt`, `reading/tts/TtsButton.kt`, `component/base/CanBeDisabledIconButton.kt`, `component/base/Banner.kt`, `component/FeedIcon.kt`, `feeds/GroupItem.kt`, `feeds/FeedItem.kt`, `feeds/FeedsBanner.kt`. Nothing else. No palette, `Shapes.kt`, `Type.kt`, preference, navigation, or ViewModel edits.
2. Modifier order matters: clip → background/border → clickable → padding, matching the existing chains. The expand wrapper's `clickable` goes on the 48dp Box (ripple clip), not the 24dp visual.
3. `fontFeatureSettings = "tnum"` is a `TextStyle` property (API 26+; minSdk 26 verified in `app/build.gradle.kts:44`).
4. Semantics additions are limited to: row `stateDescription`, dot `clearAndSetSemantics {}`, Metadata title `heading()`. Existing `contentDescription`s, toggle semantics and haptics are preserved.
5. No new dependencies, no new preferences, no new strings except the optional `read_state` (§1.3) and badge description (§5.2).
6. Motion: nothing new. Do not add springs/tweens for the pill or dot; state changes are immediate like the current tint flips. `MotionScheme.standard()` remains product-wide.
7. Previews: update `FeedItem.kt` `Preview` and add light/dark `ArticleItem` previews (unread/read/starred-read) so the truth table is inspectable in Studio.
8. Gate: `export ANDROID_HOME=/home/jason/Android/Sdk && bash scripts/gate.sh refresh-v02` with the usual memory guards (`--no-daemon --max-workers=1 -Dorg.gradle.parallel=false`, `./gradlew --stop` after). Instrumented checks: 48dp bounds on the expand wrapper and TTS button; divider absent before date headers; pill present only in the four active states.

---

## 9. Measured WCAG 2.2 contrast (static schemes)

Method: sRGB relative luminance per WCAG 2.2; `(Lmax + 0.05) / (Lmin + 0.05)`. Alpha composites blended src-over onto the stated ground and rounded to 8-bit before measurement. Thresholds: **text ≥ 4.5:1** (all sizes here are < 18sp regular / < 14sp bold, so the large-text 3:1 relaxation is never claimed), **meaningful UI/icons ≥ 3:1**. Decorative/supplementary pairs are reported with no threshold (exempt under 1.4.11; the required information they accompany passes on its own).

### 9.1 Baseline (current code) — the defects this refresh removes

B1/B2 (alpha wash), B6 (outline @70% metadata) and B7 (badge) fail AA in both static schemes. Everything else passes today and is either preserved or strengthened.

| ID | Pair | Light | Dark | Min | Status |
|---|---|---:|---:|---:|---|
| B1 | Read row title under 0.5 alpha wash — #171D1C @50% → `#888D8C` on `#FAFDFC` | 3.29 ❌ | 4.34 ❌ | 4.5 | FAIL |
| B2 | Read row feedName (tertiary) under 0.5 wash — #4B5F7D @50% → `#A2AEBC` on `#FAFDFC` | 2.20 ❌ | 3.50 ❌ | 4.5 | FAIL |
| B3 | Unread feedName tertiary — `#4B5F7D` on `#FAFDFC` | 6.35 ✅ | 10.53 ✅ | 4.5 | PASS |
| B4 | Time/description onSurfaceVariant — `#3F4947` on `#FAFDFC` | 9.10 ✅ | 10.85 ✅ | 4.5 | PASS |
| B5 | Starred icon onSurface @70% — #171D1C @70% → `#5B605F` on `#FAFDFC` | 6.25 ✅ | 7.45 ✅ | 3.0 | PASS |
| B6 | Reader metadata label outline @70% — #6F7977 @70% → `#99A19F` on `#FAFDFC` | 2.58 ❌ | 3.47 ❌ | 4.5 | FAIL |
| B7 | Feed unread badge outline on surfaceContainerHigh — `#6F7977` on `#E8EDEB` | 3.79 ❌ | 4.45 ❌ | 4.5 | FAIL |
| B8 | Group expand icon on surfaceContainerHigh circle — `#123331` on `#E8EDEB` | 11.50 ✅ | 10.69 ✅ | 3.0 | PASS |
| B9 | Group name on surfaceContainerLow — `#123331` on `#F4F7F6` | 12.62 ✅ | 12.85 ✅ | 4.5 | PASS |
| B10 | Feed name on surfaceContainerLow — `#3F4947` on `#F4F7F6` | 8.64 ✅ | 9.96 ✅ | 4.5 | PASS |
| B11 | BottomBar active icon onSecondaryContainer on surfaceContainer — `#123331` on `#EEF2F1` | 12.05 ✅ | 12.28 ✅ | 3.0 | PASS |
| B11o | BottomBar active icon, Outlined bar (surface) — `#123331` on `#FAFDFC` | 13.29 ✅ | 14.00 ✅ | 3.0 | PASS |
| B12 | BottomBar inactive icon outline on surfaceContainer — `#6F7977` on `#EEF2F1` | 3.98 ✅ | 5.12 ✅ | 3.0 | PASS |
| B12o | BottomBar inactive icon outline, Outlined bar — `#6F7977` on `#FAFDFC` | 4.39 ✅ | 5.83 ✅ | 3.0 | PASS |
| B13 | TTS stop icon tertiary on surfaceContainer — `#4B5F7D` on `#EEF2F1` | 5.76 ✅ | 9.24 ✅ | 3.0 | PASS |
| B14 | Banner title/icon onPrimaryFixed on primaryFixed — `#062F32` on `#B8E4E2` | 10.43 ✅ | 10.43 ✅ | 4.5 | PASS |
| B15 | Banner desc onPrimaryFixed @70% on primaryFixed — #062F32 @70% → `#3B6567` on `#B8E4E2` | 4.70 ✅ | 4.70 ✅ | 4.5 | PASS |

### 9.2 After — text pairs (≥ 4.5:1)

All pass in both schemes. A23f is the full-opacity banner desc that replaces the rejected 70% variant (see 9.5).

| ID | Pair | Light | Dark | Min | Status |
|---|---|---:|---:|---:|---|
| A1 | Unread title onSurface (weight 600) — `#171D1C` on `#FAFDFC` | 16.70 ✅ | 14.28 ✅ | 4.5 | PASS |
| A2 | Read title onSurfaceVariant (weight 400) — `#3F4947` on `#FAFDFC` | 9.10 ✅ | 10.85 ✅ | 4.5 | PASS |
| A3 | Unread feedName primary — `#145B60` on `#FAFDFC` | 7.61 ✅ | 10.85 ✅ | 4.5 | PASS |
| A4 | Read feedName onSurfaceVariant — `#3F4947` on `#FAFDFC` | 9.10 ✅ | 10.85 ✅ | 4.5 | PASS |
| A7 | Reader eyebrow feedName primary — `#145B60` on `#FAFDFC` | 7.61 ✅ | 10.85 ✅ | 4.5 | PASS |
| A8 | Reader eyebrow date/separator onSurfaceVariant — `#3F4947` on `#FAFDFC` | 9.10 ✅ | 10.85 ✅ | 4.5 | PASS |
| A9 | Reader author onSurfaceVariant — `#3F4947` on `#FAFDFC` | 9.10 ✅ | 10.85 ✅ | 4.5 | PASS |
| A11 | Badge pill onSecondaryContainer on secondaryContainer — `#123331` on `#CBE6E2` | 10.34 ✅ | 6.63 ✅ | 4.5 | PASS |
| A21 | Banner title onPrimaryContainer on primaryContainer — `#062F32` on `#B8E4E2` | 10.43 ✅ | 5.89 ✅ | 4.5 | PASS |
| A23f | Banner desc FULL opacity onPrimaryContainer on primaryContainer — `#062F32` on `#B8E4E2` | 10.43 ✅ | 5.89 ✅ | 4.5 | PASS |
| A26 | Collapsed flow title onSurface on surfaceContainer (elevated) — `#171D1C` on `#EEF2F1` | 15.14 ✅ | 12.52 ✅ | 4.5 | PASS |
| A27 | FontIcon fallback letter onPrimary on primary (unchanged) — `#FFFFFF` on `#145B60` | 7.79 ✅ | 7.80 ✅ | 4.5 | PASS |

### 9.3 After — meaningful UI / icon pairs (≥ 3:1)

A13 light is a decorative content ground (exempt, edge defined by the A14 hairline); dark passes as a boundary. A25/A28 are unchanged pairs re-verified.

| ID | Pair | Light | Dark | Min | Status |
|---|---|---:|---:|---:|---|
| A5 | Unread dot primary — `#145B60` on `#FAFDFC` | 7.61 ✅ | 10.85 ✅ | 3.0 | PASS |
| A10 | Reader accent rule primary — `#145B60` on `#FAFDFC` | 7.61 ✅ | 10.85 ✅ | 3.0 | PASS |
| A13 | Favicon white frame vs surface (boundary) — `#FFFFFF` on `#FAFDFC` | 1.02 ✅ | 18.43 ✅ | exempt | reported |
| A16 | BottomBar active pill icon onPrimaryContainer on primaryContainer — `#062F32` on `#B8E4E2` | 10.43 ✅ | 5.89 ✅ | 3.0 | PASS |
| A18 | TTS progress ring primary on primaryContainer — `#145B60` on `#B8E4E2` | 5.65 ✅ | 4.78 ✅ | 3.0 | PASS |
| A19 | TTS stop icon onPrimaryContainer on primaryContainer — `#062F32` on `#B8E4E2` | 10.43 ✅ | 5.89 ✅ | 3.0 | PASS |
| A22 | Banner icon primary on primaryContainer — `#145B60` on `#B8E4E2` | 5.65 ✅ | 4.78 ✅ | 3.0 | PASS |
| A25 | Extract-failed error tint on surfaceContainer (unchanged) — `#BA1A1A` on `#EEF2F1` | 5.72 ✅ | 9.52 ✅ | 3.0 | PASS |
| A28 | Group expand icon onSecondaryContainer on surfaceContainerHigh (unchanged) — `#123331` on `#E8EDEB` | 11.50 ✅ | 10.69 ✅ | 3.0 | PASS |

### 9.4 Decorative / supplementary boundaries (reported, exempt from 1.4.11)

These grounds accompany required information that passes on its own (count text, filled-vs-outlined icon swap, row spacing). No state depends on them alone.

| ID | Pair | Light | Dark | Min | Status |
|---|---|---:|---:|---:|---|
| A6 | Hairline divider outlineVariant (decorative) — `#BEC9C6` on `#FAFDFC` | 1.66 ✅ | 1.98 ✅ | exempt | reported |
| A12 | Badge pill ground vs container (supplementary) — `#CBE6E2` on `#F4F7F6` | 1.22 ✅ | 1.94 ✅ | exempt | reported |
| A14 | Favicon frame border outlineVariant on white (decorative) — `#BEC9C6` on `#FFFFFF` | 1.70 ✅ | 9.31 ✅ | exempt | reported |
| A15 | Group border outlineVariant vs surfaceContainerLow (decorative) — `#BEC9C6` on `#F4F7F6` | 1.58 ✅ | 1.82 ✅ | exempt | reported |
| A17 | Active pill ground vs surfaceContainer (supplementary) — `#B8E4E2` on `#EEF2F1` | 1.22 ✅ | 1.99 ✅ | exempt | reported |
| A17o | Active pill ground vs surface, Outlined bar (supplementary) — `#B8E4E2` on `#FAFDFC` | 1.35 ✅ | 2.27 ✅ | exempt | reported |
| A20 | TTS ring track outlineVariant on primaryContainer (decorative) — `#BEC9C6` on `#B8E4E2` | 1.23 ✅ | 1.15 ✅ | exempt | reported |
| A24 | Banner ground primaryContainer vs surface (supplementary) — `#B8E4E2` on `#FAFDFC` | 1.35 ✅ | 2.27 ✅ | exempt | reported |

### 9.5 Rejected variant — banner desc at 70% alpha

Kept for the record: `onPrimaryContainer @70%` on `primaryContainer` fails in dark (3.80); the dynamic dark sample fails too (D7 = 4.44). §5.3 therefore specifies full-opacity `descColor` (A23f / D7f).

| ID | Pair | Light | Dark | Min | Status |
|---|---|---:|---:|---:|---|
| A23 | Banner desc onPrimaryContainer @70% on primaryContainer — #062F32 @70% → `#3B6567` on `#B8E4E2` | 4.70 ✅ | 3.80 ❌ | 4.5 | FAIL |

### 9.6 Light tonal-elevation stress (article list at Level3, ground `surfaceContainerHigh`)

Dark list ground is always `surface` (existing `onDark` branch), so no dark stress case exists.

| ID | Pair | Light | Dark | Min | Status |
|---|---|---:|---:|---:|---|
| A2s | Read title, light tonal Level3 ground — `#3F4947` on `#E8EDEB` | 7.87 ✅ | — | 4.5 | PASS |
| A3s | Unread feedName primary, light tonal Level3 ground — `#145B60` on `#E8EDEB` | 6.58 ✅ | — | 4.5 | PASS |
| A5s | Unread dot, light tonal Level3 ground — `#145B60` on `#E8EDEB` | 6.58 ✅ | — | 3.0 | PASS |

### 9.7 Representative dynamic samples (QA only)

D7 is superseded by D7f (full opacity). Production wallpaper schemes still need on-device verification per UI-CONTRACT-v1 §5.1.

| ID | Pair | DynLight | DynDark | Min | Status |
|---|---|---:|---:|---:|---|
| D1 | Unread feedName primary — `#6750A4` on `#FFFBFE` | 6.28 ✅ | 10.91 ✅ | 4.5 | PASS |
| D2 | Read title/feedName onSurfaceVariant — `#49454F` on `#FFFBFE` | 9.11 ✅ | 10.91 ✅ | 4.5 | PASS |
| D3 | Badge pill text — `#1D192B` on `#E8DEF8` | 13.24 ✅ | 7.19 ✅ | 4.5 | PASS |
| D4 | Active pill icon — `#21005D` on `#EADDFF` | 13.32 ✅ | 7.23 ✅ | 3.0 | PASS |
| D5 | Banner icon primary on primaryContainer — `#6750A4` on `#EADDFF` | 5.00 ✅ | 5.47 ✅ | 3.0 | PASS |
| D6 | Banner title — `#21005D` on `#EADDFF` | 13.32 ✅ | 7.23 ✅ | 4.5 | PASS |
| D7f | Banner desc FULL opacity (dynamic light sample) — `#21005D` on `#EADDFF` | 13.32 ✅ | 7.23 ✅ | 4.5 | PASS |
| D8 | Unread dot — `#6750A4` on `#FFFBFE` | 6.28 ✅ | 10.91 ✅ | 3.0 | PASS |


Dynamic-color note: the sample columns above are the representative Material dynamic schemes from `FeedBucketColorSchemes.DynamicSample*` (numerical QA only, per UI-CONTRACT-v1 §5.1). Production dynamic roles come from the platform; the refresh uses only role pairs whose tonal relationship M3 guarantees (onXContainer on XContainer, primary on surface), but resolved-wallpaper verification on device remains an implementation gate, as in v1.

---

## 10. Edge-case checklist

| Case | Behavior after refresh |
|---|---|
| **200% font scale** | All sp text scales; dp geometry (48dp targets, 36dp pill, 24dp expand visual, 8dp dot, 60dp bar, 140dp expanded bar) does not shrink. Article rows grow vertically; title clamps unchanged; eyebrow wraps the date group to a second line when it does not fit (feed name ellipsizes only when alone on the line); Metadata title wraps (38sp line height scales); flow expanded title ellipsizes to one line inside the fixed 76dp zone, full name in semantics; FilterBar/bottom bar heights fixed, content scrolls beneath with existing bottom padding. |
| **320dp narrow screen** | Bottom bar 6×48 = 288dp fits; top bar Back+DoneAll+Search = 144dp + title ellipsis; group name/feed name ellipsize at `weight(1f)`; badge pill grows with digits, never clips the row (row is `SpaceBetween` with weighted name); no horizontal document scroll introduced. |
| **Missing thumbnail** | Title start edge unchanged (image branch simply absent); dot slot uniform across rows. |
| **Missing author** | No spacer/height reserved in Metadata; eyebrow → title → rule directly. |
| **Missing feed icon** | `feedName` start padding drops the 34dp component; dot slot still uniform; `FontIcon` fallback (letter tile) framed identically. |
| **Long titles** | List: 2-line clamp (desc shown) / 4-line (no desc), ellipsis; reader: full wrap; eyebrow: name ellipsizes at line width, date group wraps below when needed — nothing pushes off-screen. |
| **Long feed names** | List `feedName` maxLines 1 ellipsis; group name maxLines 1; reader eyebrow ellipsizes only when the name alone exceeds the line — otherwise the date group wraps below it. |
| **Dynamic color enabled** | All new colors are role-based; sample-scheme QA in §9 passes; no hardcoded color except the favicon content ground (§7, scheme-independent by design). |
| **Dark-mode favicons** | White 6dp frame keeps dark-content transparent icons legible (18.43:1 ground boundary); hairline defines the edge in light. |
| **RTL** | All paddings use start/end; dot slot and accent rule follow layout direction / `titleAlign`; auto-mirrored icons unchanged; `applyTextDirection` bidi handling preserved on title/description. |
| **Tonal elevation (list)** | Light Level3 stress ground `surfaceContainerHigh`: read title 7.87, primary feedName 6.58, dot 6.58 — all pass (§9). Dark list ground is always `surface` (`onDark` branch, `ArticleItem.kt:354–358`). |
| **Outlined reader bar mode** | Pill on `surface`: icon 10.43 / 5.89 passes; ground boundary supplementary (§9 A17o). |
| **Read-indicator `None`** | No dot, no slot, no padding compensation; all rows emphasized (600 + `onSurface` + `primary` feedName). |
| **Starred-read (`ExcludingStarred`)** | Emphasized typography, no dot; filled star unchanged. |
| **TTS `Preparing` / `Error`** | Idle icon, no pill; error toast path unchanged; `Reading` shows pill + ring. |
| **`extractFailed` flash** | `error` tint, no pill, ≤3s, stable `contentDescription` (E13 preserved). |
| **Swipe in progress** | Divider stationary outside the swipe box; swipe colors/gestures unchanged. |
| **Sticky date headers** | No divider before a header or after the last article; header rendering untouched. |
| **Process death / prefs** | No new state, no new preferences; nothing to restore. |

---

## 11. Acceptance

- `coder` implements §§1–7 exactly; gate green; instrumented target-bound checks pass.
- Every text pair in §9 "after" tables ≥ 4.5:1 and every meaningful UI pair ≥ 3:1 in both static schemes (already measured here; re-verify if any hex drifts).
- Mock `refresh-v02.html` is the visual reference (1 CSS px = 1 dp; font toggle scales sp only). It is not runtime evidence; Compose previews + emulator screenshots are.
- Fast-forward push to `origin/feedbucket-native`; no source edits from this design card.
