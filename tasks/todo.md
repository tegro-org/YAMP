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
- [ ] **Design canvas**: `Design/YAMP.html` + `design-canvas.jsx`, screens as React artboards in `Design/yamp/*.jsx`,
      Hi-Fi tokens mirrored from `presentation/hifi/HiFi.kt`. Sections:
  - [x] Canvas shell `Design/YAMP.html` (branch `feature/design-canvas`), EN/RU switch, `?only=` / `?print=1`
  - [x] Tokens & components (colours, type scale, buttons, rows, dialogs) — states (empty/error/skeleton) to add
  - [x] Existing screens as reference — real golden screenshots (phone, landscape, head unit, car)
  - [x] New: queue switcher (phone sheet, actions, new/delete, player entry, track menu, add to queue; head unit
        car pill + side panel)
  - [x] New: folder browser — phone (root, filter, inside a folder, album, menu) and head unit (pinned/recent on
        car home, tiles + A–Z rail, inside a folder)
  - [x] Split screen 1 : 2 — car mode, queues panel and folders in the wide (1280×720) and slim (640×720) panes
  - [x] New: sources + Jellyfin sign-in, merged search, Home / Library / tabs / car tiles per signed-in
        providers, mix builder + saved mixes, list states and unavailable sources (2026-11-09; drawn by agents,
        50 boards); reviewed with the user 2026-11-10 → decisions D29–D33; round 2 of edits done (tile rows, Library rows,
        mix re-run, Quick Connect, Settings → Storage "Available offline"); a few small open points in HANDOFF.md
  - [ ] Measure the real split-pane sizes on the head unit and which side the player sits on
  - [x] Style approved by the user (2026-11-09); `design-canvas.jsx` is the user's own file — fine for the
        public repo
  - [x] Split screen: used sometimes, 1 : 2 with the slim pane on the left → frames 640×720 and 1280×720
  - [ ] Frames: phone 412×915, head unit 1920×720 (user's unit) and the 1280×720 car grid
  - [x] PDF export: `Design/export-pdf.sh` → `Design/out/YAMP-design-<lang>.pdf` (from the `?print=1` view)
  - [ ] Canvas = behaviour + layout source of truth; code deviations logged in `kb/design-deviations.md`
- [x] `docs/multi-source-player.md` approved (2026-11-09, D28)
- [ ] Later: local files provider (D25, Phase 4d) — design discussion (scanning, index, track identity) when
      we get to it; until then only keep the provider abstraction server/account-free
- [x] Phase 0 — fork identity: done, merged, **released as v0.1.0** (2026-11-10); CI + release workflows work
  - [ ] Regenerate the baseline profile for `org.tegro.yamp` (needs a device)
  - [ ] Upload `Design/brand/social-preview.png` as the repo's social preview (user, GitHub settings)
  - [ ] Close the first Dependabot PRs (failed only on the old gitleaks step); review next week's

## Reported by the user, open (2026-11-11, phone, skip-fix build)

- [ ] **Crash on the Android Back button** (once; after a restart Back works). Need the trace: Settings → About →
      crash reports, or `adb logcat -d | grep -A 30 "FATAL EXCEPTION"`.
- [ ] **Head-unit HUD / driver-screen app** shows nothing for YAMP (worked for YandexMusicLite and YML). Likely
      keyed by package name (`org.tegro.yamp`) or missing notification-listener access; app name/APK to check.

## Phase 1 — multiple queues

- [x] `docs/phase-1-queues.md` approved 2026-11-10 (D38–D44); branch `feature/phase-1-queues`
- [ ] **Before Phase 1 code (D38):** `fix/player-skip-and-audio-session` (`docs/player-skip-and-audio-session.md`):
      skip = quick fade + loading glyph, "Separate audio sessions" setting (D45) — implemented 2026-11-10;
      user tests on phone + head unit → commit groups → merge → patch release
- [ ] 1. Plumbing: `kotlinx-coroutines-test`, `room-testing`; `WIDE_HEAD_UNIT` 1920×720 qualifier + car goldens;
      `TrackUrlResolver` seam over `TrackPreloadManager`
- [ ] 2. `PlaybackControllerTest` on today's behaviour (E6) — fake engine + fake resolver, Robolectric
- [ ] 3. `domain/queue` model; Room v2 (`queues`, `queue_entries`), Hilt `DatabaseModule`, migration + test, `QueueDao` tests
- [ ] 4. `QueueManager` + legacy import + controller restructure (derived flows, entry cursor, replace/move split,
      shuffle/repeat, WAVE queue per account, live-station mode) — controller tests green; phone test: upgrade keeps session
- [ ] 5. UI: `HiFiSheetPanel`, `QueueSwitcherHost` + VM, entry points (Home, player sheet, car pill), dialogs, track and
      list menu actions, EN/RU strings, Android Auto "Queues" folder
- [ ] 6. Screenshots + goldens, `verifyRoborazziDebug lintDebug`, device checklist (DPI force-stop cases), HANDOFF/KB,
      1920×720 "as built" captures into `Design/`
