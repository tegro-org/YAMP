# 05 — Yandex Music 2025.09.1: playback architecture

Subject: Yandex Music v2025.09.1 (~67 MB; 6 dex ≈ 50 MB; `lib/arm64-v8a` — only 4 libraries). Package `ru.yandex.music`, versionCode 24025461, flavor `gplayProdRelease`.
Method: `unzip -l` → `aapt2 dump` (manifest) → `strings` over the dex → **one** jadx pass (`--no-res`, 3 GB heap): reached 92 % and failed with `OutOfMemoryError` in one thread; the process was stopped, missing classes were recovered with `dexdump -d` (retry policies, retry factory).
The code is R8-obfuscated (`defpackage/C15638ei2` etc.), but **Kotlin metadata kept the original names** (`CrossfadingExoPlayerImpl.kt`, `RetryingBalancingDataSource`, `GainController`, `TrackPreFetchControlImpl`…). Matching was done by those. jadx did not decompile many coroutines — where only the structure is visible, the item is marked [I].

Notation: **[V]** — verified in the code/manifest, **[I]** — inference (from the name, indirect evidence or server parameters whose values are not visible in the APK).

> **Key point for the head unit:** `minSdkVersion = 24`, `targetSdkVersion = 35` [V] — **installs on Android 9**. `largeHeap=true` [V]. `arm64-v8a` only [V].
> The engine is **not an in-house "Yandex Player SDK"** but the **old ExoPlayer 2.18.4** (`com.google.android.exoplayer2`, string `ExoPlayerLib/2.18.4`) + the **libflac** extension [V]. Media3 **1.6.1** is present only as the **MediaSession/MediaController** layer [V]. (A separate `ru.yandex.video.player` is for video clips and unrelated to audio [V].)
> Strengths valuable for us: **its own network layer on top of DataSource** — retries by error type (4xx → no retry, 5xx/drop → backoff 0.5→8 s, **waiting up to 60 s for the network to return**), **balancing across several URLs** with banning of bad hosts, **downloading the current track fully into the cache** and **preloading the next ones** (auto-cache 250 MB…4 GB), the server picks quality from the **measured network speed**, crossfade **with two players using server-provided fade-in/out points**.

---

## 1. Engine

| What | Value | |
|---|---|---|
| ExoPlayer | **ExoPlayer 2.18.4** (`C15418eR3` = `ExoPlayerImpl`, string `[ExoPlayerLib/2.18.4]`; `C17426gr9` = `SimpleExoPlayer`; `InterfaceC11051aQ3.c` = `ExoPlayer.Builder`) | [V] |
| Media3 | **1.6.1** — only `MediaSessionImpl`/`MediaController` (`C56`, `C28166sZ5`, string `[AndroidXMedia3/1.6.1]`); the legacy `MediaSessionCompat` is created inside Media3 (`P3c`) | [V] / the 2.18 ↔ Media3 session link through a Player adapter — [I] |
| Own player SDK | None. The wrapper is the Kotlin module `shared-player` (`com.yandex.music.shared.player.*`): `CrossfadingExoPlayerImpl`, `InternalPlayerManager`, `download2/*`, `GainController`, `PlayerEffects` | [V] |
| Native libs | `libflacJNI.so` (538 KB — ExoPlayer FLAC extension), `libYandexSpeechKitJni.4.16.9.so` (voice/Alice), `libsqlite3x.so`, `libandroidx.graphics.path.so`. **No** FFmpeg, Opus, Cronet | [V] |
| Renderers (`I41` case 2 → lambda `Wi9`) | If the libflac class is available: `[LibflacAudioRenderer (C19926iu5), MediaCodecAudioRenderer (ZY5)]`, otherwise only `MediaCodecAudioRenderer`. No video renderers | [V] |
| FLAC | **software libflac first** (`C19926iu5.supportsFormat` — only `audio/flac`, if `FlacLibrary.isAvailable()`), MediaCodec as fallback | [V] |
| AAC / HE-AAC / MP3 | MediaCodec (`ZY5`, `MediaCodecSelector.DEFAULT`, `enableDecoderFallback=false`) | [V] |
| Server codecs (`EnumC6777Om1`) | `he-aac`, `mp3`, `aac`, `flac`, `he-aac-mp4`, `aac-mp4`, `flac-mp4` | [V] |
| Containers/transport (`DB8`, `V32`) | `raw` (progressive), `hls` (TS segments `seg-N-f1-a1.ts`, `HlsMetaValidator`), `encraw` (progressive, AES-CTR) | [V] |
| Quality (`EnumC1984Ai8`) | `AUTO`, `LOW`, `NORMAL`, `HIGH_LEGACY`, `LOSSLESS`; in the API — `high`/`low`/`lossless` (`EnumC33970zi8`) | [V] |
| MediaSource | `DefaultMediaSourceFactory` + `DefaultExtractorsFactory` (`MC2`, `C14113dB2`); the ExoPlayer HLS module is present | [V] |

## 2. Player instances, crossfade, gapless, preloading

| What | How | |
|---|---|---|
| How many players | `CrossfadingExoPlayerImpl` (`C15638ei2`) holds `InternalPlayerManager` (`C30170v25`): the **main** `LZ3` (ExoPlayer + `IZ3` processor) and the **"overlap player"** (`f149480try`) — **a second ExoPlayer only for the duration of a crossfade**; afterwards `stop()`+`clearMediaItems()` (`m40502if`) | [V] |
| Player factory (`I41` case 2) | Each player has its own `DefaultAudioSink` + own processor chain + own `LoadControl`; the looper is the shared player thread (`C8222Ss7.m16344new()`); `setReleaseTimeoutMs(4500)` (default 500), `usePlatformDiagnostics=false` | [V] |
| Crossfade | Two models: **SmartInCrossfade / SmartOutCrossfade** (`RB9`/`WB9`, + `*Old`) — the points `inStart/inStop/outStart/outStop` come from the server in `TrackFadeDto` (field `track_fade` in the DB). The position is polled by a coroutine, waiting in steps of ≥500 ms until the start, then 10 ms. Volume is changed **in PCM** by the `IZ3` processor (`short × k`, k∈[0,1], direction in/out), not via `setVolume` | [V] |
| Crossfade settings | `SharedCrossfadeSettings` (depends on the user and the **current quality** — `updateCurrentQuality`), experiment flags `androidCrossfadeSets`, `familiarWaveCrossfade`, `editorialWavesCrossfade`… The duration of the "regular" crossfade was not found | [V]/[I] |
| Gapless | The ExoPlayer playlist is **not used**: `t(playable, mediaSource, pos)` does `setMediaSource(src, pos)` + `prepare()` for each track. Smooth transitions come from the crossfade/overlap player and cache preloading, not from 2.18 gapless | [V] (the conclusion "no real gapless" — [I]) |
| Preloading | `TrackPreFetchControlImpl` + `PreFetcherImpl`: waits until the player **finishes buffering** (`waitForPlayerToFinishBuffering`), then downloads into the cache. Load modes (`EnumC2330Bj3`): `Play`, **`PreFetchPlayingTrack`** (download the *current* track fully), `PreFetch` (next ones), `Download` (offline). Statuses `Scheduled/Downloading/Completed/Failed/Abandoned` | [V] (how many tracks ahead — not found) |
| "Pre-heat" | experiment `androidPlayerPlayerPreHeat` | [V] (meaning [I]) |

## 3. Network, buffering, cache

### 3.1 Getting the URL — `get-file-info` [V]
`FileInfoApi`: `GET get-file-info?trackId&codecs&transports&quality&ts&sign&measuredNetworkSpeedKbps&estimatedNetworkSpeedKbps&network&device`.
Response (`FileInfoDto`): `url`, **`urls` (list of mirrors)**, `codec`, `bitrate`, `quality`, `transport`, `key` (hex key for `encraw`), **`gain`** (the track is already normalized by the server).
- **AUTO quality is decided by the server** from the submitted `measuredNetworkSpeedKbps`/`estimatedNetworkSpeedKbps` + network type (`AutoQualityParams`, `C14630dp0`; estimation — ExoPlayer `BandwidthMeter` wrappers `C15818ew0`, `C21163kP1`) [V] (the selection logic is on the server — [I]).
- The old `download-info` path (`DownloadInfoApi`) is also present [V].

### 3.2 LoadControl [V]
`I41` case 2: if the experiment `initial_buffering_time_ms` is set (>0) — `DefaultLoadControl(min=50 000, max=50 000, bufferForPlayback=<experiment>, afterRebuffer=5 000)`; otherwise the stock DefaultLoadControl 2.18.4 (**the same 50/50 s, start 2.5 s, after rebuffer 5 s**). No own LoadControl — compensated by downloading the track into the cache (3.4).

### 3.3 DataSource chain [V]
`OkHttpDataSource` (`FO6`, UA `ru.yandex.music`) → **`ConnectivityCheckHttpDataSource`** (when the network is not allowed — an immediate `NetworkNotAllowedException`, no timeout) → **`ChunkedRangesDataSource`** (Range requests in **10 MiB** chunks, `Content-Range` check) → **`RetryingBalancingDataSource`** (retries + switching to the `urls` mirrors, `uriBanManager` bans bad hosts, `RebalancedException`, `UpstreamLengthChangedException`) → for `encraw` — `AesCipherDataSource` (`C4592Ia`, AES-128, 32-hex-digit key) → `CacheDataSource` (`C26144qX0`/`C27122rj2`) on top of `SimpleCache`.

### 3.4 Retry policies (`jG8`, `Qu7`=Play, `r28`=PreFetch, `mr3`=Download; DSL `download2/f`) [V]
| Error (network during read) | Action |
|---|---|
| IO without an HTTP code (drop, timeout) | **wait for the network up to 60 s** (`f.k(60000…)`, uses `NetworkConnectivityProvider`), then backoff **0.5 → 1 → 2 → 4 → 8 s** |
| HTTP 4xx | no retry (fail) |
| HTTP 5xx | backoff 0.5/1/2/4/8 s |
| network disallowed by settings | fail immediately |
| mirror error (`RebalancedException`) | next URL immediately |
| read from cache | up to **10** attempts (`f$f(10)`) |
| `get-file-info` | network error → wait for the network up to 10 s, backoff 0.5/5/10 s; HTTP **418** → a special branch; anything else — fail; 3 attempts in total (`f$f(3)`) |
| Download | only `SocketTimeoutException` is retried, otherwise fail |

The experiment `androidPlayerNewRetries` switches between the old/new set of Play policies; `androidPlayerIgnoreCacheError` — ignore cache errors.

### 3.5 HTTP stack [V]
OkHttp **4.12.0**, HTTP/2 + HTTP/1.1, the app's base client `XC0`/`NX`: **connect 15 s, read 20 s, write 20 s**, own Dispatcher (ForkJoinPool 32, maxRequests 32). Which exact client (or its `newBuilder`) is given to the player — [I]. No Cronet/QUIC.

### 3.6 Cache and offline [V]
- A `SimpleCache` per storage (`C7513Qq9`: internal/SD `_sd`, index `exo_music_user<uid>.db`) with **`NoOpCacheEvictor`** (`ZC6`) — eviction is done by the app: table `tracks_cache` (`cache_type` = `TemporaryCache`/`AutoCache`/`PermanentStrong`/`PermanentWeak`, + codec, bitrate, gain, LUFS, truePeak), `cacheTrimmer`.
- **Auto-cache** (`EnumC12165bl0`): 250 MB / 500 MB / 1 / 2 / 4 GB / unlimited; `auto_cache_to_permanent`.
- Cache writes use a 512 KiB buffer, fragmentation allowed (`exo/a`).
- Offline downloads — `DownloadService` (FGS `dataSync`), mode `Download`.

## 4. Audio output

| What | Value | |
|---|---|---|
| AudioSink | `DefaultAudioSink` (`C5933Lz2`), `AudioCapabilities` from the system; processor chain = external ones (`C22299lo`) + **`C31569wl4`** (normalization gain) + **`IZ3`** (crossfade fade). Float output and offload **not enabled** (2.18 defaults) | [V] |
| PCM format | Both processors accept **only PCM16** (otherwise `UnhandledAudioFormatException`) ⇒ 24-bit FLAC is reduced to 16-bit by the stock 2.18 `ToInt16PcmAudioProcessor` | [V] / [I] |
| AudioAttributes | `USAGE_MEDIA`, `CONTENT_TYPE_MUSIC` (`C33949zh0`); focus — legacy `requestAudioFocus(listener, STREAM_MUSIC, GAIN)` | [V] |
| Normalization (`GainController` `C32381xl4`, `PlayerEffects`) | Data: `integratedLoudnessDb`, `truePeakDb`, `gained`. If the track is **not** "gained" by the server: `gain = −14 − LUFS`; applied **only if > 0** (raise quiet tracks), and limited so that `truePeak + gain ≤ −1 dBTP` (except in the mode with the `DynamicProcessing` limiter). Gain changes smoothly, 500 ms step. Applied as `short × 10^(dB/20)` with **hard clipping** at ±32767 | [V] (that loud tracks are attenuated by the server — [I]) |
| Normalization implementations (`InterfaceC6057Mi9.a`) | `None`, `LoudnessEnhancer`, `DynamicProcessing`, `AudioProcessor`, `Automatic` — but the code only contains `AudioProcessor` (`C7049Ph0`) and `None` (`JH6`); there are **no** `android.media.audiofx` classes in the dex | [V] |
| Mute | `VolumeMuteControl` (`C26115qUa`) — remembers the volume | [V] |

## 5. EQ / effects [V]
- **No built-in EQ**, `android.media.audiofx` is not used at all.
- A settings item opens the **system panel** `DISPLAY_AUDIO_EFFECT_CONTROL_PANEL` with the current session (`C32243xa9`).
- `AudioSessionBroadcaster` (`C12938ci0`) sends `OPEN_AUDIO_EFFECT_CONTROL_SESSION` (+`CLOSE_…` among the strings); the manifest has a `DISPLAY_AUDIO_EFFECT_CONTROL_PANEL` filter.

## 6. Reliability

| What | How | |
|---|---|---|
| Audio focus | Own implementation (`AudioFocusRequester`, `AndroidAudioFocusAdapter` `C14655dr`): `−3` → duck, `−2` → pause with resume, `−1` → pause without resume, `+1` → resume. Duck level not found | [V] |
| Wake lock | `PARTIAL_WAKE_LOCK` in `MediaSessionService`, not ref-counted, acquired when playback starts | [V] |
| Wi-Fi lock | **none** (neither its own nor ExoPlayer's `setWakeMode`) | [V] |
| Services | `MediaSessionService` (FGS `mediaPlayback`, a complex "ForegroundPromise/Started/Canceled" state machine, fallback "call startForegroundService ourselves"), `MusicBrowserService` (`MediaBrowserService`, exported) | [V] |
| Android Auto | `com.google.android.gms.car.application`, module `shared.android.auto` (content styles, likes, collections, books/podcasts), `MEDIA_PLAY_FROM_SEARCH` | [V] |
| Network lost | `ConnectivityCheckHttpDataSource` + waiting up to 60 s for the network + backoff; plus 50 s of buffer and the current track downloaded into the cache | [V] |
| Content errors | exceptions `InternalDownloadException.*`, `GetFileInfoException`, `TrackContentSourceException`, `DoNotRetryException`; mirror switching | [V] |
| Low RAM | no special handling (`onTrimMemory` in the player) found | [I] |

---

## Comparison with the previous ones

| | Ultrasonic | Symfonium | Musicolet | YouTube Music | **Yandex Music** |
|---|---|---|---|---|---|
| Android 9 | yes | no | yes | yes | **yes** (minSdk 24) |
| Core | Media3 1.9.2 | Media3 1.9.1 + FFmpeg | FFmpeg → ExoPlayer 2.11.8 | Media3 1.10 + Platypus | **ExoPlayer 2.18.4** (+ Media3 1.6.1 for the session only) |
| Decoder | MediaCodec | FFmpeg first | FFmpeg | libopus / MediaCodec | **libflac** for FLAC, MediaCodec for AAC/MP3 |
| Players | 1 | 1 + 2nd for crossfade | pair | 1 | **1 + overlap player for crossfade** |
| Crossfade | no | yes | yes | patch | **yes, by server fade points** |
| Gapless | stock | stock | Concatenating | clip queue | **no playlist**, transition via prepare/crossfade [I] |
| Start / rebuffer | 1 / 2 s | 1.5 / 5 s | — | 1.6 / 5 s | **2.5 / 5 s** (start is an experiment) |
| Readahead | 50 s | 60 s | — | up to 120 s | **50 s + the whole track in the cache** |
| HTTP | OkHttp | OkHttp 30/60 s | — | Cronet 6.5/8 s | **OkHttp 4.12, 15/20 s**, Range in 10 MiB chunks |
| Retries | stock | own layer | — | fallback ladder | **by error type + waiting 60 s for the network + mirrors** |
| Cache | own file cache | own | — | encrypted | **SimpleCache without an evictor + own DB, auto-cache up to 4 GB** |
| Normalization | volume | AudioProcessor ±15 dB | libavfilter + limiter | server-side, attenuate only | **−14 LUFS, client boosts only, ≤ −1 dBTP, AudioProcessor** |
| EQ | audiofx | PEQ + DynamicsProcessing | FFmpeg / audiofx | none | **none**, system panel |
| Audio focus | Media3 | own | own, duck 40 % | Media3 | **own**, legacy API |

## What to take for our player (head unit, Android 9, weak hardware, unstable network)

1. **Retries by error type**, not "N times": 4xx — error/next track immediately; 5xx and drops — backoff 0.5→1→2→4→8 s; **network drop — first wait for connectivity to return (up to ~60 s, via `ConnectivityManager`)** instead of wasting attempts. This is exactly about tunnels and dead zones.
2. **Fail fast when there is no network** (a wrapper over HttpDataSource, like `ConnectivityCheckHttpDataSource`): do not wait for a 15–20 s timeout when there is no network.
3. **Download the current track fully into the cache** in parallel with playback (`PreFetchPlayingTrack`) + **preload the next one after buffering ends** — for music (3–10 MB per track) this is cheaper and more reliable than any huge LoadControl.
4. **Range requests in chunks** (~10 MiB) — short HTTP sessions survive network/cell changes better, and a retry continues from the current offset.
5. **Several URLs per track and banning of bad hosts** — if the source can serve mirrors (for Subsonic/our own server — at least a primary/backup address).
6. **Cache: SimpleCache with NoOpEvictor + own accounting table** (type: temporary/auto/pinned, limit selectable 250 MB–4 GB) — controlled eviction, pinned content is never lost.
7. **Crossfade — with a second player only for the duration of the transition**, change volume in a PCM processor, stop the second player after the transition. But on a weak head unit that means two decoders at once — make it optional.
8. **Normalization to −14 LUFS** from tags/metadata, limited by true peak (−1 dBTP) and with **smooth gain changes**; the cheapest is an AudioProcessor on PCM16 (Yandex has no limiter — hard clipping; we had better stick to "attenuate only" or add a soft limiter).
9. **Software FLAC (libflac) first** — do not depend on the head unit's OMX for lossless.
10. **Report to the server / choose quality from the measured speed** (BandwidthMeter) — in AUTO, lower the bitrate on a bad network.
11. PARTIAL_WAKE_LOCK while playing is mandatory; Yandex does not take a **Wi-Fi lock** — we do not need one (mobile network in the car), but it costs nothing with a Wi-Fi hotspot.
12. `OPEN_AUDIO_EFFECT_CONTROL_SESSION` + a system panel button — minimal support for the head unit's EQ.

Don't adopt: ExoPlayer 2.18 (outdated — use Media3), the lack of a gapless playlist, hard clipping when boosting, the complex FGS state machine (our service is simpler), Yandex's signed APIs.

## Key classes (for searching again; obfuscated names will change in another version)

| Class | Role |
|---|---|
| `C15638ei2` (`CrossfadingExoPlayerImpl`) | Player wrapper with crossfade; `t()` — loading a track |
| `C30170v25` (`InternalPlayerManager`) | Main + overlap ExoPlayer |
| `I41` case 2, lambda `Wi9` | ExoPlayer factory: renderers, AudioSink, LoadControl, releaseTimeout 4500 |
| `RB9`/`WB9` (`SmartIn/OutCrossfade`), `TrackFadeDto` | Crossfade by server points |
| `IZ3` | PCM fade processor for crossfade |
| `C31569wl4`, `C33193yl4`, `C7049Ph0`, `C32381xl4` (`GainController`), `C25624pt7` | Loudness normalization |
| `C32347xi9` | Player experiments (`initial_buffering_time_ms`, `androidPlayerNewRetries`, `…PreHeat`, `…IgnoreCacheError`) |
| `download2/exo/*` (`RetryingBalancingDataSource`, `ChunkedRangesDataSource`, `ConnectivityCheckHttpDataSource`, `d`, `c`, `a`) | Network layer and cache |
| `Qu7`/`r28`/`C23155mr3`, `C20240jG8`, `download2/f`, `download2/e` | Retry policies (Play/PreFetch/Download) |
| `DE` case 4, `C30882vv` case 1, `EN` case 7 | Error → action mapping |
| `FileInfoApi`, `FileInfoDto`, `C14630dp0` | `get-file-info`, AUTO quality |
| `C7513Qq9`, `ZC6`, `EnumC12165bl0`, `tracks_cache` | SimpleCache, evictor, auto-cache sizes |
| `C9491Wka` (`TrackPreFetchControlImpl`) | Preloading |
| `C14655dr`, `C33949zh0`, `C20469jY3` | Audio focus |
| `C12938ci0`, `C32243xa9` | Session broadcast, system EQ panel |
| `ru.yandex.music.common.service.player.MediaSessionService`, `…mediabrowser.MusicBrowserService` | FGS, wake lock, Android Auto |
