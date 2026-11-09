# As-is: layers, DI, data layer (upstream `fac6af7`)

Snapshot of the imported code before any YAMP change. Paths relative to
`android/app/src/main/java/com/korenskoy/yandexmusic/`. Parent: [ARCHITECTURE.md](../../ARCHITECTURE.md).

## Packages

| Package | Content |
|---|---|
| `domain/` | ~270 LOC. `model/` (`UserAccount`, `RadioStation`, `AppPreferences.kt` incl. Yandex rotor `Wave*` enums), `repository/` (3 interfaces: `AuthRepository`, `PlayerRepository`, `SettingsRepository`). No use cases. |
| `data/` | Most code: `account`, `auth`, `cache`, `crash`, `download`, `dto`, `network`, `player`, `repository`, `reserve`, `security`, `updater` |
| `di/` | 4 Hilt modules |
| `presentation/` | screens + ViewModels (`account, auth, car, catalog, crash, hifi, main, player, radio, splash, update`) |
| `ui/theme` | vestigial Material theme (see [as-is-ui.md](as-is-ui.md)) |
| `utils/`, `widget/` | helpers; Glance widget (uses `PlaybackController` + DTOs via `@EntryPoint`) |

**Dependency direction**
- `domain → data`: none, but domain is not framework-free (`PlayerRepository` exposes `ExoPlayer`,
  `RadioStation` uses `@DrawableRes`).
- `data → di`: `DeviceSignIn`, `GitHubUpdateManager` import the `@UpdateClient` qualifier (minor).
- `presentation → data`: **pervasive** — 35 files; `data.dto` 31 imports, `data.player` 21; 22 files use
  `TrackDetailsDto`. Worst: `MainViewModel` (11), `PlaylistsViewModel`, `CatalogViewModel` (6).
  `PlaylistsViewModel.kt:197` catches `retrofit2.HttpException`.
- `data → presentation`: none.

## Hilt

- All modules are `object` + `@Provides` in `SingletonComponent`; no `@Binds`, no narrower scopes.
- `AuthModule` → `AuthRepositoryImpl`; `SettingsModule` → `SettingsRepositoryImpl` (DataStore);
  `PlayerModule` → two ExoPlayers + `PlayerRepositoryImpl`; `NetworkModule` → `Json`, default
  `OkHttpClient` (AuthInterceptor, debug-only logging with redaction, 20 MB cache, 15 s timeouts),
  `@UpdateClient` client (same minus `AuthInterceptor`), Retrofit (`https://api.music.yandex.net/`).
- Everything else: `@Singleton class X @Inject constructor`.
- `CacheDatabase` is **not** Hilt-provided: `CacheDatabase.get(context)` singleton.
- Coil `ImageLoader` has its own client (token never reaches image hosts) — `YandexMusicApp.kt:33-47`.
- ViewModels get **concrete data classes** (`PlaybackController`, `LibraryRepository`, `TrackDownloadManager`…),
  not domain interfaces. Worker and widget use `@EntryPoint`.

## Networking

- One Retrofit interface `data/network/YandexMusicApi.kt`, all `suspend`, most return
  `ResponseWrapper<T>`. Groups: account, tracks, likes (tracks only + dislikes), playlists (incl.
  `change-relative` diffs with revision), streams (`download-info`, signed `get-file-info`), catalog
  (artist brief-info, album with tracks, search), rotor (station tracks/feedback/info/settings).
- `AuthInterceptor` adds Yandex UA/client headers + `OAuth <token>` to **every** default-client request
  (incl. CDN downloads).
- **No error model**: `ResponseWrapper.error` is never read; per call site: throw (Catalog/Search),
  `runCatching…getOrDefault`, try/catch→log→null/false; `IOException` = offline → queue reaction.
  `CancellationException` rethrown by convention.
- No retry interceptor; fallback chains (uid → "me", POST → GET).

## Repositories

| Class | Interface? | Notes |
|---|---|---|
| `AuthRepositoryImpl` | yes | accounts/tokens, StateFlows, `@Synchronized`, sync API |
| `SettingsRepositoryImpl` | yes | DataStore `settings`, `Flow<T>` + suspend setters |
| `LibraryRepository` | no | likes + charts (charts = **search queries** "чарт", "новинки"); single-flight `Mutex`+`Deferred`; revision sync; optimistic like with rollback; offline reaction queue |
| `UserPlaylistsRepository` | no | revision-keyed cache, edits via `PlaylistDiff`, conflict exception |
| `CatalogRepository` | no | artist/album, LRU 20, throws |
| `SearchRepository` | no | stateless, throws |
| `SearchHistoryRepository` | no | DataStore per account key |
| `RadioRepository` | no | hard-coded FM stations + favorites |

## Persistence

- **Room** `music_cache.db` **version 1, no migrations**, schema exported to `app/schemas/1.json`.
  `TrackEntity(id, json)` (whole DTO as JSON), `ListItemEntity(owner, list, position, trackId)`,
  `ValueEntity(owner, key, value)`. `owner` = account folder or `~shared`. **Track ids unscoped.**
- `PlaylistCacheManager`: lists `liked`, `wave`, `recent`, `pending_reactions`, `playlist:<kind>`, charts;
  some lists store non-track strings (`LIKE:<id>`).
- `PlaybackStateStore`: one global `playback_state.json` (see [as-is-playback.md](as-is-playback.md)).
- DataStore: `settings`, `search_history`, `radio_settings`. SharedPreferences: `card_storage`, `crash_log`,
  auth store.
- `KeystoreStringPreferences`: AES-256-GCM values with a Keystore key (file `auth_secure`), fallback to plain
  prefs, one-time migration from `EncryptedSharedPreferences`.
- Files: `filesDir/tracks/<id>.mp3|.quality` (**global across accounts**), SD `<id>.enc`,
  `cacheDir/road_reserve/`, `http_cache`, `covers`, `updates`.
- **Per-account key** = `UserAccount.folderName` (login → displayName → uid, sanitized, 40 chars), computed
  as `getActiveAccount()?.folderName ?: "default"` in 4+ places.

## Accounts & auth

- `UserAccount(uid, login, displayName, token, avatarUrl, lastActiveTimestamp, hasPlus)`, all accounts as one
  JSON in the secure store; active/default uid; provisional `temp_<ts>` uid until account status arrives.
- `AccountSessionManager` orchestrates: `syncAll` (4 parallel jobs + `primeWave`), `switchAccount`
  (stop → switch → `resetForAccount` → reset repos → load cache → sync), logout, cache clearing.
  Hard-depends on `YandexMusicApi`, `PlaybackController`, concrete repos.
- Sign-in: WebView implicit OAuth, or `DeviceSignIn` (device flow, sealed `Poll` result,
  `BuildConfig.YANDEX_DEVICE_SECRET`). One active account; token via interceptor.

## Downloads & offline

- `TrackDownloadManager`: downloads resolved URL with the authenticated client, decrypts encraw, writes
  internal `.mp3` or card `.enc` + `.quality`; StateFlows for ids/size/progress. Keyed by bare trackId.
- `DownloadScheduler` (WorkManager unique works), `LikedTracksDownloadWorker` (`CoroutineWorker` via
  EntryPoint, foreground DATA_SYNC).
- `DownloadStorage`/`CardStorage`: internal vs SD card (card copy encrypted, `CardAwareDataSourceFactory`).
- Road reserve: `RoadReserve` (LRU cache + JSON index of `ReserveEntry(track: TrackDetailsDto…)`),
  pure `ReservePolicy`, `ReserveKeeper` constructed inside `PlaybackController`.

## Concurrency

- No injected app scope/dispatchers: each singleton makes `CoroutineScope(SupervisorJob() + Dispatcher)`;
  dispatchers hard-coded (43× IO). Never cancelled (process lifetime). Hard to test.
- Patterns: `Mutex`+`Deferred` single-flight, `@Synchronized`, `ConcurrentHashMap`, `withContext(IO)`.

## Worth keeping

Single-flight sync; cache-first then network with "never fewer details" fallback; optimistic update +
rollback; pure tested policy objects (`ReservePolicy`, `PendingReactions`, `PlaybackQueue`); Room lists as
metadata + ordered items in one transaction; write-then-rename files; token isolation; Keystore secrets.

## Yandex leaks (what blocks a second provider)

1. `TrackDetailsDto` is the domain model (Room JSON, snapshot, reserve, 22 UI files, widget, utils).
2. Unscoped string ids everywhere (Room, files, reserve, snapshot); ad-hoc `radio_` prefix.
3. One global Retrofit/OkHttp with Yandex base URL and token interceptor.
4. Accounts are implicitly Yandex (no provider/server field; `folderName` from login; `"me"` uid).
5. Yandex concepts in repo APIs (playlist kind/revision, rotor enums in `domain/model`).
6. Stream resolution (`TrackPreloadManager`) shared by player, downloads, reserve.
7. ViewModels depend on concrete data classes and `PlaybackController`.
8. Single playback snapshot slot.
9. Room v1 without migrations.
10. No common error type.
