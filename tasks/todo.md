# TODO

## Setup (2026-11-09)

- [x] Clone `tegro-org/YAMP`, import upstream `fac6af7` into `android/`
- [x] Move design doc `docs/multi-source-player.md` (re-based on upstream)
- [x] KB: index, environment, decisions, upstream tracking, privacy audit
- [x] User review → initial commit → push (`d221875`)
- [ ] Verify the imported project builds from `android/` (`./gradlew assembleDebug`) and tests pass

## Next

- [x] As-is analysis of the code (`kb/architecture/`)
- [x] Draft `ARCHITECTURE.md` + `DESIGN_GUIDE.md`
- [x] User review of the drafts; Q1–Q7 decided (`kb/decisions.md` D16–D22)
- [ ] **Design canvas** (like Vantage `Design/`): `Design/YAMP.html` + `design-canvas.jsx` / `tweaks-panel.jsx`
      copied from `~/Projects/Tegro/Vantage/Design/`, screens as React artboards in `Design/yamp/*.jsx`,
      Hi-Fi tokens mirrored from `presentation/hifi/HiFi.kt`. Sections:
  - [ ] Tokens & components (colours, type scale, buttons, rows, dialogs, states)
  - [ ] Existing screens as reference (Home/My Wave, Library, Search, Player, Car) — phone portrait + head unit
  - [ ] New: queue switcher popup, folder browser (phone + car tiles, A–Z rail, breadcrumbs), Jellyfin sign-in,
        merged search with provider badges, capability-driven Home/car home, mix builder
  - [ ] Frames: phone 412×915, head unit 1920×720 (user's unit) and the 1280×720 car grid
  - [ ] PDF export script (like Vantage `build-design-*-pdf.sh`)
  - [ ] Canvas = behaviour + layout source of truth; code deviations logged in `kb/design-deviations.md`
- [ ] Approve `docs/multi-source-player.md`
- [ ] Phase 0 — fork identity (applicationId, name, signing, updater repo, CI for `android/`)
