# Phase 2 — Radio

Status: **implemented 2026-11-13** (approved 2026-11-13; branch `feature/phase-2-radio`). Part of
[multi-source-player.md](multi-source-player.md) (Phase 2); revises D35 (see Decisions). Yandex only — Jellyfin's
Instant Mix is Phase 6 on the same model. Rules referenced by id are from `ARCHITECTURE.md` (C1, E2–E5) and
`DESIGN_GUIDE.md` (§5, §8); decisions `Dn` are in `kb/decisions.md`. Code paths are relative to
`android/app/src/main/java/org/tegro/yamp/`; line numbers are the design-time ones (`5494fb1`) and have moved.
Where the implementation differs from the design, the text says so ("As built").

## Purpose

A radio is a queue that grows by itself from a seed (a track, an album, an artist) — YouTube Music semantics
(D9): the generated list is persistent, back and forward work, it survives restarts. Phase 1 planned one radio
queue at a time (D35); in use several are wanted — one per purpose ("Radio: Pink Floyd" for the road, "Radio:
Time" at home), each resuming where it stopped. Phase 2 lifts that limit, gives radio its own queues of kind
`RADIO` (the Phase 1 model already has the kind, a `seed` per queue and a per-queue `DynamicSource` — E4), and
adds one flag per radio, **Keep**, that says whether its history is retained.

## What changes for the user

- **"Start radio"** appears in the track menu (⋮ / long-press), on the player's title row, in the car header, and
  in the header overflow of album and artist pages. It opens the **radio picker** (the queue switcher's sheet / side panel in a
  third mode, §8): a list of the existing radios and **"New radio"**. Tap an existing radio = it is **re-seeded**
  (its tracks are dropped, the name follows the new seed); "New radio" = a new queue. No confirmation — a radio's
  content is disposable by nature. The picker is the same on the phone, in landscape and in the car: radios are
  few, so the list is short (D26's two taps: open, pick).
- The sheet has a **Keep** switch (off by default) that applies to the radio being started.
- A radio queue is named **"Radio: <seed>"** ("Радио: …") — the track title, the album title or the artist's
  name; it carries the radio badge (`Sensors`) in the switcher and `∞` instead of a count. With Keep on, a small
  marker (`History`) next to `∞`. As built: the marker shows on inactive rows only — the active row's trailing slot
  is the playing indicator, as for every queue.
- **Keep** = retain history. On: every track that played stays in the queue, back and forward work over the whole
  session. Off: the queue holds the current track, a few behind it and the tail ahead; what played earlier is dropped
  **at the next refill**, not at once. Keep can be switched any time from the queue's long-press menu (phone,
  landscape); switching it off trims at the next refill, switching it on retains from that moment. The switch exists
  only on `RADIO` queues — normal queues always retain everything, My Wave keeps its Phase 1 behaviour.
- Playing a radio: the seed track plays first (if it already plays, it plays on — as "wave by track" today), similar
  tracks follow; for an album or artist seed the first fetched track plays. When the position comes within a few
  tracks of the tail, the next batch is appended ahead of time — the same rule as My Wave; never a gap.
- An **inactive radio does nothing**: no refill, no requests; it keeps its tracks, cursor and position. Switching
  back resumes it where it stopped and refilling resumes from its last track. As built, this is Phase 1's switch
  rule unchanged (`PlaybackController.switchQueue`): if something was playing, the radio plays on from its saved
  position; if playback was paused, it is shown paused there.
- Radios appear in the switcher, in Android Auto's "Queues" folder, on the first tab when active (title = the
  radio's name, knob, "Дальше" — D49) and as a History filter (D50). Long-press menu of a radio: **Keep** (switch),
  **Delete**. No Rename, Duplicate or Clear (the name follows the seed; "Add to queue…" never offers a radio).
- Rotor feedback (started / finished / skip) is sent for the radio's station as it is for My Wave, so the
  station learns.
- My Wave is **unchanged**: still one `WAVE` queue per account, its page and tuning stay as they are.
- **Wave controls follow the active queue** (D54): every wave-related control is wave-internal while the `WAVE`
  queue is active (D40's station switch with undo) and becomes its **radio equivalent** on any other queue. Concretely:

  | Control | File | On the `WAVE` queue | On any other queue |
  |---|---|---|---|
  | Track menu row "Волна по треку" | `presentation/main/components/TrackContextMenu.kt:192` | wave by track (as today) | **"Start radio"** → picker |
  | Player title-row wave button | `presentation/player/PlayerParts.kt:424` (via `FullScreenPlayer.kt:174`) | wave by track | "Start radio" → picker (radio icon) |
  | Car header pill "Волна по треку" | `presentation/car/CarScreen.kt:341` (today shown only while the wave is active) | wave by track | **shown on every queue** as "Start radio" → picker |
  | Snackbar "Волна по треку «…»" + Undo | `presentation/main/MainScreen.kt:372` | as today | a plain message "Radio: <seed>" (the app's usual message, not the Undo snackbar; the previous queue is one switch away) |
  | Wave tuning chips / pills, "Вернуться к волне" | `presentation/main/HomeScreen.kt:200`, `CarScreen.kt:336-345` | as today | no radio equivalent — stay hidden (D49) |
  | Car caption "Далее · волна по треку" | `CarScreen.kt:206` | as today | the queue's name, as for any queue |

  Album / artist header overflow has no wave counterpart and always offers "Start radio". The choice between wave
  and radio is made in one place, `MainViewModel.waveOrRadio` (from `waveActive` = active queue is `WAVE`).

## Decisions (recorded in `kb/decisions.md`)

| # | Decision | Why |
|---|---|---|
| D35′ | **Revised:** the user's own queues are never touched automatically; My Wave is one per Yandex account; **radios are as many as the user starts** — "Start radio" offers re-seeding an existing radio or a new one; one queue per saved mix (re-run replaces, D29) and a single "Last mix" for unsaved builds. "Keep copies the radio into a named queue" is dropped | One radio per purpose, each resuming where it stopped; re-seeding in place keeps the list from piling up (user, 2026-11-13) |
| D51 | **"Start radio" picks the target**: an existing radio (re-seeded, no confirmation) or a new radio; normal queues are never a target. Same picker on phone and head unit | Radios are disposable, user queues are not (D35); a short list is fine in the car (D26) |
| D52 | **Keep** is a per-radio flag that means *retain history* and nothing else: off → the played head is dropped at the next refill; on → everything stays; switchable any time; only on `RADIO` queues. Any radio stays re-seedable regardless | Back/forward over a long session when wanted, a short queue sheet when not (user, 2026-11-13) |
| D53 | **My Wave is not touched in Phase 2**: `WaveQueue` and its own list (`list_items`) stay; the radio gets its own `DynamicSource`. Merging the wave into the radio model, and retiring `WaveQueue`'s list, is a later step | The wave works on the unit today; one moving part at a time (user, 2026-11-13) |
| D54 | **Wave controls follow the active queue**: on the `WAVE` queue they are wave-internal (D40), on any other queue each becomes its radio equivalent ("Start radio" → picker); wave-only controls (tuning, "back to My Wave") have no equivalent and stay hidden off the wave (D49) | One control per place, its meaning given by the queue the user is in (user, 2026-11-13) |

## Technical design

### 1. Domain model (`domain/queue/`)

- `PlayQueue` gains `keepHistory: Boolean = false` (named so it does not collide with `WaveQueue`'s `keep` filter
  for explicit tracks). Meaningful on `RADIO` only; `false` for every other kind.
- `seed` for a radio is a **provider ref** (C1, D27): `yandex:track:<id>`, `yandex:album:<id>`, `yandex:artist:<id>`.
  The rotor station id is derived in `data/` (`track:<id>`, `album:<id>`, `artist:<id>`). `QueueKind.RADIO`'s doc
  comment ("one at a time") is corrected.
- `RadioSeed(ref: MediaRef, title: String)` — what the UI hands over; for a track seed the controller also has the
  `TrackDetailsDto` to play first. `RadioTarget = New | Replace(queueId)` sits beside it (`domain/queue/RadioSeed.kt`).
- No rotor "session" column: the rotor continues from `queue=<last queued track id>` (`WaveQueue.kt:166-168`), which
  is the radio's last entry; the response's `batchId` (`dto/ApiModels.kt:313`) is not used today and stays unused.

### 2. Persistence: `CacheDatabase` v3 → v4

- `queues` gains `keepHistory INTEGER NOT NULL DEFAULT 0`. `MIGRATION_3_4` (one `ALTER TABLE … ADD COLUMN`, the
  `MIGRATION_2_3` pattern, `data/cache/CacheDatabase.kt:180`), schema `4.json` committed, `CacheDatabaseMigrationTest`
  extended (3→4, and 1→4 through the chain). No destructive fallback.
- `QueueEntity` / `toDomain` / `toEntity` (`data/queue/QueueEntities.kt`, `RoomQueueStore.kt:90-101`) carry the flag.
- Write-through as in Phase 1 (E3): the trim below happens in the same transaction as the append.

### 3. Queue manager (`data/queue/QueueManager`)

- `startRadio(target: RadioTarget, seed: String, name: String, keepHistory: Boolean, first: List<TrackDetailsDto>)`
  where `RadioTarget = New | Replace(queueId)`. `New` → `create(name, RADIO, seed = …)` + the flag; `Replace` →
  the existing radio re-seeded (name, seed, flag; `origin` cleared, shuffle off) and its entries replaced by
  `first`. Returns the queue with the cursor on the first entry; the controller switches to it and plays it.
  As built: `Replace` does not call `restartDynamic` — both run under the manager's `mutation` mutex, which is not
  reentrant, so the re-seed is one mutation (`replaceEntries` + `update` + `saveCursor`) of its own.
- `setKeepHistory(queueId, on)`: updates the flag; nothing else (the trim is tied to refills, D52).
- `appendFetched(queueId, tracks)` (`:229`) gains the **trim rule**: for a `RADIO` queue with `keepHistory = false`,
  after appending, entries before `cursorIndex - RADIO_BEHIND` are removed (`RADIO_BEHIND = 3`, a constant, no
  setting — enough for "previous"). Pure helper `QueuePolicy.radioTrim(entries, cursorIndex, keepHistory)`, unit-tested. The
  trimmed ids go through `removeEntry` in the same mutation; `refreshIfActive` once.
- Everything else (switch, cursor, position, delete) is Phase 1 as is; D42 applies when the active radio is deleted.

### 4. Dynamic source and the controller (`data/player/`)

- **`RotorStationSource(station, api, heardLately, keep)`** (`data/queue/`, the first implementation of
  `DynamicSource`, `domain/queue/QueueStore.kt:39`): today's two requests (continued from the last queued track + a
  fresh one, `WaveQueue.kt:164-171`; one failing leaves the other), de-dup against the queue and the account's
  recently played ids (`heardLately`), the explicit filter (`keep`); `feedback(ref, event, playedMs)` =
  `sendStationFeedback(station, type, id, from = feedbackFrom(station))`. The pure helpers (`stationOf(MediaRef)`,
  `feedbackFrom`, `freshOf`) live in a small `RotorStations` object both classes use; `WaveQueue` only delegates to
  it (D53). As built: the working entry point is `fetchTracks(queued: List<TrackRef>)` — the queue **in play order**
  (the station continues from its last entry, which a `Set` cannot give) returning the provider's track objects the
  player still works with; `fetchBatch(exclude)` wraps it. A `Mutex` serialises fetches per source.
- The controller holds **one source for the active radio** (`radioSourceOf`): made lazily from the active RADIO
  queue's seed when first needed, reused while the same radio with the same station is active, replaced when another
  radio is, dropped on a re-seed (`dropRadioSource`, which also cancels the running fetch). As built: no
  `generation` counter — staleness is by identity: a batch lands only if the source that fetched it is still the
  held one **and** its queue is still active (`radio === source && active.queue.id == queueId`); otherwise it is
  discarded, so nothing is appended to an inactive or re-seeded radio.
- The four `kind == WAVE` sites become "the active dynamic source", each keeping its WAVE branch untouched:
  trackStarted feedback + refill when ≤ 20 remain (`:1036-1040`), finished/skip feedback (`:1071-1074`), refill
  when ≤ 10 are ahead in `prepareNextTrackInQueue` (`:1552`), and `keepWaveShelfFilled` (`:1601`) — for a radio
  "short" is `PlaybackQueue.waveShelfIsShort(waveActive = true, …)` on the queue's entries (`WAVE_SHELF_MIN = 3`),
  with the same retry back-off and the same "online and signed in" gate. Nothing runs for an inactive radio.
  As built: each WAVE branch stays literally and a RADIO branch sits beside it; the shelf watcher for radios is a
  separate `keepRadioShelfFilled`; "load more" at the end of the queue sheet and the end-of-queue advance also
  fetch the radio.
- **`startRadio(seed: RadioSeed, target, keepHistory)`**: offline → today's message and nothing else
  (`:825-828`); otherwise `queueOp`: `queueManager.startRadio(…)` with `first = [seed track]` for a track seed or
  `[]` otherwise → `switchTo` the radio if it is not active → if the seed track is what sounds now, `moveCursor` to
  it and `dropStandby`, else `startEntry(first entry)`; for an empty radio (album / artist seed) the first batch is
  awaited and its first entry started, with the player's loading state meanwhile (no batch → "The radio could not
  be loaded" message). `returnPoint = null` as for the wave. A plain message "Radio: <seed>" (no undo: the previous
  queue is one switch away).
- `startTrackWave` / `undoTrackWave` / `returnToMyWave` stay as they are (D53); the UI calls them only while the
  `WAVE` queue is active and `startRadio` otherwise (D54) — the choice is made in `MainViewModel` from
  `active.queue.kind`, one place.
- FM radio (`liveStation`, D41) is unaffected; while a live station plays nothing refills, as today.

### 5. UI (`presentation/queues/`, H1, H3)

- `QueueSwitcherRequest` (`QueueSwitcherHost.kt:20`) gains `StartRadio(seed: RadioSeed)`. In that mode the sheet
  (`QueueSwitcherSheet.kt`) shows title "Start radio", subtitle = the seed title, rows = `RADIO` queues only
  (each "re-seeds this radio"), a **"New radio"** row/`+`, and a **Keep** switch row (`HiFi` switch, off). The VM
  (`QueueSwitcherViewModel.kt`) gets `startRadioIn(queueId?)` → `PlaybackController.startRadio(seed, target, keep)`.
  Car: same panel (720u), big rows, `+` in the header as today; the Keep switch is a big row too (§5).
  As built: the Keep row is the list's first item (above the radios); on the phone "New radio" is the bottom pill,
  in the car the header `+`; long-press is off in picker mode.
- Switcher rows: `QueueRowState` gains `keepHistory`; a radio row shows `∞` (`:202`) plus the marker. Long-press menu
  for a radio (`QueueDialog.Menu`, `:37`): **Keep** (menu row with a switch — `ContextMenuItem` gets an optional
  trailing switch) and **Delete**; `editable` stays `kind == STATIC` so "Add to queue…" keeps excluding radios.
- Entry points (the D54 table): the track-menu row, the player title-row button and the car header pill take a
  `waveActive` flag and show "Волна по треку" / `Waves` or "Start radio" / `Sensors` accordingly; the car pill is
  no longer gated on the wave (`CarScreen.kt:333`). Album / artist header overflow (`QueueButton.kt:53`'s list menu,
  `catalog/CatalogScreens.kt`) gets "Start radio" with the album / artist as seed. Not in Android Auto (no track
  menus there; the "Queues" folder already lists radios, `MediaLibraryTree.kt:72`).
- Home / car header need nothing new: the first tab follows the active queue (D49), wave-only controls are already
  gated on `WAVE`.
- Strings: EN in `values/`, RU in `values-ru/` (K1): `radio_start`, `radio_picker_title`, `radio_new`, `radio_keep`,
  `radio_keep_hint`, `radio_queue_name` ("Radio: %1$s"), `radio_started`. Canvas: one board `Design/yamp/radio.jsx`
  (picker phone / car) added to the sections.

### 6. Tests and guards

- `CacheDatabaseMigrationTest` 3→4; `RoomQueueStoreTest` round-trips the flag.
- `QueueManagerTest`: `startRadio` new / replace (entries dropped, name and seed updated, cursor on the first entry);
  `appendFetched` trims to `RADIO_BEHIND` behind the cursor when `keepHistory` is off, never when on, never on
  `WAVE`/`STATIC`; `setKeepHistory` alone changes no entries. `QueuePolicyTest`: `radioTrim`.
- `RotorStationSourceTest` (fake `YandexMusicApi`): two requests merged and de-duplicated, `exclude` and recent
  honoured, explicit filtered, one request failing leaves the other, feedback carries `from = radio-track-<id>`.
- `PlaybackControllerTest`: a radio refills when ≤ 10 ahead and sends started / finished / skip through the source;
  an inactive radio gets no requests; switching back resumes at its position and refills; a batch for a
  re-seeded radio is discarded; the wave cases stay green unchanged.
- Screenshots (I2): the picker on the phone, landscape panel, car at `w1280dp-h720dp` and `WIDE_HEAD_UNIT`
  (both locales); switcher with radio rows and the Keep marker; the radio queue menu; track menu with the new row.
- Guards: `ArchitectureTest` B1 for `domain/queue` still holds (`keepHistory` is a plain field); `LayoutSafetyTest`
  pins unchanged. Device checklist (`kb/head-unit-dpi-app.md` rule 6, `scripts/device-check.sh`): kill while a
  radio refills, switch radio → kill → restart, re-seed while playing.

## Order of work and verification

1. Verify `album:<id>` and `artist:<id>` stations against the live API with the user's account (a scratch call from
   the debug build's log, or `curl` with the user's token on their machine — nothing committed). If one is missing,
   that seed type is left out of the UI, not emulated.
2. Model + Room v4 + migration test; `RotorStationSource` + tests; `QueueManager.startRadio`, trim, flag + tests.
3. Controller: the active-radio source, the four generalised sites, `startRadio`; controller tests green.
4. UI: picker mode, Keep switch and marker, radio menu, entry points, strings, goldens; `verifyRoborazziDebug
   lintDebug` + detekt; device checklist on the phone and the unit; HANDOFF / KB / `as-is-playback.md` updated.

Each step is a commit group; the user tests on the phone after 3 and on the unit after 4.

## Affected files

- New: `data/queue/{RotorStationSource,RotorStations}.kt`, `domain/queue/RadioSeed.kt`, `schemas/…/4.json`,
  `Design/yamp/radio.jsx`, tests in §6.
- Changed: `domain/queue/{PlayQueue,QueuePolicy}.kt`, `data/queue/{QueueEntities,RoomQueueStore,QueueManager}.kt`,
  `data/cache/CacheDatabase.kt`, `data/player/{PlaybackController,WaveQueue}.kt` (the latter only to use
  `RotorStations`), `presentation/queues/{QueueSwitcherHost,QueueSwitcherSheet,QueueSwitcherViewModel,
  QueueDialogs}.kt`, `presentation/main/components/TrackContextMenu.kt`, `presentation/player/PlayerParts.kt`,
  `presentation/main/{MainViewModel,MainScreen,MainDialogs}.kt`, `presentation/catalog/CatalogScreens.kt`,
  `presentation/queues/QueueButton.kt`, `res/values/strings.xml`, `res/values-ru/strings.xml`, goldens,
  `presentation/car/CarScreen.kt`, `kb/decisions.md` (D35′, D51–D54), `docs/multi-source-player.md` (Phase 2
  paragraph). As built, also: `presentation/player/FullScreenPlayer.kt`, `presentation/main/MainTabs.kt`,
  `PlaybackControllerTest.kt`, `QueueScreenshotsTest.kt`, `kb/as-is-playback.md`; temporary for step 1:
  `data/player/RadioStationProbe.kt` and its About button (removed at the end of Phase 2).

## Out of scope

Jellyfin Instant Mix (Phase 6, same `RADIO` queues), the `MIX` kind, "Keep as copy" / detach (dropped, D52),
merging My Wave into the radio model and retiring `WaveQueue`'s list (D53), station tuning (rotor `settings2`) for
radios, starting a radio from Android Auto, feeding the rotor de-dup from History (Phase 1b Q3), a cap on the
number of radios.

## Risks and side effects

- `album:`/`artist:` stations were unverified at design time. Step 1 (2026-11-13, debug "Probe radio stations"
  button) showed all three seed types behave alike (200, batches of 5, `queue=` continuation works). It also showed
  that rotor `feedback` answers 400 "condition is not met" for every station, My Wave included — an existing issue,
  not Phase 2's; radios send feedback the same way and it fails the same way until that is solved.
- `PlaybackController` is the file that changes most (four sites + start flow); the controller tests from Phase 1
  are the guard, and every WAVE branch is kept literally.
- A radio with Keep off whose tail never refills (offline for a long ride) is never trimmed either — by design,
  nothing is lost while nothing arrives.
- `queue_entries` of a Keep-on radio grow for as long as it is listened to; a long session is hundreds of rows,
  not thousands — well inside Phase 1's "5 000 tracks is small".
- Deleting a radio that History rows point to is fine (D50: name snapshot).

## Open questions (decided 2026-11-13)

1. **Entry points vs. "Волна по треку"** → D54: every wave control follows the active queue (wave-internal on the
   wave, radio equivalent elsewhere); see the table in "What changes for the user".
2. **`RADIO_BEHIND = 3`** → a constant, no setting.
