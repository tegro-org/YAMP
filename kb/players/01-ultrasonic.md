# 01 — Ultrasonic 4.9.0: playback architecture

Subject: `ultrasonic-4.9.0.apk` (5.6 MB, a single `classes.dex`, **no native libraries**).
Source: the public code of tag `4.9.0` (gitlab.com/ultrasonic/ultrasonic), key places cross-checked against the APK (jadx).

Notation: **[V]** — verified (in the tag's code and/or the APK), **[I]** — inference / library default, not traced line by line in the APK.

Paths below are relative to `ultrasonic/src/main/kotlin/org/moire/ultrasonic/` (unless stated otherwise).

---

## 1. Playback engine

| What | Value | |
|---|---|---|
| Library | AndroidX **Media3 1.9.2** (`gradle/libs.versions.toml: media3 = "1.9.2"`; the APK has the string `[AndroidXMedia3/1.9.2]` in `ExoPlayerImpl`) | [V] |
| Modules | `media3-common`, `-exoplayer`, `-datasource`, `-datasource-okhttp`, `-session` (+ `-inspector`) | [V] |
| Decoder extensions | none (no ffmpeg, flac or opus extension) — no `.so` in the APK | [V] |
| Decoder | default `DefaultRenderersFactory` → `MediaCodecAudioRenderer` (platform MediaCodec). No custom `RenderersFactory`/`AudioSink` | [V] (no code) |
| Extractors | `DefaultMediaSourceFactory` → `ProgressiveMediaSource` + `DefaultExtractorsFactory` | [V] |
| HTTP | Media3 `OkHttpDataSource` over a bare `OkHttpClient.Builder().build()` (OkHttp 5.3.2) | [V] |
| Alternative backend | `JukeboxMediaPlayer` — server-side playback (Subsonic jukebox), unrelated to local audio | [V] |

Core class: `service/PlaybackService.kt` (`MediaLibraryService`), method `getLocalPlayer()`.

## 2. Player instances, gapless, crossfade, preloading

- **One `ExoPlayer`** per service; the whole playlist is a list of `MediaItem`s in it. On backend change the old one is `release()`d and a new one created (`updateBackend`). [V]
- **Gapless** — stock ExoPlayer only: transition between periods of one player, encoder delay/padding trimmed from metadata (LAME/iTunSMPB/Opus pre-skip). Nothing custom. [V — no own code; mechanism — I]
- **Crossfade — none.** No second player/mixer. [V]
- **Preloading of the next track** — two levels:
  1. ExoPlayer itself starts buffering the next period once the current one is fully loaded within the buffer (50 s). [I — Media3 default]
  2. The app **downloads files** of the next tracks: `PlaybackService.cacheNextSongs()` is called on `onMediaItemTransition`, `onTimelineChanged`, `onIsPlayingChanged`, `onShuffleModeEnabledChanged` → `DownloadService.download(nextSongs, isHighPriority = true)`. [V]
     - List: `Util.getPlayListFromTimeline(timeline, shuffle, currentIndex, Settings.preloadCount)` — **includes the current track** and, because of the condition `count >= playlist.count()`, takes `preloadCount + 1` items. [V]
     - Consequence: **the current track is streamed by ExoPlayer and downloaded to a file at the same time** → double traffic on first play. [V]
- `PlaybackService` does not set `setPreloadConfiguration`/`PreloadManager`. [V]

## 3. Network, buffering, cache

### 3.1 LoadControl
No custom `LoadControl` → Media3 1.9.2 `DefaultLoadControl` (values checked in the APK, `androidx/media3/exoplayer/DefaultLoadControl.java`): [V]

| Parameter | Network | Local (file) |
|---|---|---|
| minBufferMs | 50 000 | 1 000 |
| maxBufferMs | 50 000 | 50 000 |
| bufferForPlaybackMs | 1 000 | 1 000 |
| bufferForPlaybackAfterRebufferMs | 2 000 | 1 000 |
| targetBufferBytes | -1 (→ audio default ≈ 13 MB) [I] | |

I.e. on a network drop there are ≈ 50 s of audio in reserve; start after 1 s, after a rebuffer — 2 s.

### 3.2 DataSource chain (`PlaybackService.getLocalPlayer`) [V]
```
DefaultMediaSourceFactory
 └ CachedDataSource (data/CachedDataSource.kt)        — own, NOT the media3 CacheDataSource
    └ ResolvingDataSource (resolver: id → stream URL)
       └ OkHttpDataSource(OkHttpClient())
```
- `MediaItem` URI = `"$id|$bitrate|$filePath"` (`util/MediaItemConverter.kt:68-69`, bitrate = `Settings.maxBitRate` at the time the item is created). [V]
- `CachedDataSource.open()`: if a `*.complete.*` or pinned file of the track exists — reads the **local file** (with `skipFully` to the position), otherwise passes through to upstream. The check is **only in `open()`**: a file finished during playback is picked up only at the next open (seek/retry/next pass). `.partial` is not used for playback. [V]
- The resolver drops headers (`setHttpRequestHeaders(emptyMap())`) — comment: AirSonic does not handle icy-metadata well. [V]

### 3.3 Transcoding (maxBitRate/format)
- **Streaming via ExoPlayer — no transcoding.** `RESTMusicService.getStreamUrl(id, maxBitRate, format)` builds `API.stream(id)` and **ignores** `maxBitRate` and `format` (in the APK: `stream$default(API, str, null, null, …)`). I.e. the stream comes in original quality (FLAC etc.) unless the server has a per-user limit. [V — bug/quirk]
- **Downloads (preload/pin)** — `DownloadTask.download()` → `getDownloadInputStream(track, offset, maxBitRate, save)`: `stream.view?maxBitRate=…` (cache) or `download.view` (pin, "highest quality"). `format` is never passed. [V]
- Separate Wi-Fi/mobile limits: `Settings.maxBitRate` = `maxBitrateMobile` if `Util.isNetworkRestricted()` (metered **or** cellular; Wi-Fi+VPN counts as not metered), otherwise `maxBitrateWifi`. [V]
- Risk [I]: the stream (original) and the downloaded file (transcode) are different bytes. If ExoPlayer reopens the source at a byte position (retry after a drop) after the `.complete` has appeared, the position lands in a different file → parse error/artifact.

### 3.4 Own file cache [V]
- Files: `<base>.partial.<ext>` → when finished, renamed to `.complete.<ext>` or pinned (`DownloadTask.afterDownload`, `util/FileUtil.kt`).
- Resume: Range request from the length of `.partial`; a 206 response → append (`DownloadTask.download`, `RangeHeaderInterceptor`). [V]
- `DownloadService` — foreground service (`dataSync`), `PriorityBlockingQueue`, `Settings.parallelDownloads` in parallel (default 3), retries `MAX_RETRIES = 5` (re-queues), with no network/storage — retry after `CHECK_INTERVAL = 5000` ms. [V]
- Eviction `util/CacheCleaner.kt`: LRU by `lastModified`, deletes max(excess over `cacheSizeMB`, shortfall to `MIN_FREE_SPACE = 500 MB` on the filesystem). Candidates are only `.partial`/`.complete` (pinned files untouched, `findCandidatesForDeletion`); tracks of the current playlist are protected (`findFilesToNotDelete`). Runs when the download queue becomes empty (`processNextTracks` → `cleanSpace()`). [V]

### 3.5 HTTP stack [V]
- Player: **bare `OkHttpClient()`** → OkHttp defaults: connect/read/write timeout 10 s, `retryOnConnectionFailure = true`, HTTP/2 allowed. Self-signed certificates are **not** supported for the stream (the flag exists only in the API client). [V code, I OkHttp defaults]
- API/downloads (`core/subsonic-api/.../SubsonicAPIClient.kt`): `READ_TIMEOUT = 60_000` ms; with Range — `30 000 + offset·0.02` ms (≈ +20 s per MB of offset, for server-side transcoding). [V]
- The `networkTimeout` setting (default 15000) exists in the UI but is **not used in code**. [V]

### 3.6 Behaviour on network loss [V/I]
- No own handlers: `MediaPlayerManager.onPlayerError` for the local player only logs (a toast only for jukebox). [V]
- ExoPlayer: plays out the buffer (≤50 s), retries per `DefaultLoadErrorHandlingPolicy` (≈3 attempts with 1…5 s delay, counter reset on progress), then `STATE_IDLE` + error; no auto-restart — the user presses Play. [I]
- Tracks already downloaded to `.complete` play offline without a network. [V]

## 4. Audio output [V]
- `AudioAttributes`: `USAGE_MEDIA`, `AUDIO_CONTENT_TYPE_MUSIC`, `handleAudioFocus = true` (`setAudioAttributes(…, true)`).
- `setHandleAudioBecomingNoisy(true)`.
- `AudioSink`/`AudioTrack` — defaults (`DefaultAudioSink`), float output not enabled, no custom `AudioProcessor`.
- Offload: via the `use_hw_offload` setting (default false) → `AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED` (in the APK `audioOffloadMode = 1`). With offload, on most ROMs effects (EQ) are not applied. [I]
- **ReplayGain** (`service/ReplayGain.kt`): on `onTracksChanged` reads the tags `REPLAYGAIN_TRACK_GAIN/ALBUM_GAIN`, `R128_*` from ID3 TXXX / VorbisComment, `player.volume = clamp(10^(gain/20), 0, 1)`. Attenuation only (gain >0 dB is clipped), no preamp, peak ignored; "dynamic" mode = album gain if the first ≤30 tracks of the playlist are from one album. Applied via player volume at track change → a short level jump at the boundary is possible. [V code, I effect]

## 5. Equalizer/effects [V]
- `audiofx/EqualizerController.kt`: `android.media.audiofx.Equalizer(priority = 0, player.audioSessionId)`; settings (band levels, preset, enabled) serialized to `equalizer.dat`. No own DSP; no other effects (BassBoost/Virtualizer/LoudnessEnhancer).
- The session id is generated by ExoPlayer on creation (AudioManager.generateAudioSessionId) — stable for the player's lifetime. On backend change the old Equalizer is not released (`release()` is not called in `updateBackend`).
- **`ACTION_OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION` are not sent** — neither by the app nor by Media3 (the string is not in the APK). System/vendor EQs that attach via this broadcast will not see Ultrasonic's session.

## 6. Reliability
- **Foreground**: `PlaybackService : MediaLibraryService` (`foregroundServiceType="mediaPlayback"`), notification handled by Media3 (`DefaultMediaNotificationProvider`). `DownloadService` — a separate FGS `dataSync`, `START_NOT_STICKY`. [V]
- **Wake/Wi-Fi lock**: `setWakeMode(WAKE_MODE_NETWORK)` online / `WAKE_MODE_LOCAL` offline (PARTIAL_WAKE_LOCK + WifiLock managed by ExoPlayer). `DownloadService` holds its own `WifiLock` `WIFI_MODE_FULL_HIGH_PERF` (API<34) for its whole lifetime. [V]
- **Audio focus/ducking**: entirely Media3 `AudioFocusManager` (on `CAN_DUCK` for MUSIC — volume ×0.2, on transient loss — pause/resume). No own code. [V code, I behaviour]
- **MediaSession**: `MediaLibrarySession` (Media3 session) + `MediaLibrarySessionCallback` (tree for Android Auto/browsers), custom commands heart/shuffle/repeat. [V]
- Bluetooth: `receiver/BluetoothIntentReceiver.kt` — resume/pause on A2DP connect/disconnect (configurable); headset plug — `MediaPlayerLifecycleSupport`. [V]
- Session restore: `PlaybackStateSerializer` + `MediaPlayerLifecycleSupport.restoreLastSession()`. `onTaskRemoved` — **full stop** and release (task swiped away → music stops). [V]
- **Thread priorities**: the app does not touch them; the `ExoPlayer:Playback` thread is created by Media3 with `THREAD_PRIORITY_AUDIO` [I]. Downloads — `Dispatchers.IO` + up to 3 parallel TCP connections.
- **Error recovery**: none (see 3.6).

## 7. Settings (SharedPreferences key → default)

Note: `PreferenceManager.setDefaultValues` **is not called** — until the settings screen is opened, the defaults from code apply (third column), not the ones from XML. [V]

| Key | XML default | Default in code | Meaning |
|---|---|---|---|
| `maxBitrateWifi` | 320 | 0 (no limit) | kbps limit for downloads on Wi-Fi (values 32…320, 0) |
| `maxBitrateMobile` | 256 | 0 | same on metered/cellular |
| `maxBitratePinning` | 320 | 0 → `download.view` (original) | for "pinned" tracks |
| `wifiRequiredForDownload` | false | false | download only on unmetered |
| `preloadCount` | 3 | -1 → ∞ (the whole playlist!) | how many next tracks to download |
| `parallelDownloads` | 3 | 3 | 1…10 |
| `cacheSize` (MB) | 500 | -1 → ∞ | cache limit |
| `use_hw_offload` | false | false | offload |
| `replayGain` | `replayGainDisabled` | same | Disabled/Dynamic/PreferTrack/PreferAlbum/TrackOnly/AlbumOnly |
| `incrementTime` | 5000 | 5000 | seek step, ms |
| `networkTimeout` | 15000 | — | **not used** |
| `playback.resume_play_on_headphones_plug` | false | true | |
| `resumeOnBluetoothDevice` / `pauseOnBluetoothDevice` | — | DISABLED / A2DP | |

---

## What to take for our player (head unit, Android 9, weak hardware, unstable network)

**Adopt:**
1. **Media3 ExoPlayer, one instance, playlist inside it** — gapless out of the box, minimal code. No ffmpeg extension (saves APK/RAM), as long as the server serves codecs the head unit's MediaCodec supports (MP3/AAC/Opus; FLAC — check on the specific ROM).
2. **The "download the file ahead → play from disk" model** — the main thing that makes Ultrasonic resilient to the network: the next N tracks sit in `.complete`, a network drop between tracks is inaudible. Range resume from `.partial` — adopt too.
3. Separate bitrate limits by network type (`NetworkCapabilities` metered/cellular; in a car it is usually a phone hotspot = metered).
4. `WAKE_MODE_NETWORK`, `handleAudioBecomingNoisy`, `USAGE_MEDIA/CONTENT_TYPE_MUSIC`, `MediaLibrarySession` (steering wheel / head-unit buttons / Android Auto-like clients).
5. ReplayGain from tags — a good idea, but see below.

**Do differently (Ultrasonic's bugs/weaknesses):**
1. **Pass `maxBitRate`/`format` in the stream URL too** (Ultrasonic always streams the original). For the head unit — a fixed transcode (e.g. `format=opus&maxBitRate=128` or mp3 192) for both stream and cache → identical bytes, no risk of mixing files, less CPU for decoding.
2. **Do not download the current track in parallel with the stream.** Better a single path: Media3 `CacheDataSource` + `SimpleCache` (`LeastRecentlyUsedCacheEvictor`, e.g. 1–2 GB) — the stream is written to the cache directly, and for the next tracks — `CacheWriter`/`DownloadManager` or `PreloadConfiguration`. Then there is no double traffic and data is picked up per block, not only at `open()`.
3. **Own `LoadControl` for a bad network**: e.g. min 60–120 s, max 120–300 s, `bufferForPlayback` 2–3 s, `afterRebuffer` 5 s, `setPrioritizeTimeOverSizeThresholds(true)`, cap `targetBufferBytes` (≈ 8–16 MB at 128–192 kbps is minutes of audio) — RAM stays under control.
4. **Own `LoadErrorHandlingPolicy`**: infinite retries with backoff (1→2→5→10 s) for network errors while there is buffer; plus `ConnectivityManager.NetworkCallback` → when the network returns, `prepare()`+`play()` from the same position. Ultrasonic's player just goes IDLE.
5. **OkHttp for the stream**: explicit connect 10–15 s / read 20–30 s, `pingInterval`, HTTP/1.1 (Ultrasonic itself mentions an OkHttp+Exo bug with HTTP/2 in a comment, but did not disable it for the player), self-signed support if the server is a home one.
6. **Explicit defaults** (`setDefaultValues` or code defaults = XML): "-1 → ∞" for preload/cache is dangerous on a head unit with small eMMC.
7. **Equalizer**: if the head unit's system/vendor EQ is needed — send `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` with the session id and `EXTRA_CONTENT_TYPE_MUSIC` (and CLOSE on release). Own EQ — either `audiofx.Equalizer` on the session (cheap on CPU but ROM-dependent), or an `AudioProcessor` in `DefaultAudioSink` (float biquads — controllable, but costs CPU). Do not enable offload on the head unit if EQ is needed.
8. **ReplayGain** in an `AudioProcessor` (per track, without a jump at the boundary) with preamp and peak-based clip protection, not via `player.volume` on `onTracksChanged`.
9. **Do not stop on `onTaskRemoved`** (in a car the task is often "swiped" by the launcher/ROM); FGS `mediaPlayback` with `START_STICKY` semantics and queue/position restore at start (the head unit often "wakes" from ACC sleep).
10. Limit parallel downloads to 1–2 (head-unit CPU/network), hold the WifiLock only while actually downloading.
