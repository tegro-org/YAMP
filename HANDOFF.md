# HANDOFF — 2026-11-18

## State

- **Release:** `v0.5.0` is everything on `feature/album-artist-menu`, fast-forwarded into `main`:
  - Phase 3 and 3b: providers, disk stream cache, early preload, Next states;
  - Phase 4: Jellyfin;
  - first start, Jellyfin-only mode, the album/artist menu, smooth fades, Fade then rise, pop-free handovers;
  - quality per account, Jellyfin playlists;
  - the new player.

  Notes: `tasks/release-notes-v0.5.0.md`. Before it: `v0.3.0` (new app id `org.tegro.music.yamp`), `v0.2.1`.
- **Branches:**
  - `main` is the release;
  - `debug/radio-probe`, a debug rotor probe, is pushed;
  - the feature branches since v0.3.0 are merged by fast-forward.
- **Last debug build: b122.** Debug builds use the local counter `dist/.build-number`; never share two APKs with the
  same number.
- **Player structure, the same on every layout:**
  - **Top row:** `⋮ · queue ▾`, at the hand-side edge in wide layouts, always top-left in portrait. Everything
    else is in ⋮: tune the wave on My Wave or start radio elsewhere, search, sleep timer, add to playlist,
    quality, and likes when gestures are on.
  - **Wide layouts** (phone landscape and «Крупные кнопки» on the head unit) use `wideGeometryOf`:
    - the cover and next block runs full height;
    - it's centred on wide screens and sits at the right padding on taller ones;
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
    account; never sign accounts out there, use debug Hide. `emulator-5556` is Android 14, a clean phone that is
    often out of storage.
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
- **Jellyfin downloads** and offline playback of a fully cached Jellyfin track aren't built yet.
- **Android Auto with Jellyfin accounts** hasn't been verified on a device.
- **A seek in a Yandex track** was once logged as `START cut … <pos>`, a restart at the seek position instead of a
  plain seek.
- **Wide layout:** the "taller" branch starts at about 4:3. 16:10 still centres the block; see gap (c).

## Next steps (the user sets the pace)

See "Next: gaps after v0.5.0" in `tasks/todo.md`:
- (a) the Yandex seek;
- (b) Jellyfin downloads and offline playback;
- (c) the taller threshold (controls < 1/3 of the width ≈ `w/h < 1.66`);
- (d) Android Auto with Jellyfin;
- (e) Home Jellyfin sections and merged Library rows, canvas first;
- (f) the car album long press, canvas first;
- (g) foldable shape classes;
- (h) Phase 7 export/import, design doc first.

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
