# HANDOFF — 2026-11-12

## State

- **Released:** `v0.1.0` (tag on `main` `1bbb8dd`) — the imported upstream player 1.12.2 under YAMP's identity
  (`org.tegro.yamp`, name YAMP, Rings icon, updater → `tegro-org/YAMP`). Built and published by GitHub Actions
  from the tag; the app updates itself from the releases. No feature work yet.
- **Released `v0.1.1`** (skip fade, separate audio sessions, YAMP splash). `main` also has the upstream 1.13–1.14.1
  ports U1 + U2 (security/data/build; buffering, crossfade hold, equalizer, head-unit UI fixes), the playback
  journal and the diagnostic log file (D48) — not yet tagged (`v0.1.2` when the user says so).
- **Branch `feature/phase-1-queues`** — Phase 1 implemented through step 5 (`docs/phase-1-queues.md`, checklist in
  `tasks/todo.md`): domain model, Room v2, `QueueManager`, controller restructured onto it, queue switcher UI,
  Android Auto "Queues". **Awaiting the user's test** on phone and head unit (build `…-p1-step5` in the share):
  the old session must come back as "Основная"; the three decisions under "Implementation notes" in the doc;
  `scripts/device-check.sh` for the force-stop cases. Then commit groups → merge → `v0.2.0`.
- `main` has a ruleset (no force push, no deletion).
- **Versions come from git (D37):** tag `vX.Y.Z` → `X.Y.Z`; otherwise `X.Y.Z-dev.N+sha`; `versionCode` = commit
  count. Releasing = `git tag -a vX.Y.Z` + push the tag. Never bump numbers by hand.
- **Build:** `scripts/build-apk.sh [debug|release] [--tests] [--install]` → `dist/` (ignored). Checks:
  `cd android && sh gradlew verifyRoborazziDebug lintDebug` (340 tests, 107 goldens, lint clean). JDK 21,
  SDK package `platforms;android-37.0`, `gradlew` is not executable (`sh gradlew`). See
  `kb/architecture/build-and-ci.md`.
- **Signing:** the user's keystore lives outside the repo (`~/.keys/yamp/`), config in
  `android/local.properties` (ignored); GitHub secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
  `KEY_PASSWORD` are set. `YANDEX_DEVICE_SECRET` is **postponed**: device-code sign-in disabled, WebView sign-in
  only.
- **CI:** `.github/workflows/ci.yml` (gitleaks from its Docker image — the action needs a licence for orgs;
  tests + screenshots + lint), `release.yml` (tag `v*`), dependabot weekly for `/android`. The first eight
  Dependabot PRs failed only because their branches predate the gitleaks fix; the user was advised to close
  them and let next week's run recreate them.
- **Docs approved and binding:** `ARCHITECTURE.md`, `DESIGN_GUIDE.md`, `docs/multi-source-player.md` (phases
  0–6), `docs/phase-0-fork-identity.md` (done). Decisions D1–D37 in `kb/decisions.md`.
- **Design canvas** `Design/YAMP.html` (serve with `python3 Design/serve.py`, PDF via `Design/export-pdf.sh`):
  all planned screens for phone, head unit 1920×720 and the split panes (1280×720 / 640×720); reviewed with
  the user, decisions D26–D35 applied. Icon sources + generator in `Design/icons/`; brand images in
  `Design/brand/` (social preview to upload in GitHub settings). Two icon concept zips in `Design/` are
  untracked on purpose.

## Player findings to carry into Phase 1 (all verified in code, see `kb/architecture/as-is-playback.md`)

1. **Skip looks like a hang** (old track plays up to ~10 s) and **FLAC is chosen and never downgraded on a weak
   link** → D36: parallel resolution, start with what the link carries, lossless from the next track, the next
   track prepared early (buffer-gated), quick ~0.5 s fade on manual skip + loading state, retry at lower quality.
2. **Crossfade blur on the head unit** (old track "stops", new rises, old reappears quieter): both ExoPlayers
   share one audio session (`di/PlayerModule.kt:79-80`) and the unit's sound chain collapses them to one volume.
   Confirmed by the phone control test. Rule: one audio session per player.
3. **Force-stop by the head unit's DPI tool** (`kb/head-unit-dpi-app.md`): write-through persistence, nothing
   saved only in callbacks, start-up never overwrites state, test cases listed there.

## Open reports from the user (see `tasks/todo.md`)

- Crash on the Android Back button (once, then fine after restart) — trace needed (the diagnostic log now catches it).
- The head unit's HUD / driver-screen app shows nothing for YAMP — likely keyed by package name or missing
  notification-listener access; app name/APK needed.
- Settings scaled 200 % on the head unit since dialogs keep the app's scale: waiting for the line under Settings →
  Интерфейс → Масштаб (reported/physical dpi) to bound the auto scale sensibly (150 % looked right).
- Auto-pause on the unit: fixed for the skip gap (focus kept) and the next-track preparation gated by buffer health;
  if it recurs, the diagnostic log (About → Share) says why.

## Next steps (user sets the pace)

1. **Phase 1 test and release** (above). Known follow-ups inside Phase 1's scope: wire `removeEntry`/`moveEntry`
   into the player's queue sheet (it still removes/moves by track id), drop `WaveQueue`'s own list once the
   dynamic source is generalised (Phase 2), the D36 resolver doc.
2. **Phase 1b — History** (D46), then Phase 2 radio per `docs/multi-source-player.md`.
2. Install `v0.1.0` on the head unit; measure the real split-pane sizes and which side the player gets
   (`kb/environment.md`); try the car-mode idea (D32).
3. Regenerate the baseline profile for `org.tegro.yamp` (needs a device). Upstream reviews:
   `kb/upstream-tracking.md` (baseline `fac6af7`, porting note for the package mapping).
4. Later phases per `docs/multi-source-player.md`: radio (2), provider abstraction (3), Jellyfin (4, 4b),
   local files (4d, design deferred), favourites (5), mixes (6).

## Notes

- Workflow: feature branch + design doc approved before code; stage → report → confirm → commit, in groups;
  push and merge only on the user's word (`tasks/lessons.md`).
- Docs and commit messages describe YAMP only; the user's other projects are never named in this public repo.
- The user dislikes being rushed: answer the question, don't push the next step.
- User's setup: `kb/environment.md` (head unit Android 9, 1920×720, split 1 : 2; Jellyfin over HTTPS; library
  layout). Upstream clones for reading: `~/Projects/Github/YandexMusicLite`, `~/Projects/Github/YML`.
