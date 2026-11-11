# Phase 1 — Multiple queues

Status: **approved 2026-11-10** (open questions 1–7 decided as recommended, D38–D44). Part of [multi-source-player.md](multi-source-player.md)
(Phase 1). Yandex only; the provider abstraction is Phase 3. Rules referenced by id are from
`ARCHITECTURE.md` (A1, E2, …) and `DESIGN_GUIDE.md` (§5, §8); decisions `Dn` are in `kb/decisions.md`.
Code paths are relative to `android/app/src/main/java/org/tegro/yamp/`; line numbers are `main` at `1bbb8dd`.

## Purpose

Several independent, persistent queues (D6, D8): "with family", "driving alone", "audiobook" — each resumes
exactly where it stopped, none is ever lost (process death, reboot, the head unit's DPI tool force-stop:
`kb/head-unit-dpi-app.md`). Switching is a popup, two taps everywhere, the car included (D26). This phase
also restructures the playback controller so Phases 2–4 (radio, providers, Jellyfin) can plug in without
touching it again: entry-id cursor, kind/origin instead of `PlaylistSource`, write-through Room persistence,
one dynamic source per queue (E2–E5).

## What changes for the user

- A **queue button** (glass circle, stacks glyph, small count) on Home, in the player's queue sheet and in the
  car header. It opens the **queue switcher**: phone — bottom sheet; landscape and car — side panel on the
  driver's side (§8, canvas section "Queues"). Rows: name, kind badge on the cover, current track · artist,
  progress bar, `12 / 48` (`∞` for My Wave); the active queue is anodized. Tap = switch (shown paused at its
  saved track and position; keeps playing if the player was playing). "+" = new queue.
- Long-press a row (phone / landscape): **Rename, Duplicate, Clear, Delete** (delete via `HiFiAlertDialog`,
  danger action). My Wave has only Clear. In the car the panel offers switch and "+" only (§5: big targets,
  no typing — a queue created in the car is named "Queue N" and can be renamed on the phone).
- Track menu (⋮ / long-press) gains **"Add to end of queue"** and **"Add to queue…"** (picker: the user's
  queues + "New queue"); "Play next" already exists. Album and playlist headers get the same two actions for
  the whole list — this is how a "with family" queue is built from several albums.
- Playing an album / playlist / search result / liked tracks **replaces the content of the active queue** (as
  now, D8). Other queues change only through "Add to queue…". No confirmation dialogs.
- **Shuffle** and **repeat** become per-queue modes, shown as two toggles in the player's queue sheet header.
  Shuffle keeps the original order and can be switched off again (today it reshuffles once, irreversibly).
  Repeat: Off / All / One.
- The player's queue sheet (track list of the active queue) keeps today's rows and gestures; tapping a row
  moves the cursor to **that entry**, so a track that is in the queue twice works.
- Duplicates are allowed in a queue. Nothing is auto-deleted; the user's queues are never touched by the app
  (D35).
- My Wave is a queue of its own (one per Yandex account), not editable, extended as today.
- Android Auto / the media browser gets a **"Queues"** folder: one playable item per queue.
- After the update the existing session becomes a queue called **"Main"** (whatever `playback_state.json`
  holds), active, paused where it was. If the session was My Wave, it becomes the My Wave queue instead.
- Everything else looks and behaves as today. New strings are English + Russian (K1); the rest of the app
  stays Russian until the string migration (K4).

## Technical design

### 1. Domain model (`domain/queue/`, pure Kotlin — B1)

```kotlin
@JvmInline value class TrackRef(val value: String)            // "yandex:track:12345" (C1)
enum class QueueKind { STATIC, WAVE, RADIO, MIX }              // behaviour; RADIO/MIX unused until Phases 2/6
enum class RepeatMode { OFF, ALL, ONE }
data class PlayQueue(id: Long, name: String, kind: QueueKind, origin: String?, seed: String?,
                     account: String?, cursorEntryId: Long?, positionMs: Long,
                     shuffle: Boolean, repeat: RepeatMode, sortOrder: Int, updatedAt: Long)
data class QueueEntry(entryId: Long, queueId: Long, position: Int, originalPosition: Int, ref: TrackRef,
                      title: String, artist: String, coverUrl: String?, durationMs: Long)
data class QueueSummary(queue: PlayQueue, current: QueueEntry?, size: Int, isActive: Boolean)
interface QueueStore { … }                                     // see §2; implemented in data/queue
interface DynamicSource { suspend fun fetchBatch(exclude: Set<TrackRef>): List<TrackRef>;
                          suspend fun feedback(event: …) }     // E4; Phase 1: one impl, the rotor station
```

`TrackRef` is the only track identity in the new code (C1); `MediaRef`/parsing (`provider`, `kind`, `id`,
split at the first two colons) lives next to it. The engine and the resolver still take a Yandex id in
Phase 1 — the bridge is one function, `TrackRef.yandexId`, used only in `data/`.

### 2. Persistence (F1): `CacheDatabase` v1 → v2

Two tables; the full queue is stored, no window (E3):

| Table | Columns |
|---|---|
| `queues` | `id` PK autoincrement, `name`, `kind`, `origin`, `seed`, `account` (only `WAVE`), `cursorEntryId`, `positionMs`, `shuffle`, `repeat`, `sortOrder`, `updatedAt` |
| `queue_entries` | `entryId` PK autoincrement, `queueId` (FK → `queues.id`, cascade), `position`, `originalPosition`, `ref`, `title`, `artist`, `coverUrl`, `durationMs`; index `(queueId, position)` |

- The **active queue id** is a row in the existing `cache_values` (`owner = ~shared`, `key = active_queue`),
  written in the same transaction as the switch — no third table.
- The full `TrackDetailsDto` the old code still needs for playback (loudness, cover templating, downloads)
  stays in the existing `tracks` table (`id` = Yandex id, `json`), upserted when entries are added. The
  display columns on `queue_entries` exist so the switcher, the picker and future "unavailable" rows render
  without the DTO. `CacheDao.deleteOrphanTracks` (`data/cache/CacheDatabase.kt:112`) is extended to keep
  tracks referenced from `queue_entries`, otherwise sign-out / playlist refresh would delete queue tracks.
- My Wave entries move from `list_items` (`WAVE = "wave"`, `PlaylistCacheManager.kt:104-108, 245`) to
  `queue_entries` of the account's `WAVE` queue; the `list_items` rows are deleted by the migration import.
- `CacheDatabase` becomes **Hilt-provided** (`di/DatabaseModule.kt`: database + `CacheDao` + `QueueDao`);
  `PlaylistCacheManager` takes the DAO by injection instead of `CacheDatabase.get(context)` (:29). The
  static `get` stays for the existing instrumented test.
- Migration `MIGRATION_1_2` (create the two tables + index) with a **migration test** (`room-testing`
  `MigrationTestHelper` against the exported `schemas/…/1.json`, run under Robolectric; if the helper does
  not work there, it goes to `androidTest`). Schema `2.json` is committed. No destructive fallback.
- **Import of `playback_state.json`** (`data/queue/LegacySessionImport`): runs once, inside
  `loadUserState`, before restore, only when `queues` is empty. Snapshot source `VIBE` → the account's
  `WAVE` queue (entries = snapshot queue ∪ cached wave list, seed = `waveSeed`); any other source → `STATIC`
  "Main" with `origin` from the source name; FM radio snapshots are not imported (a station is not a queue,
  §4). Cursor = `currentTrackId` (first match), position = `positionMs`. One transaction, then the file is
  deleted; a kill in between re-runs the import on the next start only if the transaction never committed
  (DPI rule 4, idempotent).
- **Write-through** (E3, DPI rule 1): every mutation goes through `QueueStore` in the call path of the
  operation; the store serialises writes through one `Channel` consumer on `Dispatchers.IO` so order is kept
  and the main thread never blocks. Position + cursor are written every 15 s while playing (today's loop,
  `PlaybackController.kt:293-298`), on pause, on switch, on `onTaskRemoved` (`MusicService.kt:440`). Every
  write is one Room transaction (DPI rule 3).
- **Start-up never writes** (DPI rule 2): restore is read-only — it already is today (`loadUserState`,
  `PlaybackController.kt:1228-1261`); the import above is the single exception and only runs on an empty
  table. Covered by a test: two restores in a row produce the same state and no writes.

### 3. Queue manager (`data/queue/QueueManager`, `@Singleton`)

Owns the list of queues and the **active queue in memory** (entries + the DTOs they map to), exposes
`queues: StateFlow<List<QueueSummary>>` and `active: StateFlow<ActiveQueue>` (queue, entries in play order,
cursor index, DTO list), and is the only writer of `QueueStore`. Operations — each one persists before it
returns (E3):

- queues: `create(name, kind = STATIC)`, `rename`, `delete`, `duplicate`, `clear`, `reorder`, `switchTo(id)`;
- content: `replaceActive(tracks, origin, startRef?)` (E5 — the only operation that replaces), `appendTo(queueId,
  tracks)`, `insertAfterCursor(tracks)` ("Play next"), `remove(entryId)`, `move(entryId, targetEntryId)`;
- cursor: `moveCursor(entryId)`, `savePosition(ms)`; modes: `setShuffle(on)`, `setRepeat(mode)`.

Shuffle: `position` is the play order, `originalPosition` the insertion order; `setShuffle(true)` rewrites
`position` randomly keeping the cursor entry first, `setShuffle(false)` restores `originalPosition`. New
entries get the next `originalPosition`; in shuffle mode they are inserted at random positions after the
cursor. Repeat is read by `PlaybackQueue.nextAction` (new parameter instead of `source`).

Switching: `savePosition` on the active queue → write `active_queue` → load the target → `active` emits the
target with its cursor/position. The controller reacts: `dropStandby`, show the cursor track paused at
`positionMs` (today's restore path), `play()` immediately if it was playing.

Account rules: the `WAVE` queue of an account is created on first sign-in (empty, then primed as today).
Entries are never removed because their source is unavailable — a signed-out provider (D22) or, later, an unmounted
USB/SD volume (D47): they are shown unavailable, skipped, and play again when the source returns.
`resetUserData`/`resetForAccount` (`AccountSessionManager.kt:107-125`) no longer clears playback — queues are app-wide (D22);
only if the active queue is another account's `WAVE` queue does the new account's `WAVE` queue become
active. `clearWaveAndHistory` → `clear` on that account's `WAVE` queue.

### 4. PlaybackController restructure (A1 — strangler, callers keep working)

The controller stops owning queue state. `_currentPlaylist` / `_currentTrack` / `_currentSource` become
**derived** from `QueueManager.active` (same `StateFlow` types, so the ~20 callers listed in
`kb/architecture/as-is-playback.md` compile unchanged); `PlaylistSource` is kept only as a derived value for
the UI labels (origin) until those screens are migrated.

| Today | Phase 1 |
|---|---|
| `playTrack(trackId, playlist, source)` replaces + moves cursor (`:315`) | `playFrom(tracks, origin, startRef)` = `replaceActive` + `playEntry(cursor)`; `playEntry(entryId)` = move cursor + play. `playTrack` stays as a thin wrapper for `PlaylistsViewModel`, `CatalogViewModel`, `MediaLibraryTree`, `MainViewModel` (E5) |
| index by `indexOfFirst { id == currentId }` | cursor index from `active`; `playNext`/`playPrev`/`prepareNextTrackInQueue`/`upcoming` use entries |
| `reorderQueue`, `enqueueNext`, `removeFromQueue`, `moveInQueue` edit `_currentPlaylist` (`:514-559`) | delegate to `QueueManager`; `removeFromQueue(entryId)`, `moveInQueue(entryId, targetEntryId)` — the queue sheet passes entry ids |
| `persistSession` → `PlaybackStateStore` (`:1212`) | `QueueManager.savePosition`; `PlaybackStateStore` is deleted after the import |
| one global `WaveQueue` (`:162`) | the `WAVE` queue's `DynamicSource` (`RotorStationSource` wrapping today's rotor calls and de-dup from `WaveQueue.kt`); `keepWaveShelfFilled` and rotor feedback run only while the active queue is `WAVE`. `startTrackWave` / `undoTrackWave` / `returnToMyWave` keep today's behaviour inside the `WAVE` queue (station switch with an undo point, `seed` persisted) — Phase 2 turns "wave by track" into the `RADIO` queue |
| FM radio = `PlaylistSource.RADIO` with fake tracks | a station is **not a queue**: `playRadioStation` enters a "live station" mode beside the active queue (which stays frozen); next/prev step stations as today; playing anything from a queue leaves the mode. Restore shows the last station paused as today (station id in `cache_values`) |
| offline mix with `returnPoint` (`:195`, `:1070`) | unchanged in behaviour; the return point is `(queueId, entryId)` instead of a copied list |
| restore in `loadUserState` (`:1228`) | import (if needed) → `QueueManager.load()` → cursor track shown paused at `positionMs`; `prepareResumption` unchanged |

Engine (`PlayerRepository`) is untouched in this phase except for finding 2 (audio session) if the user puts
it in scope (open question 1). `MusicService` command mapping is unchanged (`MusicService.kt:372-427`).

### 5. UI (`presentation/queues/`, H1, H3)

- `QueueSwitcherViewModel` (`@HiltViewModel`): `UiState(queues: List<QueueRow>, activeId, mode: Switch |
  AddTo(track or list))`, actions `switchTo`, `create(name?)`, `rename`, `delete`, `duplicate`, `clear`,
  `addTo(queueId)`. Depends on `QueueManager` and `PlaybackController` only (B3).
- `QueueSwitcherHost` (feature composable, drawn once in `MainScreen` next to `TrackMenuHost`,
  `MainScreen.kt:395-412`): opened through `LocalQueueSwitcher` (a `fun interface` launcher in a
  `staticCompositionLocalOf`, the `LocalTrackMenu` precedent, `TrackContextMenu.kt:69-76`) with a request
  `Switch` or `AddTo(…)`. The same host serves the picker for "Add to queue…".
- **`HiFiSheetPanel`** (new in `presentation/hifi/`): bottom sheet in portrait, side panel in landscape
  (driver's side in the car from `steeringWheelOnLeft`), predictive back, scrim. Extracted from the
  `WaveTuneSheet` pattern (`WaveTune.kt:296-360`) — §3 "reuse before creating": there are already three
  hand-built sheets, this must not be a fourth. `WaveTuneSheet` and the car tune panel migrate to it later,
  not in this phase (screenshot churn).
- Entry points: Home header — `GlassCircleButton` after `HiFiLinkBadge` (`HomeScreen.kt:189-192, 208-211`),
  with a count badge; player queue sheet header — a "Queues" `GlassPillButton` (`PlayerQueueSheet.kt:242`),
  plus the shuffle / repeat toggles next to the "Далее" caption; car — a `CarPill` with the active queue's
  name and a chevron in the `CarHeader` pills slot (`CarScreen.kt:473-476`) → side panel 720u wide (canvas
  `CarQueuePanel`). Tap count in the car: **2** (open, pick) — D26.
- Dialogs: new queue (`HiFiAlertDialog` + `OutlinedTextField` with `HiFiDialogDefaults.textFieldColors()`,
  as `PlaylistNameDialog`), rename (same), delete (danger). Queue menu = the menu-row sheet (rows shared
  with the track menu — `ContextMenuItem` is made internal-shared instead of private, `TrackContextMenu.kt:266`).
- Track menu: two rows added after "Играть следующим" (`TrackContextMenu.kt:191`); album / playlist header
  overflow gets "Add to end of queue" / "Add to queue…" for the whole list.
- `MediaLibraryTree`: root child `queues` (folder) → `queue/<id>` playable: `switchTo` + `play`.
- Strings: `values/strings.xml` gets the new keys in **English**, `values-ru/strings.xml` is created with
  their Russian text (K1, K2: `queue_switcher_title`, `queue_menu_rename`, `cd_queues`, …); counts use
  standard `plurals`. The app stays Russian elsewhere until K4; on an English-locale device the new screens
  are English inside a Russian app — accepted for now.
- Design deviations from the canvas, if any, go to `kb/design-deviations.md` (§9).

### 6. Tests and guards (I1–I3)

- **Before the restructure (E6):** `PlaybackControllerTest` with `FakePlayerRepository` (the interface
  exists) and a fake resolver — the controller takes `TrackPreloadManager` concretely, so a small
  `TrackUrlResolver` interface is introduced with `TrackPreloadManager` as its implementation (wrap, not
  rewrite — A3). Runs under Robolectric with `kotlinx-coroutines-test` (`Dispatchers.setMain`) and an
  in-memory Room for `PlaylistCacheManager`; the first task measures how much of the constructor can be
  faked and adds interfaces only where a fake is needed. Cases: play / next / prev / end-of-queue, restore
  paused at position, wave refill, recovery ladder entry points.
- New: `QueueDaoTest` (in-memory Room), `QueueManagerTest` (fake store: write-through on every op,
  shuffle/unshuffle keeps the cursor, duplicates, switch semantics), `LegacySessionImportTest`,
  `CacheDatabaseMigrationTest` (v1→v2), `PlaybackQueueTest` extended for repeat modes and entry cursors,
  "restore writes nothing" (DPI rule 2).
- Screenshots (I2): switcher sheet (phone), side panel (landscape), car panel at `w1280dp-h720dp` and the
  new **`w1920dp-h720dp-land-mdpi`** (`WIDE_HEAD_UNIT`, also added to the existing car goldens), add-to-queue
  picker, new/rename/delete dialogs; updated goldens for Home, the player queue sheet and the track menu.
  Car screens in both locales (§7). The 1920×720 captures are copied into `Design/` as the "as built"
  reference.
- Guards: `LayoutSafetyTest` keeps passing — `PlayerQueueSheet.kt` keeps `lastVisible >= total - 12` (:247)
  and `PlaybackController.kt` keeps `prepareNextTrackInQueue` (:421); `ArchitectureTest` gains B1 for
  `domain/queue` (no `android.`, `androidx.`, `data.` or `dto` imports) and B3 for `presentation/queues`.
- **Device checklist** (`kb/head-unit-dpi-app.md` rule 6), run on the phone and the head unit before merge:
  `am force-stop` while playing, within 1 s of launch, right after a queue edit; `wm density` change while
  playing; relaunch with `--activity-clear-task`; reboot; switch queues, kill, restart → same queue, same
  track, same position. A small `scripts/device-check.sh` drives the adb part.
- New test dependencies: `kotlinx-coroutines-test`, `androidx.room:room-testing`. No mocking library.

## Order of work and verification

1. Plumbing: test dependencies, `WIDE_HEAD_UNIT` qualifier + car goldens at 1920×720, `TrackUrlResolver` seam.
2. Controller tests on today's behaviour (E6) — green before anything moves.
3. Domain model + Room v2 + Hilt database + migration test + `QueueDao` tests.
4. `QueueManager` + legacy import + controller restructure; controller tests still green; manual check on the
   phone: upgrade from `v0.1.0` keeps the session.
5. UI: `HiFiSheetPanel`, switcher host, entry points, dialogs, track/list menu actions, strings, Android Auto.
6. Screenshots recorded, `verifyRoborazziDebug lintDebug` green, device checklist on phone + head unit,
   HANDOFF / KB / `kb/architecture/as-is-playback.md` ("Phase 1 state") updated.

Each step is a commit group; the user tests on the phone after 4 and after 5.

## Affected files (main)

- New: `domain/queue/*`, `data/queue/{QueueDao,QueueEntities,QueueStoreImpl,QueueManager,LegacySessionImport,
  RotorStationSource}.kt`, `di/DatabaseModule.kt`, `presentation/queues/{QueueSwitcherHost,
  QueueSwitcherViewModel,QueueSwitcherSheet,QueueDialogs}.kt`, `presentation/hifi/HiFiSheetPanel.kt`,
  `res/values-ru/strings.xml`, `schemas/…/2.json`, tests listed in §6, `scripts/device-check.sh`.
- Changed: `data/player/{PlaybackController,PlaybackQueue,WaveQueue,MediaLibraryTree,MusicService}.kt`,
  `data/cache/{CacheDatabase,PlaylistCacheManager}.kt`, `data/network/TrackPreloadManager.kt` (implements the
  resolver interface), `data/account/AccountSessionManager.kt`, `presentation/main/{MainScreen,MainViewModel,
  MainDialogs,HomeScreen}.kt`, `presentation/main/components/TrackContextMenu.kt`, `presentation/player/
  {PlayerQueueSheet,PlayerParts}.kt`, `presentation/car/CarScreen.kt`, `res/values/strings.xml`,
  `app/build.gradle.kts`, screenshot tests and goldens, `ArchitectureTest`.
- Deleted: `data/cache/PlaybackStateStore.kt` (after the import), the `wave` list in `list_items`.

## Risks and side effects

- **The controller is 1 300 lines with no tests** — hence step 2 before step 4. The restructure keeps the
  public `StateFlow`s so the UI does not change in the same step.
- **Migration from `v0.1.0`:** only the snapshot window (50 before / 150 after) exists, so "Main" is that
  window, not the whole original list. Failed migration must not lose downloads: no destructive fallback;
  the import is wrapped so an exception leaves the tables empty and the json in place (next start retries).
- `deleteOrphanTracks` and `deleteOwner` (sign-out) would delete queue tracks unless the query is extended —
  covered by a DAO test.
- The Room `MigrationTestHelper` may not run under Robolectric; fallback is `androidTest` (needs a device).
- Audio-session / skip behaviour (findings 1–3) is engine work and not a queue change; mixing it in makes
  the phone test ambiguous — see open question 1.
- `WaveQueue.kt` loses its global role; its rotor fetch / de-dup code is kept as is inside
  `RotorStationSource` (upstream still changes it: `kb/upstream-tracking.md`).
- Strings: a `values-ru/` with only the new keys means the app's language mix depends on the device locale
  until K4; no functional effect. Lint's `MissingTranslation` then fails for every old key (seen on the fix
  branch): step 5 adds `tools:ignore="MissingTranslation"` on the `values/` root until K4 completes the split.
- `LayoutSafetyTest` text scans pin names in files that move; they are updated, not deleted (I3).

## Open questions (all decided 2026-11-10 as recommended — kept for the reasoning)

1. **The three player findings** (`HANDOFF.md`): recommendation — *not* in this phase. (a) One audio session
   per player (`PlayerModule.kt:79-80`) and (b) manual skip = quick fade + loading state on the play glyph
   are small engine patches, testable on the unit on their own, best done as a separate fix branch first
   (`v0.1.1`); (c) D36 parallel resolution + quality ladder is resolver/engine work and gets its own design
   doc. Agree, or fold (a)+(b) into Phase 1?
2. **Repeat default** for a new queue: All (today's behaviour — a finished playlist starts over) or Off?
   Recommendation: All.
3. **"Wave by track"** stays inside the My Wave queue in Phase 1 (station switch + undo, as today) and
   becomes the Radio queue in Phase 2 — OK?
4. **FM radio** as a live-station mode beside the queues (the active queue is frozen while a station plays,
   restore shows the station as today) — OK?
5. **Deleting the active queue**: allowed (with the dialog); the next queue in the list becomes active,
   paused; if none is left, My Wave. OK?
6. **"Save as playlist"** in the queue menu (on the canvas): Phase 1 or later? Recommendation: later, with
   Phase 5 (favourites / playlist edit per provider).
7. **List-level "Add to queue…"** on album and playlist headers: included (see "What changes") — confirm, since
   the canvas only shows the track-level menu.
