# HANDOFF — 2026-11-19

## State

- **Release:** `v0.6.0` is the gap milestone on `fix/gaps-after-0.5.1`, fast-forwarded into `main`:
  - Phase 4b: Jellyfin folders (browse, pins, play a folder; phone, landscape, car);
  - Home Jellyfin sections, Library per account with «Скачанное» (D89);
  - the car library (⋮ → «Музыка», last), car album page and car "Add to queue…";
  - Android Auto with a folder per account, search across accounts, covers through a local provider;
  - offline playback of fully cached tracks, Jellyfin downloads, auto-download per account;
  - the "taller" threshold (w/h < 1.66), Fold 8 goldens, Audio Router hard cuts, CI Robolectric cache.

  Notes: `tasks/release-notes-v0.6.0.md`. Before it: `v0.5.1`, `v0.3.0` (new app id `org.tegro.music.yamp`).
- **Branches:**
  - `main` is the release;
  - `debug/radio-probe`, a debug rotor probe, is pushed;
  - the feature branches since v0.3.0 are merged by fast-forward.
- **Last debug build: b132.** Debug builds use the local counter `dist/.build-number`; never share two APKs with the
  same number.
- **Player structure, the same on every layout:**
  - **Top row:** `⋮ · queue ▾`, at the hand-side edge in wide layouts, always top-left in portrait. Everything
    else is in ⋮: tune the wave on My Wave or start radio elsewhere, search, sleep timer, add to playlist,
    quality, and likes when gestures are on.
  - **Wide layouts** (phone landscape and «Крупные кнопки» on the head unit) use `wideGeometryOf`:
    - the cover and next block runs full height;
    - it's centred when w/h ≥ 1.66 and sits at the right padding on taller screens (16:10, 4:3);
    - the controls block is centred vertically;
    - the clock has one fixed position, over the cover.
  - **Portrait:** centred labels with dislike and like at the sides; "Next tracks 3" uses the landscape cards.
  - **Swipes:** swipe up opens the queue and follows the finger; swipe down closes the queue first, then the
    player.
  - **Main screen:** the queue dropdown and tune button sit at the hand-side edge; the «Плеер» tab opens the
    player.
- **Test devices:**
  - the head unit: 1760×720, left-hand drive, Poweramp Equalizer installed;
  - a phone: 2340×1080;
  - a Galaxy Z Fold 5: cover 904×2316, inner 2176×1812.
  - AVDs: `emulator-5554` is the head unit at 1920×720 with the user's three Yandex accounts and the Jellyfin dev
    account; never sign accounts out there, use debug Hide. `emulator-5556` (`Pixel_6_API_34`) is Android 14,
    wiped on 2026-11-19 with a 16 GB data partition, signed in to the Jellyfin dev account only.
- **Poweramp Equalizer** (README section): Known Players → YAMP → Затухание (Fade) **off**, Без DVC **on**.
- **YAMP Diag** (`android/diag`, built with `-Pyamp.diag` / `scripts/build-diag.sh`, never in CI or a release):
  - collects `dumpsys` audio/flinger/media_session, logcat, dropbox crash/ANR records and YAMP's journal over the
    unit's own adb;
  - `summary.txt` says whether Poweramp's DVC is on YAMP's session.
- **Jellyfin dev server:**
  - `scripts/jellyfin-dev/` (Docker, CC BY test music); `http://10.0.2.2:8096` from the emulators;
  - credentials only in the gitignored `data/`;
  - stop it with `docker compose -f scripts/jellyfin-dev/docker-compose.yml down`.
- **Uploads:** the user's Nextcloud share by WebDAV `PUT` (see memory, not the repo). Releases: tag `vX.Y.Z` →
  `release.yml`; replace the auto notes with `gh release edit`.

## Known issues

- **Rotor feedback 400** for every station. YAMP also never reports plays to Yandex. Fix both together.
- **Android Auto with Jellyfin accounts** is built and checked with a media browser on the emulator (gap (d), b132);
  not yet in a real car or the DHU.
- **A seek in a Yandex track** was once logged as `START cut … <pos>`, a restart at the seek position instead of a
  plain seek. Postponed — watch (todo (a)).
- **Audio Router detection** is a guess (router installed + USB audio output); a USB amp with the router's routing
  off also gets hard cuts. Needs a check on the head unit.
- **Car filter** opens while driving (no parked signal on a plain Android head unit; GPS speed is the option).

## Next steps (the user sets the pace)

- The user tests b132/v0.6.0: Library with several accounts, the car album page, Android Auto in the car, Audio Router.
- Watch: (a) the Yandex seek restart.
- Later, not in this milestone: (h) Phase 7 export/import, design doc first.

## Notes

- **Workflow:**
  - the design doc is approved before code;
  - stage → report → confirm → commit, one group at a time;
  - push or merge only on the user's word;
  - in chat: be short; the user sets the pace;
  - heavy work goes to subagents, to keep the session slim.
- **Strings and gates:**
  - new strings: EN in `values/`, RU in `values-ru/`;
  - gates: `cd android && sh gradlew testDebugUnitTest verifyRoborazziDebug lintDebug compileDebugAndroidTestKotlin
    :core:domain:test :provider:jellyfin:test` + detekt (jar per `.github/workflows/ci.yml`).
- **Public repo:** docs and commit messages describe YAMP only. No server URLs, tokens or keystores.
- **References:** user setup in `kb/environment.md`; the upstream clone for reading is
  `~/Projects/Github/YandexMusicLite`.

- Audio Router (`com.usbswitch`, USB amplifier / passenger headphones): no focus or effects; while its USB routing
  is on it overrides per-player volume every 2.5 s, so YAMP switches to hard cuts (`ROUTER active → hard cuts`).
