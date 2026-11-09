# As-is: playback subsystem (upstream `fac6af7`)

Paths relative to `android/app/src/main/java/com/korenskoy/yandexmusic/`. Parent:
[ARCHITECTURE.md](../../ARCHITECTURE.md).

## Components

| Component | Role |
|---|---|
| `data/player/PlaybackController` (`@Singleton`, 1296 lines) | **Single source of truth** for queue + every playback decision: source, playlist, current track, restore position, wave seed/tuning, quality, link state; next/prev, recovery, persistence, wave, offline. Scope `SupervisorJob + Main.immediate`. |
| `data/repository/PlayerRepositoryImpl` | Engine: **two ExoPlayers** ping-pong (active + standby for preload/crossfade). Each holds exactly **one `MediaItem`** — no ExoPlayer playlist. Emits `trackEnded`, `prepareNextTrack` (15 s before end), skip, error events. Never decides what's next. |
| `domain/repository/PlayerRepository` | Engine interface keyed by `trackId, url, title, artist, coverUrl` (DTO-free) but leaks `ExoPlayer`. |
| `data/player/MusicService` (`MediaLibraryService`) | Own audio-focus handling; rebinds session to a `ForwardingPlayer` on player swap; forwards transport to the controller, ignores `setMediaItems/prepare`; `onTaskRemoved` persists. |
| `PlaybackQueue` | Pure, unit-tested: `PlaylistSource`, `NextAction`, `Recovery`, `previousIndex` (3 s rule), `snapshotWindow`, `moved`… |
| `WaveQueue` | Wave/station track list, fetch de-dup, generation counter; created inside the controller. |
| `MediaLibraryTree` | Android Auto browse tree. |
| Others | `PlayRequests` (who started playback), `SessionButtons` (like/dislike), `SleepTimer`, `TrackReactions`, `BluetoothAutoplay`, `LinkTracker`/`RoadPlan`, `SoundMode`, `Loudness`, `SystemEqualizer` |

### Flow: tap a track → play

`PlaylistsViewModel.playTrack` → `PlaybackController.playTrack(id, tracks, LIBRARY)` (replaces the queue,
preloads next 3 URLs, prepares standby, wave refill if VIBE) → `resolveAndPlay` (radio URL | downloaded
file | `RoadPlan` COPY/STREAM → `TrackPreloadManager.getOrFetchUrl`) → `PlayerRepositoryImpl.playTrack` →
`resolvePlayUri` (local file → cached URL → given URL) → swap/crossfade.

### Flow: track ends → next

Engine emits `prepareNextTrack` at −15 s → controller prepares standby. `trackEnded` (crossfade window or
`STATE_ENDED`) → sleep timer check → `playNext(isAuto)` → `PlaybackQueue.nextAction`: `PlayIndex` |
`FetchMoreWave` | `FallbackLibrary`.

## Queue model

- In memory: `_currentPlaylist: StateFlow<List<TrackDetailsDto>>`, `_currentTrack`, `_currentSource`.
- **No current index**: position recomputed by `indexOfFirst { idString == currentId }` → duplicate tracks in
  a queue misbehave.
- `PlaylistSource { LIBRARY, VIBE, SEARCH, TRENDS, RADIO, NONE }` mixes origin and behaviour. VIBE: fetch more
  at end, rotor feedback, refills at ≤20/≤10. Others: loop to index 0. FM radio via `track.isRadio`.
- **Shuffle is one-shot** (`.shuffled()` before handing the list). No shuffle mode, no original order, no repeat.
- Edits: reorder, enqueueNext, remove, move (re-prepare standby when next changes), dislike removal.
- Wave/radio: stations `user:onyourwave`, `track:<id>`; two parallel `getStationTracks` per batch, de-dup vs
  queue + recently played (100), appended to queue; only My Wave saved to Room; `startTrackWave` /
  `undoTrackWave` / `returnToMyWave`; `keepWaveShelfFilled` ≥3 ahead with backoff.
- Preload: next 3 URLs warmed; standby player loaded at −15 s; `ReserveKeeper` caches upcoming to disk.

## Persistence & restore

- `PlaybackSnapshot(userFolder, source, queue: List<TrackDetailsDto>, currentTrackId, positionMs, waveSeed)`
  in one global `filesDir/playback_state.json` (temp + rename). Other account's snapshot ignored.
- Window **50 before / 150 after** current.
- Saved on playTrack, pause, wave switches, sleep end, every 15 s while playing, `onTaskRemoved`.
  **Not** immediately after queue edits.
- Restore in controller `init`: recent + wave first, then snapshot → track + position shown **paused**;
  ExoPlayer loaded only on `play()`. Serves `onPlaybackResumption`.
- Lost on process death: queue beyond window, sleep timer, undo/return points, failure counters, resolved
  URLs, standby player.

## Stream resolution (what a provider must wrap)

- Entry: `data/network/TrackPreloadManager.getOrFetchUrl(trackId): String?` — URL cached 45 min, in-flight
  de-dup; side tables `qualities`, `offered`.
- **Compatible mode**: `download-info` → `StreamVariants.ranked` (AAC/MP3) → XML → `Md5Helper` signed URL.
- **Best mode**: `get-file-info` signed by `LosslessSigner` (HMAC, 4 spoofed client profiles; FLAC only) →
  HQ mp3 ≥256 → download-info fallback. Encraw: URL gets `#encraw_key=<hex>` fragment.
- `EncrawDataSourceFactory` strips the fragment and wraps in Media3 `AesCipherDataSource` (nonce/seek
  equivalence with `EncrawDecryptor` not verified). `CardAwareDataSourceFactory` decrypts SD-card `.enc`.
- Offline files decrypted at download time; road-reserve copies played as `file:` URIs.
- **Contract today**: in = Yandex track id string (maybe `id:album`); out = URI (maybe with `#encraw_key`) +
  `StreamQuality`; plus downloaded-file lookup and `evictUrl`. The engine itself also calls
  `preloadManager.playableCopy/getResolvedUrl` → engine coupled to the Yandex resolver.

## Media session / Android Auto

- Tree: `wave`, `liked/`, `downloaded/`, `playlists/<kind>/…`, `radio/`; prefix-encoded ids.
- Play from tree stores a `pendingStart` lambda run on next `play()` (Android 15 audio-focus rule).
- Custom commands LIKE/DISLIKE; full commands only for trusted controllers/car hosts.
- **Queue is not exposed as a Media3 timeline**; session shows the single active item.

## Yandex coupling inside the player

`TrackDetailsDto` is the queue element (controller 31 refs, WaveQueue 12, tree, reactions, reserve, snapshot).
Also: cover URL templating, `r128` loudness, `radio_` prefixes, rotor calls from controller/WaveQueue,
`isYandexReachable`, `hasPlus`.

## Threading & recovery

- Controller/engine on `Main.immediate`; ExoPlayer on Main; stale results guarded by `startRequestId`.
- `PlaybackQueue.recoveryFor`: offline → copy/continue offline; first failure → RETRY fresh URL; then copy /
  continue offline / STOP after 3 failed in a row / SKIP. Sources: player error, empty URL, ready timeout
  (8 s), resume idle, URL resolve, buffering timeout (15 s), stall watchdog (6 s).
- Offline: probe every 30 s; offline mix from reserve + downloads with return point; auto-resume.
- User messages: `SharedFlow<String>`, Toast when no UI collects.

## Seams identified

- **Multi-queue**: extract queue state into a `PlayQueue` with `queueId`; controller points to the active
  one; Room tables replace the snapshot; switching = persist old + restore-style load of new (paused).
  Needs **entry ids** (duplicates), no window, write-through on edits, `PlaylistSource` split into kind
  vs origin, one dynamic source per wave/radio queue, "move cursor" separate from "replace queue".
- **Provider**: `TrackRef(provider, id)` + neutral `Track`; per-provider `StreamResolver` returning
  `ResolvedStream(uri, quality, headers, key?, expiresAt)`; encraw stays inside Yandex; per-provider header
  hook in the data-source chain (Jellyfin auth); engine receives a final URI only; rotor/likes/reachability/
  Plus behind provider capabilities.
- **Radio/mix**: `WaveQueue` ≈ generic dynamic queue source; parameterise with `fetchBatch` + feedback.
- **Risk**: controller has no direct tests (only pure helpers are tested).
