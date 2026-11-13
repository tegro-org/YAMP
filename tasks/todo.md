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

- [ ] **Auto-pause on the head unit — 2026-11-13 log:** with "Separate audio sessions" and the announce both off, 3 focus
      losses in 1 h 15 (vs 9 in the last hour with sessions on), each 0.1–3 s after a track start. Next: `dumpsys audio`
      focus stack right after a loss (names the taker); one ride on upstream 1.14.4 for a baseline count; then maybe
      the "keep the AudioTracks alive" experiment (pause instead of stop on the outgoing/standby player).
- [x] Settings 200 % on the unit: reported 160 dpi, bogus physical ≈321 dpi → auto 200 %; auto is now bounded so the short
      side keeps ≥ 480 dp → 150 % on 1920×720 (2026-11-13)
- [ ] Back-button crash: no CRASH line in the log (handler never ran) — ask for the About → "Журнал сбоев" entry
- [ ] **Auto-pause on the head unit (every next song)** — log 2026-11-12: every pause is a permanent `FOCUS lost` 1–3 s
      after a track starts sounding; upstream 1.12.2/1.14.1 have no pauses on the same unit. Announce switch off:
      not solved. Current suspect: **"Separate audio sessions"** (on since the first ride, when the pauses began) — the
      unit's audio arbiter may take focus from a new session when the other player's track starts. Test with it off.
- [ ] **Tap on a "Далее" track in the car**: the current track stops ~0.5 s, comes back for ~2 s, then the crossfade
      (video 20261112_164222.mp4). Same suspect: the incoming track prepared on the other player opens a new audio
      session (separate sessions on) and the unit mutes the current stream for a moment; then the crossfade hold
      (READY + 5 s headroom) delays the fade. Re-test with the setting off; if it persists, look at the hold timing.

- [ ] **Crash on the Android Back button** (once; after a restart Back works). Need the trace: Settings → About →
      crash reports, or `adb logcat -d | grep -A 30 "FATAL EXCEPTION"`.
- [ ] **Head-unit HUD / driver-screen app** shows nothing for YAMP (worked for YandexMusicLite and YML). Likely
      keyed by package name (`org.tegro.yamp`) or missing notification-listener access; app name/APK to check.

## Upstream 1.13–1.14.1 ports (`kb/upstream-tracking.md`, triaged 2026-11-11)

- [x] **U1** security/data/build: Н7 token hosts, Н3 backups + orphaned key, Н6 cache evict, Н1 download lock,
      Н5 update cancel, Н9 cancellation, Н2 missing-copy fallback, Java 17 target, Compose reports, detekt job
      (2026-11-11, `fix/upstream-1.14-u1`; left: updater release-notes cleanup, Dependabot bumps)
- [x] **U2** playback/head unit: buffering policy, crossfade hold (adapted to the skip fade), equalizer announce
      (two sessions), one track per swipe, `bufferedAheadMs`, AppDialog/AppScale, car font cap, software bitmaps +
      dark bars, update window — implemented 2026-11-11 (`fix/upstream-1.14-u2`)
  - [x] head-unit ride 1 (2026-11-11): skip crossfade good; auto-pause after double skip → fixed (engine keeps
        "playing, loading" in the skip gap, audio focus never dropped); settings 200% → waiting for the scale line
  - [x] ride 2: auto-pause without action, 1-s hold after a start → next-track preparation gated by buffer health
        (D36); playback journal (About) + **diagnostic log file** (D48, `docs/diagnostic-log.md`)
  - [ ] user test of `…-u2-diaglog` build on the head unit → share the log → commit groups → merge → `v0.1.2`
- [ ] Later: see the "Later" table in the tracking log

## Phase 1 — multiple queues

- [x] `docs/phase-1-queues.md` approved 2026-11-10 (D38–D44); branch `feature/phase-1-queues`
- [ ] **Before Phase 1 code (D38):** `fix/player-skip-and-audio-session` (`docs/player-skip-and-audio-session.md`):
      skip = quick fade + loading glyph, "Separate audio sessions" setting (D45) — implemented 2026-11-10;
      user tests on phone + head unit → commit groups → merge → patch release
- [x] 1. Plumbing: `kotlinx-coroutines-test`, `room-testing`; `WIDE_HEAD_UNIT` 1920×720 qualifier + car goldens;
      `TrackUrlResolver` seam over `TrackPreloadManager` (2026-11-12)
- [x] 2. `PlaybackControllerTest` on today's behaviour (E6) — 10 tests, fakes in `data/player/fakes/` (2026-11-12)
- [x] 3. `domain/queue` model + `QueuePolicy` (pure, 8 tests); Room v2 (`queues`, `queue_entries`), Hilt
      `DatabaseModule`, `RoomQueueStore` (6 tests), migration v1→v2 + test (Room validates on open; Robolectric
      sees no test assets, so no MigrationTestHelper), B1 rule in `ArchitectureTest` (2026-11-12)
- [x] 4. `QueueManager` + legacy import + controller restructure (derived flows, entry cursor, replace/move split,
      shuffle/repeat, WAVE queue per account, live-station mode) — 14 controller tests green (2026-11-12)
  - [ ] phone test: upgrade from v0.1.2 keeps the session (the json becomes "Основная"); play/next/prev/My Wave/FM
        radio/offline as before; the three decisions in the doc's "Implementation notes"
- [x] 5. UI: `HiFiSheetPanel`, `QueueSwitcherHost` + VM, entry points (Home, player sheet, car pill), dialogs, track and
      list menu actions, EN/RU strings, Android Auto "Queues" folder — 12 new goldens, 53 re-recorded (2026-11-12);
      deviations in `kb/design-deviations.md`
- [ ] 6. `scripts/device-check.sh` on phone + head unit (DPI force-stop cases), the queue flows by hand (canvas
      boards: switch, new, rename, delete, add to queue, play next, shuffle/repeat, Android Auto "Queues"), HANDOFF/KB
      done; 1920×720 "as built" captures into `Design/` (goldens `wide_head_*`)

## Phase 1 follow-up — Home follows the active queue (D49)

- [x] First tab = active queue's page (title, knob, "Дальше"); wave pills/shelf only for the WAVE queue (phone + car);
      the car's knob and auto-start go through the controller's play rule (2026-11-12) — head-unit test pending

## Phase 1b — History (D46, D50)

- [x] `docs/phase-1b-history.md` approved and implemented 2026-11-12: Room v3 `history`, recording from the sounding
      track, Library row + page with queue chips (active queue first), "play now, keep my queue", settings switch,
      canvas boards; 445 tests — phone/head-unit test pending
- [ ] watch: `PlaybackControllerTest.aSavedSessionComesBackPausedAtItsPosition` timed out once (5 s) in a full run

## Phase 2 — Radio (`docs/phase-2-radio.md`, approved 2026-11-13; branch `feature/phase-2-radio`)

- [x] 1. `album:`/`artist:` stations verified against the live API via the debug "Probe radio stations" button
      (2026-11-13): all three behave like `track:`; rotor `feedback` answers 400 "condition is not met" for every
      station incl. My Wave — separate issue, not Phase 2. Probe stays until the end of Phase 2, then removed.
- [x] 2. Model (`keepHistory`, `RadioSeed`/`RadioTarget`, `QueuePolicy.radioTrim`), Room v4 + `MIGRATION_3_4` + test,
      `RotorStations` + `RotorStationSource` (5 tests), `QueueManager.startRadio` / trim / `setKeepHistory` (3 tests)
      — 457 unit tests, lint and detekt clean (2026-11-13)
- [x] 3. Controller: the active-radio source (lazy per active RADIO queue, dropped on re-seed; a batch lands only on
      the same active radio), the four `kind == WAVE` sites with a RADIO branch beside each, `startRadio`,
      `setKeepHistory`; 3 controller tests (2026-11-13)
- [x] 4. UI: picker mode, Keep switch and marker, radio menu (Keep + Delete), entry points (D54: track menu, player
      button, car pill on every queue, album/artist ⋮), strings EN/RU, 7 new goldens (+ `head_car_queue`,
      `settings_about` re-recorded: car pill / probe button); 467 tests, lint, detekt green; AVD smoke test
      (start from the player, refill, switch away and back, Keep toggle) (2026-11-13)
- [x] 4b. Docs: deviations from steps 2–4 folded into `docs/phase-2-radio.md` (marked implemented), `kb/decisions.md`
      (D35′, D51–D54; D35/D40 annotated), `kb/architecture/as-is-playback.md`, `docs/multi-source-player.md` Phase 2
      paragraph, canvas board `Design/yamp/radio.jsx` (+ the Phase 1 radio menu board updated) (2026-11-13)
- [ ] 4c. Device checklist on the unit (phone quick test passed 2026-11-13); HANDOFF at release
- [ ] Remove the probe (`RadioStationProbe.kt` + the About button + the `batch-id`/`timestamp` feedback params)
- [ ] Separate: rotor feedback 400 on every station — compare the form fields / headers with upstream's current version
