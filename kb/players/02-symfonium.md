# 02 — Symfonium 14.0.0: playback architecture

Subject: `Symfonium 14.0.0.apk` (19 MB, `classes.dex` + `classes2.dex`, code obfuscated by R8).
Method: jadx 1.5.3 (`--no-res`, then `--show-bad-code` for methods that did not decompile), manifest via `aapt2 dump xmltree`.
Class names like `defpackage/xx0` are obfuscated; they were matched by anchors (log strings, Media3/Android API, settings keys).

Notation: **[V]** — verified in the APK's decompiled code, **[I]** — inference (code not read to the end / library default / meaning from the name).

> **Key point for the head unit:** `minSdkVersion = 32` (Android 12L) [V, manifest]. **Symfonium 14 will not install on Android 9.** It is an architecture reference, not an install candidate.
> The APK is the base split (`requiredSplitTypes="base__abi"`, `extractNativeLibs=false`): **there are no `lib/*.so` in the file** (0 `lib/` entries); they live in the ABI split from Play. The list of native libraries was reconstructed from `System.loadLibrary` [V].

---

## 1. Engine

| What | Value | |
|---|---|---|
| Library | AndroidX **Media3 1.9.1** (`vy2` = `ExoPlayerImpl`: string `[AndroidXMedia3/1.9.1]`; `k62`: `setPlayerVersion("1.9.1")`) | [V] |
| Media3 modules | `exoplayer`, `exoplayer.dash`, `exoplayer.hls`, `extractor`, `datasource`, `datasource.okhttp`, `database`, `decoder`, **`decoder.ffmpeg`**. **No `media3-session`** — the session uses the old `androidx.media`/`MediaSessionCompat` | [V] |
| Decoders | Own `RenderersFactory` (`h30`): `FfmpegAudioRenderer` (`androidx/media3/decoder/ffmpeg/b`) **and** `MediaCodecAudioRenderer` (`t85`) + `MetadataRenderer` (`uk5`). Order is set by `player.prefer.internal.decoder` (default **true** → FFmpeg first, MediaCodec as fallback) | [V] |
| FFmpeg extension | Modified: `ffmpegInitialize(String, byte[], boolean, int, int)`, `ffmpegGetSampleRate(long, int)` + field `FfmpegAudioDecoder.x` = max output rate (from renderer `b.s0`). The stock extension has no such parameter → native resampling in JNI | [V signatures, I meaning] |
| FFmpeg codecs | mp3/mp2/mp1, aac, vorbis, opus, flac, alac, ac3, eac3(-joc), dca/dts-hd, truehd, amr-nb/wb, pcm a-law/µ-law (`FfmpegLibrary.a()`) | [V] |
| Native libraries | `ffmpegJNI` (Media3 ffmpeg), `ffmpegkit` (`com.arthenica` — ffprobe, local transcoding for casting/downloads, waveform), `ktaglib` (`com.genimee.ktaglib` — tags), `symfonik` (`LibSymfonikWrapper` — licensing/crypto), `datastore_shared_counter`, `androidx.graphics.path`. **No** native DSP of its own — all DSP is in Java | [V] |
| ExoPlayer.Builder (`wy0.s`, `hy2`) | `setWakeMode(WAKE_MODE_NETWORK)` (`l=2`, `m=true`), `setHandleAudioBecomingNoisy(true)` (`n=true`), `usePlatformDiagnostics=false` (`z=false`), own application looper | [V] |
| Media3 1.9 stuck-player detection | library defaults: buffering 600 s, playing 10 s, not-ending 60 s, suppressed 600 s (`hy2` ctor) — not overridden | [V values, I meaning] |

## 2. Player instances, gapless, crossfade, preloading

Hierarchy (log tags in parentheses) [V]:
```
zy4  MusicPlayer ("LocalDevice")              — facade of the local renderer
 └ sq5  MusicPlayer                             — source preparation, focus, cache
    ├ dy2  ExoCacheManager                      — SimpleCache + preloading
    ├ y20  AudioFocusHelper                     — own audio focus
    └ aj3  engine pool ("ForwardingPlayer")
       └ w20  2 slots: z = active AudioEngine, A = second (created lazily)
          └ p20  AudioEngine/N   — own HandlerThread + own ExoPlayer ("ExoPlayer/N")
                                   + own EqualizerManager (vu2) and AudioProcessor chain
```
- **Gapless (crossfade off)** — **one ExoPlayer, exactly 2 items**: current + next. `k1` case 20 (`updateNextMedia`) removes everything before and after the current item (`removeMediaItems(i+1, MAX)`, `(0, i)`), then adds the MediaSource of the next track. Gapless = stock Media3 period transition (encoder delay/padding trimming). [V]
- **Crossfade** — **two ExoPlayers** (the second `p20` = `AudioEngine/2` is created on demand, `w20.F`). `aj3.q`: if crossfade is on (`X`), the next track is prepared in the second engine. [V]
  - Calculation: `rn1` (controller), `tn1` `CrossfadeParameters(rampOutStartTimeMs, rampInStartTimeMs, fadeOutCurve, fadeInCurve)`, `un1` `CrossfadeResult(…, player2StartDelay, playerSwitch)`. [V]
  - Ramp: a coroutine every **8 ms** (`hz0.r(8L)`) reads the player position and sets `player.setVolume(g(t))`; curves: 0 — linear, 1 — no fade, 2 — cosine (S-curve), 3 — circular `sqrt(1-(t-1)²)`, otherwise — sine (equal-power) (`rn1.a`). [V]
  - **Smart crossfade** (`crossfade.smart`, `.smart.mixes`): start points/curves are chosen from the loudness envelope (waveform) of the track end/start (`hz0` ~lines 2600–2700; the waveform is extracted by ffmpegkit, settings `extract.local/network.waveforms`). [V presence, I algorithm details]
  - Default: `crossfade.in.mode = crossfade.out.mode = -1` → **crossfade off**; `crossfade.*.duration = 60` (units not traced). [V keys/defaults]
- **Play/pause/seek fades** (not crossfade) — `y40` "AudioTrackHelperAudioSink": a `ForwardingAudioSink` that obtains the real `AudioTrack` via `AudioOutputProvider` (`g30`/`w18`) and applies a `VolumeShaper` (ramp, cubic interpolator). `fade.in.duration = 250 ms`, `fade.out.duration = 150 ms`; on pause — fade-out and **`Thread.sleep(150)`** before `pause()`. [V]
- **Preloading of next tracks** — via the cache (§3.3): `sq5.updateNextMedia` takes `max(preload+1, 2)` queue items, hands the next one to the player and the list to `dy2.b()` for background caching. Media3 `PreloadManager`/`setPreloadConfiguration` are not used. [V]

## 3. Network, buffering, cache

### 3.1 LoadControl (`x52`, own `DefaultLoadControl` 1.9.1 instance, parameters in `wy0.s`) [V]

| Parameter | Network | Local |
|---|---|---|
| minBufferMs | **60 000** | 60 000 |
| maxBufferMs | **60 000** | 60 000 |
| bufferForPlaybackMs | **1 500** | 1 500 |
| bufferForPlaybackAfterRebufferMs | **5 000** | 5 000 |
| backBufferDurationMs | 0 | |
| targetBufferBytes | **144 179 200 B (137.5 MiB)** via a per-player map (`map3.put(PlayerId.UNSET.name, 144179200)`) | |
| prioritizeTimeOverSizeThresholds | true (`m=true`) | |

I.e. the time buffer is 60 s, and the byte limit is raised so hi-res/FLAC is not cut by size. With 2 engines (crossfade) — theoretically up to 2×137 MB of heap; hence `largeHeap=true` in the manifest [V].

### 3.2 HTTP stack and retries [V]
- OkHttp 5 (`okhttp3/`); for the player — `j13` (DataSource.Factory): takes the provider's client, **connectTimeout 30 s, readTimeout 60 s**, `retryOnConnectionFailure=true`, logging interceptor removed; option `http11.playback` (default false) → HTTP/1.1 only. Basic auth from the URL userinfo, provider-specific custom headers.
- Chain: `ProgressiveMediaSource.Factory` (`mc0`, continueLoadingCheckIntervalBytes 1 MiB, stock `DefaultLoadErrorHandlingPolicy` `fy0`: delay `min((n-1)·1000, 5000)`) → `kr9` (cache/upstream selection) → `CacheDataSource` (if the cache is on) → **`i13` "ExternalPlaylistDataSource"** → `DefaultDataSource` → `OkHttpDataSource` (`h96`).
- **`i13` is its own retry layer on top of Media3** (the main network finding):
  - `open()`: up to **4 retries + a final attempt**, pause **1.5 s × n** (1.5/3/4.5/6 s ≈ 15 s); the URL is re-resolved only on the first attempt; does not retry `FileNotFound`, HTTP 400, "Socket is closed", "Software caused connection abort", its own `z06`; on 416 sets a "do not re-resolve" flag. Logs `Open [p=…s, o=…, c=n]`.
  - `read()`: up to **10 retries**, pause **0.5 s × n** (≈ 27.5 s total); the same exceptions are not retried.
  - Result: a short network drop (up to ~15–30 s) is survived transparently inside the DataSource, on top of the 60 s buffer and the LoadErrorHandlingPolicy retries.
- HLS/DASH — for internet radio/Plex/Jellyfin (`sq5`: `.mpd`/`/dash/`).

### 3.3 Cache (`dy2` ExoCacheManager) [V]
- **Media3 `SimpleCache`** (`m69`) in `getExternalCacheDir()/exoplayer` (fallback `getCacheDir()`), `LeastRecentlyUsedCacheEvictor` (`zq4`), `StandaloneDatabaseProvider` (`dj9`).
- Size = `playback.cache.size` × 1 MiB; **default 0 = "Disabled"** (`vv0` case 7 renders "disabled" for 0; `dy2.e()` = `size > 0`). I.e. out of the box there is no disk cache for the stream — only the 60 s RAM buffer.
- `CacheDataSource.Factory` (`es0`): flags **3 = `FLAG_BLOCK_ON_CACHE | FLAG_IGNORE_CACHE_ON_ERROR`**, **own `CacheKeyFactory`** — a fixed media key (not the URL!) → tokens/transcode parameters in the URL do not break cache hits.
- Preloading: `CacheWriter` (`zs0`) on a dispatcher with parallelism 1 (`n52.X(1)`) — the next **N = `playback.preload.wifi`/`.mobile` (default 3/3)** tracks are fully downloaded sequentially; skips `file:/`, `content:/`, internet radio. Logs `Caching: …`, `Cached: … <size>/<time>s`.
- Modes (`dy2.d`): with `playback.cache.first` (default false) the current track is cached first, then played from the cache; otherwise the stream is read through the cache without a write sink, and caching runs as a parallel task. [I — logic partly from bad-code]
- **Rolling cache**: played tracks are moved from the playback cache into a separate "rolling cache" of offline files (`rolling.cache.size`, `playback.cache.rolling`; logs "moving playback cache to rolling cache", "Rolling cache cleanup"). [V strings, I semantics]
- Separately: offline downloads (`DownloaderService`, FGS `dataSync`, `simultaneous.downloads`=2, `download.wifi.only`=true), auto-offline rules (`auto_offline_cache`).

### 3.4 Transcoding [V]
- Server-side: `transcode.wifi.bitrate` / `transcode.cellular.bitrate` / `transcode.download.bitrate` (default −1 = original), `transcode.mandatory` (false), `force.restart.transcoding`. For Subsonic — models of the OpenSubsonic transcoding extension (`ClientInfo`, `DirectPlayProfile`, `TranscodingProfile`, `CodecProfile`, `transcodeDecision`, `transcodeStream`); for Plex — `/:/transcode/universal/decision`.
- Network type: `wifi.only.active.network`=true, `metered.as.mobile`=true, `vpn.as.mobile`=false.
- Local transcoding with ffmpegkit (`q8a` TranscoderManager, `/cache/transcode/`, `-threads 1 -filter_threads 1`, optional `volume=replaygain=track|album`) — for Chromecast/downloads, not for local playback.

### 3.5 Network loss [V/I]
- Layers: 60 s buffer → `i13` retries (≈15 s open / ≈27 s read) → `DefaultLoadErrorHandlingPolicy` → player error. [V]
- `mt6.X` "onMediaError": a toast; on "too many errors" — stop, otherwise move to the next track (`mt6.A`). A separate "wait for the network and continue from the position" (NetworkCallback → prepare/play) was **not found**. There are `auto.offline.mode` (false) and `player.ignore.offline.server.errors` (false). [V strings, I behaviour]

## 4. Audio output [V]
- `DefaultAudioSink` (`u32`) via builder `q32`: processor chain `[ReplayGain w68 → PEQ dn6 → Mono gm5]` + stock SilenceSkipping (`j69`) and Sonic (`wg9`, speed/pitch); `enableAudioTrackPlaybackParams = true`; **`enableFloatOutput = player.hi.res`** (default **false** → 16-bit PCM output).
- Sink wrappers: `y40` (VolumeShaper fades) ← `x68` (computes ReplayGain for the new format at `configure()`).
- **AudioTrack buffer — stock** `DefaultAudioTrackBufferSizeProvider` (`v32`): min 250 ms, max 750 ms, ×4 of `getMinBufferSize`, passthrough 250 ms, offload 50 s. No custom size.
- Sample rate: `ew0.M()` iterates 384000/352800/192000/176400/96000/48000/44100 via `AudioTrack.getMinBufferSize(sr, STEREO, PCM_16)` → the device maximum is passed to the FFmpeg decoder; `player.limited.samplerate` (false) → `min(48000, max)`. Resampling happens in native FFmpeg (not Media3). [V iteration, I where it resamples]
- Offload: `player.offload.mode` (0 = off), applied only on **SDK ≥ 30** (`ua2.e()`).
- AudioAttributes: `USAGE_MEDIA`; content type `MUSIC`, but **`SPEECH`** if the user configured a focus reaction (`p20.x = SFA≠0 || PFL`) — a trick so the system does not auto-duck and the app receives `-3` itself. `setAudioAttributes(attrs, handleAudioFocus = false)` — Media3 focus disabled. (`wy0.q`, `z10`)
- **ReplayGain** (`w68`/`x68`, `q06.K`): from tags in the stream (ID3 TXXX, Vorbis, iTunes freeform), modes `z68`: Off/Track/PreferTrack/Album/PreferAlbum/Automatic; normalization `b78`: Off/RG2/EBU. Gain ±15 dB (clamped), applied **in an AudioProcessor on samples** (16/24-bit/float) with hard clipping; set on track format change → no jump at the boundary. Clip protection comes from the PEQ limiter (§5), if enabled.

## 5. EQ / effects — two independent layers [V]
**A. Own DSP in the audio pipeline (Java):**
- `dn6` "PeqAudioProcessor": parametric EQ on biquads (`bk0`), bands `PeqBand(type, frequencyHz, gainDb, qOrSlope, channels=ALL/LEFT/RIGHT, enabled)`; types PEAKING, LOW_SHELF, HIGH_SHELF, LOW_PASS, HIGH_PASS, BAND_PASS, BAND_NOTCH; import of AutoEQ strings (`PK/LSC/HSC/LP/HP/BP/BN`, `vu2.d`); preamp ±24 dB; **limiter** with threshold −12…−0.1 dB (default −0.1 dBFS = `10^-0.005`) and 50 ms release. Works with 16/24-bit/float.
- `gm5` "MonoConverterAudioProcessor" — downmix to mono.

**B. `android.media.audiofx` on the engine's audio session** (`vu2` EqualizerManager, `cn2` "DynamicAudioProcessor", `cm`):
- **`DynamicsProcessing`** (API 28+, i.e. available on Android 9 too): pre-EQ/post-EQ (graphic, 10 bands 31.5…16000 Hz, AutoEQ profile), 10-band MBC compressor, limiter, preamp; plus `BassBoost`, `LoudnessEnhancer`, a virtualizer profile. Model — `DynamicAudioProcessorConfig` (enabled, preAmp, autoEqProfile, equalizationProfile, compressorProfile, limiterProfile, volumeBoostProfile, bassBoostProfile, virtualizerProfile, peqEnabled/peqProfile, geqEnabled/geqProfile).
- EQ profiles are bound to the output device (`equalizer_profiles.device_name/device_type`).
- **System/vendor EQ**: with `enable.system.equalizer` (default **true**) and its own EQ inactive, it sends `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` (session id + package) and `CLOSE_…` on detach; a button opens `DISPLAY_AUDIO_EFFECT_CONTROL_PANEL` (`CONTENT_TYPE_MUSIC`). Each `AudioEngine` has its own session id (`vu2.F`).

## 6. Reliability [V/I]
- **Foreground**: `PlayerService` extends `MediaBrowserServiceCompat` (`h85`), `foregroundServiceType=mediaPlayback`, `MediaButtonReceiver`, `HeadsetReceiver`, `BtHeadsetReceiver`, `AutoArtContentProvider` (artwork for Auto); `DownloaderService`/`SyncService` — `dataSync`. Session thread — `HandlerThread("SessionHandler")`. [V]
- **Android Auto**: `com.google.android.gms.car.application`, roots `auto_root`/`recent_root`, `SEARCH_SUPPORTED`, content-style hints, configurable rows/tabs (`auto.home.rows`, `auto.home.tabs`, …). [V]
- **Wake/Wi-Fi lock**: Media3 `WAKE_MODE_NETWORK` (WakeLockManager + WifiLockManager). [V]
- **Audio focus**: own `y20` AudioFocusHelper (`AudioFocusRequest(GAIN)`, without `setWillPauseWhenDucked`); reaction in `a44` case 4: ducking or pause per `player.short.focus.action` (0 = duck), auto-resume after focus is regained; `player.permanent.focus.loss` (false). Media3 focus is off. [V]
- **Threads**: each engine has its own `HandlerThread("AudioEngine/N")` — the ExoPlayer application looper (UI/coroutines do not interfere with the player); `ExoPlayer:Playback` — Media3's internal thread (audio priority, [I]). No explicit `setThreadPriority` in the player. [V]
- **Low RAM**: no own `onTrimMemory`/`isLowRamDevice` handling in the player (only in libraries), `largeHeap=true`. [V]
- Other: `restart.player.device.change` (false), `auto.play.bluetooth` (false), `auto.play.headset` (true), `auto.reset.renderer` (true), `player.keep.paused.state`.

## 7. Settings (key → default), registry `co2` [V]

| Key | Default | Meaning |
|---|---|---|
| `playback.cache.size` | 0 (MiB; 0 = off) | stream SimpleCache |
| `playback.preload.wifi` / `.mobile` | 3 / 3 | how many next tracks to pre-cache |
| `playback.cache.first` | false | download first, then play |
| `playback.cache.rolling` / `rolling.cache.size` | false / 0 | rolling cache of played tracks |
| `player.prefer.internal.decoder` | **true** | FFmpeg before MediaCodec |
| `player.hi.res` | false | float output / hi-res |
| `player.limited.samplerate` | false | limit to 48 kHz |
| `player.offload.mode` | 0 | offload (SDK≥30) |
| `player.replay.gain` / `.normalisation` | 0 (Off) / 0 (Off) | ReplayGain / RG2-EBU |
| `player.replay.gain.ffmpeg` | false | RG in local transcoding |
| `player.skip.silence` | false | skip silence |
| `crossfade.in/out.mode` | −1 (off) | curve |
| `crossfade.in/out.duration` | 60 | duration |
| `crossfade.smart` / `.smart.mixes` | false / false | smart crossfade |
| `fade.in.duration` / `fade.out.duration` | 250 / 150 ms | play/pause fades |
| `enable.system.equalizer` | true | broadcast for an external EQ |
| `player.eq.expert.mode` | false | |
| `player.short.focus.action` | 0 | duck/pause on short focus loss |
| `player.permanent.focus.loss` | false | |
| `http11.playback` | false | HTTP/1.1 only for the stream |
| `transcode.wifi/cellular/download.bitrate` | −1 | transcode bitrate |
| `transcode.mandatory` | false | |
| `wifi.only.active.network` / `metered.as.mobile` / `vpn.as.mobile` | true / true / false | network classification |
| `auto.offline.mode` / `player.ignore.offline.server.errors` | false / false | offline behaviour |
| `restart.player.device.change` | false | recreate the player on output change |
| `disable.media.session` / `disable.output.switcher` | false / false | |
| `auto.play.bluetooth` / `auto.play.headset` | false / true | autostart |
| `auto.home.rows`, `auto.home.tabs`, `auto.*.style` | see `co2` | Android Auto UI |
| `simultaneous.downloads` / `download.wifi.only` | 2 / true | downloads |

There is **no** separate "buffer size" setting — the LoadControl is hard-coded (60 s / 1.5 s / 5 s).

---

## Comparison with Ultrasonic 4.9.0

| | Ultrasonic | Symfonium |
|---|---|---|
| Media3 | 1.9.2, stock renderers | 1.9.1, own RenderersFactory, FFmpeg ext (modified) + MediaCodec |
| Players | 1 ExoPlayer, whole playlist | 1 ExoPlayer × (current+next); 2nd for crossfade |
| Crossfade | no | yes, 2 players, 5 curves, smart by waveform |
| LoadControl | default 50/50/1/2 s | 60/60/1.5/5 s, target 137 MB |
| Cache | own file cache `.partial/.complete`, current track downloaded in parallel with the stream | Media3 SimpleCache + CacheWriter, key = media id, but **off by default** |
| Retries | Media3 only | + own `i13`: open 4×1.5·n s, read 10×0.5·n s |
| HTTP | bare OkHttp (10 s) | 30 s connect / 60 s read, optional HTTP/1.1 |
| Stream transcoding | maxBitRate ignored | separate Wi-Fi/mobile/downloads, OpenSubsonic decision |
| ReplayGain | `player.volume` on track change, attenuation only | AudioProcessor on samples, ±15 dB, modes, RG2/EBU |
| EQ | `audiofx.Equalizer`, no OPEN broadcast | Java PEQ+limiter in the pipeline **and** DynamicsProcessing/BassBoost/LoudnessEnhancer, OPEN/CLOSE broadcast |
| Pause/play fades | no | VolumeShaper 250/150 ms |
| Audio focus | Media3 | own, SPEECH trick against auto-duck |
| Threads | default | dedicated HandlerThread per engine |
| Worse than Ultrasonic | — | minSdk 32; heavy (largeHeap, 2 ExoPlayers, FFmpeg by default → CPU) |

---

## What to take for our player (head unit, Android 9, weak hardware, bad network)

**Adopt:**
1. **A DataSource retry layer like `i13`**: a wrapper over `DataSource` that retries `open()` (backoff, re-resolving the URL/token) and `read()` (reopening from the current position via Range), with a "do not retry" list (400/404/416/interrupt). Cheap and covers most network dropouts in a car. For the head unit — make retries **infinite while the buffer is > X s**, and add a NetworkCallback (Symfonium has neither).
2. **Media3 `SimpleCache` + `CacheDataSource` + own `CacheKeyFactory` (key = track id + quality)** and **`CacheWriter` for the next N tracks sequentially (parallelism 1)**. But enable it by default (e.g. 1–2 GB LRU), not 0 as in Symfonium.
3. **Gapless the Symfonium way**: keep only "current + next" in ExoPlayer — less RAM/work than the whole playlist, and predictable preloading.
4. **Pause/play/seek fades via `VolumeShaper` on the real `AudioTrack`** (Media3 1.9 `AudioOutputProvider` → access to the AudioTrack) — removes clicks on pause/seek. No `Thread.sleep` on the player thread — better wait for the end of the ramp asynchronously.
5. **ReplayGain and a limiter as an `AudioProcessor`** (set the gain at the new track's `configure()`) — no volume jumps.
6. **A dedicated `HandlerThread` for the player** (application looper) — UI/DB/coroutines do not block player control.
7. **Own audio focus + `handleAudioFocus=false`**, ducking via player volume; the `CONTENT_TYPE_SPEECH` trick only if we need to catch `-3` (navigation voice prompts in the car).
8. **EQ:** Android 9 has `DynamicsProcessing` (API 28) — an effect on the session id, runs in the audio HAL/AudioFlinger, no load on our process; plus `OPEN_AUDIO_EFFECT_CONTROL_SESSION` for the head unit's stock EQ. Java PEQ in the pipeline — optional only (CPU).
9. Separate transcode bitrate for Wi-Fi/mobile (in the car — a phone hotspot = metered).

**Don't adopt / do differently:**
1. **FFmpeg decoder by default** — on a weak head-unit CPU, software FLAC/Opus decoding in a Java pipeline + float is extra load. Prefer MediaCodec, FFmpeg only as a fallback for unsupported codecs (or none at all with a fixed server transcode to Opus/MP3/AAC).
2. **targetBufferBytes 137 MB and 2 ExoPlayers** — unacceptable on a low-RAM head unit. Cap at ~8–16 MB (at 128–320 kbps that is minutes of audio), make crossfade optional or single-player.
3. **Hi-res/float, iterating up to 384 kHz** — not needed for the head unit; fix 44.1/48 kHz, 16-bit (less resampling in AudioFlinger and fewer vendor HAL bugs).
4. **AudioTrack buffer** — Symfonium uses the stock one (250–750 ms). For a head unit with stutters it is worth providing our own `AudioTrackBufferSizeProvider` (e.g. min 500 ms / max 1000–1500 ms) — survives CPU/ROM scheduler stalls at the cost of pause/volume latency.
5. Add what neither has: **auto-resume after the network comes back** (NetworkCallback → `prepare()` from the position), explicit `onTrimMemory` handling; check `THREAD_PRIORITY_AUDIO`/`URGENT_AUDIO` on the specific firmware.
6. A user-adjustable buffer size (Symfonium has none) — useful for tuning to a specific head unit/network.

## Key classes (for searching again)
`wy0.s` (ExoPlayer build), `x52` (LoadControl), `h30` (RenderersFactory), `q32`/`u32` (AudioSink builder/sink), `v32` (AudioTrack buffer), `y40` (VolumeShaper sink), `x68`/`w68` (ReplayGain), `dn6` (PEQ+limiter), `gm5` (mono), `vu2`/`cn2`/`cm` (EQ/DynamicsProcessing/audiofx), `p20` (AudioEngine), `w20`/`aj3` (engine pool), `rn1`/`tn1`/`un1`/`kn1`/`ln1` (crossfade), `sq5`/`zy4` (MusicPlayer), `dy2` (ExoCacheManager), `j13`/`i13` (HTTP DataSource + retries), `y20`/`a44` (audio focus), `co2` (settings registry), `o48` (reading settings at player creation), `mt6` (PlaybackController/onMediaError), `PlayerService`.
