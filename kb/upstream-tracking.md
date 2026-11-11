# Upstream tracking

YAMP is a hard fork: no merges. Upstream changes are reviewed in batches and each one gets a decision.

## Sources

| Source | Repo | Local clone (read-only) | Role |
|---|---|---|---|
| Upstream | [KorenskoyR/YandexMusicLite](https://github.com/KorenskoyR/YandexMusicLite) | `~/Projects/Github/YandexMusicLite` | Code base; Yandex API fixes |
| Idea source | [Ozo-bro/YML](https://github.com/Ozo-bro/YML) | `~/Projects/Github/YML` | Fork of upstream with car-split features |

## Baseline

- **Imported:** upstream `fac6af78faec620ae39ef9f2f20e37c39ba1c220` — "1.12.2: "Далее" in the car as a
  row of covers…" (2026-11-09 18:44 +1000), via `git archive` (no upstream history in our repo).
- **Last reviewed upstream commit:** `9897e61` (1.14.1, 2026-11-11) — **triaged**, ports pending per the batches
  in the review log below. Reviewed range: `fac6af7..9897e61`, 20 commits, 261 files (+8385/−761, more than half
  of them upstream screenshot goldens).
- **YML last looked at:** `f923e6c` (2026-11-09). It was 35 commits ahead / 22 behind upstream (merge-base
  `23ad3f2f`) and lacked the 1.9.4 token-leak fix.

## Porting note (after Phase 0)

Upstream paths `app/src/*/java/com/korenskoy/yandexmusic/` map to `android/app/src/*/java/org/tegro/yamp/`
(same class names; `package`/`import` lines rewritten). Identity strings and constants (app name, applicationId,
updater repo, User-Agent, wake-lock tags, widget actions, version numbers) are never ported. Upstream's version
bumps are irrelevant: YAMP's version comes from git tags (D37).

## How to review

```sh
cd ~/Projects/Github/YandexMusicLite && git pull --ff-only
git log --reverse --format='%h %ci %s' <last-reviewed>..origin/main
git show <sha>          # per commit
```

For each commit decide and log below:

- **port** — copy as directly as possible (Yandex API, stream decryption `Encraw*`, `LosslessSigner`,
  download-info, sign-in, security fixes). These live behind our Yandex provider in nearly the same shape.
- **reimplement** — take the idea, write it our way (player/download bug fixes, UI ideas).
- **skip** — not relevant (their UI polish, website, features we removed), with a one-line reason.

Then update "Last reviewed upstream commit".

## YML features worth considering (not ported)

- Car mode adapted to a split screen (navigator + music), 1920×720, big tiles without captions.
- Playlists (own, liked) directly from car mode.
- Shuffle for any queue except My Wave, remembered between launches.
- Synced lyrics shown instead of the cover when available.
- Offline wave cache with a size limit (0.5–4 GB) — upstream now has its own "road reserve".
- Download whole playlists for offline.
- Lightweight graphics for weak head units (no blur/grain/background animation).

## Review log

### 2026-11-11 — `fac6af7..9897e61` (1.13.0 → 1.14.1)

Triaged with three readers (playback / security+API / UI+build) against our tree at `9807c92`. Grouped by
behaviour, not by commit. Line numbers are upstream HEAD. "Ours" = already in YAMP or differs.

**Batch U1 — security, data, build (port now, small and safe)**

| Change | Commit | Decision | Note |
|---|---|---|---|
| Н7 token only to Yandex hosts: `AuthInterceptor.isYandexHost` (`yandex.net/.ru/.com`, `ya.ru`) | e9e405f | **port** | Our shared client still adds the token for every host; `RoadReserve` fetches through it |
| Н3 card key + kept playlists out of backups; orphaned Keystore key replaced (`KeystoreKey.key()`, `hasWrapper()`) | e71d6a1 | **port** | `backup_rules.xml`, `data_extraction_rules.xml`, `CardStorage.kt` |
| Н6 HTTP cache evicted on account change (`AccountSessionManager.resetUserData`, `dagger.Lazy<OkHttpClient>`) | ee2d1cb | **port** | |
| Н1 one download per track (`trackLocks` Mutex map around `downloadNow`) | cc304b5 | **port** | `RoadReserve` downloads separately, not covered |
| Н5 closing the update window cancels the download (call watcher + `ensureActive`; `UpdateViewModel.downloadJob`) | 6e3bd46 | **port** | keep our temp-file name |
| Н9 `CancellationException` rethrown before catch-alls (AccountSessionManager, PlaybackStateStore, LibraryRepository, GitHubUpdateManager, MusicService browse, PlaybackController rotor feedback) | ed4a187 | **port** | |
| Н2 downloaded track whose file is gone streams instead of failing: `resolvePlayUri` falls back to `getOrFetchUrl` (not for `radio_`); re-index downloads when a copy is missing; `EncryptedFileDataSource.open` maps any exception to FILE_NOT_FOUND | ce81f13 | **reimplement** | without their `localTrackUri` (USB) |
| Release-notes cleanup in the updater (strip `#`, `**`, heading with the app name) | 76c36ef | reimplement | identity-specific regex → our name |
| Java 17 compile target (`compileOptions` 11 → 17; toolchain unchanged) | 76c36ef | **port** | our CI runs JDK 21 already |
| Compose compiler reports behind `-PcomposeReports` | 0eec396 | **port** | 5 lines |
| detekt CLI 1.23.8 job in CI (sha-pinned jar, `config/detekt.yml`, baseline) | 0eec396 | **port the approach** | our own baseline (`--create-baseline`), input `android/app/src/main/java` |
| Gradle 9.8.2 → 9.9.1, roborazzi 1.77 → 1.78, zxing 3.5.3 → 3.5.4; actions checkout v7, setup-java v6, upload-artifact v7, gh-release v3 | 76c36ef | port via Dependabot | uiautomator 2.4.1 already merged (#10) |
| Н11 imports instead of FQNs (71 files, mechanical) | a7bf428 | skip | conflicts with everything we touched; apply the convention as files are edited |
| versionCode/Name bumps, release notes in `release.yml` | several | skip | versions from git (D37) |

**Batch U2 — playback and head-unit fixes (port before Phase 1 restructures the controller)**

| Change | Commit | Decision | Note |
|---|---|---|---|
| Buffering policy `PlaybackBuffering.kt`: `DefaultLoadControl` 120/180 s, 2.5/5 s start, 16 MiB; `PatientLoadErrors` (4xx fail at once → re-resolve; else retry up to 14× with ≤5 s delay) | 6066355 | **port (copy)** | biggest win for stutters; two players × 16 MB |
| Crossfade that always lands: next track prepared 60 s ahead (was 15), held silent until `STATE_READY` + 5 s headroom (`awaitingIncoming`), late fade-in 0.3 s if the old track already ended, clock-paced fades (`alongTheClock`) | 6066355 | **port, adapted** | conflicts with our skip fade: `fadeOutForSkip` must treat `outgoingPlayer` as the audible one while `awaitingIncoming`; move our skip/quick fades onto `alongTheClock` |
| Equalizer re-announce, final form: on first sound, after a pause ≥ 5 s, on AUDIOFOCUS_GAIN, never twice within 1 s; `close()` on service destroy; `keepAnnounced(scope, isPlaying)` | 4a4213c, 76c36ef | **port, adapted to two sessions** | per-session guard; announce/close both when "Separate audio sessions" is on; do not take the per-track 4a4213c version |
| One track per swipe in the car (`carSwipeStep`, 72 dp, decided on drag end) | 7b7d6be | **port** | ours still skips per drag event (`CarScreen.kt:233`); check `PlayerParts.kt:304`, `RadioScreen.kt:410` |
| `streamNeedsTheLine` = buffering ‖ `bufferedAheadMs < 20 s` (reserve fills only with headroom); `PlayerRepository.bufferedAheadMs` | 6066355 | **port** | one-liner + a property |
| Windows keep the app's scale: `AppDialog` / `AppScale` re-provide `LocalDensity` inside `Dialog`/popups; `HiFiAlertDialog` rewritten with scrolling body + `FlowRow` buttons | 81406fc | **reimplement** | real bug on the head unit; our Phase 1 sheets build on it (6 bare `Dialog` uses in our tree) |
| Car font scale capped at 1.0 (`CAR_MAX_FONT_SCALE`), pill label autosize min 11 sp | d42fa04 | **reimplement** | wrap the car subtree after our `MainActivity` density |
| Coil `allowHardware(false)` globally; `enableEdgeToEdge(dark, dark)`; `isAppearanceLightNavigationBars = false` | 7b7d6be | **reimplement** | software-rendered head units crash on hardware bitmaps |
| Update window keeps header/buttons, middle scrolls (`roomy = maxHeight ≥ 280 dp`) | 81406fc, 76c36ef | **reimplement** | 720 px head units |
| Uneven-link switch to copy / ≤192 kbps stream mid-track (`LinkSteadiness`, `SmoothPlan`, `resolveLightStream`, `PlayerRepository.switchSource`, "Не прерывать музыку" setting) | 6066355 | **reimplement later** | pure parts (`LinkSteadiness`, `resolveLightStream`) copy now if wanted; `switchSource` needs our session + skip guards; controller wiring belongs to the Phase 1 controller / D36 doc |
| Playback journal (last hour of stalls/switches, shared from About) | 6066355 | reimplement later, optional | useful for field reports; log our skip fades too |

**Later — inside our own design (reimplement when the feature comes up)**

| Change | Commit | Decision | Note |
|---|---|---|---|
| Car: big cover + "Очередь" button opening the current queue's track list; `CarQueue.kt`; `car_under_cover` setting | 4a4213c, 7b7d6be, 3ee8458 | **skip** | replaced by our queue switcher (D26); their `CarQueuePanel` name collides with ours |
| Car on a flat head unit (1920×720): three columns via pure `CarWide.layout()`; `FLAT`/`FLAT_HDPI` qualifiers | 9897e61 | reimplement later, per our canvas | take the pure layout + thresholds and the qualifiers with Phase 1's 1920×720 work |
| Settings from the car (gear in the header; `settingsHost()` extracted from `LibraryTab`) | 81406fc | reimplement | when we touch `CarHeader` for the queue pill; the extraction fits H3 |
| New API: `likes/albums` (rich), `likes/playlists`, like/unlike album; DTOs `LikedAlbumDto`, `LikedPlaylistDto`, `SearchBestDto` | 76c36ef, 9897e61 | **port when needed** (Phase 3/5) | Yandex API — copy verbatim then; repositories + Library UI as the Yandex provider's |
| Library sync without restart (`syncLibraryNow` one job, `LibrarySyncPolicy` 30 s / 3 min, skipped offline or on a weak link) | 76c36ef | reimplement | per provider; gate on our link state |
| Voice search (`RecognizerIntent`, `VoiceSearch.pick`, best search result for Android Auto) | 9897e61 | reimplement later | hard-coded `ru-RU` and Russian regex → locale-aware, multi-source; the `MediaLibraryTree` "best result" idea is good |
| Kept playlists/albums on the device, single-track download (`OfflineCollections` JSON file, WorkManager) | 4a4213c, 9897e61 | reimplement later | in our multi-source offline model, in Room not a JSON file |
| USB flash drive as download storage (SAF, `SeekableBytes`) | 4a4213c | skip for now | different from our "play files from USB" idea; `SeekableBytes` reusable |
| Upstream screenshot goldens, new screenshot suites | all | skip | our pixels; port the pure unit tests with each reimplemented fix |

No Room schema change upstream (still v1); new data lives in `cache_values` blobs, a JSON file and DataStore.
