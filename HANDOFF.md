# HANDOFF — 2026-11-13

## State

- **Released:** `v0.1.1` (skip fade, separate audio sessions, YAMP splash). `main` = `abb6169`: U1 + U2 upstream
  ports (security/data/build; buffering, crossfade hold, equalizer, head-unit UI fixes), playback journal,
  diagnostic log file (D48) — not tagged yet.
- **Branch `feature/phase-1-queues`** — 17 commits ahead of `main`, **not pushed**, last `cebb917`. Contains:
  - Phase 1 complete (`docs/phase-1-queues.md`): domain model, Room v2, `QueueManager`, controller on the manager,
    queue switcher UI, Android Auto "Queues", 1920×720 goldens, device-check script.
  - D49 Home follows the active queue; D50/Phase 1b **History** (`docs/phase-1b-history.md`, Room v3).
  - Upstream 1.14.3 "no false stalls at crossfades" ported (`kb/upstream-tracking.md`; the rest of 1.14.2–1.14.4
    still to triage).
  - Head-unit diagnostics: equalizer announce switch, focus/screen lines in the log.
  - UI: ring tap opens the player, player survives rotation, landscape player follows the steering side.
- **Uncommitted in the working tree (17 files), two groups waiting for the user's test on the unit:**
  1. `utils/DisplayScale.kt` + test — auto scale bounded so the short side keeps ≥ 480 dp (1920×720 → 150 %).
  2. the "keep audio streams" experiment: `PlayerRepositoryImpl` parks a finished player on `res/raw/silence.mp3`
     instead of `stop()` (switch, default off), settings flag/UI/strings, 2 Playback-settings goldens,
     `tasks/todo.md` with today's findings.
  Rule: stage → report the file list and message → wait → commit, one group at a time (`tasks/lessons.md`).
- **Builds:** `scripts/build-apk.sh debug` → `dist/YAMP-b<versionCode>-<versionName>[-dirty-<time>]-debug.apk`;
  uploads go to the user's Nextcloud share by WebDAV `PUT` (share token as the user name, same file name).
  Latest shared: `YAMP-b48-0.1.1-dev.23+828ea01-dirty-20261113-1354-debug.apk` (has both uncommitted groups).
- **Head-unit AVD** `yamp-headunit` (API 28, 1920×720, 160 dpi) on this machine, the user's secondary Yandex
  account signed in; `adb -s emulator-5554` (a second emulator may be running). Good for queue flows, force-stop
  cases, History; it cannot show the unit's audio behaviour.
- `gh` is installed and logged in (CI/release runs, Dependabot PRs).

## The head-unit auto-pause (the main open problem)

Facts from the diagnostic logs (shared from About → "Отправить журнал"): every auto-pause is a **permanent
audio-focus loss 0.1–3 s after a track starts sounding** (`FOCUS lost` → `PAUSE audio focus lost`). Upstream
1.12.2/1.14.4 (same focus code) does not pause on the same unit, per the user.
- "Separate audio sessions" on (since ride 1): dozens of losses per hour → **off: 3 per 1 h 15** (2026-11-13).
- Equalizer announce off: no difference by itself.
- Next: **"Не закрывать звуковые потоки"** (Settings → Playback, off by default) parks finished players on a
  silent clip so ExoPlayer keeps its AudioTracks — works on the AVD, awaiting the unit.
- Still wanted: `adb shell dumpsys audio | grep -A 25 -i "focus stack"` on the unit right after a loss (names the
  app that takes focus; `adb connect <unit-ip>:5555`), and one ride on upstream 1.14.4 for a baseline count.
- Related: the "Далее" tap stutter (stop / rise / crossfade) — the 1.14.3 port removed the false-stall path; retest.
- No decompiling of closed players (D15); the comparison point is upstream.

## Other open reports

- **Back-button crash** (twice, player screen): no `CRASH` line in the log, so the handler never ran — ask for the
  entry from Settings → About → "Журнал сбоев".
- Head-unit HUD app shows nothing for YAMP — package name / notification-listener access; app name needed.
- `PlaybackControllerTest.aSavedSessionComesBackPausedAtItsPosition` timed out once in a full run; watch CI.

## Next steps (user sets the pace)

1. User tests the two uncommitted groups on the unit → commit → **merge `feature/phase-1-queues` into `main`,
   push, tag `v0.2.0`** (release notes: queues, History, Home per queue, head-unit fixes, diagnostics).
2. Triage the rest of upstream 1.14.2–1.14.4 (lyrics, flat-unit layout, car stations, downloads on device, AI
   marks, Android 7.1, sign-in code fallback, certificates) — `kb/upstream-tracking.md`.
3. Phase 1 leftovers: wire `removeEntry`/`moveEntry` into the player's queue sheet (still by track id); drop
   `WaveQueue`'s own list when the dynamic source is generalised (Phase 2); the D36 resolver doc.
4. Phase 2 radio per `docs/multi-source-player.md`.

## Notes

- Workflow: design doc approved before code; stage → report → confirm → commit, in groups; push/merge only on the
  user's word. Chat: file names only, short. The user dislikes being rushed.
- New strings: EN in `values/`, RU in `values-ru/`; `tools:ignore="MissingTranslation"` on the `values/` root until
  K4. Gates before every hand-off: `cd android && sh gradlew verifyRoborazziDebug lintDebug` and detekt
  (`config/detekt.yml` + baseline; jar per `.github/workflows/ci.yml`).
- Docs and commit messages describe YAMP only; the repo is public — no server URLs, tokens, keystores.
- User setup: `kb/environment.md`. Upstream clone for reading: `~/Projects/Github/YandexMusicLite`.
