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
- All artboards stacked for printing: `?print=1`.

Needs internet access for React, Babel and the fonts (Roboto, Material Symbols Rounded).

## Files

| File | What |
|---|---|
| `YAMP.html` | The page; every artboard is declared once in `buildSections()` |
| `serve.py` | No-cache local server (+ `/shots/` alias for the golden screenshots) |
| `design-canvas.jsx` | Canvas shell (sections, artboards, focus view) |
| `yamp/hifi.jsx` | Tokens (`HF`, `HFType`, …) and primitives mirrored from `presentation/hifi` |
| `yamp/foundations.jsx` | Tokens and components sheets |
| `yamp/reference.jsx` | The app as built — golden screenshots from `android/app/src/test/screenshots/` |
| `yamp/queues.jsx` | Phase 1: queue switcher, queue actions, "Add to queue…" (phone + head unit) |
| `yamp/folders.jsx` | Phase 4b: folder browser — phone list, head-unit tiles, pinned/recent on car home |
| `yamp/split.jsx` | Head unit in split screen: wide pane 1280×720 and slim pane 640×720 |

## Frames

Phone 412×915 dp · head unit 1920×720 (the user's) · split screen 1 : 2 → slim pane 640×720 and wide pane
1280×720 (= the app's car design grid).

## Conventions

- Text through `useT()(english, russian)` — every screen must hold in both languages.
- Only tokens from `hifi.jsx`; if a value is missing, add it to the app's `HiFi` first.
- New screens are shown for the phone **and** the head unit (D23).
