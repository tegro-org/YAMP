# HANDOFF — 2026-11-09

## State

- `main`: initial import `d221875` + docs commits, pushed up to `8ae0180` (the user pushes; never push
  unasked).
- `android/` = upstream YandexMusicLite `fac6af7` (1.12.2), untouched (see `kb/upstream/README.md`).
  Still upstream identity (`applicationId`, package, updater → KorenskoyR). **No YAMP release before Phase 0.**
- **Build verified** (2026-11-09): `sh gradlew assembleDebug testDebugUnitTest` passes (340 tests);
  `verifyRoborazziDebug lintDebug` passes (107/107 screenshots unchanged, lint clean). See
  `kb/architecture/build-and-ci.md`.
- **Architecture docs:** `ARCHITECTURE.md` — **approved 2026-11-09**, rules A–K binding (D24).
  `DESIGN_GUIDE.md` (Hi-Fi tokens/components, car rules, localization, patterns for new screens) —
  **approved 2026-11-09** (D24). As-is analysis in `kb/architecture/`.
- **Phone is first-class, not a car-only app** (D23): the user likes the current glass look on the phone;
  keep it as the visual reference.

- **Local files are planned as a third provider** (D25, Phase 4d stub in `docs/multi-source-player.md`).
  Design (scanning etc.) is deliberately deferred — don't design it until the user brings it up.

- **Queue loss on the head unit** is partly caused by the per-app DPI tool, which force-stops apps without any
  callback (`kb/head-unit-dpi-app.md`): persistence rules and test cases for Phase 1 are there.

## How we got here (context for a fresh session)

The user found YandexMusicLite and its fork YML (Ozo-bro), asked for a privacy audit
(`kb/privacy-audit.md`) and whether more backends (Jellyfin) could be added. The design discussion produced
`docs/multi-source-player.md` (phases; **approved 2026-11-09**, D28) and the decisions in
`kb/decisions.md`. The doc was first written against YML, then moved here and re-based on upstream
`fac6af7` ("Current state" section re-checked).

## Next steps (in this order, user sets the pace)

1. ~~Initial commit + push~~ (done, `d221875`).
2. ~~Verify the build~~ (done; `gradlew` is not executable — run `sh gradlew`), incl. screenshot
   compare + lint.
3. **Architecture drafts reviewed**; Q1–Q7 decided (`kb/decisions.md` D16–D22: `org.tegro.yamp`, gradual
   modules, NavHost inside `main`, several providers at once with capability-driven UI, full localization,
   merged search, app-wide queues); `ARCHITECTURE.md` and `DESIGN_GUIDE.md` approved;
   `docs/multi-source-player.md` aligned with them (2026-11-09). Queue switch in the car decided: popup, 2 taps
   (D26); ids are `<provider>:<kind>:<id>` for everything (D27). No open questions left in the doc except
   the deferred local-files design.
4. **Design canvas** — in progress on branch `feature/design-canvas`: `Design/YAMP.html` (see
   `Design/README.md` to view). Style approved by the user. Done: foundations, as-built reference, queue
   switcher, folder browser (phone + head unit), split-screen panes (1 : 2 → 1280×720 and 640×720).
   Also drawn (2026-11-09): sources + Jellyfin sign-in, merged search, Home / Library / tabs per
   providers, mix builder, list states; PDF export (`Design/export-pdf.sh`). **Open design choices waiting
   for the user:** see "Canvas choices to confirm" below. Checklist in `tasks/todo.md`.
5. ~~Approve `docs/multi-source-player.md`~~ (approved 2026-11-09, D28).
6. Phase 0 — fork identity: `applicationId`, app name, signing key (secrets in GitHub Actions, never in the
   repo), updater → `tegro-org/YAMP`, CI for `android/`.

## Canvas choices to confirm (from the 2026-11-09 screens)

- **Home with both providers:** today's My Wave stays the hero, Jellyfin sections (folders, recently added,
  start a radio) follow below; first tab is "Wave" while My Wave exists, else "Home".
- **Library:** rows by kind with provider badges only when mixed (not grouped by provider); one "Liked" row.
- **Library Play / Shuffle and the car "Liked" tile** with both providers: the first source in the Sources
  list (decided, D30). Queues may mix providers; favourites are not merged.
- **Car tiles** (decided, D33): Folders, My Wave, Liked, Mixes, Radio; as many as fit, the row scrolls; no
  Offline tile and no "Downloaded" row in Library — downloaded music is under Settings → Storage →
  "Available offline", and the weak-signal pill / offline list state plays it.
- **Search:** results interleaved by rank across providers, no provider filter, badges only in mixed lists.
- **Sign-in:** Sources is a Settings sub-page; http address offers a one-tap "Change to https://"; Jellyfin
  Quick Connect is an alternative sign-in (decided, D31; drawn: phone opens on the password form, the
  head unit on Quick Connect).
- **Mixes:** play continues the mix's existing queue; re-run replaces it (decided, D29; drawn). Default mix
  length 200 tracks. Radio: one radio queue at a time, "Keep" saves it as a named queue; unsaved mixes share one "Last mix"
  queue (decided, D35). Open: does a re-run ask for confirmation everywhere or only when that queue is
  playing (drawn: only then)?
- **"Parked" vs "driving"** (decided, D32): "driving" = car mode (no typing needed), "parked" = the normal
  landscape UI; no detection, nothing blocked; car mode has a search button. The user will test on the unit.
  (The "Only while parked" chip is removed from the canvas.)

- **Offline:** "Play downloaded" in an offline list plays the downloaded part of that list (decided, D34). Storage is drawn as its own Settings section; today its rows sit under Playback.

## Notes

- Upstream reviews: `kb/upstream-tracking.md` (baseline `fac6af7`).
- User's setup (head unit, Jellyfin/Airsonic, library layout): `kb/environment.md`.
- `arkadym/YAMP` (an earlier GitHub fork) is superseded; the user may delete/archive it.
- The user dislikes being rushed: answer the question, don't append "shall I start…?" (`tasks/lessons.md`).
