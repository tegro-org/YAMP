# HANDOFF — 2026-11-13

## State

- **Released:** `v0.2.0` — Phase 1 queues, Phase 1b History, Home per queue (D49), Phase 2 radio queues, head-unit
  fixes and diagnostics, upstream 1.14.3 crossfade-stall port. `main` = the release commit; feature branches
  `feature/phase-1-queues` and `feature/phase-2-radio` are fully merged (fast-forward).
- **Phase 2 radio** (`docs/phase-2-radio.md`, as built): many `RADIO` queues; "Start radio" (track menu, player
  button, album/artist ⋮, car pill on every queue but My Wave — D54) opens the picker: new radio or re-seed an
  existing one; per-radio Keep = retain history (off → played head trimmed to `RADIO_BEHIND = 3` at the next
  refill); refill only while active; `WAVE` untouched (D53). Room v4 (`queues.keepHistory`). Decisions D35′,
  D51–D54 in `kb/decisions.md`. Tested by the user on the phone; **the unit check is still open** (todo 4c).
- **`debug/radio-probe`** (pushed): debug-only About button "Probe radio stations" — calls `track:`/`album:`/
  `artist:` rotor stations and feedback, logs `PROBE` lines. Removed from `main`; cherry-pick onto a branch when
  needed.
- **Builds:** `scripts/build-apk.sh debug` → `dist/YAMP-b<versionCode>-<versionName>[-dirty-<time>]-debug.apk`;
  uploads go to the user's Nextcloud share by WebDAV `PUT` (share token as the user name, same file name).
  Releases: push tag `vX.Y.Z` → `release.yml` builds and publishes; notes are auto-generated, edit them after with
  `gh release edit`.
- **Head-unit AVD** `yamp-headunit` (API 28, 1920×720, 160 dpi), the user's secondary Yandex account signed in;
  `adb -s emulator-5554`. Good for queue/radio flows, force-stop cases, History; not for the unit's audio
  behaviour. The auth token is Keystore-encrypted — don't try to extract it; use the app (a debug probe) instead.
- `gh` is installed and logged in.

## Known issues

- **Rotor feedback answers 400 "condition is not met"** for every station incl. My Wave (found by the probe;
  listed in the v0.2.0 notes). Next: compare the form fields / headers with upstream's current version.
- **Head-unit auto-pause:** permanent audio-focus loss 0.1–3 s after a track starts. "Separate audio sessions" off
  cut it to 3 per 1 h 15. Waiting for the unit: "Не закрывать звуковые потоки" (Settings → Playback, off by
  default). Still wanted: `adb shell dumpsys audio | grep -A 25 -i "focus stack"` on the unit right after a loss,
  and one ride on upstream 1.14.4 as a baseline. No decompiling of closed players (D15).
- "Далее" tap stutter — retest after the 1.14.3 port.
- Back-button crash (player screen, twice, no `CRASH` line) — ask for Settings → About → "Журнал сбоев".
- Head-unit HUD app shows nothing for YAMP — app name needed.
- `PlaybackControllerTest` is timing-flaky (`aSavedSessionComesBackPausedAtItsPosition` timed out once,
  `playingATrackStreamsItsLinkAndWarmsUpWhatFollows` failed once on 2026-11-13; both pass on rerun).

## Next steps (user sets the pace)

1. The user's findings from the unit (Phase 2 car UI, the two head-unit switches) → patch releases `v0.2.x`.
2. Rotor feedback 400 (above).
3. Triage the rest of upstream 1.14.2–1.14.4 (lyrics, flat-unit layout, car stations, downloads on device, AI
   marks, Android 7.1, sign-in code fallback, certificates) — `kb/upstream-tracking.md`.
4. Phase 1 leftovers: `removeEntry`/`moveEntry` in the player's queue sheet; `WaveQueue`'s own list when the
   dynamic source is generalised to WAVE; the D36 resolver doc.
5. Next phase per `docs/multi-source-player.md`.

## Notes

- Workflow: design doc approved before code; stage → report → confirm → commit, in groups; push/merge only on the
  user's word. Chat: file names only, short. The user dislikes being rushed. Heavy research/implementation goes
  to subagents to keep the main session slim.
- New strings: EN in `values/`, RU in `values-ru/`; `tools:ignore="MissingTranslation"` on the `values/` root until
  K4. Gates before every hand-off: `cd android && sh gradlew testDebugUnitTest verifyRoborazziDebug lintDebug` and
  detekt (`config/detekt.yml` + baseline; jar per `.github/workflows/ci.yml`).
- Docs and commit messages describe YAMP only; the repo is public — no server URLs, tokens, keystores.
- User setup: `kb/environment.md`. Upstream clone for reading: `~/Projects/Github/YandexMusicLite`.
