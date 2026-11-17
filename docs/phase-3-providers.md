# Phase 3 + 3b + 4 — Provider abstraction, next-track buffering, Jellyfin

Status: **design, awaiting approval** (2026-11-16). Roadmap: `docs/multi-source-player.md` §Phase 3, §Phase 3b,
§Phase 4.
Rules: `ARCHITECTURE.md` A1–A4, B1–B4, C1–C7, D, F3; decisions D4, D10–D13, D17, D19, D21, D25, D27, D30, D31.

## Purpose

One milestone, one release: the provider abstraction (P3) has no visible effect on its own, so it is delivered
together with the first second provider, Jellyfin (P4), which proves the abstraction against real needs instead
of a vacuum. Phases 1–2a already made the *queue* and the *accounts* provider-shaped: queue entries and History
store `TrackRef`, every queue is bound to an `AccountId`, every Yandex call goes through a per-account client
(`YandexClients`). This milestone finishes that for models, streams and screens, then adds Jellyfin: sign-in,
library, search and streaming from the user's server.

Delivery in **two stages**:
- **Stage 1 = Phase 3 + Phase 3b** (next-track buffering and its state). The P3 steps are small, invisible
  refactors, each keeping all goldens unchanged and mergeable into `main` on its own (so `main` stays releasable
  for ride fixes); P3b is built on the new resolver path right after the step that moves stream resolution to the
  controller (D74). Then a **checkpoint**: debug build, rides with Diag, fixes, possibly release **v0.4.0**.
- **Stage 2 = Phase 4 Jellyfin**, after the checkpoint → release **v0.5.0**.

## Scope and non-goals

**P3 (invisible):** `:core:domain` module; neutral models (C2); `MusicProvider` with capabilities (C3);
`StreamResolver` → `ResolvedStream` (C4); generalised per-provider networking (C6); `Outcome`/`ProviderError`
(D); `YandexProvider` behind mappers; controller, queue bridges and every screen on models; composition of
Home/Library/Search from provider sections; FM radio as a built-in source; `ArchitectureTest` B1–B4.

**P3b (visible, stage 1)** — roadmap §Phase 3b, `tasks/todo.md` §Phase 3b: the next track is preloaded a few
seconds after the current one starts (not only 60 s / 15 s before its end); **Next is never disabled** and shows
the next track's state — ready / loading (spinner ring) / failed (greyed) — a tap retries at once (load first,
then switch, D67); a failed next-track load **recovers by itself** when the link comes back (`LINK NONE → GOOD`)
with a fresh link; the progress bar shows the **buffered part**. Phone player, car screen and the car's next-track
artwork; Android Auto shows what its template allows. Canvas first.

**P4 (visible, stage 2):** `:provider:jellyfin` module (D17); sign-in with server URL + user name/password and Quick
Connect (D31), HTTPS only (D13); **each server + user is a unique account with its own cache database;
signing in to an already present server + user is refused**; library (albums, artists, playlists, genres,
album/artist/playlist pages), search (merged with Yandex, badges, D21), streaming and covers, downloads (plain
files), Jellyfin tracks in queues (bound to their account, D57; unavailable when signed out, D62), History,
car and Android Auto entries; play reporting to the server (`/Sessions/Playing*`) as the last, optional step.

**Out:** folder browsing (P4b — next milestone; the Folders capability and refs are designed here but not
built), Subsonic (P4c), local files (P4d — only the "no server, no account, no network" requirement is
honoured), Jellyfin favorites (Phase 5), Jellyfin radio / Instant Mix (Phase 6), lyrics, moving Yandex code into
`:provider:yandex`.

## What the code looks like today (measured 2026-11-16)

- `data/dto/ApiModels.kt`: 31 DTO classes. **47 files** outside `data/dto` use them — 21 in `data/`, 24 in
  `presentation/`, plus `utils/CoverUrlHelper` and `widget/NowPlayingWidget`.
- `TrackDetailsDto` alone: **46 files, 305 references** — presentation 24 (main 14, player 3, catalog 2,
  history 2, queues 2, car 1), data 20, utils 1, widget 1. (The roadmap's "36 files" was the Phase 0 count.)
- `domain/queue` (`MediaRef`, `TrackRef`, `PlayQueue`, `QueuePolicy`, `QueueStore`, `RadioSeed`) is already pure;
  `domain/account/ProviderAccount` (`Provider` enum has only `YANDEX`); B1 leaks: `RadioStation` (`@DrawableRes`),
  `PlayerRepository` (exposes `ExoPlayer`).
- `TrackUrlResolver` (seam over `TrackPreloadManager`, per account) — **the engine still calls it**
  (`PlayerRepositoryImpl.kt:428-431`), against C4.
- Room v5: `tracks(id = bare Yandex id, json = TrackDetailsDto)`, `list_items(owner, …)`,
  `queue_entries(ref, trackId, title, artist, cover, duration)`, `history(ref, trackId, …)` — entries and History
  already carry refs and their own display fields.
- Modules: `:app`, `:baselineprofile`, `:diag` (flag-only). `ArchitectureTest`: 4 text-scan rules.
- A3 upstream-hot files (wrap, keep names): `YandexMusicApi`, `TrackPreloadManager`, `LosslessSigner`,
  `EncrawDecryptor`, `EncrawDataSourceFactory`, `DownloadInfoParser`, `StreamVariants`, `Md5Helper`, `DeviceSignIn`.

## What Jellyfin needs from the abstraction

The design below is checked against these, not only against Yandex:

| Need | Jellyfin | Consequence for P3 |
|---|---|---|
| Account identity | server (its `ServerId` from `/System/Info/Public`) + `UserId`; token per server+user | `AccountId` = `jellyfin:<serverId>/<userId>`; `ProviderAccount.serverUrl` |
| Item identity | item ids are unique **per server**, shared by its users | refs must name the server: provider part `jellyfin@<serverId>` (lifts C1's "one server per provider type" limit) |
| Auth on every call | header `Authorization: MediaBrowser Client="YAMP", Device=…, DeviceId=…, Version=…, Token=…` (DeviceId stable per install) | per-account client whose interceptor signs **only that server's host** (C6) |
| Stream | `/Audio/{id}/universal?UserId&DeviceId&Container=…&AudioCodec=…&MaxStreamingBitrate=…&MaxSampleRate=…&TranscodingContainer=…&TranscodingProtocol=http` — server decides direct play vs transcode | `ResolvedStream(uri, headers, quality, decryption = none, expiresAt = none)`; token in a header, not the URL (no tokens in logs); `StreamPrefs` carries codecs the device plays, max bitrate (link-aware, D36), max sample rate (the mixer rate, D69) |
| Covers | `/Items/{id}/Images/Primary?maxWidth=…&tag=…` (tag = cache key) | `Cover.url(sizePx)` built by the provider; the image loader adds the auth header for that host only |
| Paging | `/Items?StartIndex&Limit` (libraries of thousands) | list capabilities return pages (`Page<T>(items, next: Cursor?)`); Yandex returns one page |
| Search | `/Search/Hints` or `/Items?searchTerm` per type | `Search.search(query, types)`; composition merges per type with badges |
| Folders (P4b) | `/UserViews`, `/Items?ParentId` | `Folders` capability and `folder` refs defined now, built in P4b |
| No radio in P4 | Instant Mix is Phase 6 | capabilities hide "Start radio" for Jellyfin tracks |
| Local files later (D25) | — | nothing in the interfaces assumes a server, account or network |

## Target design

### 1. `:core:domain` (pure Kotlin/JVM module, D17)

- **Refs**: `MediaRef`, `TrackRef` (moved). Provider part may carry an instance: `jellyfin@<serverId>`;
  `MediaRef.providerType` = the part before `@`. Yandex refs unchanged (`yandex:track:<id>`), so stored queues and
  History stay valid.
- **Models (C2)**: `Track(ref, title, artists, album: AlbumBrief?, durationMs, cover: Cover?, explicit,
  available, gain?)`, `Album`, `Artist`, `Playlist`, `Folder`, `Genre`, `ArtistBrief`, `AlbumBrief`, `Cover`
  (`url(sizePx)`), `Page<T>`. Fields collected while migrating — only what screens and the player use.
- **Accounts**: `Provider { YANDEX, JELLYFIN }`, `AccountId`, `ProviderAccount(provider, id, displayName,
  avatarUrl, serverUrl?)` (moved, extended).
- **Provider API (C3)**: `MusicProvider { account; capabilities; library(); search(); favorites(); radio();
  playlistEdit(); folders(); home() }` — each capability a small interface returning `Outcome<T>`.
- **Streams (C4)**: `StreamResolver.resolve(ref, prefs): Outcome<ResolvedStream>`;
  `ResolvedStream(uri, headers, quality, decryption: Decryption?, expiresAt?, localFile)`; `Decryption` is a
  marker the provider's DataSource factory understands (Yandex encraw stays in Yandex).
- **Errors (D)**: `Outcome<T> = Ok | Err(ProviderError)`; `ProviderError { Network, Timeout, Unauthorized,
  NotFound, Unavailable(reason), RateLimited, Server(code), Unknown }`; UI text from resources.
- **Queue model** moves in unchanged.

### 2. Providers

- **`YandexProvider`** (package `data/provider/yandex/` in `:app`): wraps the repositories and `YandexMusicApi`
  (A1, A3); `YandexMappers`; `YandexStreamResolver` over `TrackPreloadManager`. Capabilities: Library, Search,
  Favorites, Radio, PlaylistEdit, Home, Dislikes.
- **`JellyfinProvider`** (module `:provider:jellyfin`): Retrofit API, DTOs, mappers, `JellyfinStreamResolver`,
  auth (password, Quick Connect), its **own Room database per account** (`jellyfin-<serverId>-<userId>.db`:
  item metadata and list caches) — deleted on sign-out together with the account's downloads, while its queues
  are hidden per D62. Capabilities in P4: Library, Search (+ Folders in P4b, Favorites in P5, Radio in P6).
- **`ProviderRegistry`**: one provider per signed-in account (from the accounts list, 2a); `provider(account)`;
  an entry always resolves through its queue's account — a queue holds tracks of its own account only (D84).
- **Networking (C6)**: shared OkHttp base (pool, timeouts); per account a client with its own interceptor and
  cache dir. `YandexClients` becomes the Yandex instance; Jellyfin adds its own; HTTPS enforced at sign-in and
  in the base (`network_security_config.xml` unchanged, no cleartext, no self-signed — D13).
- **Composition (C7, D19, D21)**: Home, Library and Search are built from provider sections in a stable order
  (Yandex first, then Jellyfin accounts in sign-in order); a section shows only when a provider has the
  capability; Search is one list per type with a provider badge (badges hidden with one provider). **FM radio**
  becomes a built-in, account-less `Radio` source.

### 3. Engine and controller

The controller resolves the stream (provider of the entry, account per the rule above) and hands the engine a
`ResolvedStream`; the engine never resolves (fixes `PlayerRepositoryImpl.kt:428-431`) and picks the DataSource by
`decryption` (encraw → `EncrawDataSourceFactory`, else the default HTTP factory with the stream's headers). The
playback-stability retry policy (4/6 s, fresh link) stays in the controller and works for both providers.
Controller, `QueueTrackBridge`, `WaveQueue`, `RotorStationSource`, reserve/downloads and History take `Track`.
Downloads name files from `TrackRef` (F3; `@` and `:` sanitised).

### 4. Storage

No migration for queues or History (refs + display fields are already stored). `tracks`/`list_items` stay the
Yandex provider's private cache; Jellyfin gets its own database per account (above). Room v5 stays v5 unless the
accounts list needs a column (it doesn't: accounts live in the Keystore store).

### 5. Jellyfin sign-in and accounts (P4)

Settings → Accounts → Add → provider chooser (Yandex / Jellyfin). Jellyfin form per DESIGN_GUIDE §8: server URL
(https only, inline error), user name, password (never stored, never prefilled), or **Quick Connect** (code shown,
app polls). On success: `ServerId` + `UserId` → `AccountId`; **if that account already exists → refused** with a
message; else stored (token in `KeystoreStringPreferences`), an empty default queue is created for it (D83, like
D63's My Wave; re-sign-in of the same server + user restores it), and the account appears in the grouped switcher, markers and selectors (2a UI).

## Decisions (proposals)

| ID | Decision |
|---|---|
| D71 | `:core:domain` now (pure JVM): refs, models, accounts, provider/stream/outcome interfaces, queue model. Yandex stays in `:app` under `data/provider/yandex/`; Jellyfin is a new module `:provider:jellyfin`. |
| D72 | Caches are provider-private: Yandex keeps `tracks`/`list_items`; each Jellyfin account has its own Room database; queues and History keep refs + display fields — no migration of stored queues. |
| D73 | Provider calls return `Outcome<T>`/`ProviderError`; repositories may throw internally, the provider maps once. |
| D74 | The controller resolves; the engine plays a `ResolvedStream` and never calls a resolver. |
| D75 | Composition in a stable provider order; with one provider the UI is identical to today; badges only with ≥ 2 providers. |
| D76 | FM radio is a built-in, account-less `Radio` source. |
| D77 | `ArchitectureTest` B1–B4; "no Yandex DTO outside `data/provider/yandex/`" with an allow-list that may only shrink; `:provider:jellyfin` may depend on `:core:domain` only. |
| D78 | Jellyfin account = server + user (`jellyfin:<serverId>/<userId>`); a duplicate sign-in is refused; refs name the server (`jellyfin@<serverId>:<kind>:<itemId>`) — revises C1's known limit. |
| D79 | Jellyfin streams via `/Audio/{id}/universal` with device capabilities (codecs, max bitrate per link, max sample rate = mixer rate); token in a header; covers via `Images/Primary` with size and tag. |
| D80 | P3b: the next track is resolved and prepared a few seconds after the current one starts (on the D74 path, the controller owns it); its state (ready / loading / failed) is part of the player state shown by Next. |
| D81 | P3b: Next is never disabled; on loading/failed a tap retries under the load-first rule (D67) — the tap target is the **whole Next button** and, in the car, the **whole next-track artwork**; the badge/ring is only an indicator, never a separate small target; a failed next-track load retries by itself with a fresh link on `LINK NONE → GOOD`; the progress bar shows the buffered position. |
| D82 | Step 1 moves the existing pure `domain/` code (refs, queue model, accounts) into `:core:domain` along with the new interfaces; B1 becomes compiler-enforced. |
| D83 | A Jellyfin account gets an empty default queue at sign-in, its entry point in the switcher (like D63); re-sign-in of the same server + user restores it with the account's other queues (D62). |
| D84 | A queue holds tracks of its own account only: "Add to queue" offers only queues of the track's account; an entry always resolves through its queue's account. Multi-source queues (D30) stay a future item, not in this milestone. |
| D85 | Development and emulator tests run against a local Jellyfin in Docker with free test music (`scripts/jellyfin-dev/`, no secrets); the user's own server is used only by the user; no server URL or credentials in the repo. |
| D86 | P3b: an on-disk stream cache (Media3 `SimpleCache` + `CacheDataSource`, per-provider data-source layer) keyed by account + track + quality, LRU, size limit in Settings (default 500 MB), separate from downloads/liked; the next track is fetched fully to disk early, the previous one stays cached. |

## Order of work

Each step compiles, passes the gates and is merged into `main` on its own. P3 steps keep **all goldens
unchanged**; P3b changes only the goldens of the screens it redesigns. Sizes: S ≤ 10 files, M 10–25, L 25+.

### Stage 1 — Phase 3 + 3b

**P3 — invisible**
1. `:core:domain` skeleton; move refs (with the `@instance` provider part), accounts, the queue model; add
   `Outcome`, models, `Page`. (M, mostly moves.)
2. `ArchitectureTest` B1–B4 + allow-list of the 47 DTO users; trivial B1 leak fixes. (S.)
3. Yandex mappers + tests on recorded JSON. (S.)
4. `StreamResolver`: `YandexStreamResolver`, controller resolves, engine takes `ResolvedStream` (D74). (M — own
   ride.)

**P3b — next-track buffering, on the new resolver path**
5. **Canvas**: Next states ready / loading (spinner ring) / failed (greyed), buffered part in the progress bar —
   phone player, car screen, car next-track artwork; reviewed and **approved by the user before UI code**.
6. Early preload: the next track is resolved and prepared a few seconds after the current one starts (D80);
   next-track state exposed in the player state; journal lines. (M — engine, own ride.)
7. **On-disk stream cache** (D86, KB `kb/players/00-conclusions-and-ideas.md` §2.4): Media3 `SimpleCache` +
   `CacheDataSource` in the per-provider data-source layer (the D74 path); key = account + track + quality; LRU
   eviction; size limit in Settings (default 500 MB), separate from downloads/liked (unchanged). The preloaded
   next track is downloaded **fully** to disk early (Next is instant even after a dropout; FLAC on weak links
   gains most); the previous track stays on disk (back is instant); a mid-track dropout is covered once the rest
   is cached. Canvas impact: one Settings row (cache size). (M — engine, own ride.)
8. Next never disabled: the UI per the canvas (`Design/yamp/buffering.jsx`, approved 2026-11-16); a tap on
   loading/failed retries under load-first (D67, D81) — the target is the whole Next button and, in the car, the
   whole next-track artwork (the ring/badge is only an indicator); self-recovery of a failed next-track load on
   `LINK NONE → GOOD` with a fresh link. (M.)
9. Buffered progress in the progress bars (phone, car); Android Auto as its template allows. (S.)

**P3 — invisible, continued**
10. `MusicProvider` + `ProviderRegistry` + networking base; `YandexProvider` over the repositories. (M.)
11. Controller and queue on `Track` — 11a controller/bridge, 11b wave/radio, 11c reserve/downloads/History. (L.)
12. Player UI, mini player, queue sheet, widget, car screen. (M.)
13. Library and playlists. (M.)
14. Search through the composed path. (M.)
15. Home, catalog, History, queues, dialogs, context menu. (L.)
16. Composition of Home/Library sections + FM radio source. (M.)
17. Cleanup: allow-list empty, `TrackUrlResolver` internal, `PlayerRepository` without `ExoPlayer`. (S.)

**Checkpoint** — debug build, rides with Diag, fixes; possibly release **v0.4.0**. Phase 4 starts only after it.

### Stage 2 — Phase 4 Jellyfin (visible from step 21) → v0.5.0

18. **Local test server**: `scripts/jellyfin-dev/` — docker-compose for Jellyfin, a script that fetches free test
    music (public-domain / CC0 sets) into a gitignored folder, setup notes (test user, Quick Connect on, HTTPS with
    a local certificate the emulator trusts); no secrets, no server URLs. (S.)
19. `:provider:jellyfin` skeleton: API client, auth header, `/System/Info/Public`, DTOs, mappers; tests on
    JSON recorded from the local Docker server (D85). (M.)
20. **Canvas**: Jellyfin sign-in (password + Quick Connect), provider chooser in Accounts, Jellyfin sections and
    badges on Home/Library/Search — reviewed and approved by the user before UI code.
21. Sign-in + accounts: password and Quick Connect, duplicate refusal, per-account database, default queue,
    sign-out per D62. (M.)
22. Library: albums, artists, playlists, genres, their pages, paging. (L.)
23. Streaming and covers: `JellyfinStreamResolver`, image auth for the server host only, downloads. Ride with
    the unit. (M.)
24. Search merged with badges; Home/Library composition with two providers; car home and Android Auto. (M.)
25. Queues and History with Jellyfin tracks: "Add to queue" offers only the track's account's queues (D84),
    binding, unavailable when signed out. (M.)
26. Play reporting `/Sessions/Playing*` (optional). (S.)
27. Docs/KB as built, release notes, **release v0.5.0**.

## Risks

- **Upstream ports (A3):** hot files wrapped, mappers absorb DTO changes; UI ports are reimplemented anyway.
- **Engine regressions** (steps 4, 6, 7, 11, 23): the playback-stability fixes are fresh — scripted-player tests plus a
  unit ride with Diag after each engine step.
- **Head-unit performance:** map lists lazily per page/row, never on the main thread; measure list open times.
- **Behaviour hidden in DTOs** (availability, `albums[0]`, cover templates) → mappers tested on real JSON.
- **Jellyfin versions**: the local Docker server (D85) and the user's server may differ; endpoints (Quick Connect,
  universal params) are checked on both — the user's only by the user; nothing about it goes into the repo.
- **Size:** ~47 files in P3 plus a new module in P4 — split so no step is unreviewable.

## Tests

`:core:domain` units (refs incl. `@instance`, `Outcome`, cover sizing); Yandex and Jellyfin mapper tests on
recorded JSON; `ArchitectureTest` (B1–B4, module boundaries); controller/engine scripted tests on
`Track`/`ResolvedStream`, "the engine has no resolver"; P3b: early preload timing, next-track state
transitions (ready/loading/failed), tap-retry and `LINK NONE → GOOD` recovery with a fresh link, buffered position, new
goldens for the redesigned Next/progress (phone, car); Jellyfin auth header and host-scoped interceptor tests,
universal-URL builder tests, duplicate-account refusal; all goldens unchanged through P3; new goldens for the
P4 screens (phone, narrow, 1920×720).

## Resolved questions (user, 2026-11-16)

1. The existing pure `domain/` code moves into `:core:domain` in step 1 (D82).
2. A Jellyfin account gets an empty default queue at sign-in (D83).
3. A queue holds tracks of its own account only; multi-source queues stay a future item (D84).
4. Development uses a local Jellyfin in Docker with free test music; the user's server is for the user's own
   testing only (D85).

## As built

**Steps 3–4 (2026-11-17)**
- Models live in `:core:domain` package `org.tegro.yamp.domain.media` (not `domain.model`, which `:app` still uses for
  its storage models). `Cover` is an interface (`key`, `url(sizePx)`); Yandex's is `YandexCover` (the `%%` template).
  `Track.title` is non-null (empty when the service sent none); the R128 gain is `Track.loudness`.
- `MusicProvider` and its capabilities are left to step 10, where `ProviderRegistry` and `YandexProvider` give them
  their first implementation; step 3 is the models and mappers only.
- `StreamResolver` takes the `AccountId` as a parameter until a provider exists per account (step 10); it has three
  calls: `cached` (a fresh link, main-thread safe — no disk), `resolve` (copy → fresh link → network) and `invalidate`.
- The engine plays only what it is handed: `playTrack(id, stream: ResolvedStream?)` — null means the warm standby or
  the gapless arrival; without either the start fails with `EMPTY_URL` and the controller's recovery resolves again.
  A player failing on a stream is reported as `staleStreamEvent`; the controller invalidates the link.
- `ResolvedStream.headers` is carried but not yet applied by the engine (Yandex needs none); the Jellyfin step (23)
  adds header-aware data sources. Yandex encraw keeps its key in the URI fragment (A3); `decryption` only marks it.
- The reserve's "ordinary stream" lookup (`resolveOrdinaryStream`) and quality/offer details still go through
  `TrackUrlResolver` in the controller; they move with the controller to `Track` (step 11).
- No file stopped using DTOs in steps 3–4, so the allow-list is unchanged (47).

**Steps 6–7 (2026-11-17)**
- Early preload already ran at a track's start (`prepareStandbyWhenSettled`, upstream 1.14.3: once the current track
  has `RESERVE_HEADROOM_MS` = 20 s in hand); step 6 adds the F4 calm window (`PlayerRepository.settled`: no preload
  during a burst of skips), the next-track state `NextTrack` (None / Loading / Ready(onDisk) / Failed(reason), in
  `:core:domain` `domain.player`) as `PlaybackController.nextTrack`, `NEXT` journal lines, and self-recovery: a failed
  next track is fetched again with a fresh link when the link turns `GOOD` (D81). The engine's 60 s / 15 s
  `prepareNextTrackEvent` stays as the fallback. Ready = a warm standby (`warmTrackId`) or the whole file on disk.
- Step 7: `StreamCache` (Media3 `SimpleCache` in `cacheDir/stream_cache`, own LRU evictor so the limit changes live;
  0 = off) sits **below** the decryption in `EncrawDataSourceFactory`: encrypted streams stay encrypted on disk.
  The key comes from the provider: `ResolvedStream.cacheKey` = `account|track|codec-bitrate` (`YandexStreamResolver`),
  set on the `MediaItem` as its custom cache key; requests without a key (copies on the device, radio) bypass it.
  `NextTrackCacher` writes the next track in full (`CacheWriter`, one at a time, on IO); a track change cancels it
  unless it is the track now starting; a queue change (`dropNext`) cancels it; what was written stays. The current
  and previous tracks are cached as the players read them, so Back is instant. With no link at all the resolver falls
  back to a stream whose bytes are all on disk (`CachedStreams`). `CACHE` journal lines (written / already on disk /
  failed / cancelled).
- Settings: "Stream cache" / «Кэш потока» rows in the playback-cache group (0 / 250 / 500 MB / 1 / 2 GB, default 500,
  used size, Clear); the size applies at once.
- Known limits: the warm standby and the writer may both fetch the next track's first seconds (the player reads past
  spans the writer holds); with the link `NONE` the next track is not resolved (state "failed: offline" unless its file
  is on disk — the start itself still falls back on the disk). The older road reserve (`RoadReserve`, "next tracks
  saved ahead for a weak link") overlaps D86 in purpose; both stay, to be reconciled later.

**Steps 8–9 (2026-11-17)**

- The state reaches the screens through one composition local (`LocalPlaybackAux`, provided by `MainScreen`),
  not through every screen's parameters; its default (ready, nothing loaded) keeps every existing golden as is.
- A tap on a failed next track — Next or the car's first next-track card — drops that track's link first
  (`dropFailedNextLink`), so the skip/pick resolves it afresh; it stays load-first (D67).
- "Whole file on disk" for the bar: the started track came from a copy / a local file, or it was the last next
  track reported ready on disk (`lastOnDiskId`). If the cache evicts it in between, the bar only overstates what
  is loaded; playback is unaffected.
- Only the first card of the car's "Next" row shows the state (the next track); the others stay plain.
- Split panes: covered by the 1280-wide head-unit golden (`next_car_split_loading`); the slim pane has no
  separate golden.
- Android Auto: Next stays enabled; its template has no place for the state.

**Step 10 (2026-11-17)**

- `MusicProvider` carries `account`, `streams` and three capabilities so far — `Library` (liked tracks, playlists,
  a playlist's tracks), `Search`, `Catalog` (album, artist page). Favorites, playlist editing, radio, home and
  folders are added with the screens that use them (steps 12–16), not ahead of them.
- `StreamResolver` lost its account parameter: one resolver per account. The controller asks
  `StreamResolvers.forAccount(account)` (the `ProviderRegistry`); null keeps today's meaning — the account context
  at the moment of the call — so stream-cache keys and link lookups are unchanged.
- `YandexProvider` wraps the repositories as they are; each gained an optional `account` (default: the context, so
  every existing caller behaves as before). Exceptions become `ProviderError` once, at the provider (D73).
- Networking base: `@ProviderBaseClient` — the shared pool, timeouts and logging without credentials or cache;
  `YandexClients` builds on it and adds its account's `AuthInterceptor` first, as before.
- Providers exist for every account in `allAccounts` (sign-in order), hidden test accounts included.

**Step 11 — blocked by the order of work.** The controller's queue, wave/radio, reserve and History all hand
`TrackDetailsDto` to the screens (`currentTrack`, `currentPlaylist`, `waveSeed`, `wavePreview`) and take DTOs from
them (`playFrom`, `addToQueue`, …); the queue bridge stores the full DTO JSON because the screens need fields a
`Track` does not carry. Moving the controller first would need a lossy `Track → DTO` mapping back to the screens.
Proposed order: steps 12–15 first — each screen reads `Track` mapped from the controller's DTO flows at the edge
(`toTrack()`, always possible) — then step 11 flips the controller and drops those edge mappings.

**Steps 12–13 (2026-11-17)**

- Screens take `Track` / `Playlist`; the edge maps the controller's and the repositories' service objects
  (`toTrack()`, `toPlaylist()`) — `MainScreen` (mini and full player, cover prefetch), `MainTabs` (car screen),
  `NowPlayingWidget`, `PlaylistsViewModel`. Callbacks that need the service object back (the car's like, dislike,
  radio) look it up among the shown service tracks by id; edits take a `Playlist` and the view model finds the
  service's entry by ref (it keeps the opened playlist's revision privately to reload on a change).
- `presentation/common/TrackUi.kt` reads what screens used off the DTO: `id` (from the ref text, "" without one),
  `isRadio` (`MediaRef.PROVIDER_RADIO`, now in `:core:domain`), `coverUri` (the cover key = the old template),
  `artistNames`. A missing title is "" on `Track`: the screens show their placeholder for "" as they did for null.
- Step 13 moves the playlist entity only. Track rows (`TrackItem`) stay on the service track: the track menu acts on
  it (like, queue, playlist add, radio), and moves with step 15; `LibraryScreen`, `PlaylistScreen`,
  `TrackContextMenu`, `MainDialogs` therefore stay on the allow-list for now.
- DTO allow-list 47 → 40; no golden changed.

**Steps 14–15 (2026-11-17)**

- Search, Home, the catalog pages, History, Library and playlist rows, the track menu and the queue switcher's
  "Add to queue…" / "Start radio" requests take `Track` / `Artist` / `Album`; the tab wrappers (`MainTabs`,
  `MainScreen`) and the view models map at the edge. `queueShelfOf` / `shelfEntryOf` read the controller's queue and
  moved from `HomeScreen` to `MainTabs`; the artist page is `ArtistPage(artist, popularTracks, albums)` in
  `CatalogViewModel` (albums without an id are dropped there, as the screen did).
- Actions on a shown model need the service object back: `ServiceTracks` (`data/provider/yandex`) remembers every
  mapped track, artist and album by ref (LRU 5000); `toServiceTrack()` finds it, or rebuilds a service track from the
  model on a miss. `Track` entry points sit on `MainViewModel`, `PlaylistsViewModel`, `CatalogViewModel` and the
  controller (`addToQueue`, `createQueueWith`, `startRadioFrom`) — the queue package keeps depending on the controller
  only. The bridge goes with step 11 (controller on `Track`) and step 17.
- Edge cases that change no golden: a title the service sent as "" now shows the placeholder (as `null` did); search
  artists without an id are no longer listed (they could not be opened); an album id "0" no longer opens a page.
- DTO allow-list 40 → 27; no golden changed.

**Step 11 — 11a (2026-11-17)**

- The controller's screen-facing state is neutral: `currentTrack`, `currentPlaylist`, `waveSeed`, `wavePreview`
  and `trackWaveStarted` carry `Track`, mirrored in the same moment as the service values they come from (the
  queue list is mapped only when its list object changes). The service values stay as `serviceTrack`,
  `serviceQueue`, `serviceWaveSeed` for the data layer (the media session, the widget updater, the reserve gate)
  and the tests.
- Every entry point a screen uses takes `Track` (`playTrack`, `playFrom`, `playNowKeepQueue`, `enqueueNext`,
  `addToEnd`, `playNextTracks`, `startTrackWave`, `addToQueue`, `createQueueWith`, `startRadioFrom`); so do the
  reactions (`TrackReactions.toggleLike/dislike`) and `UserPlaylistsRepository.addTrack`. `MainViewModel`,
  `SearchViewModel`, `PlaylistsViewModel`, `CatalogViewModel`, `MainScreen`, `MainTabs` and the widget read and
  hand back `Track` only; the car screen's lookup of the service track by id is gone. `ActiveQueue.isShownAs`
  replaces the queue sheet's `tracks == queue` check.
- **Not done in this step (11b/11c):** the queue store, the wave/radio sources, the reserve, downloads and History
  still keep the service's tracks, so the controller turns a shown `Track` back into its service object at its
  entry points through `ServiceTracks` — which therefore stays, now used only inside the data layer (capacity
  5000 → 20000 so the longest lists a screen shows find their originals, not a rebuild).
- DTO allow-list 27 → 23 (`MainScreen`, `MainTabs`, `MainViewModel`, `SearchViewModel` off; `CatalogViewModel`
  keeps `ArtistBriefDto`, `PlaylistsViewModel` keeps `UserPlaylistDto` until the repositories move behind the
  provider); no golden changed.

**Step 11 — 11b/11c (2026-11-17)**

- The queue holds neutral tracks: `ActiveQueue.tracks`, every `QueueManager` operation, the radio source's batches
  and History take and give `Track`. The `tracks` table keeps its rows in the service's shape (D72, no migration):
  `QueueTrackBridge` writes `Track.toYandexTrack()` and reads back through `toTrack()`.
- The controller works on `Track` throughout; its state is `currentTrack`, `currentPlaylist` and `waveSeed` only
  (the `service*` mirrors are gone; the media session, Android Auto, the widget and the tests read the same flows).
  The few facts the player code asks a track for (`idString`, `isRadio`, `isExplicit`, `coverUri`) are read through
  `data/player/TrackFields.kt`, under the names the service object used, so a line that also reads the wave's
  service list reads the same fact from both.
- `ServiceTracks` (the lookup table) is removed. Where a Yandex repository or the rotor still takes the service
  shape — likes and dislikes, adding to a playlist, the reserve's upcoming list, the wave's station switch — the
  provider's stateless `Track.toYandexTrack()` gives it: lossless for every field the data layer reads (tested
  round trip; only extra album entries and an artist's own cover inside a track are not carried, and nothing reads
  them).
- Still on the service shape, inside the provider layer, until step 17: the wave's own list and preview
  (`WaveQueue`), the road reserve (`RoadReserve`, `ReserveKeeper`), the library and playlist repositories and their
  caches, `PlaybackStateStore`'s snapshot and `MediaLibraryTree`'s item building — each maps at its edge.
- DTO allow-list 23 → 19 (`ListeningHistory`, `SessionButtons`, `TrackReactions`, `QueueManager` off); no golden
  changed; `PlaybackControllerTest` 5/5 on reruns.

**Step 16 (2026-11-17)**

- `ProviderComposition` (`data/provider`): the composed providers are the account context's Yandex provider (D56 —
  several Yandex accounts never merge, one is the context) followed by other services' accounts in sign-in order
  (none until P4); `libraries()` lists those with a Library; `search()` asks every composed provider at once and keeps
  their order per type (`ComposedSearch` of `Sourced<T>`; `showBadges` only with ≥ 2 providers, so one provider looks
  exactly as before). A failing provider is left out; when all fail the screen keeps what it showed (as before).
- Search runs through the composition (`SearchViewModel.found` is a `ComposedSearch`). **Home and Library are not
  rewired in this step:** with one provider there is nothing to compose, and their sections are Yandex-specific
  (My Wave, hits, trends) or already per account; the Jellyfin sections arrive with P4, which wires them through
  `ProviderComposition`.
- FM radio is the built-in, account-less `FmRadio` source (`data/provider/radio`, D76): the stations, a station on air
  as a `Track` (ref `radio:track:radio_<id>`, the frequency as an artist without a ref, the logo as a `FixedCover`),
  and a `StreamResolver` returning the station's URL (never cached, D86). The controller (`playRadioStation`, station
  stepping, the restored live station) and `MediaLibraryTree` use it; the controller no longer builds a Yandex DTO.

**Step 17 (2026-11-17)**

- **The DTO allow-list is empty.** Yandex-only code moved under `data/provider/yandex/` with its names kept (A3) and
  its stored formats unchanged (D72): `network/` (`YandexMusicApi`, `StreamVariants`), `repository/` (catalog,
  library, search, user playlists), `cache/` (`PlaylistCacheManager`, `PlaybackStateStore` — the session snapshot
  keeps its JSON; the R8 keep rule follows the class), `queue/` (`RotorStations`, `RotorStationSource`,
  `QueueTrackBridge`, `LegacySessionImport`), `wave/WaveQueue`, `reserve/` (`RoadReserve`, `ReserveKeeper` — the
  reserve index keeps its JSON). What legitimately remains Yandex-shaped lives only there and in `data/dto`.
- `CatalogViewModel` reads the provider's neutral `ArtistParts` (`toArtistParts()` in `YandexMappers`);
  `PlaylistsViewModel` edits by `MediaRef` through `YandexPlaylists` (entries carry the service revision that says
  when to reload); `MediaLibraryTree` builds its items from `Track`.
- `TrackUrlResolver` is `internal`; the classes taking it (`PlaybackController`, `YandexStreamResolver` and its
  factory) have internal constructors and the binding is internal.
- `PlayerRepository` (domain) has no ExoPlayer types: the media session reads the players through `SessionPlayers`
  (`data/player`), bound to the same engine instance (`PlayerModule`); `FakePlayerRepository` implements both.
- Not done: the controller still reads stream facts (quality, offered variants, playable copies, the reserve's
  ordinary stream) through `TrackUrlResolver` directly — Yandex-specific; it moves behind the provider when a second
  provider's equivalents exist (P4).
- No golden changed; 635 unit tests; `PlaybackControllerTest` 5/5 on reruns; checkpoint build b85.

