# FeedBucket Android UI mock QA report v1

Date: 2026-11-16
Scope: `docs/creative/UI-CONTRACT-v1.md` and `docs/creative/mocks/index.html` only.
Base reviewed: amended product docs at `744df21d`; this report covers the final mock revision created by designer task `t_b548f84e`.

## Verdict and evidence boundary

**Measured HTML reference: PASS for the checks below.** The final real-browser run completed **101 screen/state/viewport cases with 0 failures**. It measured **776 visible actionable targets**, all at least **48×48 CSS px**, with **0 genuine center hit failures, 0 end-of-scroll occlusions, 0 document overflow failures, 0 unnamed button names in the sampled accessibility trees, and 0 external resource requests**.

This is not Compose runtime evidence, Android/TalkBack certification, a WCAG conformance claim, an emulator test or physical-device evidence. No app source, physical device, adb, emulator or live FeedBucket service was used. Production dynamic palettes, native semantics and API 36 behavior remain implementation gates.

## Tools and method

- **Browser:** Google Chrome **157.0.8103.78**, headless, driven through Chrome DevTools Protocol.
- **Driver:** Node.js **22.22.3**, built-in `WebSocket`; no test-package dependency.
- **Serving:** loopback-only `python3 -m http.server 18765 --bind 127.0.0.1` because the installed Chrome is Flatpak-sandboxed and could not open this checkout through `file://`. The mock itself has no server/network requirement and opens directly in an ordinary browser.
- **Geometry:** `getBoundingClientRect`, computed type, `document.elementFromPoint` at actionable centers, and scroll-to-end hit tests for every rendered scroll region, including dialogs/sheets.
- **Accessibility:** `Accessibility.getFullAXTree` for every case; native dialog focus tests with Escape and Tab.
- **Static checks:** embedded JavaScript syntax checker returned `JavaScript syntax: OK`; `npx -y impeccable@latest detect docs/creative/mocks/index.html --no-advisory` returned no findings after removing the custom progress loop.
- **Contrast:** independent Python implementation of WCAG relative luminance `(max(L1,L2)+0.05)/(min(L1,L2)+0.05)`.
- **Captures:** `Page.captureScreenshot`, settled initial viewport before any scroll mutation. End-scroll probes ran afterward. Rendered PNG/JSON files are gitignored under `docs/creative/mocks/rendered/`.

Raw evidence:

- `rendered/qa-results.json` — all cases, rectangles, target checks, end hits, computed type, accessibility summaries and resource audit.
- `rendered/qa-summary.json` — final aggregate and failure list (empty).
- `rendered/qa-interactions.json` — navigation, position, focus and diff interaction checks.

## Coverage

The 101 cases comprise:

- **59 base screen/state cases at 390×844:** Browse, Feeds, FeedDetail, Search/saved searches, Reader/diff, Recently read, Rules/previews/apply/Undo, Downloads, Migration/OPML and Settings, including specified loading, empty, error, pending, partial and success distinctions.
- **12 additional 320/768 cases:** Browse, Reader Changes, Rule Preview, Downloads, Import Preview and Settings.
- **15 large-font cases at 320/390/768:** Browse, Reader Appearance, Settings, Search and Rule Preview at 200%.
- **12 palette cases:** static dark and representative dynamic light/dark for Browse, Reader Changes, Downloads and failed Import.
- **2 narrow RTL/200% cases:** compact-density Browse and Settings, dark with reduced motion.
- **1 explicit Serif taste-preview case.**

Preparation states cover Browse, Search and RuleEdit. Search execution, saved-search execution and rule Preview/Apply are visibly gated while browsing and draft editing remain available. Downloads distinguish pending, Waiting for Wi-Fi, downloading, text-ready image incomplete, Saved, failed and storage warning/full. Failed import retains its source while Retry is offered.

## Final geometry and typography evidence

| Case | Measured result |
|---|---|
| 320×800 Browse | App width **320**; app bar **320×64** at y24; bottom navigation **320×85** at y715; final More action **48×48**, end hit true |
| 390×844 Browse | App width **390**; app bar **390×64** at y24; bottom navigation **390×85** at y759; final More action **48×48**, end hit true |
| 768×1024 Read | Start rail **80×1024** at x0; list **320×1000** at x80,y24; detail **368×1000** at x400,y24; one continuous status strip above both panes; Reader bar **368×65** at x400,y959 |
| 768×1024 at 200% | Rail **80**; list-only pane **688×1000** at x80,y24; detail pane not shown; final More action **48×48**, end hit true |
| 390×844 Reader Changes | Reader bar **390×65** at y779; source link **350×84**, end hit true; deleted/inserted accessible text present |
| 390×844 Appearance at 200% | Sheet **390×717.4** at x0,y126.6; max scroll **603**; final Line height input **262×48**, end hit true |
| Default article type | **16/24px**, Google Sans Flex loaded |
| Default Reader body | **18/28px**, Google Sans Flex loaded |
| 200% article type | **32/48px** |
| 200% Reader body | **36/56px** |
| Focus indicator | **3px solid** primary outline on Changes after Tab |

All target dimensions are CSS px standing in for dp in the reference. App bars and bars with 65/85/93px heights include border, content wrapping or the mock system inset; minimum dp geometry in the contract is not reduced.

## Interaction evidence

`rendered/qa-interactions.json` records these actual browser results:

| Check | Result |
|---|---|
| Open article | Reader opened; article heading received focus |
| List anchor | **24px** preserved exactly after Reader → Back |
| Article position | **96px** preserved exactly after closing and reopening the same article |
| Focus/position interaction | Heading focus uses `preventScroll`; it does not change the restored 96px position |
| Appearance sheet | Opened with focus on “Close reading appearance” |
| Escape | Closed the sheet |
| Focus restoration | Returned to **Reading appearance** launcher |
| Current/Changes | Changes exposed `aria-pressed="true"` and accessible deleted/inserted runs |
| Keyboard focus | Tab reached Changes with a visible **3px** outline |

Scroll capture is limited to rendered panes, so hidden compact/wide duplicates cannot overwrite the active pane's position.

## Numeric contrast audit

The audit compared **120 foreground/background pairs across static light, static dark and representative dynamic light/dark schemes: 120 passes, 0 failures**. It also verified all **168 role assignments across the four palettes** appear identically in `UI-CONTRACT-v1.md`; no missing values.

Representative rows (full set is encoded in the contract and was recomputed, not estimated):

| Pair | Static light | Static dark | Dynamic light | Dynamic dark |
|---|---:|---:|---:|---:|
| onPrimary / primary | 7.79 | 7.80 | 6.44 | 7.71 |
| onPrimaryContainer / primaryContainer | 10.43 | 5.89 | 13.32 | 7.23 |
| onSecondaryContainer / secondaryContainer | 10.34 | 6.63 | 13.24 | 7.19 |
| onErrorContainer / errorContainer | 13.26 | 7.24 | 12.77 | 7.17 |
| onSurface / surface | 16.70 | 14.28 | 16.71 | 14.35 |
| onSurfaceVariant / surfaceContainerHighest | 7.50 | 7.06 | 7.21 | 7.20 |
| primary / surface | 7.61 | 10.85 | 6.28 | 10.91 |
| outline / surface (component indicator) | 4.39 | 5.83 | 4.44 | 5.87 |
| inversePrimary / inverseSurface | 7.78 | 6.04 | 7.69 | 4.97 |

Minimum ordinary-text ratio by scheme: **6.46 static light, 5.89 static dark, 6.28 dynamic light, 7.17 dynamic dark**. Minimum measured meaningful component/focus ratio: **4.39, 5.83, 4.44, 4.97**, respectively. All exceed 4.5:1 for text and 3:1 for meaningful component/focus indicators. Outline values below 4.5 are not used as text colors.

Production wallpaper-derived schemes must be re-audited against their resolved Android `ColorScheme` values; the dynamic columns here are measured representative fixtures, not a claim for every wallpaper.

## Visual review dispositions

Actual browser screenshots were inspected, not just HTML source. Corrections made and re-verified:

- Reduced competing status pills to one Updated label; kept Starred as filled shape + text and downloads as metadata with separators.
- Replaced the 768 per-pane status bars with one continuous content-wide strip; start rail is x0.
- Changed 200% wide Read to list-first one-pane behavior rather than Reader-first.
- Stacked setting descriptions and form controls; Feed URL gets a full line with Change URL on the following line.
- Made compact sheets full width with top-only corners and retained the 360dp wide end sheet.
- Removed the custom indeterminate-progress loop; linear progress remains literal and reduced-motion safe.
- Added sheet scroll-to-end evidence and `preventScroll` heading focus.

Final inspected files include:

- `read-default-390.png`, `read-default-320.png`, `read-default-768.png`
- `read-preparing-390.png`
- `reader-changes-390-dark.png`, `reader-serif-390.png`
- `reader-appearance-200-390.png`
- `rules-preview-results-390.png`
- `downloads-warning-390-dark.png`
- `import-failed-390.png`
- `feedDetail-default-320.png`
- `settings-default-200-320-dark-rtl.png`

The final adaptive and form verification found no remaining material clipping, collision or hierarchy defect. Partially visible rows at scroll edges are intentional; every such control was proven reachable and hittable after scrolling. Horizontal chip overflow is intentional scroll behavior, not document overflow.

## Material API verification evidence

Local cached artifacts, not prose assumptions:

- `material3-android-1.4.0` contains **0 public `androidx/compose/material3/ButtonGroup*` classes**; only token classes are present. The contract therefore uses a documented stable `SingleChoiceSegmentedButtonRow` fallback for Current/Changes and forbids a fabricated `ButtonGroup` call.
- `SearchBarState` exists in 1.4.0 and has `ExperimentalMaterial3Api` in its bytecode annotations; the contract keeps that opt-in bounded.
- `MotionScheme.standard()` exists in the 1.4.0 artifact.
- Official release tables retrieved 2026-11-16: Material 3 **1.4.0 stable**, adaptive **1.3.0 stable**. The 1.5.0 RC and adaptive 1.4 alpha are not used.

The dated source → component → API/version → adaptation → verification matrix is in `UI-CONTRACT-v1.md` §10.

## Remaining downstream checks

1. Wave A resolved dependency and opt-in evidence from actual Gradle output.
2. Compose semantics/TalkBack, keyboard/D-pad, RTL, font-scale and reduced-motion instrumentation.
3. API 36 edge-to-edge and predictive Back on an explicitly selected emulator.
4. Contrast of real wallpaper-derived dynamic schemes and focus/state layers.
5. Native rendering of long article HTML, tables, code, images and sanitized diffs.
6. Gate A resolution of Undo POST-apply version guards, import FK-cascade protection, failed-import retention and terminal asset-failure semantics described in the contract.
