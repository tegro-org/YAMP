# HANDOFF — 2026-11-09

## State

- Repo `tegro-org/YAMP` (public, GitHub) cloned at `~/Projects/Tegro/YAMP`. It was empty; **nothing is
  committed or pushed yet** — everything below is untracked, waiting for the user's review and an explicit
  "commit" / "push".
- `android/` = upstream YandexMusicLite at `fac6af7` (1.12.2), exported with `git archive` (no upstream
  history, tags or dependabot branches). Untouched except:
  - `LICENSE` moved to the repo root;
  - upstream `README.md` and `.github/` moved to `kb/upstream/` (reference only);
  - upstream `docs/` (their GitHub Pages site) dropped.
  - `app/src/test/screenshots/` (~61 MB of Roborazzi golden images) kept as imported.
- **Not verified yet:** that the project builds from `android/` and the tests pass.
- The app still has upstream identity: `applicationId = "YandexMusicLite.KorenskoyR"`, package
  `com.korenskoy.yandexmusic`, updater → `KorenskoyR/YandexMusicLite`. **Do not release a YAMP build
  before Phase 0** changes this.

## How we got here (context for a fresh session)

The user found YandexMusicLite and its fork YML (Ozo-bro), asked for a privacy audit
(`kb/privacy-audit.md`) and whether more backends (Jellyfin) could be added. The design discussion produced
`docs/multi-source-player.md` (phases, still **draft, not approved**) and the decisions in
`kb/decisions.md`. The doc was first written against YML, then moved here and re-based on upstream
`fac6af7` ("Current state" section re-checked).

## Next steps (in this order, user sets the pace)

1. **User reviews** the imported tree + docs → initial commit → push.
2. **Verify the build**: `cd android && ./gradlew assembleDebug testDebugUnitTest` (Android SDK at
   `~/sdk/android`). Record anything non-obvious in the KB.
3. **Architecture & design rules** — the user's explicit next task. Must be **derived from the existing
   code first** (what upstream actually does: layers `data/ domain/ presentation/ di/`, Hilt modules,
   Compose screens + ViewModels, Media3 service/controller, Room cache, DTO usage in UI, tests incl.
   `ArchitectureTest.kt` and screenshot tests), then the target rules for YAMP (provider abstraction,
   queue model, where Yandex-specific code may live, naming, testing). Output: `ARCHITECTURE.md` (+ a
   design/UI guide), linked from `kb/README.md`. Open question to raise there: rename the package
   `com.korenskoy.yandexmusic` or not.
4. Approve / adjust `docs/multi-source-player.md`.
5. Phase 0 — fork identity: `applicationId`, app name, signing key (secrets in GitHub Actions, never in the
   repo), updater → `tegro-org/YAMP`, CI for `android/`.

## Notes

- Upstream reviews: `kb/upstream-tracking.md` (baseline `fac6af7`).
- User's setup (head unit, Jellyfin/Airsonic, library layout): `kb/environment.md`.
- `arkadym/YAMP` (an earlier GitHub fork) is superseded; the user may delete/archive it.
- The user dislikes being rushed: answer the question, don't append "shall I start…?" (`tasks/lessons.md`).
