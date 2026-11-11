# Multi-source player: queues, radio, mixes, Jellyfin

Status: **approved** (2026-11-09, D28). Each phase still gets its own design doc before code where noted. Moved from the YML clone on 2026-11-09 and re-based on upstream
YandexMusicLite `fac6af7` (1.12.2). Code paths below are relative to `android/app/src/main/java/com/korenskoy/yandexmusic/`.
Facts in "Current state" were re-checked against `fac6af7`; deeper details (e.g. how `WaveQueue` stores a
station) still need reading before Phase 1/2 design is final.

Aligned on 2026-11-09 with the approved [ARCHITECTURE.md](../ARCHITECTURE.md) and
[DESIGN_GUIDE.md](../DESIGN_GUIDE.md) — their rules are binding and are referenced below by id (C1, E2, …);
decisions D1–D25 are in `kb/decisions.md` (below, `Dn` always means a decision unless it says "ARCHITECTURE").
Where this doc and those rules disagree, the rules win.

## Purpose

Turn YAMP (hard fork of YandexMusicLite) from a Yandex-only client into a player for several music sources
used **at the same time** (Yandex Music + a personal Jellyfin server; later also local files), with
Musicolet-style multiple queues and YouTube-Music-style radio/mixes — equally good on the phone and on the car
head unit (D23).

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
  A concrete cause on the user's head unit is the per-app DPI tool, which **force-stops** an app (no lifecycle
  callback at all) and restarts it, sometimes within a second or two of launch — so nothing may be saved only
  in a callback, and start-up must never overwrite saved state. Rules and test cases:
  `kb/head-unit-dpi-app.md`.
- **Folders in few taps.** Symfonium needs too many taps to reach a folder. Target: from the car home
  screen to playing a pinned/recent folder in **1 tap**, to any folder in the tree in **≤ 3 taps**
  (pinned folder → subfolder → play).
- **Phone and car are equal targets** (D23). Every new screen is designed for the phone (portrait) and for
  the head unit; the current Hi-Fi glass look on the phone is the visual reference.
- **Capabilities, not provider checks** (C3, C7). A feature is shown when at least one signed-in provider
  supports it; no `if (provider == YANDEX)` in UI code.
- **Strangler, not rewrite** (A1). Old code keeps working; each phase moves only what it needs.

## Users' setup

Jellyfin and Airsonic-Advanced both serve **the same music folder on disk**. Both providers can be
connected, but the same track then exists twice with different ids; duplicates are not merged (see
"Decisions").

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

- Package and `applicationId` → **`org.tegro.yamp`** (D16), renamed once: touches every file; upstream ports
  then map `com/korenskoy/yandexmusic/` → `org/tegro/yamp/` (note in `kb/upstream-tracking.md`).
- App name "YAMP"; updater points at `tegro-org/YAMP` releases.
- **Own release keystore** — none exists yet. Created once, kept outside the repo and backed up; CI gets it
  as secrets. Upstream's updater accepts only the same signer, so YAMP builds never update from upstream ones.
- CI rebuilt for the `android/` subfolder (upstream workflows kept for reference in
  `kb/upstream/github-workflows-reference/`).
- Guards that pin the old identity are updated, not deleted (I3): `LayoutSafetyTest` (`app_name`,
  `applicationId`), `BaselineProfileGenerator` package name.
- The token leak from the original audit is already fixed upstream — nothing to do.

### Phase 1 — Multiple queues (Yandex only)

**First, before restructuring:** `PlaybackController` gets direct tests with a fake engine and a fake stream
resolver (E6) — today only its pure helpers are tested.

**Model** (E2; Room, new tables, `CacheDatabase` v1 → v2 with a migration and a migration test, F1):

| Table | Columns |
|---|---|
| `queues` | `id`, `name`, `kind` (`STATIC`, `WAVE`, `RADIO`, `MIX` — behaviour), `origin` (where it came from: album, playlist, search… — display only), `seed` (JSON: what a radio/mix was started from), `cursorEntryId`, `positionMs`, `shuffle`, `repeat`, `updatedAt`, `sortOrder` (app-wide; `account` only on `WAVE` queues) |
| `queue_entries` | `entryId`, `queueId`, `position`, `ref` (`TrackRef` as `"<provider>:track:<id>"`, C1), `originalPosition` (for un-shuffle), cached title/artist/cover/duration |

- The cursor is an **entry id, never a track lookup** — the same track may be in a queue twice (today's
  lookup by track id breaks on duplicates).
- `kind` and `origin` replace today's `PlaylistSource`, which mixes behaviour and origin.
- Entries store a provider-scoped `TrackRef` (`yandex:track:12345`) from day one so Phase 3 needs no data migration.
- The whole queue is stored (no 50/150 window); a queue is a list of refs, so 5 000 tracks is small.
- **Write-through** (E3): every mutation (add / remove / reorder / cursor move) is written to Room in the same
  call path; position every 15 s and on pause, queue switch and `onTaskRemoved`.
- Shuffle becomes a per-queue mode with the original order kept (today it is a one-shot reshuffle); repeat is
  per queue.

**Behaviour:**
- **Which queues exist** (D35): the user's own queues (never touched automatically), My Wave per Yandex
  account, one radio queue, one queue per saved mix, one "Last mix".
- One **active** queue drives the player; the others are frozen with their track + position.
- Switching queues: save the active one → load target → show paused at saved position (same as today's
  restore) — auto-play if the player was playing.
- Playing an album/playlist/folder: **always replaces the content of the active queue** (as now). Other
  queues are touched only explicitly ("Add to queue…"). No confirmation dialog.
- "Replace the queue" and "move the cursor" are separate controller operations (E5; today `playTrack` does
  both).
- **Queues are app-wide** (D22): switching or signing out an account never hides a queue. Entries whose
  provider is not signed in show as unavailable and are skipped during playback.
- My Wave is a queue of kind `WAVE`: one per account, not editable, extended from rotor as now — through a
  per-queue `DynamicSource` (`fetchBatch`, `feedback`; a generalised `WaveQueue`, E4) that only appends.
- Queue actions: create, rename, delete, reorder, duplicate, clear; track actions: "Add to queue…",
  "Play next", "Add to end".
- Existing `playback_state.json` is migrated into a first queue called "Main", then deleted.

**UI:**
- **No full queue-management screen** (unlike Musicolet). A **queue button** on the home screen, the
  player and car mode opens a **popup**: list of queues (name, current track, progress), tap = switch,
  "+" = new queue, long-press = rename / delete / clear. Phone: sheet; landscape and car: side panel
  (DESIGN_GUIDE §8). Switching is 2 taps (open, pick) everywhere, the car included (D26): it is done once
  per trip, not repeatedly, so no queue tiles on car home.
- The track list of the active queue stays where it is today (player's "up next").
- The switcher lives in its own feature package (`presentation/queues/QueueSwitcherHost`, own ViewModel with
  one `UiState`); `MainScreen` is not grown (H1, H3). Its strings are English + Russian from the start (K1).
- Android Auto / media browser (`MediaLibraryTree`): a "Queues" folder.

### Phase 1b — History (D46)

Every track start is recorded app-wide (`history(ref, startedAt, playedMs, origin)` in Room, write-through,
capped at a few thousand rows) and shown as a **History** row in Library: most recent first, grouped by day,
with the usual track actions (play, add to queue, like), so a track skipped past once can be found again.
Across providers like the queues (D22). Built right after Phase 1 on its `TrackRef` / Room plumbing; the
"track started" event is the queue manager's. Own short design doc; the canvas gets one board.

### Phase 2 — Radio (Yandex), YouTube Music semantics

- "Start radio" on a track / album / artist creates a new queue of kind `RADIO` with `seed` set.
  Yandex: rotor stations — `track:<id>` already exists upstream ("wave by track", `WaveQueue.kt`);
  `album:<id>`, `artist:<id>` to be verified against the live API. Reuse upstream's station code, do not
  build a second one.
- **Persistent list:** fetched tracks are appended to the queue and never replaced, so back/forward through
  history works. When near the end, the next batch is appended (like My Wave today) by the queue's
  `DynamicSource` (E4) — the same mechanism as My Wave, not a second one.
- **One radio queue at a time (D35):** "Start radio" replaces the radio queue (its name follows the seed,
  e.g. "Radio: Time"); going back and forward inside it still works and it survives restarts. "Keep" copies
  it into a named queue of the user's. No pruning setting.
- Rotor feedback (skip/like) is sent for Yandex radio queues as it is for My Wave.

### Phase 3 — Provider abstraction

- **Module `:core:domain`** (pure Kotlin, D17): neutral `Track`, `Album`, `Artist`, `Playlist`, `Folder`,
  `Genre` with only the fields the UI and player need (C2). Tracks are identified by
  `TrackRef`; albums, artists, playlists, folders and genres by a ref of the same form
  `<provider>:<kind>:<id>` (C1, D27) — also used for radio seeds, pins, mix sources and navigation.
- **`MusicProvider`** per account/server, grouped by capability (C3): `Library`, `Search`, `Favorites`,
  `Radio`, `Folders`, `PlaylistEdit`, `Lyrics`… The UI asks for capabilities and hides what is missing.
  The interface must not assume a server, an account or a network (local files, D25).
- **`StreamResolver`** per provider (C4): `resolve(ref, prefs)` → `ResolvedStream` (`uri`, `headers`,
  `quality`, optional decryption, `expiresAt`). The engine receives the final stream and never calls a
  resolver; Yandex encraw/lossless details stay inside the Yandex provider.
- **Accounts** become `ProviderAccount(provider, accountId, displayName, serverUrl?)` (C5); secrets stay in
  the Keystore store. **Networking** per provider: own OkHttp client and own auth interceptor from a shared
  base — no interceptor adds credentials for a host it does not own (C6).
- **Errors:** provider calls return `Outcome<T>` with a small `ProviderError`; exceptions don't cross the
  provider boundary; user-facing text comes from resources (ARCHITECTURE section D, rules D1–D2).
- **Several providers at once** (C7, D19): Home, Library, Search and car home are composed from the sections
  of all signed-in providers. My Wave, charts, dislikes come from Yandex; FM radio becomes a built-in "Radio"
  source that can be switched off. **Search** is one merged list with provider badges (D21).
- `YandexProvider` wraps today's `YandexMusicApi` + repositories; DTO → domain mappers. Files that receive
  upstream Yandex fixes are **wrapped, not rewritten**, and keep their names (A3). The Yandex code moves to
  `:provider:yandex` only after it is wrapped.
- UI and `PlaybackController` move from `TrackDetailsDto` to `Track`. This is the largest phase (36 files use
  the DTO, 20 of them in `presentation/`); done screen by screen behind the mappers, with screenshot tests
  kept green. `ArchitectureTest` gets an allow-list of not-yet-migrated files that may only shrink (B4).

### Phase 4 — Jellyfin provider

- **Module `:provider:jellyfin`** (D17) with its own OkHttp client and auth interceptor (C6); the Jellyfin
  token is never sent to another host.
- **Auth:** server URL + username/password → `POST /Users/AuthenticateByName` → access token stored
  in `KeystoreStringPreferences` (same as Yandex tokens). Password is not stored. Sign-in screen per
  DESIGN_GUIDE §8 (https enforced with an inline error, password never prefilled).
- **Quick Connect** as an alternative (D31): after the server address the app shows a code
  (`/QuickConnect/Initiate`), the user confirms it in a Jellyfin client where they are already signed in, the
  app polls and signs in (`/Users/AuthenticateWithQuickConnect`). Needs Quick Connect enabled on the server;
  endpoints to be verified against the user's server.
- **Browse:** `/Items` (Audio, MusicAlbum, Playlist), `/Artists`, `/MusicGenres`, `/Search/Hints`.
- **Stream:** `/Audio/{id}/universal` (server picks direct play vs transcode); covers
  `/Items/{id}/Images/Primary`.
- **Network: HTTPS only.** The server URL must be `https://`; `network_security_config.xml` stays
  unchanged (no cleartext). The certificate must be trusted by Android (e.g. Let's Encrypt via reverse
  proxy); self-signed certificates are not supported.
- **Downloads/offline:** same `TrackDownloadManager`, plain files (no decryption); file names derived from
  `TrackRef` so ids of different providers cannot collide (F3).
- Play reporting `/Sessions/Playing*` — optional, so Jellyfin's "recently played" stays correct.

### Phase 4b — Jellyfin folder browsing

The collection is organised by folders that tags cannot express (studio albums vs live shows vs
compilations like "Romantic Collection"), so the physical folder tree is a first-class way to browse.

- **Navigation:** the proper Navigation Compose graph inside `main` is introduced here at the latest (H2,
  D18); folder levels and artist/album pages of any provider become routes. Layout per DESIGN_GUIDE §8
  (phone list with breadcrumbs; car tiles). Pinned and recent folders are stored in Room (F1).
- **Screenshots** include the phone and the user's 1920×720 head unit (I2).
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

### Phase 4d — Local files provider — planned, design deferred

YAMP should also play music stored on the device (D25). It is a third provider (`local`) behind the same
`MusicProvider` interface: no account, capabilities Library / Folders / Search / Favorites, no Radio (the mix
builder can still use local tracks), stream = file URI, folder browser shared with Phase 4b.
**Requirement for Phase 3:** the provider abstraction must not assume a server, an account or a network.

Not designed yet — to be discussed when this phase starts, in its own design doc:

- **Scanning:** Android's media index (MediaStore) vs our own scan of user-chosen folders with a rescan
  button; change detection; background work on a weak head unit.
- **Index storage:** separate database owned by the provider vs tables in the main one (would amend
  ARCHITECTURE F1); where non-rebuildable user data lives (favorites, play counts).
- **Track identity:** path-based id (simple, breaks on rename/move) vs fingerprint that survives moves —
  matters because queues store track refs permanently.
- **Removable storage** (SD card, USB stick in the head unit): absent volume → tracks unavailable, not deleted.
- **Tags and covers:** tag reader, CP1251 tags in old Russian MP3s, embedded art vs `folder.jpg`/`cover.jpg`.
- **Formats:** what Media3 plays out of the box vs FFmpeg for APE/DSF/WavPack; `.cue` single-file albums.
- **Where the files are:** phone storage, SD card, USB stick — not asked yet.

Reference (2026-11-09, from Musicolet 6.9's manifest and bundled changelog only — no decompiling, D15): it
scans user-chosen folders itself (can include `.nomedia`/hidden folders, exclude folders, "Force to Rescan"),
keeps its own cached tags refreshed in the background, bundles FFmpeg, uses embedded art then `folder.jpg`,
needs only the ordinary media permissions and no internet permission.

### Phase 5 — Favorites per provider

- Library → Favorites with tabs Tracks / Albums / Artists / Playlists, **per signed-in provider** that has
  the `Favorites` capability. Several providers can be active at once (C7), so there is no single "active
  provider": each provider's favorites are a separate list, not merged (D11). How the provider is picked
  (sections or a filter pill) is settled in the design canvas. Where one button must act alone (Library
  Play / Shuffle, the car "Liked" tile) it uses the first source in the Sources list (D30).
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
  "Re-run" of a saved mix **replaces that mix's previous queue** (D29), so queues do not pile up; a mix built
  without saving goes into a single "Last mix" queue, replaced each time (D35). Mix definitions can be saved
  and re-run later.
- Live (endless) mixes are a later extension: the seed is already stored, so appending more is possible.
- The mix builder is a phone/parked action; the car shows saved mixes only (DESIGN_GUIDE §8).

## Cross-cutting work (from the architecture, not tied to one phase)

- **Localization** (K, D20): new and changed screens ship English + Russian with semantic keys from Phase 1
  on. The ~445 existing Russian strings are migrated in **one mechanical pass as its own task** (K4), not
  mixed into a feature phase. In-app language choice gets its own design doc (K3).
- **Module split** (D17): `:core:domain` appears with Phase 3, `:provider:jellyfin` with Phase 4,
  `:provider:yandex` after the Yandex code is wrapped, `:core:queue` when it pays off.
- **New-code plumbing** (G): injected app `CoroutineScope` and `DispatcherProvider` for new classes.
- **Design canvas** (`Design/`): mock-ups of the new screens for phone and head unit before they are built;
  deviations are logged in `kb/design-deviations.md` (DESIGN_GUIDE §9).
- **Privacy re-check** (J3) for every change to auth, networking or the updater (Phases 0, 3, 4).

## Affected files (main ones)

Paths are today's; after Phase 0 the package root is `org/tegro/yamp/`.

- `android/app/build.gradle.kts`, `data/updater/GitHubUpdateManager.kt`, CI workflows (P0)
- `data/player/PlaybackController.kt`, `PlaybackQueue.kt`, `WaveQueue.kt`, `MediaLibraryTree.kt`, `MusicService.kt`,
  `data/cache/CacheDatabase.kt`, `PlaybackStateStore.kt` (P1–2)
- `presentation/player/*`, `presentation/car/CarScreen.kt`, `presentation/main/*` (P1–2, P3)
- `data/network/YandexMusicApi.kt`, `data/dto/ApiModels.kt` (P2, P5)
- New in `:app`: `data/queue/*`, `data/provider/yandex/*` (until it moves to `:provider:yandex`),
  `presentation/queues/*`, `presentation/mix/*`
- New modules: `:core:domain` (models, `TrackRef`, `MusicProvider`, `StreamResolver`, `Outcome`),
  `:provider:jellyfin`

## Risks and side effects

- **Hard fork, no upstream merges.** Upstream changes are reviewed and ported/reimplemented by hand,
  tracked in `kb/upstream-tracking.md`.
- **Update channel:** until Phase 0 is done the updater still points at `KorenskoyR/YandexMusicLite`;
  a YAMP build must never be released before that, or it would offer to "update" itself to upstream.
- Rotor `track:/album:/artist:` stations are unofficial API — may change or need extra params.
- Room migration must be tested from the current schema; failed migration must not lose downloads.
- Android Auto browse tree gets deeper; keep within its item limits.

## Open questions

1. Local files provider — everything in Phase 4d is deliberately deferred.

## Resolved questions

1. Queues → **app-wide**, not per account (revised 2026-11-09, `kb/decisions.md` D22).
2. Jellyfin transport → HTTPS only.
3. "Play album" → replaces the active queue; switching queues via a popup from a queue button.
4. Updates → `tegro-org/YAMP` GitHub releases (public repo; own `applicationId` + signing key).
5. Library → one music library, flat hand-named top folders (see Phase 4b).
6. Queue switch in the car → the popup, 2 taps; no queue tiles on car home (D26).
7. Ids → one form for everything, `<provider>:<kind>:<id>` (`yandex:track:12345`, `jellyfin:folder:…`);
   one server per provider type for now (D27, ARCHITECTURE C1).

## Decisions

- **Jellyfin is the primary self-hosted backend** (Phases 4, 4b, 6). Subsonic (Phase 4c) is lower priority,
  done after Phase 6 and only if Airsonic-Advanced is still in use then.
- Airsonic-Advanced is used today only as a workaround for having **two queues** (Ultrasonic on Airsonic for
  driving with the wife, Symfonium on Jellyfin for driving alone). Multiple queues (Phase 1) on Jellyfin
  replace this, so Phase 4c is expected to be dropped.
- **No duplicate merging.** With Jellyfin primary, the same files via Airsonic are not de-duplicated; if
  both are ever connected, each provider is browsed separately.
