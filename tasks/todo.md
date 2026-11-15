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

## Next-track buffering and its state (planned; canvas first)

User, 2026-11-14 — the original app silently disables Next when the next track can't load, and sometimes stays stuck
until the app is killed, even after the network is back.
- [ ] Preload the next track early (a few seconds after the current one starts), not only 60 s / 15 s before its end
- [ ] Next is never disabled; it shows the next track's state: ready / loading (spinner ring) / failed (greyed);
      a tap retries at once (load first, then switch)
- [ ] Failed next-track loads retry by themselves when the link comes back (`LINK NONE → GOOD`)
- [ ] Progress bar shows the buffered part (like YouTube Music)
- [ ] Design on the canvas first: phone player, car screen, the next-track artwork in the car

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
