# Player fix — skip feedback and separate audio sessions

Status: **approved in discussion 2026-11-10 (D38, D45); implemented on `fix/player-skip-and-audio-session`,
awaiting the user's test on the phone and the head unit.** Findings 1 (partly) and 2 of
`kb/architecture/as-is-playback.md` → "Known problems". The rest of finding 1 — parallel variant resolution
and the quality ladder (D36) — is resolver/engine work and gets its own design doc later.

## Purpose

- A manual **skip must not look like a hang**: today the old track keeps playing at full volume until the next
  stream renders (URL resolve + up to 8 s ready wait) and the play knob shows nothing.
- The **crossfade blur on the head unit**: both ExoPlayers share one audio session, and the unit's sound chain
  applies one volume per session, so the two fading tracks fight over a single volume. Per-player sessions fix
  it, but on the phone the shared session is what lets the system equalizer shape the crossfade as one — so it
  is a per-device setting, not a global change.

## What changes for the user

- **Next / Previous** (player, mini player, car, headset, steering wheel, widget): the current track fades out in
  about half a second at once. If the next stream is ready sooner, it comes in under the tail; if not, there is
  silence and the play knob shows a **turning ring** until the next track sounds. The crossfade setting still
  applies to automatic transitions. Pressing Next again while loading retargets to the following track.
- The play knob (full player and mini player) shows the ring whenever a start the user asked for is still
  loading — first play of a restored session, resume after a long pause, recovery after a stall.
- **Settings → Playback → Sound: "Separate audio sessions"** (off by default). On a head unit
  with blurred transitions switch it on; the change is applied as soon as the second player is idle (at the
  latest at the next track change). Both sessions are announced to the system equalizer, so every track is
  still shaped; the "Equalizer" row opens the panel for the first session.

## Technical design

### Skip fade (engine + controller)

- `PlayerRepository.fadeOutForSkip()` (new). `PlayerRepositoryImpl`:
  - audible active player → a 500 ms linear fade (`SKIP_FADE_MS`, 10 steps) on its logical volume; a running
    crossfade's outgoing side is ended by the existing 150 ms quick fade first. When the fade ends and no
    transition has taken the player over, it is stopped and cleared and `currentTrackId` / `progress` are reset,
    so the engine reports *nothing playing* — the controller's `isStarting` alone drives "playing + loading".
    If `startTransition` arrives during the fade, the incoming track starts at full volume at once and the
    fading player becomes `outgoingPlayer`; the fade stops it when it ends.
  - not audible (buffering, paused, ended) → stopped and cleared immediately.
  - `pause`, `seekTo`, `stop` cancel the fade and restore the volume; a second skip while the previous tail is
    still fading ends that tail and fades the now-active player.
- `PlaybackController.playNext(isAuto = false)` and `playPrev()` (except "restart the track") call
  `fadeOutForSkip()` before resolving. Automatic `playNext(isAuto = true)` is unchanged.
- Why the engine clears `currentTrackId` at the fade end: the controller clears `isStarting` when "the player
  stopped wanting to play with a track loaded" (`PlaybackController.kt:258-262`); with the id cleared that rule
  does not fire, and `progress` falls back to the restored position (0 for the new track) — the right display.

### Loading glyph (UI)

- `PlayPauseGlyph(isPlaying, size, tint, isLoading)`: `isLoading && isPlaying` cross-fades to
  `HiFiActivityIndicator` (0.8 × size) instead of the pause bars. `PlayerControls`, `FullScreenPlayer` and
  `MiniPlayer` take `isLoading`; `MainScreen` passes `isBuffering` (`PlaybackController.isBuffering` =
  engine buffering ‖ `isStarting`). The car ring and the Home ring already had a loading mode.

### Separate audio sessions

- `SettingsRepository.separateAudioSessions` (`separate_audio_sessions`, default false) +
  `SettingsViewModel.setSeparateAudioSessions`, `SettingsUiState.separateAudioSessions`,
  `SettingsActions.onSeparateAudioSessionsChange`; a `SwitchRow` in the Sound group of the Playback page.
- `SystemEqualizer.secondAudioSessionId` (lazy, generated once) and `announce(sessionId)`.
- `PlayerRepositoryImpl.AudioSessionPolicy(shared, own, separate: Flow, announce)`: the engine collects the
  flow; player A keeps the shared session, player B is given `own` or `shared` through `setAudioSessionId`
  **only while it holds nothing** (`applyPendingAudioSession`, called from the stop-and-clear helper
  `clearPlayer` and before a standby/incoming is loaded). `PlayerModule` builds both players in the shared
  session as before and passes the policy.
- Strings: `settings_audio_sessions`, `settings_audio_sessions_hint` (semantic keys, K2) in Russian in `values/`
  like the rest of the page. A `values-ru/` with only the new keys trips lint's `MissingTranslation` for all
  ~445 old keys, so the EN/RU split (K1) starts with Phase 1, which decides how lint is handled until K4.

## Verification

- `testDebugUnitTest lintDebug`, `verifyRoborazziDebug` (the Playback settings golden changes: one more row).
- Phone: skip on a weak link → fade + ring, no old track lingering; skip twice quickly; skip during a
  crossfade; pause during the fade then play; previous within 3 s restarts without a fade; crossfade still
  smooth with the setting off; equalizer still applies.
- Head unit: setting on → crossfade without the "old track reappears" blur; the next tracks after switching
  (A and B both played at least once) still go through the unit's equalizer/DSP.

## Affected files

`domain/repository/{PlayerRepository,SettingsRepository}.kt`, `data/repository/{PlayerRepositoryImpl,
SettingsRepositoryImpl}.kt`, `data/player/{PlaybackController,SystemEqualizer}.kt`, `di/PlayerModule.kt`,
`presentation/hifi/HiFiKnob.kt`, `presentation/player/{PlayerControls,FullScreenPlayer}.kt`,
`presentation/main/{MainScreen,MainTabs,SettingsViewModel}.kt`, `presentation/main/components/{MiniPlayer,
SettingsModel,SettingsSheet}.kt`, `res/values/strings.xml`, settings golden.

## Risks and side effects

- With the setting on, a system equalizer that attaches to one session only would shape every second track;
  both sessions are announced to avoid that — to be confirmed on the unit (the reason for the device test).
- The skip fade accepts up to half a second of silence before a slow stream; D36's early preparation of the
  next track (own doc) is what removes most of that gap.
- Tapping a track in a list while another plays still crossfades as before (not a skip); extending the quick
  fade to every user-initiated start is a one-line follow-up if wanted.
