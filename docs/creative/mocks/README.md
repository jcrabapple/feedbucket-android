# FeedBucket native UI reference mocks

Open `index.html` directly in a current browser. No server, network connection, package install, external font/image, build step or FeedBucket service is required. The HTML embeds the unmodified Google Sans Flex font and its SIL OFL 1.1 notice; Material icon paths are inline SVG. All content is synthetic.

This is a reference for the Compose contract in `../UI-CONTRACT-v1.md`, not a WebView implementation or proof of native runtime behavior.

## Controls

The top mock bar selects screen, state, static/dynamic theme, density, font scale, direction and motion. **Hide controls** or `lab=0` removes it for clean captures. **F6** toggles the bar. Screen navigation, scopes, Reader Current/Changes, dialogs and Back are interactive. List and article scroll offsets are retained in the mock navigation history.

Useful direct URLs (append to `index.html`):

- `?screen=read&state=default&theme=light&font=100&lab=0`
- `?screen=reader&state=changes&theme=dark&font=100&lab=0`
- `?screen=reader&state=appearance&theme=light&font=200&lab=0`
- `?screen=read&state=preparing&theme=light&font=100&lab=0`
- `?screen=downloads&state=warning&theme=dark&font=100&lab=0`
- `?screen=import&state=failed&theme=light&font=100&lab=0`
- `?screen=read&state=default&theme=light&font=200&rtl=1&lab=0`

At 600dp and wider, Read shows rail + list/detail. At 200% font, the wide detail becomes one-pane so controls and text are not cramped. Compact and comfortable differ only by row vertical padding and summary visibility; type and targets do not shrink.

## Rendered evidence

`rendered/` is gitignored. It contains browser screenshots and machine-readable QA output generated during the design task, not files required to use the mock. `../QA-v1.md` records the exact browser, viewport, geometry, hit-testing, contrast and evidence boundaries.

To repeat the manual visual pass, open the direct URLs at 320×800, 390×844 and 768×1024 CSS pixels, then use the state selectors for loading/empty/error and 200% font. Numeric browser QA in this run used the actual managed Chromium session, not source inspection alone.
