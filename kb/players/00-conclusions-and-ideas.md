# 00 — Conclusions and ideas for our own player (ECARX KX11 head unit)

Status: **discussion**, not a design or a spec. Synthesis of reports [01](01-ultrasonic.md)–[05](05-yandex-music.md).
Goal: a player for the head unit (KX11, SA8155, Android 9 / API 28, factory firmware) — **no stutters, clicks or dropouts**, light on RAM/CPU.

Tags: **[F]** — fact from the reports / head-unit notes; **[R]** — recommendation (my opinion); **[?]** — hypothesis, verify on the device.
References like "05 §3.4" point to a section of a report.

---

## 0. What is known about the head unit

- [F] SA8155 (`msmnile`), Android 9 / SDK 28, `userdebug`, `ro.hardware.type=automotive`, partitions are virtual `/dev/block/vdX` (hypervisor) — from the user's private head-unit notes (not in this repo).
- [F] **RAM size is not documented in the head-unit notes** (no `MemTotal`, no `ro.config.low_ram`, no `dalvik.vm.heap*`). → question 4.4.
- [?] CPU-wise the SA8155 is Snapdragon 855 class (8× Kryo 485). FLAC/Opus decoding and a biquad PEQ cost it a few percent of one core. The likely causes of stutters on the head unit are not "not enough CPU" but **process scheduling/preemption, RAM and the vendor audio HAL** (see §3). This matters for priorities: measure first, optimize later.

---

## 1. Summary table

| | 01 Ultrasonic 4.9.0 | 02 Symfonium 14.0.0 | 03 Musicolet 6.9 | 04 YouTube Music 9.15 | 05 Yandex Music 2025.09 |
|---|---|---|---|---|---|
| **Android 9** | yes | **no** (minSdk 32) | yes (minSdk 21) | yes (minSdk 26) | yes (minSdk 24) |
| **Engine** | Media3 1.9.2, MediaCodec | Media3 1.9.1, FFmpeg ext first, MediaCodec as fallback | own FFmpeg+libavfilter → ExoPlayer 2.11.8 as output | Media3 1.10 + native Platypus (SABR); libopus software, AAC via MediaCodec | ExoPlayer 2.18.4 + libflac; MediaCodec for AAC/MP3 |
| **Instances** | 1, whole playlist | 1 (current+next), 2nd for crossfade | a pair; gapless in one, crossfade uses the second | 1 (crossfade only via patch) | 1 + overlap player only for the duration of a crossfade |
| **Gapless** | stock | stock | Concatenating, prepared ~5 s ahead | clip queue, fallback `DISABLE_GAPLESS` | no (prepare per track) |
| **Start / rebuffer** | 1 / 2 s | 1.5 / 5 s | — (own source) | 1.6 / 5 s | 2.5 / 5 s |
| **RAM buffer** | 50 s, ≈13 MB | 60 s, **137 MB** target, `largeHeap` | PCM ≤1.6 s | adaptive up to 120 s | 50 s |
| **Disk cache** | own `.partial/.complete`, N next tracks (current one downloaded twice) | SimpleCache + CacheWriter (parallelism 1), key = id; **off by default** | not needed (local files) | encrypted, server-defined size | SimpleCache + NoOpEvictor + own DB, auto-cache 250 MB–4 GB, full download of the current track |
| **Retries** | Media3 only | own layer: open 4×1.5·n s, read 10×0.5·n s | 1 MediaPlayer retry | fallback ladder + "stuck" watchdogs | by error type, wait for network 60 s, backoff 0.5→8 s, mirrors + host ban |
| **HTTP** | OkHttp default 10 s | OkHttp 30/60 s | — | Cronet 6.5/8 s | OkHttp 15/20 s, Range in 10 MiB chunks |
| **Output** | default, 16-bit | 16-bit (float optional), FFmpeg resamples to the device maximum; AudioTrack 250–750 ms | **resampling to the native mixer rate**, 16-bit; AudioTrack ≤750 ms | AudioTrack 750 ms | 16-bit (24→16), default |
| **Normalization** | `player.volume`, attenuate only, jump at the track boundary | AudioProcessor ±15 dB, at `configure()` | libavfilter `volume` + `alimiter` always | server loudness → `setVolume`, attenuate only | −14 LUFS, boost only, ≤−1 dBTP, smooth 500 ms, hard clip |
| **EQ** | audiofx Equalizer, no OPEN broadcast | Java PEQ+limiter in the pipeline **and** DynamicsProcessing; OPEN/CLOSE | FFmpeg EQ 3–31 bands or audiofx; OPEN/CLOSE; **profiles per output device** | none, system panel; broadcast depends on a server flag | none, system panel + OPEN |
| **Pause/seek fades** | no | VolumeShaper 250/150 ms (no fade on seek) | Java thread, 100 ms steps, off by default, seek without fade | — | crossfade in a PCM processor |
| **Audio focus** | Media3 (duck ×0.2) | own, SPEECH trick | own, 5 levels, duck 40 % | Media3 | own, legacy API |

Details: 01 §1–6, 02 §1–6, 03 §1–6, 04 §1–6, 05 §1–6.

---

## 2. Recommended architecture by layer [R]

### 2.1 Engine
- **Media3 ExoPlayer, latest stable branch (1.9.x/1.10.x).** [F] Media3 1.10 runs on Android 9 (YTM: minSdk 26, 04 §1). Media3 1.9 provides what we need: `AudioOutputProvider` (access to the `AudioTrack` for VolumeShaper — 02 §2), the "stuck player" detector (02 §1), `PreloadConfiguration`. ExoPlayer 2.x (03, 05) — do not use.
- **MediaCodec first, software decoder as fallback.** FFmpeg-first (02 §1, 03 §1) means extra CPU/APK size and native code. Exceptions where software is better:
  - **FLAC** — libflac/FFmpeg first if the head unit's OMX misbehaves on FLAC (Yandex does this, 05 §1) [?] check OMX on the head unit;
  - **Opus** — libopus (04 §1): Android 9 has MediaCodec Opus, but vendor OMX can be buggy [?].
- Minimal set: `decoder-flac` (or `ffmpeg` trimmed to flac/opus/alac) as a fallback renderer via `DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON` (after MediaCodec).
- If the source is our own server (Subsonic/Navidrome): **fixed transcode** (e.g. Opus 160 / MP3 256) for both the stream **and** the cache — identical bytes, less traffic, no mixing of files (01 §3.3).

### 2.2 Player instances
- **One ExoPlayer, playlist holds only "current + next"** (02 §2, 03 §2.2) → stock gapless, one AudioTrack, predictable preloading.
- Crossfade (if needed) — **a second player only for the duration of the transition**, then `stop()+clearMediaItems()` (05 §2). Change volume in a PCM processor or via VolumeShaper, not from a Java thread (03 §2.3 — "staircase").
- With little RAM (to be confirmed, §4) — **no crossfade at all**; it is the main RAM/CPU consumer (02 §3.1: 2×137 MB).

### 2.3 LoadControl (RAM buffer)
Principle: **reliability comes from the disk cache (§2.4), not from a huge RAM buffer** (05 §3.2 + §3.4).

| Parameter | Proposal | Rationale |
|---|---|---|
| bufferForPlaybackMs | 1 500 | 02 §3.1, 04 §3.2 |
| bufferForPlaybackAfterRebufferMs | 5 000 | all three (02, 04, 05) — after a dropout it is better to wait than to stutter |
| minBufferMs / maxBufferMs | 30 000 / 120 000 | 04 §3.2 — adaptive growth up to 2 min |
| targetBufferBytes | **4–8 MB** (explicit) | at 160–320 kbit/s that is 2–4 min; FLAC ~1000 kbit/s — ~30–60 s, the disk covers the rest |
| prioritizeTimeOverSizeThresholds | false (caps bytes) | on low RAM the byte cap matters more; 02 sets true + 137 MB — not our case |
| backBufferDurationMs | 0 | |
| Configurability | buffer size in settings | 02 §7 — Symfonium lacks it, and we need it to tune for the head unit |

- For **local/cached** files LoadControl barely matters — 10–20 s is enough.
- Handle `onTrimMemory` (nobody does: 02 §6, 03 §6, 05 §6): on `RUNNING_LOW` cut the target to the minimum and stop preloading.

### 2.4 Cache and preloading to disk (the main defence against the network)
- **Media3 `SimpleCache` + `CacheDataSource`** with **our own `CacheKeyFactory`** (key = track id + quality, not the URL) — 02 §3.3.
- **Download the current track fully** into the cache (05 §2 `PreFetchPlayingTrack`) and **the next N=1–3 sequentially** (`CacheWriter`, parallelism 1 — 02 §3.3) **after buffering of the current one has finished** (05 §2). No double traffic as in Ultrasonic (01 §2).
- Eviction: LRU **enabled by default** (1–2 GB; 01/02 defaults of "∞" or "0" are dangerous), pinned tracks kept separately (05 §3.6 cache types). Minimum free space on the filesystem (01 §3.4: 500 MB).
- Separate quality/preload limits for metered networks (a phone hotspot in the car = metered) — 01 §3.3, 02 §3.4.

### 2.5 HTTP: retries, backoff, mirrors, waiting for the network
Chain (modelled on 05 §3.3):
```
OkHttpDataSource (connect 8–10 s, read 10–15 s; optionally HTTP/1.1)
 └ ConnectivityCheck  — no network → immediate "wait for network" error, no timeout
   └ ChunkedRanges    — Range requests in chunks (~4–10 MiB)
     └ Retrying(+Mirrors) — error classification, backoff, address switching
       └ CacheDataSource / SimpleCache
```
- **By error type** (05 §3.4): 4xx (except 408/429) — do not retry, next track; 5xx — backoff 0.5→1→2→4→8 s; connection drop/timeout — **wait for the network first** (`ConnectivityManager.NetworkCallback`, up to 60 s), then backoff.
- **While there is audio in the buffer/cache — retry indefinitely** (02 "Adopt" item 1).
- **Mirrors**: for our own server — at least a LAN/WAN address (or primary/backup), ban a host for N minutes after a series of errors (05 §3.3).
- **Auto-resume**: if the player did end up in an error state — `prepare()` from the saved position when the network comes back (neither 01 nor 02 does this).
- Short timeouts + a large buffer = we notice quickly that the network is gone (04 §3.4).

### 2.6 Watchdog and fallback ladder
- **Watchdog every 1 s** (04 §3.3, 03 §6):
  - buffer ≥ start threshold but state BUFFERING for > 2 s → "stuck.bufferfull";
  - READY+playWhenReady but the position does not advance for > 3 s, or `AudioTrack.getPlayState()==STOPPED` → "stuck.playing".
- **Ladder** (each step once, then the next one; 04 §3.5):
  `retry from position` → `recreate AudioTrack/player` → `drop the track's cache` → `other quality/transcode` → `software decoder` → `disable gapless/crossfade` → `skip track`.
- Telemetry to log/file: `AnalyticsListener.onAudioUnderrun`, `AudioTrack.getUnderrunCount()` (API 24+), watchdog events — so we can see on the head unit **which** step fires.

### 2.7 Audio output
- **Sample rate**: [R] fixed output at the **native mixer rate** (`AudioTrack.getNativeOutputSampleRate`, usually 48 kHz) — idea from 03 §4. [?] Means: Media3 `SonicAudioProcessor.setOutputSampleRateHz` is cheap but its resampler is basic; first **measure** how the head unit's own AudioFlinger resamples (§3), and only if the result is poor — our own (libsoxr/speexdsp, native).
- **AudioTrack buffer**: own `AudioTrackBufferSizeProvider`, **min 500 / max 1 000–1 500 ms** (02 "Don't adopt" item 4, 03 "Don't adopt" item 5; stock is 250–750 ms). Survives scheduler stalls on the ROM; mask pause/volume latency with fades.
- **16-bit vs float**: [R] **float inside the DSP**, **PCM16** at the output (the head unit's HAL is almost certainly 16-bit/48 kHz; less memory, fewer conversions and vendor bugs — 02 "Don't adopt" item 3). Hi-res / up to 384 kHz is not needed. [?] Check whether Media3 AudioProcessors run with float input (float output used to bypass the processors).
- **Offload — off** (otherwise no EQ/processors — 01 §4).
- **AudioProcessor chain** (order): `ReplayGain/normalization` → `PEQ (if own EQ)` → `fades/ramps` → **`limiter always` (−0.5…−1 dBFS)** → (resampler).
- **ReplayGain/normalization in a processor**, gain set at `configure()`/track boundary (02 §4) — no jump at the boundary (01 has one, 01 §4). Preamp like Musicolet (+5 dB with RG, −3 dB without, 03 §4) — caught by the limiter; without a limiter — "attenuate only" (04 §4).

### 2.8 Equalizer
- [R] **Primary EQ — our own** biquad PEQ/graphic EQ in an AudioProcessor (02 §5A, 03 §5.1): independent of the vendor effects library, predictable, CPU cost for 10 bands is negligible (see §0).
- **Live coefficient changes without rebuilding the pipeline** (03 §5.1); on/off — a smooth transition to neutral parameters, not "stop + seek" (03 "Don't adopt" item 2).
- **Head unit's system EQ** as an option: always send `ACTION_OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION` (session id, `CONTENT_TYPE_MUSIC`) — 01 does not, 04 only conditionally; a `DISPLAY_AUDIO_EFFECT_CONTROL_PANEL` button. **One persistent session id** for all player instances (03 §2.1/§5.1).
- `audiofx` (Equalizer / DynamicsProcessing API 28) — optional only, everything in try/catch, **on failure fall back to our own DSP automatically** (03 §5.1 — Musicolet silently plays without EQ).
- Anti-click when attaching an effect to a new session: ~300 ms of silence → ramp (03 §2.4).
- **EQ profiles per output device** (head-unit speakers / BT / AUX) — 03 §5.1, 02 §5.

### 2.9 Fades (pause/play/seek/skip)
- **VolumeShaper on the real AudioTrack** (via `AudioOutputProvider`, 02 §2) or a per-sample ramp in a processor. Not a Java thread with `sleep` (03), not `Thread.sleep` on the player thread (02).
- Defaults: play 150–250 ms fade-in, pause 100–150 ms fade-out (02 §2).
- **Seek/skip**: fade-out ~30–50 ms → `seekTo` → after the flush the processor does a 20–50 ms fade-in (ramp reset in `onFlush`). Nobody does this (02, 03 §2.4).

### 2.10 Audio focus
- **Own** handler, `handleAudioFocus=false` (02 §6, 03 §6, 05 §6): `-3` (navigation prompt) — duck our own volume **smoothly** to 30–40 %, `setWillPauseWhenDucked(true)` so the system does not duck by itself; `-2` — pause with auto-resume; `-1` — pause.
- The `CONTENT_TYPE_SPEECH` trick (02 §4) — do not use without need [?] on automotive it may break usage-based routing.
- Settings "pause on mute / don't play at volume 0" (03 §6).

### 2.11 Service, locks, session, threads
- **Foreground service `mediaPlayback`**, do not stop on `onTaskRemoved` (01 §6), restore queue/position at start and after ACC sleep (01 "Do differently" item 9).
- `PARTIAL_WAKE_LOCK` while playing; Wi-Fi lock only while actually downloading (01 "Do differently" item 10; 05 §6 — none at all).
- **Media3 `MediaLibrarySession`** — steering wheel / head-unit buttons, system media widget (01 §6).
- Threads: a dedicated `HandlerThread` as the player's application looper (02 §6); `ExoPlayer:Playback` at `THREAD_PRIORITY_AUDIO` (03 §2.1 [F] for 2.11.8; for Media3 [?]); downloads/cache at background priority, parallelism 1–2 (01, 02).
- Raise `releaseTimeoutMs` (05 §2: 4 500 ms) — on a slow HAL, AudioTrack release can hang.

---

## 3. Risks specific to the manufacturer's head unit

| Risk | Consequence | How to detect/measure |
|---|---|---|
| **Vendor audio HAL / hypervisor** [?] (audio may go through a virtual device or another domain) | large/unstable latency, underruns under load, AudioTrack "stuck" after sleep | `adb shell dumpsys media.audio_flinger` — output streams: sample rate, format, frame count, latency, per-track underrun counters; `logcat -s AudioTrack AudioFlinger` ("obtainBuffer timed out", "underrun", "restartIfDisabled"); in the app — `getUnderrunCount()` |
| **Forced resampling** 44.1→48 kHz | artifacts, extra CPU in AudioFlinger | in `dumpsys media.audio_flinger` a track's "SR" ≠ the stream rate → resampling; `getprop af.resampler.quality`; `/vendor/etc/audio_policy_configuration.xml` (mixPorts, rates, deep_buffer) — available in the user's vendor partition dump |
| **Automotive routing** (`ro.hardware.type=automotive` [F]) — car audio buses, CarAudioService [?] | volume/ducking is done by the HAL/amplifier, the app may not receive `-3`; usage affects the bus | `dumpsys car_service` (if present), `dumpsys audio` (focus stack, ducking), `dumpsys media.audio_policy` (devices/bus) |
| **Effects library broken / partial** | `new Equalizer()` crashes, DynamicsProcessing missing, the head unit's EQ does not attach | `dumpsys media.audio_flinger` (effect chains), `/vendor/etc/audio_effects.xml` from the dump; probe `AudioEffect.queryEffects()` at start |
| **Aggressive process killing** by the ROM / lmkd | music stops in the background, service not restarted after ACC sleep | `logcat | grep -iE "lowmemorykiller|lmkd|Killing"`, `dumpsys activity processes` (oom_adj of our process), `dumpsys deviceidle`; check vendor "whitelist"/autostart |
| **Low RAM / load from stock apps** (navigation, cameras) | GC pauses, page cache eviction, underruns | `cat /proc/meminfo`, `dumpsys meminfo <pkg>`, `getprop ro.config.low_ram`, `dalvik.vm.heapgrowthlimit`; `top -H -p <pid>` while navigating |
| **Ducking by navigation prompts** | abrupt volume jumps, "swallowed" phrase starts, no resume | log of our focus handler + `dumpsys audio` at the moment of a prompt |
| **Slow/poor eMMC** for the cache | read latency while preload writes | time `read()` from the cache in the log; write in 512 KiB buffers (05 §3.6) |
| **ACC sleep / resume** | dead AudioTrack, stale sockets | watchdog §2.6 + `NetworkCallback`; test: put the head unit to sleep / wake it during playback |

[R] **First step before any code**: capture from the head unit `dumpsys media.audio_flinger`, `dumpsys media.audio_policy`, `dumpsys audio`, `/proc/meminfo`, `getprop` (partly possible from the existing vendor dumps, without the device) and record them in the head-unit notes. This determines output rate/format, available effects and the RAM budget.

---

## 4. Open questions for the user

1. **Music sources**: own Subsonic/Navidrome? Yandex Music (its API is signed/closed — 05 "Don't adopt")? Local files on USB/SD? Several at once?
2. **Formats and quality**: is lossless/hi-res needed, or is a server-side Opus/MP3 transcode enough? (Determines decoders, RAM buffer, cache size.)
3. **Is crossfade needed?** Or are gapless + pause/seek fades enough? (The most expensive choice in RAM/CPU.)
4. **Head-unit RAM** (and how much is free while navigation runs) — not recorded in the head-unit notes. Can we capture `/proc/meminfo` and `dumpsys meminfo`?
5. **Which glitches are observed now** and in which app: stutters on a bad network? clicks on pause/seek/track change? dropouts during navigation prompts? cut-off after sleep? This sets the priorities of §2.5–§2.10.
6. **Network in the car**: phone hotspot, the head unit's built-in modem, or mostly offline cache?
7. **EQ**: do we need our own, or is there a stock equalizer/sound processor on the head unit to "connect" to (OPEN broadcast)?
8. **Controls**: steering wheel / head-unit buttons, stock media widget, voice — which of these must work?
9. **Cache space**: how much can be used on `/data` (or is there SD/USB)?

### User's answers (2026-11-15)

1. Sources: **Jellyfin** and **Yandex Music**.
2. Formats: Yandex Music serves **FLAC** and **MP3 320**; the **Jellyfin** collection is **MP3 only** (no FLAC).
3. Crossfade: desirable but optional (a setting).
4. Head-unit RAM: the user will check.
5. No glitches in existing apps. In **our own test app** — stutter **only on track change** (skip and natural transition).
6. Network: Wi-Fi from a **travel router with a 4G modem**.
7. EQ: the head unit already runs the **Poweramp equalizer** (external, attaches to the audio session) — no own EQ needed; what matters is exposing the session correctly.
