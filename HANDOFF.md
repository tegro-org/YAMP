# HANDOFF — 2026-11-09

## State

- `main`: initial import `d221875` + architecture/design docs commits, all pushed.
- `android/` = upstream YandexMusicLite `fac6af7` (1.12.2), untouched (see `kb/upstream/README.md`).
  Still upstream identity (`applicationId`, package, updater → KorenskoyR). **No YAMP release before Phase 0.**
- **Build verified** (2026-11-09): `sh gradlew assembleDebug testDebugUnitTest` passes (340 tests);
  `verifyRoborazziDebug lintDebug` passes (107/107 screenshots unchanged, lint clean). See
  `kb/architecture/build-and-ci.md`.
- **Architecture docs:** `ARCHITECTURE.md` (as-is summary + rules A–K; Q1–Q7 decided, no open questions),
  `DESIGN_GUIDE.md` (Hi-Fi tokens/components, car rules, localization, patterns for new screens), as-is analysis
  in `kb/architecture/` (data, playback, UI, build/CI). Rules still marked "[proposed]" await confirmation.

## How we got here (context for a fresh session)

The user found YandexMusicLite and its fork YML (Ozo-bro), asked for a privacy audit
(`kb/privacy-audit.md`) and whether more backends (Jellyfin) could be added. The design discussion produced
`docs/multi-source-player.md` (phases, still **draft, not approved**) and the decisions in
`kb/decisions.md`. The doc was first written against YML, then moved here and re-based on upstream
`fac6af7` ("Current state" section re-checked).

## Next steps (in this order, user sets the pace)

1. ~~Initial commit + push~~ (done, `d221875`).
2. ~~Verify the build~~ (done; `gradlew` is not executable — run `sh gradlew`), incl. screenshot
   compare + lint.
3. **Architecture drafts reviewed**; Q1–Q7 decided (`kb/decisions.md` D16–D22: `org.tegro.yamp`, gradual
   modules, NavHost inside `main`, several providers at once with capability-driven UI, full localization,
   merged search, app-wide queues). Remaining: user confirms the other "[proposed]" rules in
   `ARCHITECTURE.md` / `DESIGN_GUIDE.md`; then align `docs/multi-source-player.md` with them (`TrackRef`,
   queue entry ids, write-through persistence, providers simultaneously).
4. **Design canvas** like Vantage's `Design/` (React artboards in an HTML canvas, PDF export) — plan in
   `tasks/todo.md`. Reuse `design-canvas.jsx` / `tweaks-panel.jsx` from `~/Projects/Tegro/Vantage/Design/`.
5. Approve / adjust `docs/multi-source-player.md`.
6. Phase 0 — fork identity: `applicationId`, app name, signing key (secrets in GitHub Actions, never in the
   repo), updater → `tegro-org/YAMP`, CI for `android/`.

## Notes

- Upstream reviews: `kb/upstream-tracking.md` (baseline `fac6af7`).
- User's setup (head unit, Jellyfin/Airsonic, library layout): `kb/environment.md`.
- `arkadym/YAMP` (an earlier GitHub fork) is superseded; the user may delete/archive it.
- The user dislikes being rushed: answer the question, don't append "shall I start…?" (`tasks/lessons.md`).
