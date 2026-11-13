# As-is: playback subsystem (upstream `fac6af7`) — and the Phase 1 / Phase 2 state

**Phase 1 (2026-11-12) changed the queue side; the engine side below still holds.** Current structure:

| Component | Role after Phase 1 |
|---|---|
| `domain/queue/*` | Pure model: `MediaRef`/`TrackRef` (C1), `PlayQueue`/`QueueEntry` (entry-id cursor), `QueueStore`, `DynamicSource`, `QueuePolicy` (next/prev/shuffle, repeat modes). |
| `data/queue/QueueManager` | Owns the list of queues and the active one (`ActiveQueue`: entries + DTOs aligned + cursor index); every mutation written through `RoomQueueStore` before it returns (E3), serialised by a mutex. |
| `data/queue/RoomQueueStore`, `QueueDao`, `QueueEntities` | Room v2 tables `queues`, `queue_entries`; the active queue id in `cache_values`. |
| `data/queue/QueueTrackBridge` | Entries ↔ cached `TrackDetailsDto` (`tracks` table) until Phase 3's neutral `Track`. |
| `data/queue/LegacySessionImport` | One-time import of `playback_state.json` into the first queue. |
| `PlaybackController` | No longer owns a playlist: `currentPlaylist`/`currentTrack`/`currentSource` mirror the active queue; `playFrom` (replace) and `playEntry` (move) are separate (E5); queue ops run in a FIFO chain (`queueOp`); FM radio is a live station beside the queue (D41); the offline mix is its own queue; My Wave is the account's WAVE queue, still fed by `WaveQueue` (which also keeps its own list for the wave page — dual storage until Phase 2). |
| `PlaybackStateStore` | Only read by the import; deleted after it. |
| `data/queue/RotorStationSource`, `RotorStations` (Phase 2) | The first `DynamicSource`: one rotor station (`track:`/`album:`/`artist:` from the RADIO queue's seed), two requests per batch (continued from the queue's last entry + fresh), de-dup vs the queue + recently played, explicit filter, station feedback. `RotorStations` holds the station-id / `from` / de-dup helpers shared with `WaveQueue` (which is otherwise unchanged, D53). |

**Phase 2 (2026-11-13, `docs/phase-2-radio.md`) — radio queues.** Any number of `RADIO` queues (D35′), Room v4
adds `queues.keepHistory` (D52). Refill is generalised beside the wave, never instead of it: each of the
controller's `kind == WAVE` sites (track start ≤ 20 left + `trackStarted`, finished/skip feedback, ≤ 10 ahead in
`prepareNextTrackInQueue`, the end-of-queue advance, "load more") has a `RADIO` branch next to it, and
`keepRadioShelfFilled` mirrors `keepWaveShelfFilled` (≥ 3 ahead, back-off, online + signed in). The controller
holds one source for the active radio (`radioSourceOf`, lazy; replaced when another radio becomes active, dropped
on re-seed); a batch lands only while its source is still held and its queue still active — an inactive radio
gets no requests and keeps its tracks, cursor and position. Trim: `QueueManager.appendFetched` on a RADIO queue
with Keep off drops entries more than `RADIO_BEHIND = 3` behind the cursor (`QueuePolicy.radioTrim`) in the same
mutation — so Keep off trims at the next refill, not when switched. "Start radio" = `PlaybackController.startRadio`
→ `QueueManager.startRadio(New | Replace)`; the UI picks wave vs radio in `MainViewModel.waveOrRadio` (D54).

Tests: `PlaybackControllerTest` (14, fake engine + resolver), `QueueManagerTest` (10, fake store), `RoomQueueStoreTest`,
`CacheDatabaseMigrationTest`, `LegacySessionImportTest`, `QueuePolicyTest`. Device cases: `scripts/device-check.sh`.

---


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

## Known problems (observed by the user on the phone, 2026-11-10; verified in code)

Weak mobile link, Yandex Plus active, "Максимальное" quality. Line numbers are upstream `fac6af7`.

1. **"Next" looks like a hang — the old track keeps playing for ~10 s.** `playNext` (`PlaybackController.kt:451`)
   → `playTrack` with `crossfade = true` (:319) → `resolveAndPlay` (:784): if the URL is not pre-warmed, a full
   best-mode resolve (2–3 HTTP calls, 15 s timeouts). Then `PlayerRepositoryImpl.startTransition` (:338) keeps
   the **outgoing player at full volume** while it waits for the incoming one to render, up to
   `INCOMING_READY_TIMEOUT_MS = 8 s` (:574). Only then `READY_TIMEOUT` → recovery RETRY (`PlaybackQueue.kt:111`)
   → fresh resolve at the **same quality**. There is no loading state in `FullScreenPlayer` / `PlayerControls`
   (`isBuffering` is consumed only by the home shimmer and the car ring) and `isPlaying` stays true while
   starting (:111).
2. **FLAC is chosen regardless of the link and never downgraded.** `resolveBest` (`TrackPreloadManager.kt:129`):
   lossless via `LosslessSigner` → HQ mp3 → download-info. No input is the link state, the stall count or the
   failure reason; RETRY re-resolves FLAC again; the recovery ladder is RETRY → copy → continue offline → skip /
   stop, never "lower quality". With link WEAK and nothing on the device, `CONTINUE_OFFLINE` → `playOfflineMix`
   (:1070) pauses with `waitingForConnection`; WEAK is cleared only by audible playback or a network flap
   (`LinkTracker.kt:32-54`) — hence "played after ~15 minutes". The "weak link" state only chooses between a
   road-reserve copy and streaming (`RoadPlan.originFor`, `LinkTracker.kt:73`); it never changes the request.

3. **Crossfade on "next": the old track seems to stop, the new one rises, then the old one reappears quieter
   and fades.** The crossfade itself is sound: both players run on `Main.immediate`, the outgoing player is
   never paused before its 3 s equal-power fade ends (`PlayerRepositoryImpl.startTransition` :338-385,
   `runCrossfade` :391-421), audio focus has one owner (`MusicService.kt:121-132`) and the players are built
   with `handleAudioFocus = false` (`di/PlayerModule.kt:54-59`). What is wrong: since upstream 1.12.0
   (`6a91f49`) **both ExoPlayers share one audio session id** (`PlayerModule.kt:79-80`, from
   `SystemEqualizer.audioSessionId`) so the phone's equalizer attaches to it. When a system effect with volume
   control (equalizer, bass boost, Dolby) sits on a session, the mixer applies **one volume per session**, set
   by whichever track was processed last: at fade start the incoming player's volume 0 silences the outgoing
   track, then the two values alternate and the effect's smoothing produces the "reappears quieter" blur.
   Observed on the **head unit**, whose vendor sound chain (OEM equalizer / DSP) is attached to every media
   session and cannot be switched off — hence "almost every time" there. **Confirmed by the control test
   (2026-11-10): on the phone the crossfade is correct** (only the 2–3 s FLAC delay of problem 1 remains). The
   crossfade must be re-tested on the unit once the sessions are separated. **Verified on the head unit
   2026-11-11:** with the quick skip fade alone the blur was reduced; with "Separate audio sessions" on it was gone (D45).

### What YAMP must do differently (input to Phase 1 and the C4 resolver; proposed rules, not yet approved)

- A **manual skip quick-fades the current track at once** (about half a second) while the next one is
  requested: if the next is ready before the fade ends it comes in under it (a short crossfade); if not, the
  fade ends in silence and the play glyph shows loading until the stream is ready. The user's long crossfade
  setting applies to automatic transitions only. Next pressed again while loading retargets to the following
  track.
- The resolver takes **preferences with a link hint and a set of qualities to avoid** (what already failed) and
  returns the chosen variant **plus the alternatives**, so the engine can downgrade without a new round trip.
- Recovery ladder: retry same → **retry lower quality** → copy → skip. A FLAC that stalls or times out is
  retried as MP3; on a weak link lossless is not requested and a cached MP3 beats a fresh FLAC.
- Ready timeout per codec (FLAC needs more data before it renders); on timeout the outgoing player is stopped,
  not left playing until the retry.
- WEAK expires by itself; "waiting for connection" retries periodically instead of waiting for a network flap.
- **One audio session per player, never shared** (the system equalizer is attached to each session, or the
  two tracks are mixed in-app into one sink so system effects see a single stream); exactly one audio-focus
  owner; the crossfade stays a single main-thread state machine in which the outgoing player is never paused
  before its fade ends.
- **Variants are resolved in parallel; playback starts with the one that fits the link; quality upgrades at the
  next track boundary** (D36): the standby player prepares the next track in the higher quality when the
  measured link allows. **The next track is prepared early, gated by buffer health, not by a fixed point:**
  the standby player starts buffering it as soon as the current track has a healthy buffer ahead (≈30 s)
  and the link is not weak — seconds after the start on a good link, later on a slow one, so the two downloads
  never compete. The quality of the next track is chosen from the measured throughput of the current one.
  A skip after that point is a crossfade; loading appears only for a skip before it or two tracks ahead.
  The road reserve (full downloads to disk on a good link) stays as today. No mid-track stream switching (not sample-aligned → audible; doubles data on a weak
  link; Yandex serves whole files, not adaptive segments) — at most a later experiment.
- Smallest upstream-style fixes if wanted before Phase 1: `crossfade = false` for user skips; a `preferMp3`
  input to `resolveBest` / `fetchOrAwait` set when WEAK or after a lossless READY/BUFFERING timeout; spinner on
  the play glyph from `isBuffering`.
