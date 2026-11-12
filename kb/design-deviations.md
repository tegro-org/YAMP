# Design ↔ code deviations

Where the app departs from `DESIGN_GUIDE.md` or the canvas in `Design/`, and why (DESIGN_GUIDE §9).

| Date | Screen | Canvas | Code | Why |
|---|---|---|---|---|
| 2026-11-12 | Queue switcher (phone) | "New queue" pill floats over the list's bottom | The pill sits below the list | Nothing is hidden under it; the list scrolls above it |
| 2026-11-12 | Queue switcher rows | Non-active progress bar: 55 % white fill | `HiFiProgressBar` at 70 % alpha | Reuse of the existing component (§3) |
| 2026-11-12 | Album / artist pages | List-level "Add to queue…" next to Play / Shuffle | A ⋮ button in the top row next to Back (landscape: `HiFiWideHeader.trailing`); playlists use their existing ⋮ menu | A third button beside Play / Shuffle clipped "Перемешать" on a phone |
| 2026-11-12 | Queue menu (long-press) | A second bottom sheet | `HiFiSheetPanel` with wrap height; in landscape a side panel on the switcher's side | One container for every sheet/panel (§3) |
| 2026-11-12 | Car header pill | Always the active queue's name | "Queues" when nothing is active | There is no name to show |
| 2026-11-12 | Queue menu | "Save as playlist" row | Not present | Deferred to Phase 5 (D43) |
