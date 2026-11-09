# As-is: presentation & design system (upstream `fac6af7`)

Paths relative to `android/app/src/main/java/com/korenskoy/yandexmusic/`. Parent:
[DESIGN_GUIDE.md](../../DESIGN_GUIDE.md), [ARCHITECTURE.md](../../ARCHITECTURE.md).

## Navigation & screen structure

- Navigation Compose only at top level (`MainActivity.AppNavigation`): routes `splash`, `auth`, `main`.
- Inside `main` **no NavHost**: `MainScreen.kt` builds the frame by hand — `MainTab` enum (HOME, SEARCH,
  LIBRARY, RADIO, CAR) in `AnimatedContent` with `SaveableStateProvider`; artist/album pages are a
  **ViewModel-held stack** (`CatalogViewModel._stack`); full player is an `AnimatedVisibility` overlay;
  library sub-pages via `rememberSaveable`; Back via `BackHandler` + predictive-back helper.
- Car mode is a tab; it hides backdrop, mini-player and bar.
- Dialogs/sheets: boolean state in MainScreen, rendered by `*Host` composables (`MainDialogs.kt`). No
  Material `ModalBottomSheet`: full-screen `Dialog` sheets (`SettingsSheet`, `WaveTune`) or in-tree overlay
  (`PlayerQueueSheet`).

## ViewModels & state

- All `@HiltViewModel`; `MainViewModel` created in `AppNavigation`; others `hiltViewModel()` defaults on
  `MainScreen` (scoped to `main` entry) — MainScreen holds 8 VMs + all dialog state.
- Dominant pattern: **many separate StateFlows**, often re-exposing repository/controller flows. Exceptions:
  `SettingsUiState`, `CatalogPageState`, sealed `PhoneSignInState`, generic `LoadState<T>`
  {Loading, Failed, Empty, Ready} (`hifi/HiFiStates.kt`, "one vocabulary for every list").
- Events: `SharedFlow<String>` → one `SnackbarHostState`. UI calls VM methods directly.
- Player state bundled once into `@Immutable NowPlaying` (`MainTabs.kt:31-43`); **progress passed as
  `() -> Long`, read in draw phase**.
- CompositionLocals: `LocalStreamQuality`, `LocalLinkState`, `LocalTrackMenu`, `LocalHiFiLite`,
  `LocalArtworkTint`.

## Screens

Splash; Auth (WebView) + PhoneSignIn (device code); MainScreen frame; Home ("Моя волна", knob in spectrum
ring, never scrolls); Search; Library ("Моя музыка"); Playlist (drag reorder, TalkBack actions); Radio (FM
dial); Car ("В авто"); Catalog (artist/album); FullScreenPlayer (+ controls, queue sheet, quality dialog);
components (MiniPlayer 62 dp capsule, floating bar/dock, TrackItem, context menu, settings, wave tuning,
playlist dialogs, download modal); account dialogs; update dialog; crash log; Glance widget (own colours).
No lyrics screen.

## Design system ("Hi-Fi")

- `ui/theme` is **vestigial** (dark-only, `MaterialTheme.` used 0 times in presentation, dead tokens).
- Real tokens in `presentation/hifi/HiFi.kt` — `object HiFi`:
  - base `Ground #05060A`, `Accent #0A6CFF`
  - text: Primary white, Secondary white α0.66, Tertiary α0.55
  - glass: `Hairline` α0.10, `GlassFill` α0.08, `GlassBorder` α0.16
  - surfaces: `BarFill #B81C1E26`, `Scrim #E605060A`, `SheetFill #10121A`, `Placeholder #1C1F28`
  - status: `Danger #FF6B6B`, `Live #FF4D4D`, `Success #6FD08C`, `Warning #FFB547`, `Love #FF3B5C`
  - metal: `OnMetal`, `Anodized*`, `Steel*`, `Paper`, gradients `MetalSweep`, `KnobFace`, …
- `HiFiMotion`: 120 / 220 / 350 / 700 ms, easing `CubicBezier(0.2,0,0,1)`, spring 0.86.
- `HiFiType` (`HiFiControls.kt:72-79`): LargeTitle 30/36 Bold; SectionCaps 13/18 ExtraBold +0.1em caps;
  RowTitle 17/22 SemiBold; CardTitle 15/19 Bold; Body 15/20; Caption 13/17. Dialog title 20 ExtraBold.
- Look: dark ground, glass, anodized metal, steel rims, one blue accent, **app-wide tint from the cover**
  (`HiFiTint.kt`, Palette, normalized, 700 ms).
- Backdrop: ground + tint glow + vignette + grain; live blurred cover only non-lite **and SDK ≥ 31**.
- **Lite mode** (`HiFi.kt:96-117`): SDK < 29, low-RAM, power save, animator scale 0, or setting. **On the
  Android 9 head unit lite is on by default** (no blur/grain/shared-element/stagger).
- Primitives: `HiFiKnob`, `PlayPauseGlyph`, `SpectrumRing`; modifiers `hifiGlass`, `hifiSoftShadow`,
  `hifiRecessed`, `hifiAnodized`, `hifiMachined`, `hifiPress` (scale 0.97 + haptic, **no ripple**);
  buttons `MetalPillButton` (primary 52 dp), `GlassPillButton` (52), `GlassCircleButton` (40),
  `HiFiTextAction` (44); `HiFiFilterPill`, `IconPlaque`, `HiFiSegmented`, `HiFiSwitch`, `HiFiSlider`,
  `HiFiLargeTitle`, `HiFiSectionHeader`, `HiFiWideHeader`; states `HiFiEmptyState`, `HiFiErrorState`,
  `HiFiSkeletonRows`, `HiFiActivityIndicator`, `HiFiSnackbar`, `HiFiProgressBar`; dialogs
  `HiFiAlertDialog` + `HiFiDialogAction(Primary|Danger|Quiet)` (container `#15171F`, radius 28).
- Shapes/spacing: **no tokens**; local constants (bar 32, mini-player 31, row 12, cover 10, dialog 28);
  16 dp gutter, 20 list padding, 12 gaps. Icons: Material `Icons.Rounded.*`.
- Hard-coding: `HiFi.*` 174 refs vs **230 ad-hoc `Color.White.copy(alpha)`**; raw `Color(0x…)` in ~15 files.

## Adaptive layout

- Phone vs landscape vs head unit decided by **orientation only** + thresholds (`heightDp < 480` short;
  dock tab width; `landscapeColumns` = width/210 clamped 4..6). No size classes.
- Portrait: MiniPlayer + `FloatingGlassBottomBar`; landscape: one `FloatingGlassDock` (tabs + mini player).
- `utils/DisplayScale.kt`: auto UI scale = physical dpi ÷ reported dpi (when they differ ≥15 %), clamped
  0.5..2.0, manual override; applied as overridden `LocalDensity` → **every dp/sp is scaled**.
- **CarScreen**: designed on a **1280×720 dp** grid (portrait 600×1200); `carScaleOf` clamped 0.55..2;
  all sizes through `Scale u(...)`. Landscape split: driver panel `u(652)` + far column (cover ≤300, "next"
  row), mirrored by `steeringWheelOnLeft`. Sizes: play knob 176/144, prev/next 96/80, like/dislike 72/60,
  dock tiles 96/88, pills 56, title 56/44 sp. `keepScreenOn`, swipe prev/next (raw px threshold), double-tap.
- Tests: `CarLayoutTest`, `LandscapeDockTest` (qualifiers `w1280dp-h720dp-land-mdpi`,
  `w1024dp-h600dp-land-mdpi`, phone-on-side), `ScreenshotTest`, `DisplayScaleTest`; **no 1920×720 case**.

## Strings

~445 Russian strings in `res/values/strings.xml`, no other locales (Russian-only by design). Keys
`<screen>_<translit>`, `cd_*`. Plurals via `russianQuantityString(count, one, few, many)`; one place uses
`pluralStringResource` (inconsistent). ViewModels emit ready `String`s.

## Accessibility

`contentDescription` on clickable container, `Role.Button`, 88 semantics uses, TalkBack reorder. Phone min
≈44 dp but `GlassCircleButton` and TrackItem "⋮" are 40 dp. Car targets 56–176 dp on the design grid but
scale down to ×0.55 (56 → ~31 dp) on small/dense panels.

## Smells

Duplicate/contradicting `ui/theme`; ad-hoc alphas; no spacing/shape tokens; widget palette separate; two
plural mechanisms; VMs emit display strings; orientation-only breakpoints; many inline FQNs; MainScreen
god-composable (8 VMs, all dialogs, no inner NavHost); car swipe threshold in raw px;
`hifiSoftShadow` doc says "skipped in lite" but isn't.
