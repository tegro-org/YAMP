# YAMP Design Guide

Status: **approved** (2026-11-09). YAMP keeps upstream's "Hi-Fi" visual language; this guide
codifies it and adds rules for new screens (queue switcher, folder browser, mix builder, provider sign-in).
As-is details and token values: [kb/architecture/as-is-ui.md](kb/architecture/as-is-ui.md).
Code architecture: [ARCHITECTURE.md](ARCHITECTURE.md).

## 1. Visual language

Dark ground, glass, anodized metal and steel rims; one blue accent (`#0A6CFF`); an app-wide tint taken from
the current cover. Dark-only. No Material ripples or tonal surfaces — presses scale + haptic (`hifiPress`).

**Phone and car are both first-class** (D23): YAMP is not a car-only app. The current phone look — modern
glass, iOS-like buttons — is the visual reference and must not degrade; every new screen is designed for the
phone (portrait) **and** the head unit, and car-driven changes to shared components are checked on the phone.

## 2. Tokens — single source

- **Colours, motion, type come from `presentation/hifi`**: `HiFi.*`, `HiFiMotion.*`, `HiFiType.*`.
  `ui/theme` and `MaterialTheme` are not used for styling.
- **No new raw colours.** No new `Color(0x…)` or ad-hoc `Color.White.copy(alpha = …)` in new/changed code;
  if a needed value is missing, add a named token to `HiFi` first.
- **Add spacing & shape tokens**: `HiFiSpace` (4, 8, 12, 16 gutter, 20 list, 24, 32) and
  `HiFiShape` (row 12, cover 10, pill/circle, dialog 28, bar 32) — collected from today's literals; new code
  uses them, old code migrates when touched.
- Type scale (`HiFiType`): LargeTitle 30/36 Bold · SectionCaps 13/18 ExtraBold caps · RowTitle 17/22 SemiBold ·
  CardTitle 15/19 Bold · Body 15/20 · Caption 13/17. `TextUnit(` constructor is banned (test).

## 3. Components — reuse before creating

| Need | Use |
|---|---|
| Primary / secondary action | `MetalPillButton` / `GlassPillButton` (52 dp) |
| Icon button | `GlassCircleButton` (phone ≥ 44 dp — see §6), `HiFiKnob` for transport |
| Text action | `HiFiTextAction` |
| Lists | `TrackItem` row; `LoadState<T>` + `HiFiSkeletonRows` / `HiFiEmptyState` / `HiFiErrorState(onRetry)` |
| Filters / modes | `HiFiFilterPill`, `HiFiSegmented`, `HiFiSwitch`, `HiFiSlider` |
| Headers | `HiFiLargeTitle` (collapsing), `HiFiSectionHeader`, `HiFiWideHeader` (landscape) |
| Dialog / confirm | `HiFiAlertDialog` + `HiFiDialogAction(Primary/Danger/Quiet)` |
| Sheet / panel | full-screen `Dialog` sheet (phone) → centred/side panel (landscape), like `SettingsSheet`/`WaveTune` |
| Message | `HiFiSnackbar` only (one host) |
| Icons | Material `Icons.Rounded.*` |

A new primitive goes into `presentation/hifi/` with a screenshot test, not into a feature package.

## 4. Layout & form factors

- Breakpoints today are **orientation-based** (portrait phone vs landscape dock/head unit); keep that for now.
- Portrait: content + MiniPlayer + `FloatingGlassBottomBar`, 16 dp gutter. Landscape: `FloatingGlassDock`,
  side insets for cutout/nav bars, bottom padding `DockClearance`.
- `DisplayScale` multiplies every dp/sp — never compensate for it manually.
- Lite mode (`LocalHiFiLite`) gates blur, grain, shared-element and stagger. **The Android 9 head unit runs in
  lite mode** — design for the glow-only backdrop; nothing may depend on blur. Expensive effects must check
  `LocalHiFiLite` (fix `hifiSoftShadow` when touched).

## 5. Car mode rules

- Car screens are laid out on the **1280×720 dp grid** with `Scale u(...)` (portrait 600×1200) and respect
  `steeringWheelOnLeft` (driver panel on the driver's side).
- **Touch targets ≥ 56 dp after scaling**: clamp car control sizes so scale ≤ 1 never yields
  < 56 dp (today 56 → ~31 dp possible at ×0.55).
- **Tap budget** (D12): from car home → play a pinned/recent folder in **1 tap**; any folder in **≤ 3 taps**;
  switch queue in **2 taps** via the queue popup (D26 — done once per trip, not repeatedly). Every new car
  flow states its tap count in its design doc.
- Big tiles, short labels (one line, ellipsized), covers over text. No text input while driving except the
  optional filter box (search is a parked-car action).
- Add a **1920×720** screenshot qualifier for the user's head unit (alongside `w1280dp-h720dp` and
  `w1024dp-h600dp`).

## 6. Accessibility

- `contentDescription` on the clickable container (inner icon `null`), `Role.Button` on custom clickables,
  TalkBack custom actions for reorder.
- Phone touch targets ≥ 44 dp: raise `GlassCircleButton` default and TrackItem "⋮" from 40 → 44 dp (when
  those components are next touched; needs re-recorded screenshots).
- Text on dark ground at α ≥ 0.55 white; no information by colour alone (e.g. provider badge has an icon/letter).

## 7. Text & language

- All UI text in resources: `values/` English, `values-ru/` Russian (ARCHITECTURE section K). Semantic keys
  `<screen>_<meaning>`; content descriptions `cd_*`.
- Plurals via standard `plurals` + `pluralStringResource`; `russianQuantityString` is retired during the string
  migration.
- Layouts must survive longer translations: no fixed-width text containers; one-line labels ellipsize; car
  tiles are tested in both languages (screenshot tests per locale for car screens).
- ViewModels don't build display strings (ARCHITECTURE D2).

## 8. Patterns for the new YAMP screens

- **Queue switcher** (D8): queue button (glass circle with queue glyph + small count) on Home, player and car
  dock → popup panel (phone: sheet; landscape/car: side panel). Rows: queue name, current track, progress bar;
  active queue anodized. "+" new queue; long-press → rename / clear / delete (`HiFiAlertDialog` for delete).
- **Folder browser** (D12): breadcrumb row (each level tappable), filter field, list of folder rows (cover or
  folder glyph, name, item count) then tracks (`TrackItem`); per-folder actions Play / Shuffle / Radio /
  Add to queue. Car: tiles grid (`landscapeColumns`), A–Z rail for Latin + Cyrillic, Pinned & Recent rows on
  car home.
- **Provider badge**: small letter/icon plaque (Y / J) on rows only when the list mixes providers.
- **Provider sign-in** (Jellyfin): server URL (https enforced, inline error), user, password →
  `MetalPillButton`. Password field never prefilled; nothing persisted except the token.
- **Mix builder**: sources as removable pills, rules as `HiFiSwitch`/`HiFiSlider` rows, primary
  "Build mix" — phone sheet / landscape panel; not available while driving (car shows saved mixes only).

## 9. Design ↔ code tracking

Any UI change that diverges from this guide or from mockups in `Design/` must be recorded in
`kb/design-deviations.md` (create on first entry) with the reason — so "does the app follow the design?" can
always be answered.
