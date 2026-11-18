# Playback stability on the head unit — auto-pause, stutter, loading

Status: **implemented** — F1–F9 on branch `fix/playback-stability` (plan approved and built 2026-11-16; see As built). Source: the
2026-11-16 rides (two drives + a parked FLAC session), three YAMP Diag snapshots (12:33:26, 13:13:10, 13:21:02), the
YAMP journal (`yamp (4).log` covers 12:12:15–13:20:35) and three phone videos analysed for dropouts. Decisions `Dn` are
proposals until approved. Code paths are relative to `android/app/src/main/java/org/tegro/yamp/`; line numbers are
the design-time ones (`2905ba9`).

## Findings

**F-a. Every auto-pause (12 of 12) was triggered by Poweramp Equalizer.** `dumpsys audio` focus history: 20–90 ms
before each `FOCUS lost` the user's equalizer app `com.maxmpz.equalizer` (uid 10072) requests focus
`GAIN_TRANSIENT_MAY_DUCK` (`req=3`, usage MEDIA), and abandons it 0.1–1.3 s later; it never plays anything. YAMP
receives a **permanent** `AUDIOFOCUS_LOSS` (not a duckable one — the unit's focus policy, unconfirmed) and pauses with
nothing to resume it (`data/player/MusicService.kt:88-95`). Pairs: 12:28:31, 12:29:38, 12:29:54, 12:59:29, 13:03:49,
13:08:26 (MP3); 13:14:31, 13:15:10, 13:16:17, 13:16:23 (FLAC); 13:15:37, 13:18:34 (MP3). Ruled out: YAMP re-requesting
focus (one client id, no request between grant and loss), a system/vendor requester (none in ±5 s), a non-focus pause
(the journal writes "lost" only from the callback). Parked with no other sound fits: the equalizer takes focus
silently.

**F-b. What triggers the equalizer: YAMP opening, rebuilding or releasing an AudioTrack.** Its requests follow
`OUTPUT` (new AudioTrack) or `UNDERRUN` lines; it attaches its effects to YAMP's session even with "announce to the
equalizer" off. So more AudioTrack churn = more pauses: FLAC on a weak link (re-buffering, picks retried) was worst;
"Separate audio sessions" (more sessions) made it worse earlier; "Keep audio streams" (fewer new tracks) helped.

**F-b2. What the equalizer actually reacts to: a session it equalizes closing.** Poweramp Equalizer tracks players
through its notification listener and `registerAudioPlaybackCallback`, re-scanning the sessions ~50 ms later. When
the session it equalizes for an app closes, it briefly "fades" the player: it requests `GAIN_TRANSIENT_MAY_DUCK` with
forced ducking and abandons it within ≤1250 ms (per-app **"Fade"** flag, default on — "Fade player temporarily when the
session is recreated"). On this unit that request reaches YAMP as a permanent LOSS. YAMP used several session ids
today (journal `announce session 121/145`, later 137, AudioFlinger session 153): "Separate audio sessions" was on
(player B on its own id, `data/player/SystemEqualizer.kt:46-54`, `di/PlayerModule.kt:85-95`), and every player swap /
AudioTrack rebuild closed one of them. **Immediate workaround for the user:** in Poweramp Equalizer → Known Players →
YAMP, switch **"Fade"** off.

**F-c. The mixer runs at 48 kHz; every track is 44.1 kHz.** `dumpsys media.audio_flinger`: media output
`AUDIO_OUTPUT_FLAG_MEDIA`, 48000 Hz, 16-bit, HAL buffer 960 frames, no FastMixer; every YAMP track 44100 Hz → resampled
by AudioFlinger. Track buffer 22050 frames (500 ms).

**F-d. AudioTrack churn on skips.** About 12 AudioTracks created and released in 5 s of fast skipping (13:19:00–05).
The outgoing player is rebuilt as a silent standby (`OUTPUT … standby` right after a skip) and runs dry (AudioFlinger
removes tracks with 10 000+ underrun frames, journal `UNDERRUN … standby gap ~500 ms`). Video dropouts line up:
25–150 ms mid-song notches with `OUTPUT … active` + 55–64 ms `STALL` (12:28:05, 12:28:53); the 1.5–1.7 s "broken-up"
start after a skip with the outgoing player's standby rebuild + run-dry (13:17:34); 1.1–2.8 s silences with network
stalls / FLAC re-buffering.

**F-e. Stale crossfade job (bug).** A newer start reverts a loading pick with `revertPick(…, reason = null)`
(`data/repository/PlayerRepositoryImpl.kt:490`); that clears `outgoingPlayer`, so `fadeOutRunningCrossfade` (:684)
returns before cancelling `crossfadeJob`, and :550 overwrites the reference. The orphaned `runCrossfade` (:566) keeps
its old players: it times out on a cleared player (`pick failed: READY_TIMEOUT` with no title, 13:17:59) or finishes
and calls `clearPlayer(outgoing)` (:610) on what is by then the active player. Seen as: music audible while the button
shows ▶ and focus is abandoned "nothing playing" for ~23 s (13:17:05→13:17:28, video 13:17:19, photo 13:18:10); media
session `state=2` on a different title than the journal.

**F-f. Picks give up after 8 s.** `INCOMING_READY_TIMEOUT_MS = 8_000` (`PlayerRepositoryImpl.kt:987`) reverts a pick
(:575-577); FLAC (~1 Mbit/s) on the weak link needed 5.0–26.3 s to be ready → 6× `READY_TIMEOUT` (13:17:48–13:20:32).
YAMP never pauses itself for slowness; the buffering watchdog (`BUFFERING_TIMEOUT_MS = 15_000`,
`data/player/PlaybackController.kt:2142`) goes to the recovery ladder (`data/player/PlaybackQueue.kt:109-117`: retry
once, saved copy / offline / skip, stop after `MAX_FAILED_TRACKS = 3`).

**F-g. audioserver crashed at 13:18:35.016**, 18 ms after an equalizer focus request (`onAudioServerDied`). Not YAMP's
crash; same trigger.

**F-h. Diag gaps.** Logcat holds only the system and crash buffers, the 8 MB cap keeps the *oldest* lines, ~51k lines
are `CarPlayService not found` spam; it stops at 12:21:28 / 13:10:47 — nothing for the parked session.

**F-i. Sharing YAMP's journal / crash log fails with LocalSend.** The journal (`presentation/main/SettingsViewModel.kt:
140-144`) and the crash log (`presentation/crash/CrashLogViewModel.kt:61-71`) are shared as **text**
(`ACTION_SEND`, `text/plain`, `EXTRA_TEXT`) — LocalSend receives no file. The diagnostic log
(`data/diagnostics/DiagnosticLog.kt:115-127`) shares files (`SEND_MULTIPLE` + FileProvider) but without `ClipData`.

## Fixes

**F1 — Resume after a focus loss when nothing else plays (D66).** On `AUDIOFOCUS_LOSS`: pause as today, then after
~1.5 s check `AudioManager.getActivePlaybackConfigurations()` (API 26+) for an active player of another uid with usage
MEDIA/GAME/ASSISTANCE_NAVIGATION/VOICE_COMMUNICATION; if there is none and the audio mode is normal (no call, no
ringtone — `AudioFocusPolicy` already reads the mode), re-request focus and resume. At most 3 tries per minute, then it
stays paused (the journal says why). **Hard guard:** a flag set at the moment of the loss records whether YAMP was
actually playing (sounding, or loading to play); resume happens **only** with that flag set and no user pause/stop
since. A loss while paused, idle, just opened or in the background never starts playback (YAMP abandons focus when
not playing, but the guard is explicit anyway). On by default on phones and the head unit alike.
Journal: `FOCUS lost (by <pkg> if known) → resumed, nothing else playing` / `… stays paused: <pkg> playing`.
Tests (guard): loss while paused → no resume; loss while playing → resume when nothing else plays; user pause during
the wait → no resume.
Setting in Settings → Playback, next to "Keep playing under announcements": **"Resume when nothing else plays"** /
**«Продолжать, если ничего не играет»**, default **on**. Tests: `AudioFocusPolicy` unit tests (playing configs, mode,
rate limit); a controller test for the resume path.

**F9 — One stable audio session id (D70).** Today a shared id is generated once per process
(`SystemEqualizer.audioSessionId`) and set on both players at creation (`di/PlayerModule.kt:22,87-88`); with
"Separate audio sessions" on, player B moves to a second id (`PlayerRepositoryImpl.applyPendingAudioSession`, :237),
and both ids are announced on each "sound comes back" (`SystemEqualizer.keepAnnounced`, `MusicService.kt:159`) and
closed in `onDestroy` (`MusicService.kt:532`). Change:
- **One id per playback-service lifetime**, `AudioManager.generateAudioSessionId()`, set with `setAudioSessionId` on
  both ExoPlayers and kept through every AudioTrack rebuild, player swap, park/idle (`SilentIdler`), seek, pause and
  track change; re-applied after `onAudioServerDied` (same id if still valid, otherwise a new one + one new OPEN).
- **One OPEN / one CLOSE pair:** `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` once with that id, `EXTRA_PACKAGE_NAME`
  and `EXTRA_CONTENT_TYPE = CONTENT_TYPE_MUSIC`, when the service first plays; `ACTION_CLOSE_…` only on real shutdown
  (service destroy). No re-announce on pause/resume, focus regain or track change (`keepAnnounced` goes).
- **"Separate audio sessions" is retired with this step** — it is the opposite of the fix (setting, `secondAudioSessionId`
  and `AudioSessionPolicy` removed; a stored "on" is ignored). The **equalizer-announce switch stays** (default on): it
  now controls only that single OPEN/CLOSE pair, for units where an equalizer app misbehaves on the broadcast.
- Journal: `EQ session <id> opened` once, `closed` on destroy; an `OUTPUT` line shows the session id.
Tests: both players report the same session id after swaps, park/idle and rebuilds; OPEN sent once per service
lifetime, CLOSE only on destroy; the announce switch off sends neither.

**F2 — Stale crossfade (bug).** `revertPick` cancels `crossfadeJob` (and the transition lock) itself, so every path
that drops a pick also stops its job; `fadeOutRunningCrossfade` cancels the job before its early return;
`runCrossfade` checks after each wait that `outgoingPlayer === outgoing && activePlayer === incoming` and returns if
not, so a late job never clears the active player. `refreshPlaybackState` derives `isPlaying` from the player that
actually sounds. Tests: pick → newer pick → old pick ready late (no clear of the active player, `isPlaying` stays
true); revert during the hold.

**F3 — Keep loading until the user stops (D67).** The user's rule: slowness never gives up a track. A pick, or a
current track that stalls, keeps loading as long as bytes arrive; the 8 s pick revert and the 15 s buffering watchdog
become progress-aware. After **no data for 20 s** playback **pauses** (no revert to the previous track, no skip): the
user hears silence and sees the paused state; the journal says why (`PAUSE no data 20 s «title»`). Auto-skip and the
recovery ladder stay for **hard errors only** (HTTP 403/404/410, decoder failure, no link). The play button shows
loading meanwhile; a tap on it stops (pauses) the load. Tests: a slow-but-moving pick does not revert or pause; a dead
pick or stall pauses after the no-data window; hard errors still skip.

**F4 — Fewer AudioTrack rebuilds (D68).** Decision: **normal playback stays on one player; the second player is only
for a crossfade or a pick, and goes quiet afterwards.**
- Natural track ends without crossfade: the next track is queued as the active player's next `MediaItem`, so
  ExoPlayer plays it gaplessly on the same AudioTrack (same format — always the same with F5).
- Crossfade and picks keep the second player (a pick must load while the current one plays; a crossfade needs two
  voices). After the transition the outgoing player is **paused at volume 0 and kept prepared on its last item** — not
  rebuilt as a silent standby, never left to run dry — and is reused for the next crossfade/pick (`setMediaItem` on a
  paused player keeps its AudioTrack when the format matches).
- A skip during a running fade cancels and reuses, it does not create a third AudioTrack.
- "Keep both players running" (`data/repository/SilentIdler.kt`) and "Keep audio streams" stay until F4 + F5 are
  measured on the unit, then are retired (settings removed, `SilentIdler` and `res/raw/silence.mp3` deleted).
Measure: `OUTPUT` lines per skip in the journal (target ≤ 1 per pick/crossfade, 0 per gapless advance) and equalizer
focus requests in the Diag focus history. Tests: engine tests for the reuse path; journal counts in a skip burst.
(A single-player crossfade by PCM mixing — KB `kb/players/00-conclusions-and-ideas.md` §2.2 — is out of scope.)

**F5 — Fixed output format (D69).** Every player outputs the mixer's native rate (read at start:
`AudioManager.getProperty(PROPERTY_OUTPUT_SAMPLE_RATE)`, `PROPERTY_OUTPUT_FRAMES_PER_BUFFER`), 16-bit stereo: a
`SonicAudioProcessor` (Media3 1.11.1, `androidx.media3.common.audio.SonicAudioProcessor`, `setOutputSampleRateHz`) in
the `DefaultAudioSink` processor chain resamples in the player, so AudioFlinger no longer resamples and the AudioTrack
format never changes between tracks (44.1, 48, 96 kHz FLAC alike). Behind a switch **"Fixed output format"** /
**«Фиксированный формат вывода»** in Settings → Playback, default **off** until A/B on the unit. Tests: the sink is
built with the processor when on; `OUTPUT` lines report the mixer rate.

**F6 — Journal additions.** Lines for user actions: `TAP next / prev / pick «title»`, so a dropout can be tied to a
tap; at start the mixer's rate and frames per buffer (`AUDIO mixer 48000 Hz, 960 frames`).

**F7 — Sharing that works with LocalSend.** The journal and the crash log are written to a file in the app's cache
and shared as a **file**: `ACTION_SEND`, `EXTRA_STREAM` = FileProvider content URI, `ClipData` with the same URI,
`FLAG_GRANT_READ_URI_PERMISSION`, MIME `text/plain`; the text stays in `EXTRA_TEXT` for messengers. The diagnostic log
(two files) gets `ClipData` too. Test: the built intents carry stream + clip + flag.

**F8 — Diag improvements** (`android/diag`). `logcat -d -b main,system,events,crash` for the time window; when over
the size cap keep the **newest** lines; drop `CarPlayService not found` lines (count them in the manifest). AudioFlinger
and focus dumps as now. Later, not now: a "snapshot now" intent the YAMP debug build sends after a focus loss.

## Order of work

Each step is a commit group with the full gates (`testDebugUnitTest verifyRoborazziDebug lintDebug
compileDebugAndroidTestKotlin` + detekt) and a debug APK with a new build number.

1. **F2** stale crossfade — test on the unit: fast picks and Prev; the play icon matches the sound.
2. **F9** one stable session — test: a ride with the equalizer's "Fade" flag back on; no auto-pauses, Diag shows one
   YAMP session id all ride.
3. **F7** sharing, **F6** journal taps + mixer rate, **F8** Diag — test: share to LocalSend; one Diag run.
4. **F1** resume after focus loss — test: a ride with Poweramp Equalizer on; pauses resume within ~2 s, the journal
   names the requester.
5. **F3** keep loading — test: FLAC parked on the weak link; picks wait, no revert while loading moves.
6. **F4** fewer rebuilds — test: skip bursts; count `OUTPUT` lines and equalizer requests; stutter on skips.
7. **F5** fixed output format, switch off by default — test: A/B on the unit, same route, switch on vs off.
Then retire "Keep both players running" / "Keep audio streams" if F4 + F5 hold.

## User check (independent of the code)

One ride with **Poweramp Equalizer disabled** (or its auto-attach / focus option off, if it has one): if the
auto-pauses disappear, F-a is confirmed beyond the focus history.

## Out of scope

Crossfade by PCM mixing inside one player; automatic quality by network speed (Yandex `get-file-info` with measured
speed — next round); rotor feedback 400 and play reporting; a YAMP-triggered Diag snapshot.

## Open questions

None — closed 2026-11-16: F1 on by default everywhere, with the was-playing guard; F3 pauses after 20 s without data.

## As built

**F2 + F9 (2026-11-16).**
- F2 as planned: `revertPick` cancels the transition's job; `fadeOutRunningCrossfade` cancels before its early
  return; `runCrossfade` checks `stillMine()` (outgoing and active players still its own) in every wait and before it
  clears anything. `refreshPlaybackState` also counts the other player as playing when it sounds a real track at a
  volume above 0 (not the silent clip, not a crossfade's tail) — a safety net; with the guards it should not trigger.
- F2 tests drive the engine with scripted players (`ScriptedExoPlayer` on Media3's `StubExoPlayer`, test-only
  dependency `media3-test-utils`): both reproduce the 13:17 sequence and fail on the old engine.
- F9: the process already had one shared session for both players (`SystemEqualizer.audioSessionId`); the change is
  removing the second session (`secondAudioSessionId`, `AudioSessionPolicy`, `applyPendingAudioSession`, the
  "Separate audio sessions" setting and its strings) and the re-announcements (`keepAnnounced`, the announce on focus
  regain). `SystemEqualizer.openOnce` sends one OPEN the first time the sound starts in a service lifetime;
  `close` sends CLOSE only after an OPEN (service destroy); with announcing off neither goes out. The journal says
  `EQ session <id> opened` / `closed`; every `OUTPUT` line carries `session <id>`.
- Not done: re-applying the session after `onAudioServerDied` — Android 9 has no callback for it, and ExoPlayer
  recreates its AudioTracks with the session id it was given, so the id stays the same.


**F7 + F6 + F8 (2026-11-16).**
- F7: `data/diagnostics/FileShare` builds every log share: one file → `ACTION_SEND`, several → `ACTION_SEND_MULTIPLE`;
  the content URI in `EXTRA_STREAM` and in `ClipData`, `FLAG_GRANT_READ_URI_PERMISSION`, MIME `text/plain`. The journal
  and the crash log are first written to `cache/shared/yamp-journal-<time>.txt` / `yamp-crashes-<time>.txt` (the five
  newest kept) and shared as files; their text stays in `EXTRA_TEXT` up to 100 000 characters (larger would risk the
  binder limit). The diagnostic log (two files) goes through the same builder. Checked on the emulator: the chooser
  receives the content URI in its clip data with the read grant.
- F6: journal kinds `TAP` (`next` / `prev` / `pick`, with the track aimed at or the current one; automatic advances
  are not taps) and `AUDIO` (`mixer 48000 Hz, 720 frames` on the AVD, from `AudioManager` output properties, once
  when the playback service is created).
- F8 (Diag): logcat reads `-b main -b system -b events -b crash` in the 2 h window; the logcat item is read to the end
  and `LogTail` keeps the **newest** lines within the cap (a header counts what was left out), drops lines with
  `CarPlayService not found` (counted), and folds runs of the same message into one line with a repeat count. Other
  items keep the old head cap.

**F1 + F3 (2026-11-16).**
- F1: `FocusResumer` (decision in `AudioFocusPolicy.afterLoss`, pure) runs from `MusicService` on `AUDIOFOCUS_LOSS`:
  "was playing" is read before the loss's own pause (`PlaybackController.isPlaying`: sounding or loading to play);
  after `RESUME_CHECK_DELAY_MS` = 1.5 s it resumes via `PlaybackController.play()` unless the setting is off, the user
  acted since (`PlaybackController.userIntentEpoch` changes with every play / pause / stop, or YAMP plays again), a
  call is on, other music plays, or it already resumed 3 times within 60 s. A focus GAIN cancels the wait.
  **Deviation:** "another app plays" is `AudioManager.isMusicActive()`, not `getActivePlaybackConfigurations()`: for
  an ordinary app those configurations hide the player state and the uid, so they cannot tell a playing app from a
  paused one (ours included). Consequence: with "Keep audio streams" / "Keep both players running" on, a silent clip
  that kept looping after the pause would read as music and block the resume (safe side) — to check on the unit.
  Journal (`FOCUS`): `lost, will resume when nothing else plays` → `resumed, nothing else playing` /
  `not resuming: other music plays | call | played or paused since | resumed 3 times in a minute`; `lost, not
  resuming: was paused`; plain `lost` with the setting off. The `PAUSE audio focus lost` line now comes before it.
  Setting `resume_when_quiet` (Settings → Playback, after "Keep playing under announcements"), default on.
  Tests: `FocusResumerTest` (the three guard cases, other music, call, setting off, rate limit),
  `AudioFocusPolicyTest`. The `MusicService` wiring itself has no test (a service).
- F3: the fixed 8 s READY limit is gone. `runCrossfade` waits while `LoadProgress` sees the incoming player's
  buffered position move; after `NO_DATA_TIMEOUT_MS` = 20 s without new data it reports `PlaybackFailure.NO_DATA`
  (journal `HOLD_TIMEOUT`, "next track brought no data in 20 s") and the controller pauses (`PAUSE no data 20 s`) —
  no revert to the previous track, no skip; the pick stays the current track and Play continues the same load.
  The controller's 15 s buffering watchdog became the same no-data check for a current track that stalls
  (`bufferedPositionMs()` on `PlayerRepository`, checked every second). The recovery ladder gained `Recovery.PAUSE`:
  network timeouts (`ERROR_CODE_IO_NETWORK_CONNECTION_FAILED / _TIMEOUT`, `ERROR_CODE_TIMEOUT`) retry once with a
  fresh link and then pause instead of skipping; HTTP errors, decoder failures and a missing link still retry and
  skip. A player error on a pick still reverts to the playing track (`PICK_FAILED`), as before. The silence-handover
  and crossfade-headroom waits keep their own 8 s bounds (they only shape a transition that is already ready).
  **Not changed:** the pick wait still shows the outgoing track as playing (no loading spinner while the old track
  sounds); a tap on Play/Pause then pauses and keeps the pick as the current track.
  Tests: `LoadProgressTest`, `PlayerEngineTest` (a 30 s trickle never gives up; 20 s of nothing reports NO_DATA and
  keeps the pick), `PlaybackControllerTest` (no data 20 s pauses without a skip; a trickle keeps loading; the
  engine's NO_DATA pauses; a network timeout pauses after its retry), `RoadLogicTest`.

**F4 (2026-11-16).**
- **Finding that changed the plan:** in Media3 1.11.1 `DefaultAudioSink.flush()` releases the AudioTrack, and a new
  item set, a stop and a seek all flush. So "`setMediaItem` on a paused player keeps its AudioTrack" (the plan) does
  not hold — and neither does "Keep audio streams" ("a parked player keeps its AudioTrack for the next track"): parking
  on the silent clip only opens one more AudioTrack per park. The one way a track change keeps an AudioTrack is a
  playlist move inside one player (gapless). F4 is built on that.
- **Crossfade off — gapless (D68):** `prepareNextTrack` queues the next track behind the playing one on the active
  player (`queuedOnActive`) when crossfade is off and neither silent-clip mode is on. ExoPlayer moves on by itself; the
  `AUTO` media-item transition stands in for `STATE_ENDED` (track-ended event), and the controller's start for that
  track is taken over in place (`continueGapless`: state follows, the played item leaves the playlist, journal
  `START … gapless`). Any other start, a skip, `dropStandby` or switching crossfade on takes the queued track off
  (`dropQueuedOnActive`), so a fading or reverted track can never play on into it. The sleep timer "end of track"
  readies no next track (`SleepTimer.endsWithTrack`; arming it drops one already readied).
- **Crossfade on:** two voices need two AudioTracks; per crossfade one is opened (the incoming one, at its warm-up)
  and the outgoing one is stopped at the end of the fade — never left to run dry, never rebuilt as silence in a burst.
- **Bursts:** a player set aside within `SETTLE_MS` = 3 s of a track change (or during a skip) is just stopped — no
  silent loop ("Keep both players running"), no parking ("Keep audio streams"); an empty standby starts idling once
  calm (`idleStandbyWhenCalm`, from the progress loop).
- **F1 interplay:** every pause silences our clip — a looping one and one playing out into a queued warm-up
  (`SilentIdler.setRunning`); resuming re-arms the warm-up hand-over. So `isMusicActive()` never sees our own silence.
- Journal: every `OUTPUT` line carries `#n`, the AudioTracks opened since the service started.
- Emulator (crossfade on, both switches off; 10 Next presses ~0.6 s apart): before (b79) 5 `OUTPUT` for 3 started
  tracks, after (b80) 4 for 3 — one per started track, no crash. The gapless path and the silent-mode bursts were not
  exercised on the emulator (crossfade and the switches stayed at their defaults); they are covered by engine tests.
- **Not changed:** after a skip fade the faded player still "plays" at volume 0 until the target starts (the session
  and notification keep their state); single-player crossfade by PCM mixing stays out of scope.
- Recommendation from the finding: retire "Keep audio streams" now (it can only add AudioTracks); decide on "Keep
  both players running" after a ride with F4 + F5.
  Tests (`PlayerEngineTest`, with `ScriptedExoPlayer` modelling Media3's flush-releases-the-AudioTrack rule): gapless
  keeps one AudioTrack for two tracks with no stop; a skip takes the queued track off; a crossfade opens one AudioTrack
  and stops the outgoing player; a 6-skip burst with "Keep both players running" opens one per track and no silent
  clip, then idles once calm; a pause silences an idling standby and a warm-up's silence, and play brings it back.

- **F5** (D69): `data/player/FixedOutputRate.kt` gives each player's `DefaultAudioSink` a resampler — Media3's
  `SonicAudioProcessor`, used only for the rate — in front of the default chain (silence skipping + the speed Sonic).
  Deviation: the rate is re-applied at **every** processor configure, not set once on the processor, because Media3
  resets processors when a player stops (seen on the emulator: after F4's stops the standby player came back at
  44100 Hz). The mixer rate is read once (`PROPERTY_OUTPUT_SAMPLE_RATE`, fallback 48000). The switch "Fixed output
  format" / «Фиксированный формат вывода» (Settings → Playback, default off) reaches a player the next time it
  configures a new track's format; a restart applies it to both at once. The journal's start line says
  `AUDIO mixer 48000 Hz, 720 frames, fixed output 48000 Hz` (or `fixed output off`). Emulator (mixer 48000): with the
  switch on, every `OUTPUT` line is `pcm16 48000 Hz` for 44.1 kHz MP3/FLAC, AudioFlinger shows YAMP's tracks at 48000;
  off, they are 44100. With crossfade off, a natural track end logs `START gapless` with no new `OUTPUT` line.
  Tests: `FixedOutputRateTest` (5): off passes through, on → 48 kHz 16-bit stereo, the rate survives a processor
  reset, the switch applies at the next configure, a 48 kHz track passes through.
- Noticed while testing, not changed: a media button sent without a package name (`adb shell media dispatch`) crashes
  Media3's legacy session on Android 9 (`MediaSessionManager$RemoteUserInfo: packageName should be nonempty`). Real
  head units send buttons with a package; worth a guard if a unit ever does not.

### After the b83 ride (2026-11-16 evening, build b84)

The ride showed a 90 ms "pop" of the previous song near the start of the next one, and FLAC starts that hung.
- **Cause of the pop: Poweramp's DVC on the shared session.** Poweramp Equalizer inserts Dynamics Processing and
  Volume effects flagged "volume mgmt: implements control" on YAMP's one session (D70). AudioFlinger then mixes every
  track of the session at unity and the chain applies one volume: a player's own volume — fades, the volume hold,
  volume 0 — is not applied per player, so any overlap of the two players leaks the old track.
- **A — no overlap on a skip or a pick** (`PlayerRepositoryImpl`): the skip fade is 150 ms (was 500) and the faded
  player is paused and cleared instead of playing on at volume 0; a target that arrives while a skip fade runs ends
  that fade (pause + clear) before the new track sounds; a ready pick fades the current track out, pauses it, and
  only then starts; a plain cut pauses the old player before the new one plays. Clearing a player sets a new item or
  stops it, which flushes its AudioTrack — no buffered PCM of the old song can play later. "Keep both players running"
  idles only on the silent clip. The natural-end **crossfade still overlaps the two by design**: it needs each
  player's own volume, i.e. Poweramp "Без DVC" on for YAMP (or no volume-managing effect).
- **B — a silence that never hands over** (`SILENCE_HANDOVER_MAX_MS`, 8 s): the queued track is loaded on that player
  directly (`loadPastSilence`, journal `ERROR … handover from silence timed out, loading the track directly`); the
  silent clip never counts as the track (b83: "START pick" with no title, nothing playing).
- **C — dead-connection starts**: stream HTTP timeouts 4 s connect / 6 s read (were 8 / 8); a load that opened at
  position 0 and brought no bytes gives up after one try (`PlaybackBuffering.giveUpOnLink`) so the controller's RETRY
  evicts the cached link and resolves a fresh one (journal `→ RETRY new link`). Mid-track (the download resumes at a
  later position) the patient retries stay. Not changed: a *pick* whose start fails this way still reverts to the
  track that plays (existing pick-failure path); the user taps again.
- **D — Poweramp hint**: Settings → Playback shows "Poweramp Equalizer — for a clean crossfade: Known Players → YAMP —
  «Без DVC» on, «Затухание» off" under the crossfade when `com.maxmpz.equalizer` is installed (`<queries>` package
  entry); a tap opens Poweramp Equalizer. Its settings can't be changed by YAMP (no API).
- **E — Diag**: `pidof` and `ps -A -o PID,NAME` added; `EffectSummary` reads the audio_flinger dump and writes one
  line to `summary.txt` and the manifest, e.g. `DVC on YAMP session 193: yes (Dynamics Processing, Volume) — effects:
  Visualizer (com.maxmpz.equalizer), …`.
- Emulator (crossfade 3 s, keep-both on, fixed output on): 10 fast Next, then two picks — AudioService shows the old
  player `paused` before the new one `started` at each switch (same millisecond, old first); no START without a title;
  no crash.

### Settings cleanup (2026-11-18, D88)

- Removed: "Keep both players running", "Keep audio streams" (with `SilentIdler`, the parking policy and the silent
  clip `res/raw/silence.mp3`), the "Announce to equalizer" switch (always announced) and the "Fixed output format"
  switch (always at the mixer rate). Stored values of the removed keys are never read again.
- "Resume when nothing plays" and "Keep playing under announcements" stay as settings, off by default for new
  installs; a stored choice is kept.
- The Poweramp Equalizer hint names the two settings that matter (Fade off, Без DVC on); the same in `README.md`.
- Wide player (same build): the wheel-side shade covers the whole screen (no band under the status bar), the
  portrait vignette is drawn in portrait only, an empty artist reads "Unknown artist", and the Car layout's cover
  and next block follow the phone-landscape proportions (cover ~64 % of the height, placed under the clock when
  that gives a larger cover; three cards span the cover's width).
