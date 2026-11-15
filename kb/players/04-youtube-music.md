# 04 — YouTube Music 9.15.51 (ReVanced/Morphe "anddea" p4.2.0): playback architecture

Subject: `YTM-anddea-v9.15.51-p4.2.0-arm64-v8a.apk` (~80 MB; 10 dex ≈ 47 MB; `lib/arm64-v8a` — 21 libraries).
Method: `unzip -l` → `strings` over the dex → apktool (smali) → one full jadx pass (`--no-res`, 3 GB heap — completed without OOM, 53 decompilation errors). Manifest via `aapt2 dump xmltree`.
Google's code is obfuscated by R8 (names like `auih`, `aujf`, `apbu`); matched by anchors: log strings, names of innertube protobuf classes (`MediaCommonConfigOuterClass$…`), Media3/Android API. The Morphe patch code (`app/morphe/extension/…`) is not obfuscated.

Notation: **[V]** — verified in the code/manifest, **[I]** — inference (from the name, indirect evidence or server configuration whose values are not visible in the APK).

> **Key point for the head unit:** `minSdkVersion = 26`, `targetSdkVersion = 36` [V] — **installs on Android 9**. `largeHeap=true` [V].
> This is **not "ExoPlayer with settings"** but a hybrid: **Media3 1.10.0** (ExoPlayer) as the renderer/AudioTrack layer + the native **Platypus** core (C++ in `libgoogle3.so`, 28 MB), which fetches media over the proprietary **SABR/UMP** protocol and itself "pushes" samples into ExoPlayer (`exo2/platypus/mediapush/BufferManagerImpl`). Almost all numbers (buffers, readahead, retries, timeouts) **come from the server** in `PlayerConfig` (protobuf); the APK only shows fallback defaults for missing fields.
> Its value for us is not the code but the **techniques**: adaptive readahead up to 120 s, different start/rebuffer thresholds, a "buffer full but playback not started" watchdog, a fallback ladder on errors, gapless via a clip queue in one player, loudness normalization that only attenuates.

---

## 1. Engine

| What | Value | |
|---|---|---|
| ExoPlayer | **AndroidX Media3 1.10.0** (string `[AndroidXMedia3/1.10.0]`), bundled whole, obfuscated into `defpackage` (`coc` = `DefaultLoadControl`, `czy` = `DefaultAudioSink.Builder`, `cdv` = `AudioFocusManager`, `cnj` = `LibopusAudioRenderer`) | [V] |
| Native core | **Platypus** — `com/google/android/libraries/youtube/media/player/exo2/platypus/*` (Java wrappers `PlatypusControllerWrapper`, `ClipQueue`, `BufferManagerImpl`, `OnesieMediaFetchIntegration`) + C++ (`libplatypus.so` 18 KB — a stub, `NEEDED libgoogle3.so`; `libelements.so` → also `libgoogle3.so`) | [V] |
| "Platypus on/off" decision | `PlatypusEnablingManager.c()`: ENABLED / DISABLED_FOR_PLAYBACK / DISABLED_DUE_TO_OFFLINE / DISABLED_BY_PLAYER_CONFIG / DISABLED_BY_SABR_STREAMING_URI. **The Morphe patch adds a check at the very start**: when `SpoofVideoStreamsPatch.disableSABR()` is true, the method returns `DISABLED_BY_SABR_STREAMING_URI` | [V] |
| Native libs (arm64) | `libcronet.146.0.7680.144.so` (6.8 MB), `libgoogle3.so` (28.8 MB), `libopusV2JNI.so` (135 KB — JNI `androidx_media3_decoder_opus_OpusDecoder_*`), `libvpx.so`/`libvpxV2JNI.so` (VP9 video), `libgvr_audio.so` (360° spatial audio), the rest — UI/ML/crash handler | [V] |
| Opus | **Software libopus** via its own subclass `aujn extends cnj (LibopusAudioRenderer)`; output 48 kHz, PCM16 or float (`OpusDecoder.a ? float : 16-bit`). The renderers contain **two** copies of `aujn` (plain and with the spatial-audio processor `aufu`) | [V] |
| AAC / xHE-AAC | Via `MediaCodec` — `aujq extends dak` (`MediaCodecAudioRenderer`). Formats: `DASH_FMP4_HE_AAC_ULTRALOW_LQ/LOW`, `AAC_MED` (itag 140, 128 kbit/s), `AAC_HIGH` (141, 256 kbit/s, Premium), `USAC_XHEAAC_ULTRALOW/LOW/MED` | [V] (itag 140/141 per the standard list — [I]) |
| Which audio formats are requested | `auym`: the stock set = `apbl.z()` = **AAC only** (+CENC). The patch `OpusCodecPatch.enableOpusCodec()` (default **on**) replaces it with `apbl.b()` = AAC + **Opus** (`DASH_WEBM_OPUS_ULTRALOW_LQ/LOW/MED/HIGH/ULTRAHIGH`, itag 249/250/251…) + xHE-AAC | [V] |
| Renderers (`auih.ig()`) | `aujq` (MediaCodec audio), `aujs` (MediaCodec video), `aujp` (libvpx), `aujn` ×2 (libopus), `aujm` (dav1d AV1), `sao` (video/texture) | [V] |
| Audio preview of local files | A separate activity `AudioPreviewPlayerActivityPeer` on **`android.media.MediaPlayer`** with its own focus handling (duck to 0.1) | [V] |

## 2. Player instances, gapless, crossfade, preloading

| What | How | |
|---|---|---|
| How many players | **One ExoPlayer** per session; coordinator `augf` holds the current item `augf.o` (`auoc`) and the next one `auoc.n` | [V] |
| Gapless | Via a **clip queue inside one player**: `auih.V()` (`queueVideo`) creates an `auoc` for the next track, `PlatypusControllerWrapper.c(...)` adds it to `ClipQueue` → one timeline, transition without recreating the AudioTrack. Without Platypus — its own `MediaSource` (`ab()`) | [V] |
| Gapless errors | A decoder error on the next clip (`fmt.decode`) → event `load.next` (a regular load of the next track instead of a seamless one). Errors `gapless.seek.next`, `gapless.reload.next/prev`. The recovery ladder has a **`DISABLE_GAPLESS`** step | [V] |
| Prefetch | Pre-request of the player response for the next queue item (`currentIndex;…indexOfItemToPrefetch;…prefetchPrebufferParameters;isImmediatePrefetch;fetchMedia`, "Failed to prefetch sequenceItem"); **Onesie** — a single encrypted request that returns both the player response and the first media segments at once (`OnesieMediaFetchIntegration`, `LoadVideo(…audioOnly=…)`) | [V] presence, [I] timing |
| Crossfade (stock) | **None** | [V] |
| Crossfade (Morphe patch) | `app/morphe/extension/music/patches/CrossfadeManager` (3.9k lines): via YouTube's factory creates a **second ExoPlayer** with the same `LoadControl` (`createNewPlayer` → `patch_createPlayer`), swaps the player in the coordinator (`patch_setExoPlayer`), fades the old one out with `setVolume` at `TICK_MS=50`. Default **off**; duration 250 ms–… (default **3000 ms**), curves EQUAL_POWER (cos), EASE_OUT_CUBIC/QUAD, SMOOTHSTEP; auto-advance `AUTO_ADVANCE_THRESHOLD_MS=5000` before the end, `READY_TIMEOUT_MS=10000`, `QUICK_FADE_MS=400`. A fragile hack on top of obfuscated internals | [V] |

## 3. Network, buffering, cache

### 3.1 Protocol
- **SABR** (Server ABR) over **UMP** (`application/vnd.yt-ump`, "Invalid UMP response received", "Hanging data during UMP parsing") — the client POSTs its buffer state, and **the server decides** which segments to send and in which quality (`SabrPrefetchConfig$ReadaheadBasedPrefetchPolicy / PrefetchWindowBasedPrefetchPolicy / ContinuousBufferPrefetchPolicy`, `MediaCommonConfig$ServerReadaheadConfig`, `NextRequestPolicy`). [V]
- Fallback path — classic DASH/adaptive URLs via ExoPlayer; there is also `PROGRESSIVE_FALLBACK`. [V]
- **What the patch changes:** "Spoof streaming data" (`revanced_spoof_streaming_data`, default **on**), default client `VISIONOS_1_02`; if the chosen client does not require SABR → `disableSABR()` = true → Platypus off → the player runs on the **legacy ExoPlayer path** with its own LoadControl (`aujf`). Spoofed responses lose "stable volume" (string `morphe_spoof_video_streams_about_no_stable_volume`). [V] / which clients require SABR — [I]

### 3.2 YouTube's LoadControl — `aujf` (implements `LoadControl`; the stock `DefaultLoadControl` `coc` is used only by secondary players) [V]

| Parameter | Value (default if the server sent nothing) | Where |
|---|---|---|
| Playback start threshold | **1 600 ms** (`apbu.o()`, field `bqgf.r`, else `0x640`); in the constructor before configuration — 2 500 ms | `aujf.b()`, `g()` |
| Threshold after rebuffer | **5 000 ms** (`apbu.q()`, `bqgf.s`, else `0x1388`) | `g()`: `crw.g` (rebuffering) ? k : j |
| Always load if buffer < | **500 ms** (`0x7a120` µs) | `f()` |
| Target readahead | `min(max, start + rate·(t − t_start))`, where `MediaCommonConfig.c.b` (max) and `.c` (start) default to **120 000 ms**, `.d` — growth rate; then multiplier `.e` | `f()` |
| SABR mode | readahead is set by the server (`ServerReadaheadConfig.b`, `NextRequestPolicy`), `aujf.r()` | [V] |
| NextRequestPolicy timeout | 60 000 ms by default (`aujf.o()`) | [V] |

In total: ~1.6 s of buffer at start, 5 s after a dropout, and in steady state it accumulates **up to ~2 minutes of audio** (for Opus 128–160 kbit/s ≈ 2 MB — cheap on RAM).

### 3.3 "Stuck in buffering" watchdog [V]
- `aufz/aufx`: polls every **1 000 ms**; if the buffered amount is ≥ the start threshold (1.6 s) but the player has been in BUFFERING for longer than `max(config, 2 000 ms)` → error **`android.stuck.bufferfull`** → goes to recovery.
- Similarly `android.stuck.buffering` / `android.stuck.playing` (position not moving, `chc`: type+timeout) — behind a server flag.

### 3.4 HTTP stack [V]
- **Cronet 146** (`ajas.b()`): `enableQuic(true)`, `enableHttp2(true)`, `enableBrotli(true)`, QUIC hints for YouTube/googleapis hosts, optional `NetworkQualityEstimator` + RTT listener, Cronet disk HTTP cache (`cronet_metadata_cache`, `enableHttpCache(2 = DISK, 0)`), thread priority −2 or 9.
- Media DataSource: `atoj/atot extends CronetDataSource` — traffic tags, `setIdempotency` for idempotent requests. **Timeouts: connect 6 500 ms, read 8 000 ms** (`apbu.k()/l()`, the server may override); in another path 8 000/8 000; if Cronet fails to start — fallback to `DefaultHttpDataSource` with the same timeouts (`aszz`).
- Patch `revanced_disable_quic_protocol` (default off) — forces no QUIC (useful on networks that block UDP).

### 3.5 Errors and recovery — the `auwy` ladder [V]
`SAME_CONDITION` → `NEW_SURFACE` → `CACHE_PURGE` → `PROGRESSIVE_FALLBACK` → `DRM_*` → `NETWORK_UNAVAILABLE` → `SW_CODEC_FALLBACK` (software MediaCodec) → `DISABLE_PLATYPUS` (SABR → legacy) → **`DISABLE_GAPLESS`** → `SABR_RETRY_FORMAT_ERROR` … The attempt counter is capped by the server value `bzel.f` (`auqx`). Network codes: `net.connect(.timeout)`, `net.read(.timeout)`, `net.dns`, `net.closed`, `net.badstatus`, `net.retryexhausted`, `net.unavailable`. I.e. an error does not kill the session but moves the player into an increasingly "conservative" mode.

### 3.6 Quality and offline
- Stream quality: `AUDIO_QUALITY_SETTING_NORMAL_QUALITY / HIGHER_QUALITY` (server settings), "Wi-Fi only" — `StreamWifiOnlyController`, `stream_over_wifi_only`. Adaptation on a bad network — **on the server (SABR)** or ExoPlayer ABR on the legacy path. [V] presence / [I] algorithm
- Offline: `OFFLINE_AUDIO_QUALITY_LOW/MEDIUM/HIGH`, "smart downloads" (`enable_auto_offline`, `auto_offline_max_num_songs`, `pref_enable_smart_download_recent_music`), SD card, `canOfflineOverWifiOnly`. Offline playback runs **without Platypus** (`DISABLED_DUE_TO_OFFLINE`). [V]
- The streaming media cache is **encrypted** (AES, key from the keystore + IV in prefs `media_cache_initialization_vector`, `atbz.c()`), accounting `exo_cache_size_bytes_used`, eviction `cevict`. Cache size is server-defined, not found. [V]/[I]
- Network drop: events `net.unavailable` → step `NETWORK_UNAVAILABLE`; plays as long as there is buffer (up to ~2 min). [V]/[I]

## 4. Audio output

| What | Value | |
|---|---|---|
| AudioSink | Stock Media3 `DefaultAudioSink` (`czy`), default `AudioCapabilities` (`cww.a`) | [V] |
| AudioTrack buffer size | `DefaultAudioTrackBufferSizeProvider` (`dae`) with **750 000 µs (750 ms)**; the server may override (`avad.w()` ms) | [V] |
| Processor chain | `DefaultAudioProcessorChain(…, SilenceSkippingAudioProcessor(parameters from flags 45707004–8), Sonic)` — `auiu.f()`; for spatial audio — `aufu` (head orientation quaternion, GVR) | [V] |
| Loudness normalization | Per-track loudness value from the server (`loudnessDb` in the format, `bqoz.J`); the gain factor is the dB value converted to linear and capped at 1 (`auvm.a`), then multiplied by a config-dependent attenuation and clamped to 0…1 (`auvm.b`) → **`player.setVolume()`** (`atym`, `atyk`). **Attenuation only, no boost.** `vnorm` logging: `trkcfg / albcfg / atvcfg` (track/album/…), `tar` (target), `inpvol`, `mult`, `outvol`, `omvgain`; `VolumeStats(normalizationMode, targetLoudness, loudnessForNormalization, relativeNormalizationGain, absoluteNormalizationGain, volumeMultiplier, finalVolume)` | [V] |
| "Stable volume" / DRC | Separate DRC variants of audio formats (label `drc`); for xHE-AAC — `inpvol` + `LoudnessCodecController` (API 35+, not on Android 9). Patch `revanced_disable_drc_audio` (default off): zeroes `loudnessDb` and turns off the DRC feature flag | [V]/[I] |
| AudioAttributes | Media3 default (USAGE_MEDIA); in the preview — `CONTENT_TYPE_MUSIC + USAGE_MEDIA` | [I]/[V] |
| Offload | There is a provider `aufv` (wrapper of `AudioOffloadSupportProvider`); no explicit enabling of offload mode found; offload is impossible for Opus (libopus) | [I] |

## 5. EQ / effects [V]
- **No built-in EQ.**
- Settings → "Equalizer" (`PlaybackSettingsFragment`, key `equalizer`) opens the system panel `DISPLAY_AUDIO_EFFECT_CONTROL_PANEL` with the current player's `AUDIO_SESSION` and `CONTENT_TYPE=0` (music); the item is hidden if there is no panel (`ayjd.a()`).
- The `OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION` broadcast (`auri`, `atzp.c()`) is sent **only if enabled by server config** (`auwk.aE()`), i.e. an external EQ on the head unit may not "see" the session.

## 6. Reliability

| What | How | |
|---|---|---|
| Audio focus | Stock Media3 `AudioFocusManager` (`cdv`): LOSS_TRANSIENT_CAN_DUCK → **volume ×0.2** (for speech — pause), LOSS_TRANSIENT → pause, LOSS → stop+abandon | [V] code / [I] whether it is the one used in the main player |
| Wake/Wi-Fi lock | Classes `ExoPlayer:WakeLockManager` (`PARTIAL_WAKE_LOCK`), `ExoPlayer:WifiLockManager` (`WIFI_MODE_FULL_HIGH_PERF`) are present; `WAKE_LOCK` permission; foreground service `mediaPlayback` | [V] / [I] mode |
| MediaSession / Android Auto | `MediaBrowserService`, `MediaButtonReceiver`, `com.google.android.gms.car.application` (Android Auto) | [V] |
| Low RAM | `isLowRamDevice()` is used only outside the player (Glide: 2 MB image cache instead of 4 MB; SQLite WAL off). In the player — the `DefaultLoadControl` "memory pressure" logic and a byte limit of 13 107 200 B (`coc`, for secondary players) | [V] |
| Decoder errors | `fmt.decode` → skip to the next track (`load.next`); `SW_CODEC_FALLBACK`; `player.timeout` for `codec_init` | [V] |
| What the patches change in playback | Opus on (default), client spoofing + disabling SABR/Platypus (default), crossfade (off), disable DRC (off), disable QUIC (off), playback speed, background playback | [V] |

---

## Comparison with the previous ones

| | Ultrasonic | Symfonium | Musicolet | **YouTube Music** |
|---|---|---|---|---|
| Android 9 | yes | no | yes | **yes** (minSdk 26) |
| Core | Media3 1.9.2 | Media3 1.9.1 + FFmpeg | FFmpeg+libavfilter → ExoPlayer 2.11.8 | **Media3 1.10.0 + native Platypus (SABR/UMP)** |
| Decoder | MediaCodec | FFmpeg first | FFmpeg | **libopus (software)** for Opus, MediaCodec for AAC |
| Players | 1 | 1 + 2nd for crossfade | pair | **1** (crossfade only via patch, 2nd player) |
| Gapless | stock | stock | Concatenating | **clip queue in one player**, with fallback `DISABLE_GAPLESS` |
| Start / rebuffer | 1 s / 2 s | 1.5 s / 5 s | — | **1.6 s / 5 s** |
| Readahead | 50 s | 60 s, 137 MB | — | **adaptive, up to 120 s** (or server) |
| HTTP | OkHttp | OkHttp 30/60 s + own retries | — | **Cronet (QUIC/H2), 6.5/8 s** |
| Retries | stock | own layer 4+10 | — | **fallback ladder** + "stuck" watchdogs |
| AudioTrack buffer | default | default | ≤750 ms | **750 ms** |
| Normalization | `volume` | AudioProcessor ±15 dB | libavfilter + limiter | **server loudness → setVolume, attenuate only** |
| EQ | audiofx | Java PEQ + DynamicsProcessing | FFmpeg EQ / audiofx | **none**, system panel only |
| Audio focus | Media3 | own | own, duck 40% | Media3 (duck 20%) [I] |

## What to take for our player (head unit, Android 9, weak hardware, unstable network)

1. **Different start and rebuffer thresholds**: ~1.5 s to start, ~5 s after a rebuffer — after a dropout it is better to wait longer than to stutter every 2 s. (Matches Symfonium.)
2. **Adaptive readahead**: keep the buffer small at start and **grow it with playback time** up to 1–2 min; with a buffer < 0.5 s — always load. For 128–320 kbit/s audio, 2 min = 2–5 MB — the head unit can handle it.
3. **A "buffer is there but not playing" watchdog** (every 1 s; if the buffer is ≥ the start threshold and the state has been BUFFERING > 2 s — restart the renderer/AudioTrack). Catches AudioTrack/codec hangs on custom firmware.
4. **A recovery ladder instead of a single retry**: same source → drop cache → other format/bitrate → software decoder → disable gapless/preloading. Each step once, then the next.
5. **Gapless in one ExoPlayer** (Concatenating / Media3 playlist) — no second player and no second AudioTrack; a second player only if crossfade is needed (and then with a shared LoadControl).
6. **Loudness normalization that only attenuates** via `setVolume`: no clipping and no limiter needed — cheap on CPU.
7. **Short timeouts** (connect ~6–8 s, read ~8 s) + a large buffer: we notice faster that the network is gone and have time to reconnect while the reserve plays. For the head unit without Cronet — OkHttp with HTTP/2; a "no QUIC/HTTP3" option for carrier networks.
8. **Opus via libopus** — stable on any hardware (independent of the head unit's buggy OMX); for AAC — MediaCodec with a software fallback.
9. AudioTrack buffer **750 ms** — a reasonable compromise between latency and resilience to CPU lags.
10. Do not rely on the audiofx session: if EQ is needed on the head unit — our own AudioProcessor (like Symfonium/Musicolet), and always send the `OPEN_AUDIO_EFFECT_CONTROL_SESSION` broadcast.

Don't adopt: SABR/UMP/Onesie (proprietary, server-side), encrypted media cache, the crossfade patch's hacks on top of obfuscated classes.

## Key classes (for searching again; names are obfuscated and will change in another version)

| Class | Role |
|---|---|
| `auih` | Main player controller: renderers (`ig()`), queue (`V()`), errors (`r()`), `vnorm` logs |
| `aujf` | YouTube LoadControl (`f()` — continueLoading, `g()` — shouldStartPlayback) |
| `apbu` | `PlayerConfig` wrapper: `o()`=1600, `q()`=5000, `k()`=6500, `l()`=8000 |
| `auwy` | Enum of recovery steps; `auqx` — retry policy |
| `aufz`/`aufx` | `android.stuck.bufferfull` watchdog |
| `ajas`, `atoj`/`atot`, `aszz` | Cronet engine, CronetDataSource, timeouts |
| `aujn` / `aujq` | libopus / MediaCodec audio renderers |
| `dae`, `auiu.f()` | 750 ms AudioTrack buffer, SilenceSkipping parameters |
| `auvm`, `auco` | Loudness normalization (`VolumeStats`) |
| `auri`, `PlaybackSettingsFragment` | audiofx broadcast, system EQ panel |
| `PlatypusEnablingManager`, `PlatypusControllerWrapper`, `ClipQueue` | Native core and gapless queue |
| `app/morphe/extension/music/patches/CrossfadeManager` | The patch's crossfade |
