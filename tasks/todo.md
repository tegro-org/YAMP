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
- [ ] **Head-unit HUD / driver-screen app** shows nothing for YAMP (worked for YandexMusicLite and YML). Cause
      (2026-11-14, decompiled): "Заставка Home" accepts only players whose package name contains `music` / `audio` /
      `media` / `player` / `radio` / `podcast` or is on a fixed list; MSpeed takes any active media session. Fix:
      `applicationId` → `org.tegro.music.yamp` (D55, branch `feature/package-rename`) — verify on the unit.

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
- [x] Remove the probe (`RadioStationProbe.kt` + the About button + the `batch-id`/`timestamp` feedback params) —
      kept on branch `debug/radio-probe`; released as `v0.2.1` (2026-11-13)
- [ ] Separate: rotor feedback 400 on every station — compare the form fields / headers with upstream's current version
- [ ] Report plays to Yandex (play-audio / history) under the queue's account — upstream never does; this is what lets
      each account's taste follow what's listened to in YAMP. Do together with the feedback-400 fix.

## Phase 2a — Accounts: every account live, queues bound to an account (`docs/phase-2a-accounts.md`) — implemented 2026-11-14

Why (user, 2026-11-14 ride): two radios on one Yandex account converge — the account's history steers both. Separate
tastes need separate accounts, so a queue must play, refill and report under its own account.

Model and UX (agreed in discussion 2026-11-14):
- [x] **No global account switch.** All signed-in accounts are live at once (search, browse); the active queue *is*
      the current account — Home (D49), Library, Search, likes follow it.
- [x] **Every queue is bound to an account at creation** (normal and radio): its Yandex tracks stream under that
      account; likes and (once fixed) feedback/plays go to it; a radio's recommendations come from it.
      WAVE stays one per account (no choice). Default = the active queue's account (first start: the first account).
- [x] **Choose the account** in "New queue" and in "Start radio" → "New radio"; re-seeding a radio keeps its
      account. **The binding is permanent** — no "change account" anywhere (D61).
- [x] **Queues of every signed-in account always shown**, each with an account marker (initial / avatar); no filter
      control. Car / Android Auto the same.
- [x] **One account → no extra controls** anywhere (no selector, no marker).
- [x] Revise D22 (queues stay visible to all; each has an owner account) and D7 history note in `kb/decisions.md`.

Technical (from the 2026-11-14 code check):
- [x] Per-request account instead of the global token swapped on switch: the Yandex client resolves the token by
      the queue's / caller's account (streaming, likes, rotor, search) — `ProviderAccount` and per-provider clients
      from Phase 3 (C5, C6) introduced here for Yandex, so Phase 3 builds on them.
- [x] Key accounts by Yandex uid, not the folder name (`UserAccount.kt:27-30`: login → display name → uid; can
      collide, and changes when a provisional `temp_` account gets its details). Migrate `owner` / `account` columns.
- [x] `queues.account` for every queue (today only WAVE), migration: existing queues → the current default account.
- [x] Sign-out (D62): the account's queues (its WAVE queue too) are kept but hidden — a collapsed "Signed-out
      accounts · N queues" row at the bottom of the phone switcher (Delete only), absent in the car / Android Auto;
      signing back in to the same uid restores them. Its cached lists and unshared downloads are removed. History
      stays app-wide (D46), with a nullable account column.
- [x] Startup account picker and the account switcher of upstream retire (Settings keeps add / remove / default).

Later (multi-source queues, with Phase 3+): a queue holds entries from several accounts/providers; the selector
becomes multi-select, the marker shows "mixed".

- [x] Step 1 — canvas boards approved and committed (`9c3289a`, 2026-11-14)
- [x] Step 2 done — files: new `domain/account/ProviderAccount.kt`, `data/account/AccountContext.kt`,
      `data/network/YandexClients.kt`, test `data/network/YandexClientsTest.kt` (5), fake
      `test/.../fakes/FixedYandexClients.kt`; changed `domain/repository/AuthRepository.kt` (`token(account)`),
      `data/network/AuthInterceptor.kt` (token supplier), `di/NetworkModule.kt` (single Retrofit/API bindings →
      `YandexClients`), `data/account/AccountSessionManager.kt` (also evicts per-account caches),
      `data/repository/{Library,Search,Catalog,UserPlaylists}Repository.kt`, `data/network/TrackPreloadManager.kt`,
      `data/player/{PlaybackController,WaveQueue}.kt`, `data/queue/RotorStationSource.kt`, tests `PlaybackControllerTest`,
      `SoundModeTest`, `PlaylistOpeningTest`, `RotorStationSourceTest` — 479 tests green, no goldens changed (2026-11-14)
- [x] Step 3 done — accounts keyed by `yandex:<uid>` (D59), Room v5, `AccountKeyMigration` (2026-11-14). Files: new
      `data/account/AccountKeyMigration.kt`, `data/cache/CacheKeyMigration.kt`, `schemas/…/5.json`, test
      `data/account/AccountKeyMigrationTest.kt`; changed `domain/model/UserAccount.kt` (`folderName` → `legacyFolderName`),
      `data/cache/{CacheDatabase,PlaylistCacheManager}.kt`, `data/queue/{QueueDao,QueueEntities,LegacySessionImport}.kt`,
      `data/history/{HistoryDao,HistoryEntities}.kt`, `data/repository/{LibraryRepository,UserPlaylistsRepository,
      SearchHistoryRepository}.kt`, `data/account/AccountSessionManager.kt`, `data/player/PlaybackController.kt`,
      `di/ResolverModule.kt`; tests `CacheDatabaseMigrationTest`, `LayoutSafetyTest`, `PlaylistOpeningTest`,
      `fakes/FakeAuthRepository`; androidTest `PlaylistEditingInstrumentedTest`
- [x] Step 4 done — per-account state (2026-11-14): likes and playlists held per account (a switch only shows the
      other account's; no reset, no cache eviction), every like / sync / playlist edit made with its account's API;
      stream links resolved and cached per account (`TrackUrlResolver` calls take `account`, `null` = context; the
      download-info XML through the account's own HTTP client); the controller names `playbackAccount` (still the
      active account — step 5 makes it the queue's). `AccountContext` unchanged (= active account). Files: new
      test `data/repository/PerAccountLibraryTest.kt`, androidTest `OneApiClients.kt`; changed
      `data/network/{TrackUrlResolver,TrackPreloadManager,YandexClients}.kt`, `data/repository/{LibraryRepository,
      UserPlaylistsRepository}.kt`, `data/account/AccountSessionManager.kt`, `data/player/PlaybackController.kt`,
      `config/detekt-baseline.xml` (renamed signatures); tests `PlaybackControllerTest`, `SoundModeTest`,
      `LayoutSafetyTest`, `fakes/{FakeTrackUrlResolver,FixedYandexClients}`; androidTest
      `PlayerEngineInstrumentedTest`, `PlaylistEditingInstrumentedTest`
- [x] Step 5 done — queue binding (2026-11-14): `AccountContext` = the active queue's account while it is signed
      in (else the active account); the active account follows the active queue (`PlaybackController.
      followQueueAccount`: wave state reloaded, `accountEntered` → the session loads that account's lists; playback
      untouched); new queues / radios / the auto-made main and offline-mix queues bound to the context account,
      a re-seed and a copy keep theirs; a radio's source asks with its queue's account; downloads fetched through
      the account's client; "Switch account" = `enterAccount` (its My Wave queue becomes active); sign-in →
      `ensureWaveQueue` (D63) and, for a fresh sign-in or no queue at all, `enterAccount`; `refreshAccountStatus`
      asks with the account being signed in. `resetForAccount` stays for sign-out only (step 7). Files:
      `data/account/{AccountContext,AccountSessionManager}.kt`, `data/queue/QueueManager.kt`,
      `data/player/PlaybackController.kt`, `data/network/TrackDownloadManager.kt`,
      `data/download/LikedTracksDownloadWorker.kt`; tests `PlaybackControllerTest` (+5), `QueueManagerTest` (+1),
      `fakes/FakeAuthRepository.kt`
- [x] Step 6 done — UI (2026-11-14), per the approved boards: account markers / section headers / chips
      (`presentation/account/AccountMarks.kt`, colour per account key); switcher grouped (header per account, its
      My Wave first, no row markers) or flat (marker per row) by "Группировать" / "Group" (Settings → Accounts, only
      with >1 account, default on, `SettingsRepository.groupQueuesByAccount`); the removed accounts' queues collapse
      into "Удалённые аккаунты · N" at the bottom of the phone switcher (dimmed, tap → Delete-only menu; never in the
      car / pickers); account chips in "New queue" (also from "Add to queue…") and the radio picker's "New radio"
      (phone + car), default = the active queue's account; car pill marker; player queue header marker; Home "as
      <name>" caption; History chips with markers; Android Auto "Queues": a sub-folder per account when grouped,
      the account name in the subtitle when flat, removed accounts' queues never; account dialog without switching
      (each account removable, "log out of this account" only with one) and the startup picker removed. 519 unit
      tests; 13 new goldens `accounts_*`; `dialog_accounts` re-recorded (two-account dialog lost its switch). Files:
      `AccountMarks.kt` (new), `AccountManagementDialog.kt`, `AccountViewModel.kt`, `StartupAccountPickerDialog.kt`
      (deleted), `QueueSwitcherViewModel.kt`, `QueueSwitcherSheet.kt`, `QueueSwitcherHost.kt`, `QueueDialogs.kt`,
      `CarScreen.kt`, `MediaLibraryTree.kt`, `PlaybackController.kt` (account params on create / startRadio),
      `MainViewModel.kt`, `MainTabs.kt`, `MainScreen.kt`, `MainDialogs.kt`, `HomeScreen.kt`, `FullScreenPlayer.kt`,
      `PlayerQueueSheet.kt`, `HistoryViewModel.kt`, `HistoryScreen.kt`, `HiFiControls.kt` (filter pill `leading`),
      `SettingsRepository(Impl).kt`, `SettingsModel.kt`, `SettingsSheet.kt`, `SettingsViewModel.kt`, both
      `strings.xml` (startup-picker strings dropped); tests `AccountTagsTest` (new), `PlaybackControllerTest` (+2),
      `QueueScreenshotsTest`, `HistoryScreenshotsTest`, `ScreenshotTest`, `LayoutSafetyTest`, `FakeSettingsRepository`,
      `UserPlaylistsInstrumentedTest`; goldens `accounts_*.png`, `dialog_accounts.png`
- [x] Step 7 done — sign-out (2026-11-14, D62): `AccountSessionManager.signOut` (logout current / all / remove)
      keeps the accounts' queues incl. My Wave, drops their cached lists (own rows only — the shared ones hold the
      hidden queues' track details), network clients + cache dirs (`YandexClients.forget`) and the downloads no
      staying account likes (`SignOutPlan`); `PlaybackController.leaveAccount` replaces `resetForAccount`: stops and
      moves to the default (else most recent) account's first queue, My Wave first, or to none (`QueueManager.leave`);
      a re-sign-in (same uid) restores the queues with positions, no new My Wave. Removed accounts' names kept in a
      plain prefs file (`RemovedAccounts`, via `AuthRepository.removedAccountNames`) for the dimmed marker; a tap on a
      removed queue opens it (`RemovedQueueSheet`, canvas `ac-removed-open`), a hold opens the Delete-only menu (now
      with marker and name). Android Auto filter as `carQueues`. 529 unit tests; new golden `accounts_removed_open`,
      re-recorded step-6 goldens `accounts_menu_removed`, `accounts_switcher_removed` — files:
      `data/account/{AccountSessionManager,RemovedAccounts,SignOutPlan}.kt`, `data/network/YandexClients.kt`,
      `data/player/{PlaybackController,MediaLibraryTree}.kt`, `data/queue/QueueManager.kt`,
      `data/repository/AuthRepositoryImpl.kt`, `domain/repository/AuthRepository.kt`,
      `presentation/account/AccountMarks.kt`, `presentation/queues/{QueueSwitcherViewModel,QueueSwitcherSheet,
      QueueSwitcherHost,QueueDialogs}.kt`, `res/values{,-ru}/strings.xml`; tests `PlaybackControllerTest`,
      `QueueScreenshotsTest`, new `data/account/SignOutPlanTest`, `data/player/CarQueuesTest`; screenshots
      `accounts_removed_open`, `accounts_menu_removed`, `accounts_switcher_removed`
- [x] Step 8 done — on start, a restored queue of a no-longer-signed-in account gives way (`PlaybackController.leaveStrandedQueue`, same fallback as sign-out; 1 controller test); docs: `kb/decisions.md` (D22′, D56–D64; D22 marked revised), `docs/phase-2a-accounts.md` (implemented, "As built"), `docs/multi-source-player.md` (Phase 2a implemented; Phase 3 note), `kb/architecture/{as-is-data,as-is-playback}.md` (2026-11-14)

## Car skip stutter and announcements (branch `fix/car-skip-and-focus`, 2026-11-14)

From the 2026-11-14 ride: a tap on the next track's artwork stuttered and held the old track ~2 s; a navigator's
prompt paused the music (other players keep playing).
- [x] A track picked while one sounds loads first (user's decision): the current one plays on untouched until the
      pick is READY, then fades out in 0.5 s and the pick starts at once — no crossfade; latest tap wins; a failed
      load leaves the current track playing, goes back to it and shows the error (`PICK_FAILED`). Callers:
      `playTrack`, `playFrom`, `playEntry`, `playNowKeepQueue`, `playWaveFrom`, `playMyVibe`, `playRadioStation`
      (`pickOp`); Next: a warm standby fades as before, a cold one loads first. START line: `pick, tap→ready N ms`.
      4 controller tests. `PlayerRepositoryImpl` added to the detekt baseline as `LargeClass` (like
      `PlaybackController`) — split the engine's transition logic later
- [x] "Keep playing under announcements" (default on): a transient focus loss ducks instead of pausing unless a call
      is on (`AudioFocusPolicy`, 3 tests); a call that starts while ducked pauses (audio mode watched)
- [x] Journal: `UNDERRUN` (per player, rate-limited) and `TIMING` (skip → request → link → ready → sounding)
      lines (`EngineDiagnostics`)
- [x] "Keep both players running" (experimental, default off; wins over "keep audio streams"): the idle player loops
      the silent clip; the next track is queued behind it (warm-up held paused on arrival) — `SilentIdler`
- [ ] Ride with the APK: compare switch 4 on/off; read UNDERRUN / TIMING lines from the log
- [ ] Later: split `PlayerRepositoryImpl`'s transition logic out of the class (now in the detekt baseline)

## Phase 3b — Next-track buffering and its state (implemented 2026-11-17 in stage 1; roadmap: `docs/multi-source-player.md`)

User, 2026-11-14 — the original app silently disables Next when the next track can't load, and sometimes stays stuck
until the app is killed, even after the network is back.
- [x] Preload the next track early (a few seconds after the current one starts), not only 60 s / 15 s before its end
- [x] Next is never disabled; it shows the next track's state: ready / loading (spinner ring) / failed (greyed);
      a tap retries at once (load first, then switch)
- [x] Failed next-track loads retry by themselves when the link comes back (`LINK NONE → GOOD`)
- [x] Progress bar shows the buffered part (like YouTube Music)
- [x] Design on the canvas first: phone player, car screen, the next-track artwork in the car

## Small fixes before v0.3.0 (branch `fix/journal-buildno-radio-menu`, 2026-11-15)

From the 2026-11-15 head-unit ride (b71) and the user's notes.
- [x] Journal: `SETTINGS` lines — everything stored at process start, then each change "key: old → new"
      (`SettingsRepository.allSettings` diffed in `DiagnosticLog`, one place for every setting); `FORMAT` line after
      each TIMING (codec, rate, channels, bitrate of the track that started) and `OUTPUT` line on every AudioTrack
      (re)configuration (player, encoding, rate, channels, buffer, offload/tunneling) — to match the new-track stutter
      with output changes and the auto-pauses with FLAC on a slow link
- [x] Debug build numbers from a local counter (`dist/.build-number`, `-Pyamp.buildNumber`), one per build;
      release builds keep the commit count
- [x] Skip to a warm next track whose link left the cache: no link fetch, starts at once (the "skip→request
      1257 ms" in the ride log was that fetch, heard as silence after the fade); TIMING now counts from the
      controller's request, so a link lookup shows as "→link"
- [x] "Запустить радио" in the track menu on every queue, next to "Добавить в очередь" (D65, revises D54)
- [ ] Ride: read FORMAT / OUTPUT / SETTINGS lines against the stutters and auto-pauses

## Playback stability (`docs/playback-stability.md`, plan 2026-11-16, branch `fix/playback-stability`)

From the 2026-11-16 rides: every auto-pause was Poweramp Equalizer's "Fade" (focus grab when an equalized session closes).
Workaround now: Poweramp Equalizer → Known Players → YAMP → "Fade" off.
- [x] F2 stale crossfade job (play icon while playing, silence after Prev)
- [x] F9 one stable audio session id, one OPEN/CLOSE pair; retire "Separate audio sessions" (D70)
- F2+F9 done — files: `data/repository/PlayerRepositoryImpl.kt`, `data/player/SystemEqualizer.kt`,
  `data/player/MusicService.kt`, `data/player/EngineDiagnostics.kt`, `di/PlayerModule.kt`,
  `domain/repository/SettingsRepository.kt`, `data/repository/SettingsRepositoryImpl.kt`,
  `presentation/main/{MainTabs,SettingsViewModel}.kt`, `presentation/main/components/{SettingsModel,SettingsSheet}.kt`,
  `res/values/strings.xml`; tests `PlayerEngineTest` (new, 3), `EqualizerAnnounceTest` (rewritten, 5),
  `fakes/{ScriptedExoPlayer.kt,NoErrorExoPlayer.java}` (new), `FakeSettingsRepository`; goldens `settings_playback`,
  `settings_playback_narrow`; `gradle/libs.versions.toml` + `app/build.gradle.kts` (`media3-test-utils`, tests only)
- F7+F6+F8 done — files: new `data/diagnostics/FileShare.kt`, `data/diagnostics/DiagnosticLog.kt`,
  `presentation/crash/CrashLogViewModel.kt`, `presentation/main/SettingsViewModel.kt`, `data/player/PlaybackJournal.kt`,
  `data/player/PlaybackController.kt`, `data/player/MusicService.kt`, `presentation/main/components/PlaybackJournalDialog.kt`;
  tests `FileShareTest` (4), `PlaybackControllerTest` (+1); Diag: new `LogTail.kt`, `DiagCommands.kt`, `AdbSession.kt`,
  tests `LogTailTest` (4), `DiagCommandsTest` (+1); `kb/decisions.md` (D45 retired by D70); this doc's As built.
- [x] F7 journal / crash log shared as files (LocalSend); F6 journal taps + mixer rate; F8 Diag logcat buffers
- [x] F1 resume after a focus loss when nothing else plays (D66)
- [x] F3 keep loading until the user stops (D67)
- F1+F3 done — files: new `data/player/FocusResumer.kt`, `data/player/LoadProgress.kt`; `data/player/AudioFocusPolicy.kt`,
  `data/player/MusicService.kt`, `data/player/PlaybackController.kt`, `data/player/PlaybackQueue.kt`,
  `data/repository/PlayerRepositoryImpl.kt`, `domain/repository/PlayerRepository.kt`, `domain/repository/SettingsRepository.kt`,
  `data/repository/SettingsRepositoryImpl.kt`, `presentation/main/SettingsViewModel.kt`, `presentation/main/MainTabs.kt`,
  `presentation/main/components/{SettingsModel,SettingsSheet,PlaybackJournalDialog}.kt`, both `strings.xml`; tests
  new `FocusResumerTest` (6), `LoadProgressTest` (2), `AudioFocusPolicyTest` (+5), `RoadLogicTest` (+1),
  `PlayerEngineTest` (+2), `PlaybackControllerTest` (+4), fakes `FakePlayerRepository`, `FakeSettingsRepository`,
  `ScriptedExoPlayer`; goldens `settings_playback`, `settings_playback_narrow` re-recorded (new switch row).
- [x] F4 fewer AudioTrack rebuilds: one player for normal playback, second only for crossfade/pick (D68)
- F4 done — files: `data/repository/PlayerRepositoryImpl.kt`, `data/repository/SilentIdler.kt`,
  `data/player/EngineDiagnostics.kt`, `data/player/PlaybackController.kt`, `data/player/SleepTimer.kt`; tests
  `PlayerEngineTest` (+5), fake `ScriptedExoPlayer` (playlist, gapless move, AudioTracks opened); 573 unit tests.
- [x] F5 fixed output format at the mixer's rate, behind a switch (D69)
- F5 done — files: new `data/player/FixedOutputRate.kt`; `di/PlayerModule.kt`, `data/player/MusicService.kt`,
  `domain/repository/SettingsRepository.kt`, `data/repository/SettingsRepositoryImpl.kt`,
  `presentation/main/{MainTabs,SettingsViewModel}.kt`, `presentation/main/components/{SettingsModel,SettingsSheet}.kt`,
  both `strings.xml`; tests new `FixedOutputRateTest` (5), `FakeSettingsRepository`; goldens `settings_playback`,
  `settings_playback_narrow` re-recorded (new switch row); 578 unit tests. Debug build b82.
- b84 fixes after the b83 ride done — files: `data/repository/PlayerRepositoryImpl.kt` (no overlap on skip/pick,
  150 ms skip fade, handover timeout loads the track), `data/player/PlaybackBuffering.kt` (`giveUpOnLink`, timeouts),
  `data/player/EncrawDataSourceFactory.kt` (4 s / 6 s), `data/player/PlaybackController.kt` (`RETRY new link`),
  `data/player/SystemEqualizer.kt` + Settings (Poweramp hint), `AndroidManifest.xml`, both `strings.xml`; Diag
  `EffectSummary.kt`, `DiagCommands.kt`, `DiagViewModel.kt`; tests `PlayerEngineTest` (+4), new `PlaybackBufferingTest`
  (4), Diag `EffectSummaryTest` (4) + fixture; golden new `settings_playback_poweramp`. Debug build b84.
- [ ] User check: one ride with Poweramp Equalizer disabled

## Main screen + new player screen (user, 2026-11-16 / 2026-11-17; canvas first)

Problem: the player and the car screen are two similar screens with inconsistent navigation; the car screen's
enter/exit behaviour feels like it wants to be the main page. A single merged screen doesn't work (the tab bar and
the next-track cards can't both fit well), so: **two screens**.

**Main screen** — unchanged, two small changes:
- [ ] Queue dropdown at the top.
- [ ] A tap around the Play button or on the track title below it opens the player; the "Player" label goes.
- The mini player on the other tabs stays as it is (a tap opens the main screen).

**Player screen** — new:
- [ ] Next tracks setting: "1" = today's player layout as is (tap on the next track opens the queue); "3" = the card
      row of the main screen / car mode (current big + three smaller). Landscape: the next track(s) under the cover.
- [ ] Progress bar with times.
- [ ] Swipe/drag up opens the queue; swipe down closes the open queue first, otherwise closes the player (as today).
- [ ] Layout chosen in Settings (Phone / Car), no automatic logic. Car: bigger play controls and the ring around Play
      (the main screen's ring, as used in car mode).
- [ ] The car screen's extra controls (radio tiles, wave-by-track pill, tune panel) stay: placed per the
      left/right-hand setting; option for wide screens: bottom (as now) or a vertical panel at the side.
- [ ] Portrait layout unchanged except the next-track cards (per the setting).
- [ ] Maybe drop the like/dislike buttons: double tap on the cover = like, long press = dislike, with a clear
      visual confirmation — try both on the canvas.
- [ ] The separate "In car" screen is dropped (its ideas live in the player's Car layout); its tab button becomes
      "Player" / «Плеер» and opens the player.

- [x] Built 2026-11-18 (uncommitted, branch `feature/album-artist-menu`): Settings → Player page (Layout Phone/Car,
      Next tracks 1/3, Like by gestures); main screen queue dropdown + tune right-aligned, title tap opens the player,
      "Player" link gone; tab «В авто» → «Плеер» (opens the player, starts the active queue if nothing plays; start page
      "Car" = open the player); `CarScreen`/`CarTimeline` retired, its parts moved to `player/WidePlayer.kt`
      (phone-landscape and Car layouts share it); portrait 3-card strip, likes by gestures (double tap = like + burst,
      hold = dislike with Undo snackbar, buttons hidden), swipe up from the bottom edge / next track(s) opens the queue.
      Deviations: Car layout standing = the phone portrait player (approved 2026-11-18). Fixed since: the queue
      follows the finger, a swipe down at the top of the list closes it, Undo of a dislike restores the track.
- [x] ⋮ at the end of the wide layouts' top row (queue · tune · search · ⋮; approved 2026-11-18, b109): sleep timer
      (second level), Add to playlist…, Quality (opens the playing account's quality sheet), and with likes by gestures
      Like / Dislike (with Undo) as a fallback; car-sized rows. `wideMenuItems` + test; goldens `player_land_more_menu`,
      `player_car_more_menu`.

## Home-screen shortcut per queue (idea, 2026-11-16)

- [ ] "Add to home screen" on a queue (switcher ⋮): a pinned launcher shortcut (`ShortcutManager.requestPinShortcut`,
      intent with the queue id) that opens YAMP on that queue. Maybe also dynamic shortcuts (long-press on the app
      icon) for the most used queues. Open: open only, or open and start playing; a deleted / removed-account queue
      (shortcut disabled with a message); the queue's icon (cover / account marker); whether the head unit's launcher
      supports pinned shortcuts at all — check on the unit first.

## Phase 3 + 3b — stage 1 (`docs/phase-3-providers.md`, branch `feature/phase-3-providers`)

- [x] Step 1 done — `:core:domain` (pure Kotlin/JVM, `android/core/domain`): moved `domain/queue/*` (MediaRef, TrackRef,
      PlayQueue, QueuePolicy, QueueStore, RadioSeed) and `domain/account/ProviderAccount.kt` (Provider, AccountId,
      ProviderAccount); package names unchanged, so no import churn. `UserAccount.accountId()/toProviderAccount()` stay
      in `:app` (`domain/account/UserAccountMappings.kt`, same package). New: `MediaRef.providerType`/`instance`
      (`jellyfin@<serverId>`, D78), `provider/Outcome.kt` (Outcome, ProviderError, D73), `provider/Page.kt`.
      `QueuePolicyTest` moved, `MediaRefTest` new. Gradle: `kotlin-jvm` plugin alias, `include(":core:domain")`,
      `:app` depends on it; CI/release run `:core:domain:test`, detekt input adds `core/domain/src/main/kotlin`.
- [x] Step 2 done — `ArchitectureTest`: `:core:domain` stays pure (build file + sources), the app's remaining
      `domain/` leaks only the two known framework types, Yandex DTO users = shrink-only allow-list
      (`app/src/test/resources/architecture/yandex-dto-users.txt`, 47 files).
- [x] Step 3 done — neutral models in `:core:domain` (`domain/media/Media.kt`: `Track`, `Artist`, `Album`,
      `Playlist`, `ArtistBrief`, `AlbumBrief`, `Cover`, `Loudness`); Yandex mappers `data/provider/yandex/YandexMappers.kt`
      (`YandexCover` from the `%%` template; playlist ref `yandex:playlist:<owner>:<kind>`; FM stations get `radio:` refs);
      `YandexMappersTest` (7) on recorded-shape JSON in `app/src/test/resources/yandex/`. `MusicProvider` stays in step 10.
- [x] Step 4 done — `StreamResolver`/`ResolvedStream`/`StreamQuality`/`Decryption`/`StreamPrefs` in `:core:domain`
      (`domain/provider/StreamResolver.kt`); `YandexStreamResolver` (copy → fresh link → network, encraw marker) bound
      in `ResolverModule`; the controller resolves every start and standby and hands the engine a `ResolvedStream`;
      `PlayerRepositoryImpl` has no resolver (null stream = the warm standby, else `EMPTY_URL`), reports
      `staleStreamEvent`, the controller invalidates. Tests: `YandexStreamResolverTest` (6), 2 engine + 2 controller
      tests; `LayoutSafetyTest` check updated. 599 app tests green, goldens unchanged.
- [x] Step 6 done — early preload waits for the F4 calm window; `NextTrack` state (`PlaybackController.nextTrack`),
      `NEXT` journal lines, failed → refetch with a fresh link on `LINK GOOD`. Files: `PlaybackController.kt`,
      `PlayerRepository(Impl).kt` (`warmTrackId`, `standbyFailedEvent`, `settled`), `core/domain/.../player/NextTrack.kt`,
      `PlaybackJournal(Dialog).kt` (NEXT, CACHE); tests in `PlaybackControllerTest` (+4), fakes (2026-11-17)
- [x] Step 7 done — on-disk stream cache (D86): `StreamCache.kt`, `NextTrackCacher.kt`, `EncrawDataSourceFactory.kt`,
      `YandexStreamResolver.kt` (cache key, offline fallback), `ResolvedStream.cacheKey`, `PlayerModule.kt`, settings
      (`streamCacheMb`, rows, EN/RU), goldens `settings_playback`, `_narrow`, `_poweramp`; `StreamCacheTest` (7),
      `YandexStreamResolverTest` (+2) — 612 unit tests green (2026-11-17)
- [x] Step 8 done — Next never disabled (D81): `presentation/player/NextState.kt` (`NextLook`, `NextStateDecor`,
      `NextCoverOverlay`, `LocalPlaybackAux`), phone `PlayerControls`, `MiniPlayer`, car knob and first next-track
      card in `CarScreen` (whole knob / card = tap target); `PlaybackController.dropFailedNextLink` (Next or a pick
      on a failed next track drops its link → load-first retry); strings `next_state_*` EN/RU; `MainScreen`
      provides the state; Android Auto: Next stays enabled, no custom state (template)
- [x] Step 9 done — buffered part: `PlayerRepository.bufferedProgress`, `PlaybackController.bufferedProgress`
      (`Long.MAX_VALUE` = whole file on disk: a copy, a local file, or the next track written to disk),
      `HiFiTimeline(buffered)` (phone + car), the mini player's hairline; 2 controller tests; 7 new goldens
      `next_*` (no existing golden changed) — 621 unit tests green (2026-11-17)
- [x] Step 10 done — `MusicProvider` + `Library`/`Search`/`Catalog` (`:core:domain` `domain/provider/MusicProvider.kt`),
      `StreamResolver` without the account parameter (one resolver per account), `ProviderRegistry` +
      `StreamResolvers` (`data/provider/ProviderRegistry.kt`), `YandexProvider` (`data/provider/yandex/YandexProvider.kt`,
      errors → `ProviderError`), `YandexStreamResolver.Factory`, networking base `@ProviderBaseClient`
      (`di/NetworkModule.kt`, `YandexClients` built on it); per-account reads in `SearchRepository`, `CatalogRepository`,
      `UserPlaylistsRepository` (`playlistsFor`, `tracks(…, account)`), `LibraryRepository.likedTracksOf`; controller
      resolves via `streams.forAccount(…)`; tests `ProviderRegistryTest` (4), resolver/controller/LayoutSafety tests
      adapted — 625 unit tests green, no golden changed, DTO allow-list 47 (2026-11-17)
- [ ] Step 11 — deferred until after step 15 (accepted 2026-11-17; see "As built" in `docs/phase-3-providers.md`): the
      controller can't move onto `Track` while the screens still read its DTO flows
- [x] Step 12 done — player UI on `Track`: `FullScreenPlayer` (+ `QueueSplit`/`queueSplitOf`), `PlayerParts`,
      `PlayerQueueSheet`, `MiniPlayer`, `CarScreen`, `NowPlayingWidget`, `prefetchCovers` (`utils/CoverUrlHelper.kt`);
      edge mapping in `MainScreen` / `MainTabs` (car taps looked up again among the shown service tracks) and in the
      controller's `prefetchCovers` call; `presentation/common/TrackUi.kt` (`id`, `isRadio`, `coverUri`, `artistNames`);
      `MediaRef.PROVIDER_RADIO` in `:core:domain`; tests mapped (`toTrack()`) in the screenshot / HiFiLogic /
      instrumented tests (2026-11-17)
- [x] Step 13 done — the playlist entity on `Playlist`: `PlaylistsViewModel` exposes `List<Playlist>` and takes
      `Playlist` in every edit (the service's entry found again by ref; revision kept privately), `OpenedPlaylist.playlist`,
      `LibraryScreen` (`PlaylistsPage`), `PlaylistScreen` (`PlaylistCover`), `TrackContextMenu` (picker,
      `LocalTrackMenuPlaylist`), `MainDialogs`; track rows stay on the service track until the track menu moves (step 15);
      `TrackUiTest` (4); 629 unit tests green, no golden changed, DTO allow-list 47 → 40 (2026-11-17)
- [x] Step 14 done — Search on `Track` / `Artist` / `Album`: `SearchScreen`, `SearchParts` (results, hits, trends,
      artists, albums, best result, `availableFilters`); `SearchTab` maps at the edge (`toTrack`, `toArtist`, `toAlbum`)
      (2026-11-17)
- [x] Step 15 done — track rows and the track menu on `Track`: `TrackItem`, `TrackContextMenu` (`TrackMenuLauncher`),
      `MainDialogs` (`TrackMenuRequest`), `LibraryScreen` (+ `CoverCard`), `PlaylistScreen` / `OpenedPlaylist.tracks`,
      `HistoryScreen` / `HistoryRow`, `HomeScreen` (`WaveShelfState`, `waveShelfOf`; `queueShelfOf` / `shelfEntryOf` moved
      to `MainTabs`), `CatalogScreens` / `CatalogPageState` (`ArtistPage`, `Album`), `QueueSwitcherRequest.AddTo` /
      `StartRadio`, `AddToQueueMenu`. Bridge back to the service objects: `data/provider/yandex/ServiceTracks.kt` (mapped
      models remembered by ref, LRU 5000; rebuilt from the model on a miss); `Track` entry points on `MainViewModel`,
      `PlaylistsViewModel`, `CatalogViewModel`, `PlaybackController` (`addToQueue`, `createQueueWith`, `startRadioFrom`).
      629 unit tests green, no golden changed, DTO allow-list 40 → 27 (2026-11-17)
- [x] Step 11a done — controller state and entry points on `Track` (service values kept as `serviceTrack`/
      `serviceQueue`/`serviceWaveSeed`); `TrackReactions`, `UserPlaylistsRepository.addTrack` take `Track`; screens and
      view models hand back `Track` only (`ServiceTracks` now data-layer only, capacity 20000); `ActiveQueue.isShownAs`.
      Files: `PlaybackController`, `TrackReactions`, `UserPlaylistsRepository`, `QueueManager`, `ServiceTracks`,
      `MusicService`, `NowPlayingWidget(Updater)`, `MainViewModel`, `SearchViewModel`, `PlaylistsViewModel`,
      `CatalogViewModel`, `MainScreen`, `MainTabs`; tests (controller → `serviceTrack/serviceQueue`, `HiFiLogicTest`,
      `InteractionLogicTest`, 2 instrumented). DTO allow-list 27 → 23; no golden changed (2026-11-17)
- [x] Step 11b/11c done — the queue (`ActiveQueue.tracks`, `QueueManager`, `QueueTrackBridge`), the radio source's
      output, History, the session buttons, the reactions and the controller's own state are on `Track`
      (`currentTrack` / `currentPlaylist` / `waveSeed` only — the `service*` duplicates are gone); **`ServiceTracks`
      removed**, replaced by the stateless reverse mapper `Track.toYandexTrack()` used only where a Yandex repository
      or the rotor still takes the service shape (likes/dislikes, playlist add, the reserve's upcoming list, the wave
      station switch, the `tracks` table rows). Still on the service shape inside the provider layer (step 17): the
      wave's own list/preview (`WaveQueue`), the reserve (`RoadReserve`/`ReserveKeeper`), the library/playlist
      repositories and caches, `PlaybackStateStore`, `MediaLibraryTree`'s item building. Files: `YandexMappers`
      (+`toYandexTrack`), `ServiceTracks` (deleted), `TrackFields` (new), `QueueManager`, `QueueTrackBridge`,
      `LegacySessionImport`, `RotorStationSource`, `ListeningHistory`, `PlaybackController`, `TrackReactions`,
      `SessionButtons`, `MediaLibraryTree`, `MusicService`, `NowPlayingWidgetUpdater`, `UserPlaylistsRepository`,
      `MainTabs`, `MainViewModel`, `HistoryViewModel`; tests (`YandexMappersTest` +2, controller, queue, history,
      HiFi, interaction, queue screenshots). DTO allow-list 23 → 19; no golden changed; controller test 5/5 (2026-11-17)
- [x] Step 16 done — composition and FM radio: `ProviderComposition` (`data/provider`) gives the composed providers in
      the stable order (the account context's Yandex provider, then other services' accounts — D75, several Yandex
      accounts never merge), `libraries()`, and a merged `search()` (`ComposedSearch`, `Sourced<T>`, badges only with
      ≥ 2 providers); Search runs through it (`SearchViewModel`, `MainTabs`). FM radio is the built-in account-less
      `FmRadio` source (`data/provider/radio`, D76): stations, a station as a `Track`, its stream; the controller and
      `MediaLibraryTree` use it. Home and Library screens are not rewired yet (one provider: nothing to compose — the
      Jellyfin sections come with P4). Tests: `FmRadioTest` (2), `ProviderRegistryTest` (+2 composition). (2026-11-17)
- [x] Step 17 done — cleanup: **DTO allow-list empty** (19 → 0). The Yandex-only code moved under
      `data/provider/yandex/` (`network/YandexMusicApi`, `network/StreamVariants`, `repository/{Catalog,Library,
      Search,UserPlaylists}Repository`, `cache/{PlaylistCacheManager,PlaybackStateStore}`, `queue/{RotorStations,
      RotorStationSource,QueueTrackBridge,LegacySessionImport}`, `wave/WaveQueue`, `reserve/{RoadReserve,
      ReserveKeeper}`) — names kept (A3), stored formats unchanged (D72); `CatalogViewModel` reads `ArtistParts`,
      `PlaylistsViewModel` edits by ref through `YandexPlaylists`, `MediaLibraryTree` builds items from `Track`;
      `TrackUrlResolver` internal; `PlayerRepository` has no ExoPlayer types (the service reads `SessionPlayers`).
      No golden changed; 635 unit tests; controller test 5/5 (2026-11-17)
- [x] **Stage 1 (P3 + P3b) complete** — checkpoint build `YAMP-b85` + Diag; next: rides on the unit with Diag, fixes,
      possibly v0.4.0; then Stage 2 (P4 Jellyfin)
## Stage 1 checkpoint findings (b85, for the next build)

- [x] After a skip back, Next shows "loading" although the next track (the one just left) is on disk or buffered —
      on every next-track change check the stream cache first: fully cached → READY at once; partly → LOADING and
      the preload only fills the rest (don't wait for the calm window / 20 s buffer in that case).
- [ ] Observe: YouTube Music started by itself while YAMP played (2026-11-17 08:28, phone SM-F946B); Android's
      AudioHardening ignored its focus request, so both played — YAMP never lost focus. Idea if it recurs: pause or
      duck when another app's music plays while YAMP plays (needs care: `isMusicActive()` doesn't say whose).

## Phase 4 — Jellyfin, stage 2 (`docs/phase-3-providers.md`, branch `feature/phase-4-jellyfin`)

- [x] Step 18 done — `scripts/jellyfin-dev/` (`docker-compose.yml`, `fetch-test-music.sh`, `setup.sh`, `README.md`),
      `.gitignore` (`scripts/jellyfin-dev/data/`); Jellyfin 12.4.0 running locally with 2 albums / 6 tracks
      (CC BY 4.0, ~55 MB), Quick Connect on, emulator URL `http://10.0.2.2:8096` (2026-11-17)
- [x] Step 19 done — `android/provider/jellyfin/` (`JellyfinApi`, `JellyfinAuth`, `JellyfinDtos`, `JellyfinMappers`,
      tests + fixtures), `:core:domain` `Provider.JELLYFIN` / `AccountId.jellyfin` + `AccountIdTest`,
      `settings.gradle.kts`, `libs.versions.toml` (`okhttp`), `ArchitectureTest`, `ci.yml` / `release.yml`
      (2026-11-17)
- [x] Step 20 — canvas: Jellyfin sign-in, provider chooser, sections and badges (approved 2026-11-17)
- [x] Step 21 done — sign-in + accounts: `provider/jellyfin` `JellyfinSignIn` (+ Quick Connect API, `JellyfinSignInTest`);
      `app` `data/provider/jellyfin/{JellyfinAccount,JellyfinAccountStore,JellyfinClients,JellyfinAccountSession}`,
      `data/account/SignedInAccounts`, `AccountContext`, `QueueManager.defaultQueue`, `PlaybackController`,
      `MediaLibraryTree`, `di/JellyfinModule`, `presentation/account/{JellyfinSignIn,JellyfinSignInViewModel,
      AccountMarks,AccountManagementDialog,AccountViewModel}`, `QueueSwitcherViewModel`, `HistoryViewModel`,
      `MainViewModel`, `MainDialogs`, `MainScreen`, strings EN/RU, debug `network_security_config.xml`,
      `app/build.gradle.kts`; tests `JellyfinAccountStoreTest`, `QueueManagerTest` (+1), `JellyfinScreenshotsTest`
      (11 goldens) (2026-11-17)
- [x] Step 22 done — library: `:core:domain` `Genre`, `Browse` (paged albums / artists / genres / playlists / genre albums,
      counts), `Catalog.playlist`, `MusicProvider.reachable`; `provider/jellyfin` `JellyfinLibrary` (+ `/Artists/AlbumArtists`,
      `/MusicGenres`, `/Playlists/{id}/Items`, `Users/{id}/Items/{id}`, `JellyfinListCache`, errors → `ProviderError`,
      `JellyfinLibraryTest`, `FakeJellyfinApi`, fixtures `artists/genres/playlists/playlist-items.json`); `:app`
      `JellyfinProvider`, `JellyfinCacheDatabase` + `JellyfinDatabases` (per account, deleted on sign-out, schema 1),
      `ProviderRegistry` Jellyfin providers (`provider`, `jellyfin()`, `forget`), Library rows (`JellyfinLibraryViewModel`),
      catalog stack: `CatalogPage.Browse` / `.Playlist` + account, `BrowsePageScreen`, offline banner (retries every 15 s),
      `TrackItem(showLike)`; `JellyfinLibraryScreenshotsTest` (6 new goldens), `JellyfinDatabasesTest`, registry test;
      `scripts/jellyfin-dev/setup.sh` adds a test playlist. Smoke on the AVD (b92). (2026-11-17)
- [x] Step 23 done — streaming and covers: `provider/jellyfin` `JellyfinStreamResolver` (+ test), `JellyfinCover`
      `{px}` template; `:app` `StreamHeaders` (+ test), `EncrawDataSourceFactory.httpFactory`, engine/`NextTrackCacher`
      register headers, `JellyfinImageAuth` + `YandexMusicApp` image loader, `ProviderRegistry` (+ test deps),
      `JellyfinProvider`, `PlaybackController` (Jellyfin playback account, `refOf`, `playInAccount`, D84 guard),
      `QueueManager.entryOf` / `QueueTrackBridge.recall` keep the ref, `CatalogViewModel.play`, `CoverUrlHelper`;
      tests: controller (+1), `InteractionLogicTest`, `JellyfinMappersTest`. Downloads of Jellyfin tracks not done.
      Smoke on the AVD (b93) passed (2026-11-17)
- [x] Steps 24–25 done — composition and Jellyfin tracks in queues / History: `provider/jellyfin` search
      (`JellyfinApi` `SearchTerm`, `JellyfinLibrary.search`, test +2); `ProviderComposition` adds Jellyfin; `:core:domain`
      `QueueFit` (+ test); `ProviderBadge`, `TrackItem` badge, `SearchScreen`/`SearchParts`/`MainTabs` (badges, account-
      aware opens); `QueueSwitcherHost`/`Sheet`/`ViewModel` (D84 picker); `PlaybackController` D84 guards and merged-list
      taps (+2 tests); `AccountContext.accountOf`; `TrackContextMenu`/`MainDialogs`, `CatalogScreens` (menu on Jellyfin
      rows), `HistoryScreen`, `ListeningHistory` (own ref + account, test +1), `CarScreen`, `MainViewModel` (no Jellyfin
      likes); golden `jellyfin_search_merged`. Home Jellyfin sections and merged Library rows not done (see As built).
      Smoke on the AVD (b96) passed (2026-11-17)
- [x] Fix — Home's like / dislike hidden for Jellyfin tracks (`Track.supportsLikes`, also the car's radio chip);
      `TrackUiTest` (+1); no golden changed (2026-11-17)
- [x] Step 26 done — play reporting (D87): `provider/jellyfin` `PlaybackReportDto`, `JellyfinPlayReporter` (+ test, 2),
      `Sessions/Playing*` endpoints; `:app` `PlayReportPlanner` (+ test, 4), `JellyfinPlayReporting` (started in
      `YandexMusicApp`), `PlaybackController.playingAccount`, journal kind `REPORT`. Smoke on the AVD with the dev
      server: now playing, position, paused (needs `EventName`), play count 1 (2026-11-17)
- [x] Step 27 done (docs only, no release) — D66–D87 in `kb/decisions.md`; `docs/multi-source-player.md` Phase 3 / 3b / 4
      implemented; `kb/architecture/as-is-data.md`, `as-is-playback.md`; draft notes `tasks/release-notes-v0.4.0-v0.5.0.md`
- [ ] Release v0.4.0 (stage 1) after the b85 rides, then v0.5.0 — on the user's word

### Deferred from Phase 4
- [ ] Jellyfin sections on Home (needs a scrolling Home; folders → P4b, Instant Mix → P6)
- [ ] Library rows merged with provider badges (today: a section per Jellyfin account)
- [ ] Jellyfin downloads, and offline play of a fully cached Jellyfin track
- [ ] Jellyfin favourites / likes (Phase 5)
- [ ] Android Auto browse tree with Jellyfin accounts — verify on a device


## Album / artist actions (user, 2026-11-17; canvas first)

- [x] Menu for an album or artist: Play now (replace queue) · Play next · Add to end · Add to queue… (picker of
      the account's queues + "New queue") · Start radio (Yandex `album:`/`artist:` stations; hidden for Jellyfin
      until P6). From a long press wherever albums/artists are listed (search, Library, catalog, artist page) and a
      ⋮ button in the album and artist page headers. (2026-11-17, b103)
- [ ] Car: long press on an album tile — the car screen lists no albums today (only sources and the Next row), so
      there is nothing to hold; comes with car browsing.
- [x] Artist "add" asks first, with a choice: all albums in release order / popular tracks; singles and
      compilations as an option; counts shown (e.g. «12 альбомов · 147 треков»).
- [x] Canvas boards approved before code (`9e15022`).
- [x] Jellyfin only: the first tab reads "Home" / «Главная»; the Settings card shows the Jellyfin account.
- [ ] Seen in the smoke test, not fixed: with Jellyfin only, the Search page's start (charts) stays "loading" —
      it is Yandex's discovery list.


## First start with a provider choice; Jellyfin only (2026-11-17, branch `feature/album-artist-menu`)

- [x] Welcome chooser (Yandex Music / Jellyfin) instead of forced Yandex sign-in; the app opens on any account
- [x] Jellyfin only: its default queue active at start/sign-in; provider-wide sign-out fallback; Yandex-only rows,
      tiles, Android Auto nodes and session like/dislike hidden; empty-queue card on Home
- [x] Next ready from disk after a skip back (stream cache asked first)
- [ ] Open: first tab label «Волна» with Jellyfin only; Settings profile card without Yandex; search smoke with
      Jellyfin only


## Smooth seek / play / pause, tied to the crossfade length (user, 2026-11-17)

- [x] One rule, from the crossfade setting (interval X); crossfade off → no fades at all:
      seek = fade-out X/3 → jump → rise 2X/3 (3 s: 1 s + 2 s); play/resume = rise X/2; pause = fade-out X/2.
      The user shortens X if it feels slow.
- [x] Exceptions (keep): skip/pick fade 150 ms and load-first; a focus loss or a call pauses immediately;
      resume after a focus loss uses the play rise.
- [x] Done 2026-11-17 (b105): engine `pauseSmoothly` / `resumeSmoothly` / `seekSmoothly` / `riseOnNextStart`
      (`PlayerRepositoryImpl`), controller user/media-button pause smooth, focus loss / "no data" plain; journal
      `FADE pause|play|seek N ms`; 7 engine + 2 controller tests.
- [ ] Note: with Poweramp DVC on, per-player volume (and so these fades) don't apply — same as the crossfade.
- [x] Bug (found on b105): the full player shows like/dislike for a Jellyfin track — hide as on Home/car (`Track.supportsLikes`).
- [x] Search with Jellyfin only: the empty page stays on "loading" (starter charts are Yandex-only) — show a neutral empty state.

## Transition style: Crossfade or Fade then rise (user, 2026-11-17)

- [x] Setting "Transition": "Crossfade" (simultaneous, equal-power — today) / "Fade then rise": the current track
      fades out over X/2 (dB curve, like pause), the next starts right after and rises over X/2 (like play); total = X.
      No overlap → works with Poweramp DVC on and decodes one track at a time (lighter CPU on the unit); the next
      track is preloaded and can play gaplessly on the same player. Skips, picks and seek unchanged.
- [x] After the jump-to-start bug (2026-11-17 ride) — done in b108.

## Jellyfin jump to the start / unseekable transcode (2026-11-17 ride; b108)

- [x] Cause: a transcode (`/Audio/{id}/universal` when the server converts) is sent chunked, no Content-Length,
      `Accept-Ranges: none`; a file played as it is comes with its length and byte ranges. Media3 can't seek in the
      former (a seek lands at 0), restarts it from 0 when its connection breaks mid-way, and gives it no duration.
- [x] Journal: FORMAT says seekable / length unknown; `JUMP` for an unasked jump to the start; CACHE lines for the
      playing track's write, a wait for it and a reopen from disk.
- [x] The playing track's whole file goes to disk when its stream is unseekable (`NextTrackFetch.fetchPlaying`); a seek
      or a recovery waits for it (loading, bar held, max 60 s) and reopens from disk at the position.
- [x] End-of-track transition timed by the catalog's length when the stream has none (`ResolvedStream.durationMs`).
- [x] Transcodes asked at a constant bitrate (`EnableAudioVbrEncoding=false`).
- [ ] Ride check: a weak link mid-track with "Совместимое" on — no jump to 0; seeks land.


## Quality per account, unmetered vs metered (user, 2026-11-17)

- [x] Settings → Accounts → account: two choices — quality on unmetered networks / on metered networks (Android's
      metered flag: mobile data, or a Wi-Fi the user marks as metered — e.g. the car's 4G travel router on the head
      unit). Yandex: Lossless (FLAC) / Compatible (MP3). Jellyfin: Original (seekable, no server transcoding) /
      Converted MP3 320. New accounts start from the current global setting; the global Playback row goes (or
      becomes the default for new accounts). A network change applies from the next track.
      Done 2026-11-18: `AccountQualityStore` (settings keys `quality|<account>|metered|unmetered`) + `NetworkMonitor.meteredFlow`;
      `SoundMode.compatibleFor/isCompatible(account)` (own choice, else the global one — the migration); link cache key
      carries the quality; journal `QUALITY` line on each change; the account sheet `AccountQualitySheet` (bigger
      switches on a ≥ 900 dp screen); the Playback quality row replaced by a note.
- [ ] Fallback if the unit's Android hides "metered" for Wi-Fi: a per-network override in YAMP (later, only if needed).

## Add to playlist for both providers (user, 2026-11-18)

- [x] Jellyfin playlists: `POST /Playlists`, `POST /Playlists/{id}/Items` (`JellyfinLibrary.addToPlaylist/createPlaylist`,
      `PlaylistEdit` capability); the track menu's picker lists the track's own account's playlists (D84), shown for
      Jellyfin too. Album/artist menu: "Add to playlist…" (artist via the add dialog), one change for Yandex
      (`PlaylistDiff.insertAll`). Verified on the dev server (album → new playlist, track → existing one).

## Playback settings cleanup (user, 2026-11-18)

- [x] Remove "Keep both players running" and "Keep audio streams" (switches, prefs, code paths: SilentIdler park/loop).
- [x] Remove the switches "Announce to equalizer" (always announce: one OPEN/CLOSE per session, D70) and "Fixed output
      format" (always on, mixer rate).
- [x] Keep "Resume when nothing plays" and "Keep playing under announcements" as settings, both **off by default**
      (existing users: keep their stored value).
- [x] Poweramp hint text: Fade off, Без DVC on (Keep Equalizer Session / Multisession on are fine).
- [x] Document the Poweramp Equalizer setup (only two changes from its defaults): Known Players → YAMP → Затухание
      (Fade) **off** (stops the focus grab that paused YAMP on head units), Без DVC **on** (YAMP's own volume works:
      clean crossfades, fades and skips); Keep Equalizer Session / Multisession: leave as they are. In the in-app hint
      (Settings → Playback, shown when Poweramp Equalizer is installed, with "Open") and a short README section
      "Head units with Poweramp Equalizer".
- [x] Same build: wide/Car player backdrop full-screen (no band under the status bar), artist fallback in landscape,
      Car cover and next block in phone-landscape proportions (D88, playback-stability "As built").

## One player structure (2026-11-18, as built)

- [x] «Крупные кнопки» switch; ⋮ · queue top row everywhere with the shared ⋮ menu; portrait title block and
      landscape-type 3 cards; `wideGeometryOf` (full-height block, centred on wide screens, far padding on taller ones,
      vertical centring on near-square ones); the clock's fixed place with shadow + scrim; Fold cover / inner goldens.

## Phase 7 — Export / import settings (user, 2026-11-18; roadmap: `docs/multi-source-player.md`)

- [ ] One versioned JSON file (Android file picker / share sheet — LocalSend works): settings (playback, player,
      interface, cache size, quality per account), FM radio favourites, queues (name, account binding, order, entry
      refs, positions, radio seeds, Keep), accounts **without secrets** (service, Yandex uid + display name; Jellyfin
      server address + user name). History optional (a checkbox in the export dialog).
- [ ] Import: **merge by default** (same account + name → updated), "replace all" as the alternative. Imported
      accounts need sign-in; their queues wait hidden (like removed accounts, D62) and appear with positions after
      signing in to the same account key. Nothing plays before that.
- [ ] Export dialog note: "The file contains your server addresses — don't share it publicly." Never tokens or
      passwords; no downloads or stream cache. Design doc first when it's picked up.

## Next: gaps after v0.5.0 (ordered)

- [ ] (a) **Yandex seek restarts the track** (journal `START cut … <pos>` on a seek): check whether the
      unseekable-reopen path misfires for Yandex, or whether the encrypted `encraw` source reports itself as
      unseekable. Fix it and add a test. *Done when* a seek in a Yandex stream is a plain seek: no START line, no gap.
- [ ] (b) **Jellyfin downloads and offline playback** of a fully cached Jellyfin track. *Done when* a downloaded or
      fully cached Jellyfin track plays in airplane mode.
- [ ] (c) **Wide-layout "taller" threshold:** taller when the controls would get **less than 1/3 of the width** after the full-height cover block (≈ **width/height < 1.66**; geometric form preferred — it includes the next block and paddings). 16:10 = 1.6 is taller, with the
      block at the right padding; 16:9 = 1.78 is wide, with the block centred. Apply it in `wideGeometryOf`.
      *Done when* tests pass at 1280×800 and 1920×1200 (taller) and at 1760×720 and 2340×1080 (wide), and the
      clock never crosses the cover edge on the taller ones.
- [ ] (d) **Android Auto browse tree with Jellyfin accounts:** verify on the unit or the emulator, and fix what's
      missing. *Done when* the Jellyfin queues are browsable and playable from Android Auto.
- [ ] (e) **Home Jellyfin sections and merged Library rows** with service badges. Canvas first. *Done when* the
      boards are approved and built, and Yandex-only Home stays pixel-identical.
- [ ] (f) **Car long press on album tiles.** This needs a car album view; canvas first. *Done when* a car album
      tile opens the album menu with car-sized rows.
- [ ] (g) **Foldable shape classes in the tests:**
      - the Fold 5 cover and inner screens;
      - Galaxy Z Fold 8 (specs from the user, 2026-11-18): **cover** 5.5", 1248×1972 px, 428 ppi (≈ 466×737 dp
        portrait, w/h 0.63 — passport-like); **inner** 7.6", 2448×1848 px, 4:3, 404 ppi (≈ 969×731 dp landscape,
        w/h 1.32 → the "taller" branch; also portrait 731×969 dp).

      Add the Fold 8 numbers once confirmed. *Done when* geometry tests and goldens cover them.
- [ ] (h) **Phase 7 export/import:** design doc first. *Done when* the doc is approved.
