# Phase 1b — History

Status: **approved 2026-11-12** (open questions 1–3 as recommended; 4 decided: filter by queue, the active queue by default — D50). Part of [multi-source-player.md](multi-source-player.md)
(Phase 1b, D46). Built on Phase 1's plumbing (`TrackRef`, Room, `QueueManager`). Code paths relative to
`android/app/src/main/java/org/tegro/yamp/`.

## Purpose

A good track heard once — in the wave, skipped past, half-listened in the car — can be found again. Every track
that *sounded* is recorded, app-wide across providers and accounts (D22), and shown in Library under **History**,
newest first, with the usual track actions.

## What changes for the user

- Library gains a row **"История"** ("History") between "Скачанные" and "Плейлисты", with the count of the last
  days' tracks. The page lists tracks newest first, grouped by day ("Сегодня", "Вчера", then dates), each row the
  usual `TrackItem` (cover, title, artist, ⋮). A track heard several times in a row appears once.
- **One list, filtered by queue** (D50): a chip row at the top — the **active queue first and selected by default**
  (queues are separate spaces), then the other queues, My Wave, Radio, and "Все" last. Each row records where it
  played (`queueId` + a name snapshot, so a deleted queue's history still reads); in "Все" the subtitle shows the
  queue name.
- **Tap a row = "play now, keep my queue"**: the track is inserted right after the cursor of the active queue and
  played; the queue goes on after it. (Playing from History never replaces the queue — D35; it is one track, not a
  list.) The ⋮ menu has everything the track menu has: Play next, Add to end of queue, Add to queue…, Like, Artist,
  Album.
- The page's header has "Очистить историю" (confirmation dialog). Settings → Playback → Content gets a switch
  **"Вести историю прослушивания"** (default on); off = nothing is recorded (the list stays until cleared).
- Phone and landscape in this phase; no car tile (D33's tile list stays), the car reaches it through Library later.
- Nothing is sent anywhere: History is local.

## Technical design

- **Room v3** (F1): table `history(id PK autoincrement, ref, trackId, title, artist, coverUrl, durationMs,
  startedAt, playedMs, queueId, queueName)`; indices on `startedAt` and `queueId`. Migration 2→3 + test (same pattern as
  `CacheDatabaseMigrationTest`). `deleteOrphanTracks` also keeps tracks referenced from `history`, so a tap can
  rebuild the DTO (`QueueTrackBridge.recall`). Capped at 5 000 rows (oldest pruned on insert).
- **Recording**: `data/history/ListeningHistory` (`@Singleton`): `started(track, queueName)` opens a row when the
  engine's `soundingTrackId` becomes the track (not when a start is requested, so a track skipped before it sounded
  is not listed); `playedMs` is updated when the track stops sounding (next, pause, end). The same track sounding
  again within 10 minutes of its last row updates that row instead of adding one. Called from `PlaybackController`
  where it observes `soundingTrackId` (the audible rule from 1.14.3) — one place. Write-through, one Room
  transaction per event (DPI rules).
- **Reading**: `HistoryDao.recent(limit)` and `recentOf(queueId, limit)`: `Flow<List<HistoryEntity>>`;
  `HistoryViewModel` (`presentation/history/`, H1: `UiState(filters: List<Filter>, selected, days: List<DaySection>,
  loading, enabled)`), the filter chips from `QueueManager.queues` + the queues that only exist in history, the
  active queue selected on open; grouping by local day in the VM with `DayKey` (today/yesterday/date), strings in
  the UI (D2).
- **UI**: `presentation/history/HistoryScreen.kt` — Library opens it as the other sections (`LibrarySection.HISTORY`
  with the existing `sectionName` pattern in `LibraryScreen`); rows use `TrackItem` with `LocalTrackMenu`; tap →
  `MainViewModel.playNowKeepQueue(track)` = `PlaybackController.playNowKeepQueue(track)`: `insertAfterCursor` +
  `playEntry` of the new entry (a new controller op, tested). Empty state: "Здесь появится то, что вы слушали".
- **Settings**: `SettingsRepository.listeningHistory` (DataStore, default true) + the switch; `ListeningHistory`
  checks it before writing; "Clear" = `HistoryDao.clear()`.
- **Strings**: EN in `values/`, RU in `values-ru/` (K1).
- **Tests**: migration 2→3; `HistoryDao` (insert/prune/merge-within-10-min); `ListeningHistory` on a fake clock;
  `DayKey` grouping; controller `playNowKeepQueue`; screenshots: History page phone + 1280 landscape, Library row.
- **Canvas**: one board `Design/yamp/history.jsx` (phone list by day; landscape list) added to the canvas sections.

## Affected files

New: `data/history/{HistoryEntities,HistoryDao,ListeningHistory}.kt`, `presentation/history/{HistoryScreen,
HistoryViewModel}.kt`, tests, `Design/yamp/history.jsx`, schema `3.json`. Changed: `CacheDatabase` (v3, orphan
rule), `DatabaseModule`, `PlaybackController` (sounding observer → history; `playNowKeepQueue`), `MainViewModel`,
`LibraryScreen` (row + section), `SettingsRepository(+Impl)`, `SettingsViewModel/Model/Sheet`, strings.

## Open questions (decided 2026-11-12)

1. **Tap = "play now, keep my queue"** (insert after the cursor and play) — recommended; the alternative
   (replace the active queue with the one track) throws the queue away.
2. **Merge window** 10 minutes for the same track (restarts, prev/next bouncing) — fine?
3. **Keep the wave's "recently played" (100 ids, rotor de-dup)** as is for now; History is the user-facing record.
   Phase 2 can feed the rotor's de-dup from History instead. OK?
4. **Per queue or one list?** One list with a queue filter, the active queue selected by default (user: the queues
   are separate spaces; D50).
