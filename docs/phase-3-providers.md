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
| D81 | P3b: Next is never disabled; on loading/failed a tap retries under the load-first rule (D67); a failed next-track load retries by itself with a fresh link on `LINK NONE → GOOD`; the progress bar shows the buffered position. |
| D82 | Step 1 moves the existing pure `domain/` code (refs, queue model, accounts) into `:core:domain` along with the new interfaces; B1 becomes compiler-enforced. |
| D83 | A Jellyfin account gets an empty default queue at sign-in, its entry point in the switcher (like D63); re-sign-in of the same server + user restores it with the account's other queues (D62). |
| D84 | A queue holds tracks of its own account only: "Add to queue" offers only queues of the track's account; an entry always resolves through its queue's account. Multi-source queues (D30) stay a future item, not in this milestone. |
| D85 | Development and emulator tests run against a local Jellyfin in Docker with free test music (`scripts/jellyfin-dev/`, no secrets); the user's own server is used only by the user; no server URL or credentials in the repo. |

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
7. Next never disabled: the UI per the canvas; a tap on loading/failed retries under load-first (D67, D81);
   self-recovery of a failed next-track load on `LINK NONE → GOOD` with a fresh link. (M.)
8. Buffered progress in the progress bars (phone, car); Android Auto as its template allows. (S.)

**P3 — invisible, continued**
9. `MusicProvider` + `ProviderRegistry` + networking base; `YandexProvider` over the repositories. (M.)
10. Controller and queue on `Track` — 10a controller/bridge, 10b wave/radio, 10c reserve/downloads/History. (L.)
11. Player UI, mini player, queue sheet, widget, car screen. (M.)
12. Library and playlists. (M.)
13. Search through the composed path. (M.)
14. Home, catalog, History, queues, dialogs, context menu. (L.)
15. Composition of Home/Library sections + FM radio source. (M.)
16. Cleanup: allow-list empty, `TrackUrlResolver` internal, `PlayerRepository` without `ExoPlayer`. (S.)

**Checkpoint** — debug build, rides with Diag, fixes; possibly release **v0.4.0**. Phase 4 starts only after it.

### Stage 2 — Phase 4 Jellyfin (visible from step 20) → v0.5.0

17. **Local test server**: `scripts/jellyfin-dev/` — docker-compose for Jellyfin, a script that fetches free test
    music (public-domain / CC0 sets) into a gitignored folder, setup notes (test user, Quick Connect on, HTTPS with
    a local certificate the emulator trusts); no secrets, no server URLs. (S.)
18. `:provider:jellyfin` skeleton: API client, auth header, `/System/Info/Public`, DTOs, mappers; tests on
    JSON recorded from the local Docker server (D85). (M.)
19. **Canvas**: Jellyfin sign-in (password + Quick Connect), provider chooser in Accounts, Jellyfin sections and
    badges on Home/Library/Search — reviewed and approved by the user before UI code.
20. Sign-in + accounts: password and Quick Connect, duplicate refusal, per-account database, default queue,
    sign-out per D62. (M.)
21. Library: albums, artists, playlists, genres, their pages, paging. (L.)
22. Streaming and covers: `JellyfinStreamResolver`, image auth for the server host only, downloads. Ride with
    the unit. (M.)
23. Search merged with badges; Home/Library composition with two providers; car home and Android Auto. (M.)
24. Queues and History with Jellyfin tracks: "Add to queue" offers only the track's account's queues (D84),
    binding, unavailable when signed out. (M.)
25. Play reporting `/Sessions/Playing*` (optional). (S.)
26. Docs/KB as built, release notes, **release v0.5.0**.

## Risks

- **Upstream ports (A3):** hot files wrapped, mappers absorb DTO changes; UI ports are reimplemented anyway.
- **Engine regressions** (steps 4, 6, 10, 22): the playback-stability fixes are fresh — scripted-player tests plus a
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
