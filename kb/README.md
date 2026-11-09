# YAMP Knowledge Base

Root index. Keep it a tree: this index → section docs. Add new docs under a section and link them here.

## Project

- [User setup & environment](environment.md) — head unit, Jellyfin/Airsonic servers, music library
  layout, repos and SSH alias.
- [Product decisions](decisions.md) — what was decided during the design discussion and why.
- [Head-unit DPI app and queue loss](head-unit-dpi-app.md) — how the per-app DPI tool force-stops apps, what
  that means for persistence and start-up, tests to run. **Read before touching queue persistence.**

## Upstream

- [Upstream tracking](upstream-tracking.md) — baseline commit, review log, port/reimplement/skip
  decisions. **Start every upstream review here.**
- [Upstream reference material](upstream/README.md) — their README and CI workflows as imported.

## Security

- [Privacy audit (2026-11-08)](privacy-audit.md) — how auth, token storage, network hosts and the
  updater work; findings and their status.

## Architecture

Rules (both approved 2026-11-09): [ARCHITECTURE.md](../ARCHITECTURE.md), [DESIGN_GUIDE.md](../DESIGN_GUIDE.md).
As-is analysis of the imported code (upstream `fac6af7`):

- [Layers, DI, data layer](architecture/as-is-data.md) — packages, Hilt, networking, repositories,
  persistence, accounts, downloads, concurrency, Yandex leaks.
- [Playback](architecture/as-is-playback.md) — controller/engine split, queue model, persistence/restore,
  stream resolution, media session, recovery, seams for multi-queue and providers.
- [Presentation & design system](architecture/as-is-ui.md) — navigation, VM/state patterns, Hi-Fi tokens and
  primitives, adaptive/car layout, strings, accessibility, smells.
- [Build, tests, CI](architecture/build-and-ci.md) — versions, signing, test suites and guards, CI, gotchas.
