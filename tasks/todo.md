# TODO

## Setup (2026-11-09)

- [x] Clone `tegro-org/YAMP`, import upstream `fac6af7` into `android/`
- [x] Move design doc `docs/multi-source-player.md` (re-based on upstream)
- [x] KB: index, environment, decisions, upstream tracking, privacy audit
- [x] User review → initial commit → push (`d221875`)
- [x] Verify the imported project builds from `android/` (`./gradlew assembleDebug`) and tests pass
      (2026-11-09: `assembleDebug testDebugUnitTest` OK, 340 tests)
- [x] Run `verifyRoborazziDebug lintDebug` (what upstream CI runs): 107/107 screenshots unchanged, lint clean

## Next

- [x] As-is analysis of the code (`kb/architecture/`)
- [x] Draft `ARCHITECTURE.md` + `DESIGN_GUIDE.md`
- [x] User review of the drafts; Q1–Q7 decided (`kb/decisions.md` D16–D22)
- [x] `ARCHITECTURE.md` approved (2026-11-09, D24); phone is first-class next to the car (D23)
- [x] `DESIGN_GUIDE.md` approved (2026-11-09, D24)
- [x] Align `docs/multi-source-player.md` with `ARCHITECTURE.md` (`TrackRef`, entry-id cursor, queue kinds,
      several providers at once, `org.tegro.yamp`, phone + car) — done 2026-11-09
- [x] Queue switch in the car: popup, 2 taps (D26)
- [x] Ids: one form `<provider>:<kind>:<id>` for tracks, albums, artists, playlists, folders (D27, C1)
- [ ] **Design canvas** (like Vantage `Design/`): `Design/YAMP.html` + `design-canvas.jsx` / `tweaks-panel.jsx`
      copied from `~/Projects/Tegro/Vantage/Design/`, screens as React artboards in `Design/yamp/*.jsx`,
      Hi-Fi tokens mirrored from `presentation/hifi/HiFi.kt`. Sections:
  - [x] Canvas shell `Design/YAMP.html` (branch `feature/design-canvas`), EN/RU switch, `?only=` / `?print=1`
  - [x] Tokens & components (colours, type scale, buttons, rows, dialogs) — states (empty/error/skeleton) to add
  - [x] Existing screens as reference — real golden screenshots (phone, landscape, head unit, car)
  - [x] New: queue switcher (phone sheet, actions, new/delete, player entry, track menu, add to queue; head unit
        car pill + side panel) — **awaiting the user's review of the style before the rest**
  - [ ] New: folder browser (phone + car tiles, A–Z rail, breadcrumbs), Jellyfin sign-in, merged search with
        provider badges, capability-driven Home/car home, mix builder, unavailable-provider states
  - [ ] Decide: split-screen frame (960×720) for the head unit — does the user run YAMP in split screen?
  - [ ] Decide: `design-canvas.jsx` is copied from Vantage and has no licence header — OK for the public repo?
  - [ ] Frames: phone 412×915, head unit 1920×720 (user's unit) and the 1280×720 car grid
  - [ ] PDF export script (like Vantage `build-design-*-pdf.sh`)
  - [ ] Canvas = behaviour + layout source of truth; code deviations logged in `kb/design-deviations.md`
- [x] `docs/multi-source-player.md` approved (2026-11-09, D28)
- [ ] Later: local files provider (D25, Phase 4d) — design discussion (scanning, index, track identity) when
      we get to it; until then only keep the provider abstraction server/account-free
- [ ] Phase 0 — fork identity (applicationId, name, signing, updater repo, CI for `android/`)
  - [ ] Create YAMP's own release keystore (none exists yet; Release tasks fail without it). Keep it outside
        the repo, back it up, pass it to CI as secrets (`KEYSTORE_BASE64`, passwords, alias)
