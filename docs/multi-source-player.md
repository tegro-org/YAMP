# Multi-source player: queues, radio, mixes, Jellyfin

Status: **draft — awaiting approval**. Moved from the YML clone on 2026-11-09 and re-based on upstream
YandexMusicLite `fac6af7` (1.12.2). Code paths below are relative to `android/app/src/main/java/com/korenskoy/yandexmusic/`.
Facts in "Current state" were re-checked against `fac6af7`; deeper details (e.g. how `WaveQueue` stores a
station) still need reading before Phase 1/2 design is final.

## Purpose

Turn YAMP (hard fork of YandexMusicLite) from a Yandex-only client into a player for several music sources (Yandex Music + a personal
Jellyfin server), with Musicolet-style multiple queues and YouTube-Music-style radio/mixes.

Goals:
- Several independent, persistent queues (car with wife / driving alone / audiobook), each resuming exactly
  where it stopped.
- "Radio from this track / album / artist" on any source, kept as a browsable queue.
- A mix builder over the user's own picks (artists, albums, playlists, genres, favorites).
- Favorites handled per provider, each with its native semantics.

Non-goals: syncing favorites between providers; transcoding settings UI (use server defaults first);
Plex (the abstraction must allow it later, nothing more).

## Design principles (from experience with Symfonium and Ultrasonic)

- **The queue is never lost.** Ultrasonic loses the play queue when Android kills the app. Every queue
  change (add/remove/reorder/track change) is written to Room immediately; position every 15 s and on
  pause. After process death or reboot the app restores the active queue paused at the saved position.
  Covered by an instrumented test that kills the process.
- **Folders in few taps.** Symfonium needs too many taps to reach a folder. Target: from the car home
  screen to playing a pinned/recent folder in **1 tap**, to any folder in the tree in **≤ 3 taps**
  (pinned folder → subfolder → play).

## Users' setup

Jellyfin and Airsonic-Advanced both serve **the same music folder on disk**. Both providers can be
connected, but the same track then exists twice with different ids (see open questions).

## Current state (upstream `fac6af7`, 1.12.2)

- **One queue.** `data/player/PlaybackController.kt` (1296 lines) holds the current playlist and source
  (`PlaylistSource`: LIBRARY, VIBE, SEARCH, TRENDS, RADIO (FM), NONE, …). Pure queue logic in
  `PlaybackQueue.kt`; the wave/station queue is split out into `WaveQueue.kt`.
- **Persistence** is one global JSON file tagged with the account folder (`data/cache/PlaybackStateStore.kt`): source, a window of
  50 tracks before / 150 after the current one (`PlaybackQueue.SNAPSHOT_*`), current track id, position.
- **Room** (`data/cache/CacheDatabase.kt`) already exists: `tracks`, `list_items`, `cache_values`.
- **Yandex types everywhere.** DTOs from `data/dto/` are imported in 38 files, 20 of them in
  `presentation/`. No provider interface. ~25.6k lines of Kotlin in 133 files.
- **Radio:** rotor `rotor/station/{station}/tracks` with `user:onyourwave` (My Wave) and `track:<id>`
  ("wave by track", 1.12.0). FM radio stations are a separate `RADIO` source.
- **Favorites:** liked tracks (+ add/remove), dislikes. No liked albums/artists/playlists endpoints.
- **Sign-in:** WebView OAuth page **or** OAuth device flow (`data/auth/DeviceSignIn.kt`: code shown on
  the head unit, confirmed at ya.ru/device). Both use the official Yandex Music `client_id`.
- **Offline:** "road reserve" (`data/reserve/`) keeps tracks for driving without signal.
- **Token leak fixed upstream** (1.9.4): the updater uses an `@UpdateClient` OkHttp client without
  `AuthInterceptor`. See `kb/privacy-audit.md`.

## Phases

Each phase is shippable on its own and is reviewed/tested before the next starts.

### Phase 0 — Fork identity (no feature work)

- Own `applicationId`, app name "YAMP", own signing key; updater points at `tegro-org/YAMP` releases.
- CI rebuilt for the `android/` subfolder (upstream workflows kept for reference in
  `kb/upstream/github-workflows-reference/`).
- Package rename (`com.korenskoy.yandexmusic` → own namespace) — decide in the architecture rules
  (cost: touches every file once; makes later upstream ports slightly harder to compare).
- The token leak from the original audit is already fixed upstream — nothing to do.

### Phase 1 — Multiple queues (Yandex only)

**Model** (Room, new tables; DB migration):

| Table | Columns |
|---|---|
| `queues` | `id`, `name`, `kind` (`MANUAL`, `RADIO`, `MIX`, `WAVE`), `seed` (JSON: what a radio/mix was started from), `currentIndex`, `positionMs`, `shuffle`, `repeat`, `updatedAt`, `sortOrder` (app-wide; `account` only on `WAVE` queues) |
| `queue_items` | `queueId`, `index`, `provider`, `trackId`, `originalIndex` (for un-shuffle), cached title/artist/cover/duration |

- Items store **provider + trackId** from day one so Phase 3 needs no data migration.
- The whole queue is stored (no 50/150 window); a queue is a list of ids, so 5 000 tracks is small.
- Position is saved per queue: on switch, on pause, and every 15 s as now.

**Behaviour:**
- One **active** queue drives the player; the others are frozen with their track + position.
- Switching queues: save the active one → load target → show paused at saved position (same as today's
  restore) — auto-play if the player was playing.
- Playing an album/playlist/folder: **always replaces the content of the active queue** (as now). Other
  queues are touched only explicitly ("Add to queue…"). No confirmation dialog.
- **Queues are app-wide** (D22): switching or signing out an account never hides a queue. Entries whose
  provider is not signed in show as unavailable and are skipped during playback.
- My Wave is a queue of kind `WAVE`: one per account, not editable, extended from rotor as now.
- Queue actions: create, rename, delete, reorder, duplicate, clear; track actions: "Add to queue…",
  "Play next", "Add to end".
- Existing `playback_state.json` is migrated into a first queue called "Main", then deleted.

**UI:**
- **No full queue-management screen** (unlike Musicolet). A **queue button** on the home screen, the
  player and car mode opens a **popup**: list of queues (name, current track, progress), tap = switch,
  "+" = new queue, long-press = rename / delete / clear.
- The track list of the active queue stays where it is today (player's "up next").
- Android Auto / media browser (`MediaLibraryTree`): a "Queues" folder.

### Phase 2 — Radio (Yandex), YouTube Music semantics

- "Start radio" on a track / album / artist creates a new queue of kind `RADIO` with `seed` set.
  Yandex: rotor stations — `track:<id>` already exists upstream ("wave by track", `WaveQueue.kt`);
  `album:<id>`, `artist:<id>` to be verified against the live API. Reuse upstream's station code, do not
  build a second one.
- **Persistent list:** fetched tracks are appended to the queue and never replaced, so back/forward through
  history works. When near the end, the next batch is appended (like My Wave today).
- **Re-run = new radio:** "Start radio" again on the same seed creates a new queue; the old one stays until
  deleted. Old radios can be auto-pruned (setting: keep last N radios, default 10).
- Rotor feedback (skip/like) is sent for Yandex radio queues as it is for My Wave.

### Phase 3 — Provider abstraction

- `domain/model`: neutral `Track`, `Album`, `Artist`, `Playlist`, `Genre`, each with `ProviderRef(provider, id)`.
- `MusicProvider` interface: account/auth, search, library (playlists, albums, artists), item details,
  `streamSource(track)` (URL + headers + optional decryptor), favorites, radio, lyrics (optional).
- Capabilities flag set per provider (`WAVE`, `DISLIKE`, `LYRICS`, `PLAYLIST_EDIT`, …) → UI hides what
  is not supported.
- `YandexProvider` wraps today's `YandexMusicApi` + repositories; DTO → domain mappers.
- UI and `PlaybackController` move from `TrackDetailsDto` to `Track`. This is the largest phase
  (~34 files); done screen by screen behind the mappers, with screenshot tests kept green.

### Phase 4 — Jellyfin provider

- **Auth:** server URL + username/password → `POST /Users/AuthenticateByName` → access token stored
  in `KeystoreStringPreferences` (same as Yandex tokens). Password is not stored.
- **Browse:** `/Items` (Audio, MusicAlbum, Playlist), `/Artists`, `/MusicGenres`, `/Search/Hints`.
- **Stream:** `/Audio/{id}/universal` (server picks direct play vs transcode); covers
  `/Items/{id}/Images/Primary`.
- **Network: HTTPS only.** The server URL must be `https://`; `network_security_config.xml` stays
  unchanged (no cleartext). The certificate must be trusted by Android (e.g. Let's Encrypt via reverse
  proxy); self-signed certificates are not supported.
- **Downloads/offline:** same `TrackDownloadManager`, plain files (no decryption).
- Play reporting `/Sessions/Playing*` — optional, so Jellyfin's "recently played" stays correct.

### Phase 4b — Jellyfin folder browsing

The collection is organised by folders that tags cannot express (studio albums vs live shows vs
compilations like "Romantic Collection"), so the physical folder tree is a first-class way to browse.

- **API:** start from the user's music libraries (`/UserViews`), then `/Items?ParentId={id}` level by level;
  folders (`Folder`, `MusicAlbum`) first, then tracks; sort by name, tracks by disc/track number.
  Exact behaviour to be checked against the user's server (Jellyfin's own web UI has the same "Folders" view).
- **User's layout:** one music library; the root is a flat list of ~30+ top folders, named by hand and not
  matching tags: artist (`Pink Floyd`), discography (`Fleur - Дискография (2000-2026)`), format
  (`Metallica (mp3)`), split by kind (`Мельница & Хелависа - альбомы` / `- концерты`), compilations
  (`RC - Sorted`). Mixed Latin and Cyrillic. Hence:
  - the Folders view opens **directly at the library root** (skip the library level when there is one);
  - natural sort (Latin then Cyrillic, case-insensitive, numbers by value);
  - A–Z quick-scroll covers both alphabets;
  - a filter box on every level (type "мел" → Мельница folders).
- **Browse modes** for Jellyfin side by side: Folders / Artists / Albums / Genres / Playlists / Favorites.
- **Play a folder:** play / shuffle / "start radio" / "add to queue…" on any folder, recursive
  (`Recursive=true&IncludeItemTypes=Audio`), album order kept unless shuffled.
- **Fast navigation (car):**
  - big tiles with cover (folder image or first album cover), breadcrumb with one-tap jump to any level;
  - remembers last opened folder; "Pinned folders" and "Recent folders" on the car home screen;
  - A–Z quick-scroll for long levels.
- **Android Auto / media browser:** a "Folders" node in `MediaLibraryTree`, playable at every level.
- **Mix builder:** folders are a source like artists/albums.
- Yandex has no folders; it gets the equivalent split by album type (albums / singles / compilations,
  from artist `brief-info`) on the artist page.

### Phase 4c — Subsonic provider (Airsonic-Advanced, also Navidrome, gonic) — deferred

**Low priority:** implemented after Phase 6, only if Airsonic-Advanced is still in use. Kept here so the
provider abstraction (Phase 3) covers its needs (password-based auth, native folder API).

Generic Subsonic / OpenSubsonic provider; tested against the user's Airsonic-Advanced.

- **Auth:** server URL + username/password. Subsonic signs every request with `t = md5(password + salt)`,
  `s = salt`, so the **password itself must be stored** (Keystore-encrypted, like tokens). If the server
  advertises the OpenSubsonic `apiKeyAuthentication` extension, use an API key instead. Never use the
  legacy `p=` plain/hex password parameter.
- **Folders (native):** `getMusicFolders` → `getIndexes` → `getMusicDirectory` — the physical tree,
  same UI as Phase 4b.
- **Tags view:** `getArtists`, `getArtist`, `getAlbum`, `getAlbumList2` (recent, frequent, random…).
- **Favorites:** `star` / `unstar` (song, album, artist), `getStarred2`.
- **Radio:** `getSimilarSongs2` / `getTopSongs` (needs Last.fm configured on the server); if empty →
  fall back to a mix from the same artist + genre.
- **Random / mixes:** `getRandomSongs` (genre, year filters) + the mix builder.
- **Stream / covers:** `stream` (server transcoding as configured), `getCoverArt`.
- **Search:** `search3`. **Lyrics:** OpenSubsonic `getLyricsBySongId` when available, else `getLyrics`.
- **Scrobble:** `scrobble` so the server's play counts / "recent" stay correct.
- Server-side `savePlayQueue` is not used: queues live in the app (multiple, Room).

### Phase 5 — Favorites per provider

- Library → Favorites with tabs Tracks / Albums / Artists / Playlists, for the **active provider**.
- Yandex: add the missing `likes/albums` and `likes/artists` (read + `add-multiple` / `remove`).
- Jellyfin: `Filters=IsFavorite`, `/Artists?IsFavorite=true`, `POST/DELETE /UserFavoriteItems/{id}`;
  heart state from `UserData.IsFavorite`.
- Dislike shown only where the provider supports it (Yandex).

### Phase 6 — Jellyfin radio + mix builder

- **Jellyfin radio** = Instant Mix: `/Items/{id}/InstantMix`, `/Artists/InstantMix`,
  `/MusicGenres/{name}/InstantMix`. Same `RADIO` queue semantics as Phase 2; refill fetches again and
  appends only tracks not already in the queue.
- **Mix builder:** pick sources (artists, albums, playlists, genres, favorites, another queue; any
  provider) + rules:
  - exclude played in the last N days;
  - max N consecutive tracks per artist;
  - favorites weight;
  - year/decade range; exclude audiobooks/podcasts.
  Result is a `MIX` queue: the pool is resolved, then shuffled with the rules. **Persistent** like radio;
  "Re-run" creates a new queue. Mix definitions can be saved and re-run later.
- Live (endless) mixes are a later extension: the seed is already stored, so appending more is possible.

## Affected files (main ones)

- `android/app/build.gradle.kts`, `data/updater/GitHubUpdateManager.kt`, CI workflows (P0)
- `data/player/PlaybackController.kt`, `PlaybackQueue.kt`, `WaveQueue.kt`, `MediaLibraryTree.kt`, `MusicService.kt`,
  `data/cache/CacheDatabase.kt`, `PlaybackStateStore.kt` (P1–2)
- `presentation/player/*`, `presentation/car/CarScreen.kt`, `presentation/main/*` (P1–2, P3)
- `data/network/YandexMusicApi.kt`, `data/dto/ApiModels.kt` (P2, P5)
- New: `domain/model/*`, `domain/provider/MusicProvider.kt`, `data/provider/yandex/*`,
  `data/provider/jellyfin/*`, `data/queue/*`, `presentation/queues/*`, `presentation/mix/*`

## Risks and side effects

- **Hard fork, no upstream merges.** Upstream changes are reviewed and ported/reimplemented by hand,
  tracked in `kb/upstream-tracking.md`.
- **Update channel:** until Phase 0 is done the updater still points at `KorenskoyR/YandexMusicLite`;
  a YAMP build must never be released before that, or it would offer to "update" itself to upstream.
- Rotor `track:/album:/artist:` stations are unofficial API — may change or need extra params.
- Room migration must be tested from the current schema; failed migration must not lose downloads.
- Android Auto browse tree gets deeper; keep within its item limits.

## Open questions

None at the moment.

## Resolved questions

1. Queues → **app-wide**, not per account (revised 2026-11-09, `kb/decisions.md` D22).
2. Jellyfin transport → HTTPS only.
3. "Play album" → replaces the active queue; switching queues via a popup from a queue button.
4. Updates → `tegro-org/YAMP` GitHub releases (public repo; own `applicationId` + signing key).
5. Library → one music library, flat hand-named top folders (see Phase 4b).

## Decisions

- **Jellyfin is the primary self-hosted backend** (Phases 4, 4b, 6). Subsonic (Phase 4c) is lower priority,
  done after Phase 6 and only if Airsonic-Advanced is still in use then.
- Airsonic-Advanced is used today only as a workaround for having **two queues** (Ultrasonic on Airsonic for
  driving with the wife, Symfonium on Jellyfin for driving alone). Multiple queues (Phase 1) on Jellyfin
  replace this, so Phase 4c is expected to be dropped.
- **No duplicate merging.** With Jellyfin primary, the same files via Airsonic are not de-duplicated; if
  both are ever connected, each provider is browsed separately.
