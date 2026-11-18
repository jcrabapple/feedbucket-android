# FeedBucket launcher icon directions

Status: **design review; production resources not yet wired**. Select one direction before final launcher, notification/monochrome, legacy mipmap and store-icon assets are installed in `app/src/main/res` and Fastlane.

The marks follow the approved **Margin Notes** direction and the static petrol/ink palette in `UI-CONTRACT-v1.md` §5.1. There is no text, RSS-wifi glyph, book glyph, gradient, shadow, external image or new hue in any candidate.

## Directions

### 1. Collected Lines

Two reading lines sit inside an open basin. This is the most direct "articles collected" construction and the lightest visual treatment.

- Background: `primaryContainer` — `#B8E4E2`
- Mark: `primary` — `#145B60`
- Preview: [`direction-1/icon-512.png`](direction-1/icon-512.png)
- Light/dark and themed: [`direction-1/light-dark.png`](direction-1/light-dark.png)
- Home screen: [`direction-1/home-screen.png`](direction-1/home-screen.png)

### 2. Index Shelf

Three dot-and-line editorial entries rest above one short collecting shelf. This is the most literal Margin Notes/index interpretation.

- Background: `surfaceContainerHighest` — `#E2E8E6`
- Mark: `primary` — `#145B60`
- Preview: [`direction-2/icon-512.png`](direction-2/icon-512.png)
- Light/dark and themed: [`direction-2/light-dark.png`](direction-2/light-dark.png)
- Home screen: [`direction-2/home-screen.png`](direction-2/home-screen.png)

### 3. Confluence — recommended

Three staggered inputs fall into one shared basin. The inverted petrol tile gives FeedBucket the strongest launcher silhouette and clearest separation at 48px while remaining quiet and editorial.

- Background: `primary` — `#145B60`
- Mark: `primaryFixed` — `#B8E4E2`
- Preview: [`direction-3/icon-512.png`](direction-3/icon-512.png)
- Light/dark and themed: [`direction-3/light-dark.png`](direction-3/light-dark.png)
- Home screen: [`direction-3/home-screen.png`](direction-3/home-screen.png)

**Recommendation: Direction 3, Confluence.** Rendered review found it the clearest candidate at launcher size and under rounded/circular masks. Direction 1 is the lighter fallback. Direction 2 is the most editorial but also the closest to a conventional list glyph.

See [`overview.png`](overview.png) for the side-by-side comparison.

## Vector and adaptive geometry

Every direction includes editable SVG and candidate Android VectorDrawable XML:

- `icon.svg`: full-color 108×108 source.
- `monochrome.svg`: one-color source.
- `android/ic_launcher_foreground.xml`: 108dp foreground with rounded vector strokes.
- `android/ic_launcher_monochrome.xml`: single-color vector for Android 13+ themed icons.
- `android/ic_launcher_background.xml`: 108dp VectorDrawable background.
- `android/ic_launcher_background_color.xml`: matching color resource for legacy generation.
- `adaptive-rounded-512.png` and `adaptive-circle-512.png`: mask previews.

Measured artwork bounds include stroke widths. The centered 66dp safe zone is `(21,21)`–`(87,87)` on the 108dp canvas.

| Direction | Measured bounds `(left,top)`–`(right,bottom)` | Safe-zone result |
|---|---|---|
| Collected Lines | `(25.5,37.5)`–`(82.5,81.5)` | Inside |
| Index Shelf | `(30,27)`–`(83.5,80.5)` | Inside |
| Confluence | `(24.5,21.5)`–`(83.5,81.5)` | Inside |

## Verification completed for the candidates

- Rendered and visually inspected at 48, 64, 96 and 128px, plus 512px sources.
- Inspected on light/dark surfaces, as single-color themed icons, under rounded/circular masks, and in neutral home-screen grid/dock mockups.
- All 12 candidate Android XML files compiled successfully with `aapt2` 36.1.0 after staging them under standard `res/drawable` and `res/values` paths. No Gradle invocation was used.
- No device or emulator verification has been performed. Home-screen images are design mockups, not installed-app evidence.

## Finalization after selection

The selected direction will be wired into the existing adaptive and round launcher resources, with a separate single-color monochrome drawable. Legacy square/round PNGs will be generated at mdpi 48px, hdpi 72px, xhdpi 96px, xxhdpi 144px and xxxhdpi 192px, plus a 512×512 store icon. The production resource set and Fastlane icon replacements will then receive a fresh aapt2/resource-link check. No app resource files are changed in this candidate-review commit.
