# YAMP design canvas

Mock-ups of YAMP screens for the phone and the head unit, drawn with the app's own "Hi-Fi" tokens.
Rules: [DESIGN_GUIDE.md](../DESIGN_GUIDE.md). The canvas is the source of truth for layout and behaviour of new
screens; where the code deviates, the reason goes into `kb/design-deviations.md`.

## View

```sh
python3 Design/serve.py          # optional: a port number, default 5520
```

The script serves this folder with caching switched off (a normal reload always shows the latest files) and
exposes the app's test screenshots under `/shots/` for the "As built" sections. Standard library only.

- Canvas: <http://localhost:5520/YAMP.html> — pan/zoom, click an artboard to focus it.
- Russian: add `?lang=ru` (or use the EN / RU switch top-right).
- One artboard at its real size: `?only=<id>` (e.g. `?only=q-sheet`).
- All artboards laid out for printing: `?print=1`.

## Export to PDF

```sh
Design/export-pdf.sh             # both languages; or: Design/export-pdf.sh ru
```

Writes `Design/out/YAMP-design-<lang>.pdf` (the folder is not committed). Needs Chromium; set
`CHROMIUM_PATH` if it is not on the PATH.

Needs internet access for React, Babel and the fonts (Roboto, Material Symbols Rounded).

## Files

| File | What |
|---|---|
| `YAMP.html` | The page; every artboard is declared once in `buildSections()` |
| `serve.py` | No-cache local server (+ `/shots/` alias for the golden screenshots) |
| `export-pdf.sh` | PDF export of the whole canvas, one file per language |
| `design-canvas.jsx` | Canvas shell (sections, artboards, focus view) |
| `yamp/hifi.jsx` | Tokens (`HF`, `HFType`, …) and primitives mirrored from `presentation/hifi` |
| `yamp/foundations.jsx` | Tokens and components sheets |
| `yamp/reference.jsx` | The app as built — golden screenshots from `android/app/src/test/screenshots/` |
| `yamp/queues.jsx` | Phase 1: queue switcher, queue actions, "Add to queue…" (phone + head unit) |
| `yamp/radio.jsx` | Phase 2: "Start radio" picker (Keep switch, re-seed, New radio), radio rows in the switcher (phone + head unit) |
| `yamp/accounts.jsx` | Phase 2a components and screens (no sections of its own): account marker, section header, account chips, grouped / flat queue lists, removed accounts, Settings → Accounts. Each 2-account variant (`ac-*` phone, `acc-*` head unit) is registered next to its one-account board: sections 1, 4, 5, 8 (`YAMP.html`), 5b, 5c/5d, 9/13, 11a |
| `yamp/buffering.jsx` | Phase 3b components (no sections of its own): Next states ready / loading / failed, the next track's artwork in the car, the buffered part of the progress bar. Variants sit next to the existing boards: `nb-*` phone in section 4, `nbc-*` car in section 5, `nbs-*` split panes in section 8 |
| `yamp/jellyfin.jsx` | Phase 4 components and screens (no sections of its own): provider chooser in Accounts, server address check, Quick Connect confirmed, duplicate refused (D78), accounts with Jellyfin, queues with its default queue (D83), Add to queue for its own account only (D84), album and playlist pages, server unreachable, car pill and panel. `jellyfinBoards()` places each variant after a given board: sections 4, 5, 9, 11b, 13 (`withExtras` in `YAMP.html`); `CarHome` got `pillName` / `track` |
| `yamp/albummenu.jsx` | Album / artist actions (no sections of its own): one menu from a long press or the ⋮ in the album / artist page header — Play now · Play next · Add to end · Add to queue… · Start radio (Yandex only); the artist confirmation (all albums / popular, singles optional, counts); Add to queue… from an album; the car menu. `albumMenuBoards()` adds `am-*` / `amc-*` boards to sections 4, 5, 10, 11b (`mergeExtras` in `YAMP.html`) |
| `yamp/welcome.jsx` | First start (no sections of its own): the source chooser instead of a forced Yandex sign-in (phone, head unit), and Jellyfin-only Home, Library and car home with the empty default queue. `welcomeBoards()` adds `wl-*` boards to section 9 after `jf-add` |
| `yamp/quality.jsx` | Stream quality per account (no sections of its own): Settings → Accounts with each account's quality summary, the account sheet with two choices — unmetered (Wi-Fi) / metered (mobile, limited Wi-Fi) — Yandex Lossless / Compatible, Jellyfin Original / Converted; the "Now: metered" chip; how to mark the car's Wi-Fi as metered; Playback without the global quality row; head-unit variants. Placed in section 9 after `jf-accounts` |
| `yamp/player.jsx` | Main screen + new player (no sections of its own): the main screen with the queue dropdown on top, a tap around Play / on the title opens the player, the "In car" tab becomes "Player"; the player — Phone layout portrait (next tracks 1 = as today / 3 = card row), landscape with the next track(s) under the cover, Car layout (no buttons bar; a header row with the queue dropdown and Tune the wave; bigger controls, the ring around Play, times; cover and next 3; left / right wheel; 1760×720 unit); swipe up = queue, swipe down closes the queue first, then the player; like gestures (double tap / hold) vs buttons; Settings → Player. `playerBoards()` adds `pl-*` / `plc-*` boards to sections 4, 5, 9. It also replaces the old `CarHome` with the new player body, so every car board shows the new player; old car-mode boards with no new equivalent are marked superseded in `YAMP.html` (`SUPERSEDED`) |
| `yamp/folders.jsx` | Phase 4b: Jellyfin folder browser (refreshed 2026-11-19) — phone root / level / album folder with breadcrumbs, a filter on every level, pinned on top, the folder menu (album menu look); the placeholder for folders without art (`FdPlaceholder`); the car Folders tab and folder level (left / right hand). Sections 6 and 7 in `YAMP.html` |
| `yamp/carbrowse.jsx` | (f) The car Library (`CbShell`): one entry from the player's ⋮ → Library, tabs per service, A–Z rail and back on the wheel side, account chip and now-playing capsule on the far side; the Albums tab and the car album menu (left / right hand). `carBrowseBoards()` adds `ca-*` boards to section 7 |
| `yamp/homejf.jsx` | (e) Home with Jellyfin groups below the hero (first screen, scrolled, whole page; Jellyfin only), Library with merged rows and Y / J badges (both, Jellyfin only, Yandex only), the per-account alternative, a merged row opened. `homeJfBoards()` adds `hj-*` boards to sections 11a and 11b |
| `yamp/split.jsx` | Head unit in split screen: wide pane 1280×720 and slim pane 640×720 |
| `yamp/signin.jsx` | Phase 4: sources list, Jellyfin sign-in (password and Quick Connect); list states, unavailable sources, Settings → Storage → "Available offline" |
| `yamp/search.jsx` | Phase 3: merged search across providers |
| `yamp/home.jsx` | Home, Library and car home composed from the signed-in providers |
| `yamp/mix.jsx` | Phase 6: mix builder and saved mixes |

## Launcher icon

`icons/` holds the icon sources (`rings.svg`, `three-rings.svg`, each with a `-mono.svg` mask for Android 13
themed icons) on the 108 dp adaptive-icon canvas, and `make-launcher-icons.sh <name>`, which renders the
Android resources (foreground, monochrome, legacy PNGs) into `android/app/src/main/res`. The concept pages
they came from are the two zips next to this README.

## Frames

Phone 412×915 dp · head unit 1920×720 (the user's) · split screen 1 : 2 → slim pane 640×720 and wide pane
1280×720 (= the app's car design grid).

## Conventions

- Text through `useT()(english, russian)` — every screen must hold in both languages.
- Only tokens from `hifi.jsx`; if a value is missing, add it to the app's `HiFi` first.
- New screens are shown for the phone **and** the head unit (D23).
- All canvas scripts share one global scope: a new screen file prefixes its top-level names and exposes
  `<name>Sections()`, which `YAMP.html` adds to `buildSections()`.
