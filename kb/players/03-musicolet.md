# 03 — Musicolet 6.9 (build 445): playback architecture

Subject: `Musicolet 6.9 build445.apk` (`classes.dex` 7.8 MB + `classes2.dex`; `lib/` for arm64-v8a, armeabi-v7a, x86, x86_64).
Method: jadx (`--no-res`), manifest/resources/`res/xml/default_preference.xml` via `aapt2 dump`, native libraries via `strings` (FFmpeg configuration).
The code is obfuscated (R8 + **PairIP**: `libpairipcore.so`, `com.pairip.VMRunner`; some strings are decrypted at runtime and are not visible in the decompilation). Names like `in.krosbits.musicolet.w1`, `g8.f` are obfuscated; they were matched by anchors (Android/ExoPlayer API, log strings, settings keys).

Notation: **[V]** — verified in the decompiled code/manifest/resources, **[I]** — inference (code not fully decompiled, meaning from the name / library default).

> **Key point for the head unit:** `minSdkVersion = 21`, `targetSdkVersion = 33` [V, manifest] — **installs and runs on Android 9**. No `INTERNET` permission [V] — a purely local player.
> The core is **its own native decoder on FFmpeg + a libavfilter chain (EQ, ReplayGain, limiter, tempo/pitch, resampling to the device's native rate)**, which hands finished PCM to a stripped-down **ExoPlayer 2.11.8** (used only as an "AudioTrack machine" and for gapless). The alternative in settings is the system `MediaPlayer` (`setNextMediaPlayer`).
> Risk: PairIP (Play licensing/integrity) — an APK not installed from Play on the head unit may refuse to work or limit features (see §1, flag `l.e.f9108b`). [I]

---

## 1. Engine

| What | Value | |
|---|---|---|
| Engine choice | Setting "Decoder" `k_i_adt`: **0 = "Musicolet decoder"** (default), 1 = "System decoder" (`R.array.options_decoder_type`/`values_decoder_type`). For realme on Android 11 forced to 1 on first launch (`na.e()`, `fa.g.L()`) | [V] |
| Engine wrapper | Abstraction `t` (play/pause/seek/volume/fade/session). Implementations: `w1` — ExoPlayer+FFmpeg (log prefix `epw:`), `h` — `MediaPlayer` (`AAPW>`), `e0` — Chromecast. The service holds `y` — **a pair of identical players** (`y.u` current, `y.f7512v` next) | [V] |
| ExoPlayer | Bundled **ExoPlayer 2.11.8** (string `[ExoPlayerLib/2.11.8]` in `v1`), packages `m3` (core, `m3.k0` = SimpleExoPlayer, `m3.w` = ExoPlayerImplInternal), `o3` (audio: `o3.t` = DefaultAudioSink), `u3` (source: `u3.m` = ConcatenatingMediaSource). No Media3/HLS/DASH/network modules | [V] |
| Own renderer | `g8.b` (subclass of BaseRenderer `m3.i`): accepts **only `audio/raw`** PCM16 (encoding 2) or float (encoding 4), ≤ 8 channels, ≤ 192 kHz. No decoding in ExoPlayer at all | [V] |
| Own source | `g8.g` (MediaSource) → `g8.f` (MediaPeriod) → `g8.e` (SampleStream) → `in.krosbits.nativex.FDTS` (JNI) | [V] |
| Native libraries | `lib_musicolet.so` (JNI `Java_in_krosbits_nativex_FDTS_*`), `libavcodec`, `libavformat`, `libavfilter`, `libavutil`, `libswresample`, `libz`, `lib_x86_arm_mapping`, `libandroid`/`liblog` (shims), `libpairipcore`. Loaded by `NativeLibx` (order: mapping → avutil → swresample → avcodec → avformat → avfilter → _musicolet) | [V] |
| FFmpeg build | NDK r23c, `--enable-small --disable-pthreads --disable-network --disable-protocols --disable-encoders`, audio only | [V, strings] |
| FFmpeg decoders | aac, aac_latm, ac3, alac, als, amrnb/wb, ape, dsd_*, dst, dca, flac, gsm, mlp, truehd, mp1/2/3float, mpc7/8, opus, vorbis, tak, tta, wavpack, wmav1/2/pro/lossless, real_144/288, ralf, adpcm_*, pcm_* (**no eac3**) | [V] |
| libavfilter filters | `aresample, aformat, equalizer, bass, volume, silenceremove, replaygain, dynaudnorm, stereowiden, stereotools, atempo, asetrate, asetpts, alimiter, extrastereo, channelmap, afifo` | [V] |
| Fallback decoder | `g8.a` = `MediaExtractor` + `MediaCodec` (`pcm-encoding=2`); native code calls the Java callbacks `FDTS.nt_gAMS()` / `nt_ams_fP()` / `nt_ams_sk()` and receives PCM16 into the same filter chain | [V callbacks, I — exactly when it is used] |
| Multithreaded decoding | `k_b_mtdc` "Multi thread decoding", default **true**: a separate thread `":BpR0Dt_<ts>"` (`g8.d`) decodes ahead; with false — decoding happens synchronously in `readData` on the player thread (`g8.e.l`) | [V] |
| License flag | `l.e.f9108b = NativeLibx.a0()` (`f6.java`). Without it **ReplayGain is off** (`g8.f.p()`), **crossfade = 0** (`y.G`) | [V], meaning of the check [I] |

## 2. Player instances, gapless, crossfade, fades

### 2.1 How many players [V]
- `y.G()`: with `k_i_adt=0` **two `w1`** are created (each with its own `SimpleExoPlayer` + own `DefaultAudioSink` + own `HandlerThread` + own wake lock). Audio session: the first gets `AudioManager.generateAudioSessionId()`, the second — **the same id if `etu2==1` (system EQ)**, otherwise a new one.
- With `k_i_adt=1` — two `h` (subclass `i` of `MediaPlayer`).
- ExoPlayer is built with **its own looper** (`HandlerThread`); ExoPlayer's internal playback thread runs at `THREAD_PRIORITY_AUDIO (-16)` (`m3.w`). [V]

### 2.2 Gapless (`k_b_glp`, default **true**) [V]
- **ExoPlayer mode:** the next track is **appended to the `ConcatenatingMediaSource` of the same player** (`w1.g0(next)` → `u3.m.t(1, source)`), i.e. one AudioTrack, sample-accurate transition. The second `w1` is not involved in gapless.
- **MediaPlayer mode:** a second `MediaPlayer.prepareAsync(next)`, in `onPrepared` → `current.setNextMediaPlayer(next)` (`androidx.fragment.app.r.v`).
- **When the next one is prepared:** `y.L()` → `postDelayed(h0, 1000 ms)` (after the current one is prepared / the queue changes). [V]
- In the source: `g8.f.e()` (bufferedPosition) returns END_OF_SOURCE when less than **5 000 000 µs (5 s)** remain to the end of the track — ExoPlayer starts preparing the next period ~5 s before the end. [V code, I — effect]
- Reset: when gapless is turned off / the queue changes, `y.m0()` → `setNextMediaPlayer(null)` / `g0(null)`.

### 2.3 Crossfade (`k_i_cfd`, default **0**) [V]
- Values: 0 (off) or **5…60 s** in 1 s steps (`SettingsActivity`: 57 items, `(i+4)*1000`).
- **Does not work with the system EQ** (`etu2==1`) — the UI blocks it (`k9.b1`, "not supported with System equalizer"), since two players = two sessions.
- Condition: `crossfade>0` and `current duration > C` and `next duration > 2C`; then the gapless path is disabled and the next track is prepared **in the second player** (`f7512v.V(next,false)` + volume 0).
- Start: `y.j0()` schedules the moment `duration − C` on a `ScheduledThreadPoolExecutor` (adjusted for speed). Then the current player does `N(C, …)` (fade-out), the next one `e0(C, …)` (fade-in).
- The fade is a **Java thread with a 100 ms step** (`o` = fade-out, `n` = fade-in, subclasses of `k8.o`), volume 0…100 → `b0()`:
  - curve 0 — linear, 1/2 — quadratic `(x/10)^2`; mode 2 for fade-out starts at 1/3 of the length;
  - an odd duration for fade-in → the first pause is 300 ms instead of 100.
- In ExoPlayer mode `b0()` = `DefaultAudioSink.setVolume()` → `AudioTrack.setVolume()` (`o3.t.f10931x`); in MediaPlayer mode — `MediaPlayer.setVolume(L,R)` taking balance into account (`i.a()`).

### 2.4 Pause/play fades (anti-click) [V]
| Action | Key | Default | Implementation |
|---|---|---|---|
| Play/resume | `k_b_fdid` "Fade-in volume when play" (SeekBar) | **0** | `G0.e0(ms, 1)` — quadratic curve, 100 ms step |
| Pause | `k_b_fdod` "Fade-out volume when pause" (SeekBar) | **0** | `G0.N(ms, 2, null)`; on focus loss — immediately `b0(0)` + `pause()` |
| Play with the system EQ active and the session/device changed (`MusicService.k0()`) | — | forced to ≥ **101 ms**, odd | volume 0 → ~300 ms of silence → 100%: masks the click when audiofx attaches to a new session |
| Seek | — | — | **no fade**, `seekTo` directly (`w1.R`, `h.R`) |

With fade 0: `b0(0)` → `pause()` — volume is zeroed before pausing (AudioFlinger smooths `setVolume` within the buffer) [I].

## 3. File I/O
- Source — a URI (`file://`, `content://` SAF/MediaStore). `FDTS.e()`: `ContentResolver.openFileDescriptor(uri,"r")` → **the fd is passed to native code** (`ntIDTS(fd, size, …)`), FFmpeg reads the file itself; size via `FileChannel.size()`. [V]
- Fallback if no fd can be obtained: `openInputStream()` + size from `_size`; native code reads via the JNI callbacks `FDTS.fr1()` (read) / `fr2()` (seek/size). [V]
- MediaPlayer mode: `setDataSource(path)` for `file:` with `:` in the path, otherwise `setDataSource(ctx, uri)`; on IOException — retry via the SAF document (`h.W`). [V]
- No network: no `INTERNET`, FFmpeg built with `--disable-network --disable-protocols`. A Chromecast module (`e0`, `k_b_accf` default true) exists, but cannot work without INTERNET [V manifest, I — actual behaviour].
- PCM buffers: chunk = **200 ms** at the output rate (`FDTS.f7595p = 200`, `FDTS.c()`); queue in the decode thread: for "light" codecs (PCM or mp3/aac/ac3/vorbis/opus/wma < 384 kbps) 1 chunk ×2 (≈400 ms), for heavy ones (FLAC etc.) 2 chunks ×4 (≈1.6 s). Direct `ByteBuffer`. [V]
- ExoPlayer `DefaultLoadControl(min 0, max 300 ms, bufferForPlayback 0, afterRebuffer 0)` (`w1.G`) — effectively unused: the own MediaPeriod does not "load" (`continueLoading` → false). [V]

## 4. Audio output
| What | Value | |
|---|---|---|
| AudioAttributes (Exo) | `o3.a(contentType=2 MUSIC, flags=0, usage=1 MEDIA, allowedCapturePolicy=1)` | [V] |
| AudioAttributes (service/MediaPlayer/focus) | `setUsage(1).setContentType(2).setLegacyStreamType(3)` (`MusicService.y()`) | [V] |
| Sample rate | `k_i_osprt` (default **0**) → `AudioTrack.getNativeOutputSampleRate(STREAM_MUSIC)`: **native code resamples (swresample/aresample) to the mixer's native rate** → AudioFlinger does not resample | [V] |
| Format | `k_b_opr64b` (default **false**) → 16-bit; true → float (encoding 4). No UI found for `k_i_osprt`/`k_b_opr64b` in settings (hidden / via PairIP strings) | [V] reading, [I] UI |
| AudioTrack buffer | stock DefaultAudioSink 2.11.8: `max(250 ms, min(4×minBuffer, 750 ms))` (`o3.p`) | [V] |
| Speed/pitch | `k_f_plyspd4`, `k_f_plyptch4` (×10000, default 10000 = 1.0) → native (`atempo` + `asetrate`); a change rebuilds the source | [V] |
| ReplayGain | `k_i_rgm` (0 off / 1 track / 2 album with fallback to track), default **0**. The `volume` filter is first in the chain: with RG `gain = clamp(k_f_rgpa,−24..0) + 5 + RG`, without RG `clamp(k_f_rgpna,−24..0) − 3` dB. Clipping is caught by `alimiter`. FFmpeg mode only. Auto reading/calculation of RG for the current and next tracks — `k_i_crgvcns` (default 2 = off; 1 — `i8.b` reads/calculates) | [V] |
| Limiter | `alimiter=limit=1:attack=0.1:release=175`, **always on** (`g6.f6747f.f5666a = true`) | [V] |
| Balance/mono | `stereotools balance_out=%g`, mono — `extrastereo m=0`; in MediaPlayer mode — `setVolume(L,R)` (`k_i_lfch`, `k_i_rgch`, default 100) | [V] |
| HDMI | `ACTION_HDMI_AUDIO_PLUG` is read (stock ExoPlayer AudioCapabilities logic) | [V] |

Filter strings in the code are obfuscated with a Caesar shift of +1 (`h8.i.a()`: `bmjnjufs` → `alimiter`). [V]

## 5. EQ and effects
### 5.1 EQ engine choice — `etu2` "Equalizer to use" [V]
Values (`R.array.value_equalizer_to_use`): **−1 = none, 0 = Musicolet Equalizer (default), 1 = system EQ**.
- `etu2 = 1` → `MusicService.D0()` sends **`ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`** (session = `G0.t()`, `CONTENT_TYPE=0`), on stop — `CLOSE_…` (`F0()`); a UI button opens `DISPLAY_AUDIO_EFFECT_CONTROL_PANEL` (`MusicActivity`). Both players share one session; crossfade is unavailable.
- `etu2 = 0` → internal EQ; its implementation is then chosen by a second key `r1.h()` (key name hidden by PairIP):
  - **0 (default) — DSP in FFmpeg** (graphic EQ on `equalizer`): number of bands `EQNB_<device>_0`, default **10**; options **3/5/10/15/31** (`g6.a()`/`g6.h()`), width in octaves 3.0/2.0/1.0/0.667/0.333, ISO frequencies (10 bands: 31.5, 63, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz). Bass boost — a filter with `g = strength·15/1000` (up to +15 dB), Virtualizer — `stereowiden feedback=s·0.89/1000, crossfeed=s·0.79/1000`. Preamp/volume — `volume`.
  - **1 — system `android.media.audiofx`** on the **player session** (not 0): `Equalizer(127, session)`, `BassBoost(127, s)`, `Virtualizer(127, s)` (`o8.a`), `LoudnessEnhancer(session)` (`l1.m`), one per session from `G0.w()` (both sessions of the pair).
- Moving sliders in internal mode — **without a rebuild**: commands `h8.e(instance, target, cmd, arg)` go to native code (`FDTS.ntSCD` → avfilter `process_command`) at the next `readData`. Turning effects on/off, changing speed or RG mode → `w1.c0()`: stop → new MediaSource → `seekTo(current position)` → play (**a short gap**). [V]
- EQ profiles **per output device** (`MusicService.F0` = `r1.j()`; BT devices — id 40000+hash of the name, `r1.c()`): keys `EQNB_`, `EQBBS_<dev>` (bass), `EQVS_<dev>` (virtualizer), `EQCHBL_<dev>` (balance, default 100), `k_i_prmd_<dev>` (preamp mode: −1 → headroom −`k_i_hdrmg_` (default 6 dB), 1 → boost +`k_i_vbg_` (default 2 dB)). [V keys, I semantics of prmd]
- System EQ presets are captured once by a probe `new Equalizer(0, 1)` (`r1.z()`): number of bands, centre frequencies, level range, presets → `SEQCF`, `SEQMNMX`, `EQSPJA`. User presets — `EQURPJA`. [V]
- Reverb: `k_b_eqrefe` "Reverb effects", default **false** → `EnvironmentalReverb(1, 0)` — **an aux effect on session 0**, `attachAuxEffect(id)` + `setAuxEffectSendLevel(1.0)` (Exo: `o3.m(effectId, 1.0)`). [V]
- **Handling of ROM effect errors:** every `new …`/`setX`/`release` call is wrapped in `try/catch Throwable` and silently ignored; if creation fails, `M0/N0/O0/P0` stay `null` and audio plays without the effect. The probe `r1.z()` logs an exception and does not save presets. There is no switch to the internal EQ on failure. [V]
- Reattaching on player change: effects are created on the **list of sessions of both players** at service start (`MusicService.G`); the sessions of `w1` are permanent (only the MediaSource is recreated), so no reattaching is needed. On output device change — `o()` re-reads the profile. [V/I]

## 6. Reliability
| What | How | |
|---|---|---|
| Audio focus | Own; ExoPlayer focus disabled (`AudioFocusManager.f9879d = null`). API 26+: `AudioFocusRequest` with `setWillPauseWhenDucked(true)` + own listener. Level `B_R_AFL` (default **3**): 0 — ignore; 1 — pause only for calls; 2 — pause on long loss; **3 — + duck to 40% (`F()*0.4`) on `-3`**; 4 — pause on short losses too. Resume after transient — `B_RAF_RACE` (default true); after duck — fade-in `k_b_fdid` | [V] |
| Wake lock | `w1`: `PARTIAL_WAKE_LOCK` `":exw.N_wlck"`, `setReferenceCounted(false)`, acquire on play, release on pause/stop; `MediaPlayer.setWakeMode(PARTIAL)` | [V] |
| Foreground | `startForeground(100, n, FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK=2)`; on pause `stopForeground(DETACH)` | [V] |
| MediaSession / Auto | `MediaSessionCompat` in `MusicService`; `MediaBrowserServiceImpl` + `com.google.android.gms.car.application` (Android Auto). Options "ignore media button broadcasts" `k_b_igbdmbev` (false), "ignore MediaSession callbacks" `k_b_mstrcc` (false) | [V] |
| Noisy/volume | `AUDIO_BECOMING_NOISY` → pause (`key_disconnect_pause`, true); "Pause on mute" `k_b_pwm` (true), "Resume on un-mute" `k_b_rwum` (true), "Don't play at volume 0" `k_b_dtplim` (false) | [V] |
| MediaPlayer errors | `-38` ignored; `(1,-32)` or `(100,1)` (server died) → **one** attempt (`y.L<1`): `reset` + synchronous `prepare` of the same file (`r.u`) | [V] |
| Stuck AudioTrack (Exo) | `w1.k0()`: if ExoPlayer is READY+playWhenReady but `AudioTrack.getPlayState()==STOPPED` — re-prepare the source (≤1 time, counter `F`) | [V] |
| ExoPlayer errors | `onPlayerError` → `MusicService.u()` → if permissions are missing, restart the app; otherwise handled in `y`/UI | [V/I] |
| Release | `AudioTrack.release()` on a separate thread (`o3.q`), the `HandlerThread` is shut down after 3 s | [V] |
| Low RAM | Only `Application.onLowMemory()` → clearing caches (`MyApplication.d()`); `onTrimMemory`/`isLowRamDevice` not used. Memory use is small anyway: PCM queue ≤1.6 s, AudioTrack ≤750 ms | [V] |

## 7. Settings (key → default) [V, `default_preference.xml` + code]
| Key | UI | Default |
|---|---|---|
| `k_i_adt` | Decoder: Musicolet / System | 0 |
| `etu2` | Equalizer to use: none(−1) / Musicolet(0) / System(1) | 0 |
| `k_b_glp` | Gapless playback | true |
| `k_i_cfd` | Crossfade, ms (0, 5000…60000) | 0 |
| `k_b_fdid` / `k_b_fdod` | Fade-in on play / Fade-out on pause, ms | 0 / 0 |
| `B_R_AFL` | Audio focus (levels 0–4) | 3 |
| `k_f_plyspd4` / `k_f_plyptch4` | Speed / pitch ×10000 | 10000 |
| `k_i_rgm` | ReplayGain mode | 0 |
| `k_f_rgpa` / `k_f_rgpna` | Preamp with RG / without RG, dB (≤0) | 0 / 0 |
| `k_i_crgvcns` | Automatically read/calculate RG of the current and next tracks | 2 |
| `k_b_mtdc` | Multi thread decoding | true |
| `k_b_eqrefe` | Reverb effects | false |
| `k_b_pwm` / `k_b_rwum` / `k_b_dtplim` | Pause on mute / resume on un-mute / don't play at 0 | true / true / false |
| `k_i_osprt` / `k_b_opr64b` | Output rate / float output (no UI found) | 0 (native) / false |
| `k_i_lfch` / `k_i_rgch` | L/R volume (balance) | 100 / 100 |
| `k_b_accf` | ChromeCast | true |

---

## Comparison with Ultrasonic 4.9.0 and Symfonium 14.0.0

| | Ultrasonic | Symfonium | Musicolet |
|---|---|---|---|
| Android 9 | yes | **no** (minSdk 32) | **yes** (minSdk 21) |
| Core | Media3 1.9.2, MediaCodec | Media3 1.9.1, FFmpeg ext first | **ExoPlayer 2.11.8 as output only**; decoding+DSP in its own native code (FFmpeg+libavfilter), `MediaPlayer` option |
| Players | 1 ExoPlayer | 1 + a 2nd for crossfade | a pair (`y`): gapless in one player, crossfade uses the second |
| Gapless | stock ExoPlayer | stock, current+next | Concatenating (Exo) / `setNextMediaPlayer`; prepared ~5 s before the end |
| Crossfade | no | yes, 5 curves | 5–60 s, linear/quadratic, Java thread with 100 ms step |
| Pause/play fades | no | VolumeShaper 250/150 ms | Java thread, **off** by default (0); seek without fade |
| Resampling | AudioFlinger | FFmpeg (modified) | **native → the device's native rate** |
| ReplayGain | `player.volume` | AudioProcessor ±15 dB | `volume` in libavfilter + `alimiter` always |
| EQ | audiofx | Java PEQ + DynamicsProcessing | **FFmpeg graphic EQ 3–31 bands** or audiofx on the player session; OPEN/CLOSE broadcast for an external EQ |
| Per-device profiles | no | — | **yes** (speaker/wired/each BT device) |
| Audio focus | Media3 | own | own, 5 levels, duck to 40% |
| Buffers | 50 s LoadControl | 137 MB target | **PCM ≤1.6 s + AudioTrack ≤750 ms** — minimal RAM |
| Network | yes | yes | no |

---

## What to take for our player (head unit, Android 9, weak hardware)

**Adopt:**
1. **Resampling to the mixer's native rate** (`AudioTrack.getNativeOutputSampleRate`) on our side — vendor AudioFlinger resamplers on head units are often a source of artifacts. In Media3 this is done with our own `AudioProcessor`/`SonicAudioProcessor.setOutputSampleRateHz` before the AudioTrack; fix the output rate instead of following the file.
2. **Gapless via one player with "current + next"** (like Musicolet's Concatenating and like Symfonium), preparing the next track ~5–10 s before the end, not the whole playlist.
3. **A limiter at the end of the DSP chain, always on**, and ReplayGain with preamp (Musicolet: +5 dB on top of RG, −3 dB without RG, the limiter catches peaks) — even loudness without clipping.
4. **Live EQ parameter changes without recreating the pipeline** (Musicolet uses avfilter `process_command`); rebuild only when the structure changes, and even then with a fade, not "stop + seek" (their weak spot).
5. **EQ profiles per output device** (head-unit speakers / BT / AUX) — in a car the output changes regularly.
6. **Anti-click when a system effect attaches to a new session**: keep volume at 0 for ~300 ms after start, then ramp (Musicolet `k0()`); if creating audiofx fails, play silently without the effect, plus (unlike Musicolet) switch to our own DSP automatically.
7. **A "system EQ" mode** with `OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION` and one persistent session id for all player instances — the head unit's stock EQ/DSP sees a single session.
8. **Volume-0/mute settings** (`pause when muted`, `don't play at 0`) — on a head unit the volume is often 0 at start.
9. **Own audio focus with levels** and ducking via our own volume (0.4), `setWillPauseWhenDucked(true)` — so the system does not duck by itself.
10. **Small PCM buffers + a separate decode thread** — a RAM/stability compromise, but for a stuttering head unit raise the AudioTrack buffer (see below).
11. **A detector for "AudioTrack stopped while the player thinks it is playing"** with one re-prepare — cheap insurance against vendor HAL glitches.

**Don't adopt / do differently:**
1. **Fades from a Java thread with `sleep(100)`** — 10 steps/s are audible as a "staircase" and depend on the scheduler. Use `VolumeShaper` on the AudioTrack (API 26+, available on Android 9) or a per-sample ramp in an AudioProcessor. Enable pause/play fades by default (100–250 ms) and **add a fade on seek** — Musicolet has none.
2. **Rebuilding the source on any effect toggle / speed change** (`w1.c0`) causes a gap; keep the chain constant and disable an effect with neutral parameters.
3. **Own FFmpeg decoder for everything**: `--disable-pthreads` and software FLAC on a weak CPU + 2 decoders during crossfade. For the head unit — MediaCodec first, FFmpeg as fallback (Musicolet does the opposite: MediaCodec is the fallback).
4. **Two full ExoPlayers for crossfade** — on low RAM better make crossfade optional or mix in a single pipeline.
5. **Stock AudioTrack buffer 250–750 ms** — raise it for the head unit (500–1500 ms), compensate latency with fades.
6. **PairIP/licence checks and "hidden" settings** (`k_i_osprt`, `k_b_opr64b` without UI) — in our player, everything that affects sound should be explicit and documented.
7. Musicolet's error recovery is minimal (1 retry, no position) — we need re-prepare with the saved position and skipping a broken file.

## Key classes (for searching again)
`MusicService` (service, focus `e0()`/`x0()`, effects in `G()`, play/pause with fades ~2550), `y` (player pair, gapless/crossfade, `h0`/`j0`/`m0`), `t` (base player, `N`/`e0` fades), `o`/`n` (fade-out/in threads), `w1` (ExoPlayer wrapper), `h`/`i` (MediaPlayer), `e0` (Chromecast), `g8.g`/`g8.f`/`g8.e`/`g8.d`/`g8.b` (source/period/stream/decode thread/renderer), `g8.a` (MediaCodec fallback), `in.krosbits.nativex.FDTS`/`NativeLibx` (JNI), `g6` (internal DSP: bands, state), `h8.*` (filter descriptions, `h8.i` — string cipher), `o8.a` (audiofx EQ/BassBoost/Virtualizer), `l1.m` (LoudnessEnhancer), `r1` (EQ profiles, preset probe, reverb), `androidx.fragment.app.r` (pair callbacks: onPrepared/onError), `m3.k0`/`m3.w`/`o3.t`/`o3.p` (ExoPlayer 2.11.8: SimpleExoPlayer/Internal/DefaultAudioSink/buffer size), `k9`/`SettingsActivity` (settings).
