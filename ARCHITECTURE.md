# YAMP Architecture

Status: **approved** (2026-11-09). Part 1 describes the imported code as it is; Part 2 sets the binding rules
for YAMP code. Changes to the rules need the user's approval and a row in `kb/decisions.md`.

UI/visual rules: [DESIGN_GUIDE.md](DESIGN_GUIDE.md). Feature plan: [docs/multi-source-player.md](docs/multi-source-player.md).

---

## Part 1 — As-is (upstream YandexMusicLite `fac6af7`)

Kotlin, Jetpack Compose, Hilt, Retrofit + kotlinx-serialization, OkHttp, Media3 (ExoPlayer + MediaLibraryService),
Room, DataStore, WorkManager, Glance, Coil. Single `:app` module + `:baselineprofile`. ~25.6k LOC, 133 files.

```
presentation/  (Compose screens + @HiltViewModel)  ──uses directly──►  data/*  (incl. DTOs, PlaybackController)
      │                                                                   │
      └────────────►  domain/  (3 interfaces + few models) ◄─────────────┘
di/  (4 Hilt modules, all Singleton)
```

Key facts (details in the KB):

| Area | Fact | Detail |
|---|---|---|
| Layers | `domain` is thin; **UI imports `data` widely** (35 files, Yandex DTO in 22) | [as-is-data](kb/architecture/as-is-data.md) |
| Model | `TrackDetailsDto` (Yandex JSON) is the de-facto domain model everywhere, incl. Room and the playback snapshot | as-is-data |
| Ids | Track ids are unscoped strings (Room, files, reserve, snapshot) | as-is-data |
| Network | One Retrofit/OkHttp with Yandex base URL; token interceptor on every default-client call; no error model | as-is-data |
| Player | `PlaybackController` (1296 lines) = single source of truth; engine = 2 ExoPlayers with one item each; queue only in the controller | [as-is-playback](kb/architecture/as-is-playback.md) |
| Queue | One queue, no index (lookup by id → duplicates break), one-shot shuffle, persisted as one JSON file with a 50/150 window, edits not saved immediately | as-is-playback |
| Streams | `TrackPreloadManager` = Yandex resolution (signed URLs, lossless, encraw); engine also calls it | as-is-playback |
| Persistence | Room v1, no migrations; per-account key = `folderName` | as-is-data |
| Concurrency | Each singleton creates its own scope; dispatchers hard-coded | as-is-data |
| UI | No NavHost inside `main`; MainScreen holds 8 VMs; Hi-Fi design system in `presentation/hifi` | [as-is-ui](kb/architecture/as-is-ui.md) |
| Quality | Lint warnings = errors; ~107 screenshot goldens; source-text guard tests | [build-and-ci](kb/architecture/build-and-ci.md) |

Worth keeping: single-flight sync, cache-first with "never fewer details" fallback, optimistic updates with
rollback, pure tested policy objects, Room lists as metadata + ordered items in one transaction,
write-then-rename files, token isolation, Keystore secrets, controller-owned queue with a dumb engine,
`() -> Long` progress lambdas, `LoadState<T>`.

---

## Part 2 — Rules for YAMP

### A. Change strategy

- **A1 Strangler, not rewrite.** Old code keeps working; new structure grows beside it and old parts move in
  only when a feature needs them. No big-bang refactors.
- **A2 New code follows Part 2; touched old code** is moved toward it only within the feature's scope.
- **A3 Keep upstream-hot files recognisable.** Files that receive Yandex API fixes upstream
  (`YandexMusicApi`, `TrackPreloadManager`, `LosslessSigner`, `EncrawDecryptor`, `EncrawDataSourceFactory`,
  `DownloadInfoParser`, `StreamVariants`, `Md5Helper`, `DeviceSignIn`) are **wrapped, not rewritten**, and
  keep their names, so upstream diffs can still be ported (see `kb/upstream-tracking.md`).
- **A4 One feature = one design doc** in `docs/` approved before code (global workflow).

### B. Layers, modules and packages

**Modules (decided Q2 — gradual split):** new code starts in its own Gradle modules — `:core:domain` (pure
Kotlin/JVM), `:provider:jellyfin`, later `:core:queue` and others as they appear. Existing code stays in `:app`;
the Yandex code moves to `:provider:yandex` only after it has been wrapped behind the provider interface. Module
boundaries replace text-scan rules wherever they exist.

```
domain/                 pure Kotlin: models, provider/queue interfaces, pure policies (no Android, no DTO)
data/provider/<name>/   one package per provider: yandex/, jellyfin/ (API, DTOs, mappers, resolver)
data/queue/             queue store (Room), queue manager
data/player/            playback engine + controller (provider-neutral after migration)
data/...                existing packages until migrated
presentation/<feature>/ screen + ViewModel + UiState
presentation/hifi/      design system (see DESIGN_GUIDE)
di/                     Hilt modules
```

- **B1** `domain` imports nothing from `data`, `presentation`, Android framework or Media3.
  (Existing leaks — `PlayerRepository` exposing `ExoPlayer`, `@DrawableRes` in `RadioStation` — are fixed
  when touched.)
- **B2** Provider DTOs never leave their `data/provider/<name>/` package; mappers produce domain models.
- **B3** New/rewritten ViewModels depend on **domain interfaces or manager classes**, never on Retrofit APIs,
  DTOs or another provider's package.
- **B4** Enforced by `ArchitectureTest` (extend the existing text-scan test): rules B1–B3 for the new packages,
  plus "nothing outside `data/provider/yandex/` imports Yandex DTOs" once a screen has been migrated (allow-list
  of not-yet-migrated files, which may only shrink).

### C. Domain model & providers

- **C1 References** (D27). Every item of a provider is identified by `MediaRef(provider, kind, id)`.
  Persisted form: `"<provider>:<kind>:<id>"`, `kind` being a lowercase word — `track`, `album`, `artist`,
  `playlist`, `folder`, `genre`, … — e.g. `yandex:track:12345`, `yandex:playlist:1234567:1003`,
  `jellyfin:folder:3f2a…`. Parsing splits at the **first two colons**; the rest is the provider's own id,
  opaque, and may itself contain colons. No letter-prefix or kind-less short forms.
  `TrackRef` is a reference of kind `track` and the only track identity in new code (queue entries, player,
  downloads). Refs are used as Room keys, file name stems (sanitised), Media3 media ids, radio/mix seeds,
  pins and navigation arguments.
  Known limit: the provider part names the provider type, so **one server per provider type** (a second
  Jellyfin server would need the provider part extended, e.g. `jellyfin@home`).
- **C2 Neutral models**: `Track`, `Album`, `Artist`, `Playlist`, `Folder`, `Genre` — each carries its ref
  (C1) and only the fields the UI and player need (title, artists, album, duration, cover URL, explicit,
  loudness gain, …). Provider extras stay in the provider.
- **C3 `MusicProvider` interface** per account/server, grouped by capability (`Library`, `Search`,
  `Favorites`, `Radio`, `Folders`, `PlaylistEdit`, `Lyrics`…). The UI asks `capabilities` and hides what is
  missing — no `if (provider == YANDEX)` in UI code.
- **C4 `StreamResolver`** per provider: `suspend resolve(ref, prefs): ResolvedStream` with `uri`, `headers`,
  `quality`, optional `decryption`, `expiresAt`. Encraw/lossless details stay in the Yandex provider. The engine
  receives a final `ResolvedStream` and never calls a resolver itself.
- **C5 Accounts** become `ProviderAccount(provider, accountId, displayName, serverUrl?)`; secrets stay in
  `KeystoreStringPreferences`. Queues are app-wide, not tied to an account (Q7).
- **C7 Several providers at once** (decided Q4): the user can be signed in to Yandex **and** Jellyfin (and the
  built-in FM radio) simultaneously. Every feature/section is shown only when at least one configured provider
  supports it (capabilities, C3): My Wave, charts, dislikes → Yandex; folders, Instant Mix → Jellyfin; FM radio
  → built-in "Radio" source that can be switched off. Home, Library, Search and car home are composed from the
  sections of all active providers; the start page is the first available one. Upstream extras are therefore
  kept, not dropped.
- **C6 Networking per provider**: each provider has its own OkHttp client built from a shared base (pool,
  cache, timeouts) with **its own** auth interceptor. No interceptor adds credentials for a host it does not
  own (lesson from the token leak).

### D. Errors & results

- **D1** Provider calls return `Outcome<T>` (`Ok` / `Err(ProviderError)`) with a small sealed
  `ProviderError` (Offline, Unauthorized, NotFound, Server(code), Unsupported, Unknown). Exceptions don't cross
  the provider boundary (except `CancellationException`, always rethrown).
- **D2** ViewModels map errors to UI state; user-facing text comes from string resources in the UI, not from
  VMs/data (`UiMessage` = resource id + args).

### E. Playback & queues

- **E1** Keep the proven split: controller decides, engine (2 ExoPlayers, one item each) plays. Don't move the
  queue into ExoPlayer's playlist.
- **E2 Queue model**: `PlayQueue(id, name, kind, origin, seed?, cursor, positionMs, shuffle, repeat)` with
  `QueueEntry(entryId, ref, …)` — the cursor is an **entry id/index, never a track lookup**, so duplicates
  are allowed. `kind` = STATIC / WAVE / RADIO / MIX (behaviour); `origin` = where it came from (display only).
- **E3 Write-through persistence**: every queue mutation is written to Room in the same call path; position
  every 15 s + on pause/switch/`onTaskRemoved`. No window — the full queue is stored. Restore = active queue,
  paused at its position (today's restore semantics).
- **E4 Dynamic queues** (wave, radio, live mixes) get one `DynamicSource` per queue (generalised `WaveQueue`:
  `fetchBatch`, `feedback`), appended, never replaced.
- **E5** "Replace queue" and "move cursor" are separate operations (today `playTrack` does both).
- **E6** The controller gets direct tests before it is restructured (fake engine + fake resolver).

### F. Persistence

- **F1** Room is the home for new structured state (queues, provider accounts' non-secret data, folder pins).
  `CacheDatabase` moves to Hilt-provided; from now on **every schema change ships a migration + migration test**
  (first one is v1→v2).
- **F2** Secrets only in `KeystoreStringPreferences`. Never in Room, DataStore, logs or crash reports.
- **F3** Files: write-then-rename; names derived from `TrackRef` (C1).

### G. DI & concurrency

- **G1** New interfaces bound with `@Binds` in modules; new classes stay `@Singleton @Inject constructor`.
- **G2** New code gets an injected `@AppScope CoroutineScope` and a `DispatcherProvider` (testability). Old
  singletons keep their own scopes until touched.
- **G3** Main-thread only for ExoPlayer and UI; IO for disk/network via `withContext`.

### H. Presentation

- **H1** New screens: one `@Immutable UiState` per screen exposed as `StateFlow`, `LoadState<T>` for lists,
  VM methods as actions. Player state via `NowPlaying` + Locals (as now); progress as `() -> Long`.
- **H2 Navigation (decided Q3):** introduce a proper Navigation Compose graph inside `main` with type-safe
  routes, at the latest together with the folder browser. Browse pages (folders, artist/album of any provider)
  become routes; the in-VM `CatalogViewModel._stack` is migrated onto it. Tabs keep their own back stacks.
  Popups/sheets stay Hi-Fi dialogs. Details in the folder-browser design doc.
- **H3** MainScreen is not grown further: new hosts/VMs go into feature composables
  (e.g. `presentation/queues/QueueSwitcherHost`).

### K. Localization (decided Q5)

- **K1** Proper Android resources: `values/` = **English** (default/fallback), `values-ru/` = Russian; more
  locales later by adding `values-<lang>/`. Standard `plurals` resources (`pluralStringResource`) replace the
  `russianQuantityString` workaround — the workaround existed only because the UI was Russian-only on any device
  language, which K1 removes.
- **K2** Semantic keys (`queue_switcher_title`, `cd_play`), not transliterated text (`library_mne_nravitsya`).
- **K3** In-app language choice (System / English / Русский): per-app locale via `LocaleManager` on Android 13+
  and an app-level override for older devices (the Android 9 head unit) — mechanism to be decided in its
  design doc.
- **K4** Migration follows A1: new/changed screens use K1–K2 immediately; the existing ~445 strings are
  migrated in one mechanical pass (keys renamed + English added) as its own task, not mixed with features.
- **K5** No user-visible text outside resources (also from ViewModels/data, see D2); numbers/dates/sizes
  formatted with the current locale.

### I. Testing & quality

- **I1** Pure logic in plain objects/classes with unit tests (as upstream: `PlaybackQueue`, `ReservePolicy`).
- **I2** Every new screen gets screenshot tests incl. the head-unit qualifier; add a **1920×720** qualifier
  matching the user's unit.
- **I3** Lint stays warnings-as-errors; `ArchitectureTest` grows with B4; `LayoutSafetyTest` guards are updated,
  not deleted, when YAMP changes them (Phase 0 rename).
- **I4** Instrumented tests that wipe auth run only on a test device.

### J. Security & privacy

- **J1** Credentials sent only to the host that issued them (C6). Jellyfin HTTPS only; no cleartext additions.
- **J2** Public repo: no keys, tokens, server URLs or personal data committed; signing via CI secrets.
- **J3** Re-run the privacy checklist (`kb/privacy-audit.md`) for any change to auth, networking or the updater.

---

## Decided

- **Q1** Package and `applicationId` → **`org.tegro.yamp`**, renamed once in Phase 0. Upstream ports then map
  `com/korenskoy/yandexmusic/` → `org/tegro/yamp/` (note in `kb/upstream-tracking.md`).
- **Q3** Proper navigation graph inside `main` (H2).
- **Q5** Full localization, English default + Russian, more later (section K).
- **Q2** Gradual module split (section B).
- **Q4** Upstream extras stay; visibility is driven by the configured providers' capabilities (C7).
- **Q6** Search across providers = one merged result list with provider badges.
- **Q7** Queues are **app-wide**, not per account (replaces D7). Entries of a provider that is not signed in are
  shown as unavailable and skipped during playback. Per-account stays: My Wave, likes, Yandex playlists.
  (Yandex has no named multi-queue concept to follow: its `/queues` API and Ynison only sync one current
  playback context across devices — from general knowledge, not verified in code.)

## Open questions

None at the moment.

