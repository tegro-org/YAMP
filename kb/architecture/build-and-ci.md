# Build, tests, CI (upstream `fac6af7`)

Gradle project in `android/`. Parent: [ARCHITECTURE.md](../../ARCHITECTURE.md).

**Verified 2026-11-09** (JDK 21, no `local.properties`, no keystore, `ANDROID_HOME` set):
`sh gradlew assembleDebug testDebugUnitTest` → BUILD SUCCESSFUL, 14 min cold (Gradle + dependencies
downloaded), 35 suites / 340 tests, 0 failures; `app-debug.apk` ≈ 32 MB. Plain `testDebugUnitTest` executes
the screenshot tests but does **not compare** them with the goldens; `sh gradlew verifyRoborazziDebug lintDebug`
(the upstream CI command) does → 107/107 screenshots unchanged, lint "No issues found" (2 min warm). So the
goldens render identically on this Linux PC and can serve as the baseline for YAMP changes.

## Build

- AGP 9.5.0, Kotlin 2.4.21 (AGP built-in Kotlin, no `kotlin-android` plugin), KSP 2.3.13, Gradle 9.8.2.
- Modules `:app`, `:baselineprofile`. `org.gradle.configuration-cache=true`, `-Xmx2048m`.
- `:app`: namespace and applicationId **`org.tegro.yamp`** (Phase 0; upstream was `com.korenskoy.yandexmusic` /
  `YandexMusicLite.KorenskoyR`), compileSdk 37, minSdk 26, targetSdk 36, Java 11. **Version from git (D37):**
  `versionName` = tag `vX.Y.Z` → `X.Y.Z`, otherwise `X.Y.Z-dev.N+sha` (`0.0.0-dev…` before the first tag,
  `0.0.0-local` without git); `versionCode` = commit count. ABIs: all four. No flavors.
- BuildConfig: `YANDEX_SALT` (local.properties → env → committed default, not secret),
  `YANDEX_DEVICE_SECRET` (empty → device-code sign-in disabled).
- Signing: `KEYSTORE_FILE` (default `android/release.keystore`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
  `KEY_PASSWORD` from `local.properties` or env. Any task with "Release" (except UnitTest) **fails fast
  without a valid key** (incl. `lintRelease`, baseline profile). Debug builds use the release key if present.
- R8 on release (strips `Log.v/d/i`, keeps DTO/updater/model/player packages). Compose stability config marks
  `data.dto.*` and `domain.model.*` stable. Room schemas exported to `app/schemas/`.
- Baseline profile module (device needed, release key needed); generated profiles committed (still upstream's,
  rewritten to the new package — regenerate when a device is at hand); package `org.tegro.yamp` in
  `BaselineProfileGenerator.kt`.
- Lint: `warningsAsErrors`, `abortOnError`, `lint.xml` ignores version-age checks. No detekt/ktlint.

## Dependencies (main)

Compose BOM 2026.09.00, Navigation 2.10.2, Glance 1.2.0, Coil 3.6.3, Hilt 2.60.1, Retrofit 3.0.0 +
kotlinx-serialization, OkHttp logging 5.5.1, zxing 3.5.3, Media3 1.11.1, coroutines 1.11.0, Room 2.8.5,
DataStore 1.3.0, security-crypto 1.1.0, WorkManager 2.12.0. Tests: JUnit4, Robolectric 4.17, Roborazzi 1.77.0.

## Tests

- **Unit** (`app/src/test`, Robolectric for 13 files): data (device sign-in, crash log, card storage,
  DTO shapes, lossless crypto, encraw, stream variants, playback queue/rules, road reserve, playlist diff,
  updater incl. "token not sent to GitHub"), presentation logic, car layout/dock, display scale, widget.
- **Screenshots**: `ScreenshotTest` (54) + `Screenshots112Test` (53), Robolectric SDK 35, Pixel7 + head-unit
  qualifiers; goldens `app/src/test/screenshots/*.png` (107 files, ~61 MB); threshold 0.5 %.
  `./gradlew recordRoborazziDebug` / `verifyRoborazziDebug`. **Any UI change needs re-recording.**
- **ArchitectureTest**: (1) no `YandexMusicApi` in `presentation/`; (2) only `AccountSessionManager` calls
  `playlistsRepository.resetForAccountChange()`, and `MainViewModel` calls `session.syncAll()`.
- **LayoutSafetyTest** (32 source-text guards): MainScreen bar/dock structure, exact Retrofit signatures,
  `TextUnit(` ban, `app_name == "Yandex Music Lite"`, **`applicationId == "YandexMusicLite.KorenskoyR"`**,
  no literal passwords in build file, updater/player constants, no global cleartext. → must be updated in
  Phase 0 (rename).
- **Instrumented** (20 files, device): Android Auto browse, auth migration, car mode, player engine, playlist
  cache, road fallback, insets, widget… Some **wipe auth storage** — test device only.

## CI (`.github/`, Phase 0; upstream originals in `kb/upstream/github-workflows-reference/`)

- `ci.yml`: push to main / PR → gitleaks; JDK 21; `working-directory: android`; `chmod +x gradlew`
  (committed 100644, no `gradlew.bat`); `verifyRoborazziDebug lintDebug`; screenshot diffs uploaded on failure.
- `release.yml`: tag `v[0-9]*` → keystore from `KEYSTORE_BASE64` (+ `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
  `KEY_PASSWORD`, optional `YANDEX_DEVICE_SECRET`), unit tests, `assembleRelease`, built `versionName` must
  equal the tag, APK content check (no keys inside), GitHub release with one asset `YAMP-<version>.apk`
  (the updater takes the first `*.apk` asset and its `digest`), auto-generated notes.
- dependabot: gradle weekly in `/android` (groups androidx, kotlin), actions monthly.
- Local: `scripts/build-apk.sh` (see below).

## Local builds

`scripts/build-apk.sh [debug|release] [--tests] [--install]` builds the APK and copies it to
`dist/YAMP-<versionName>-<variant>.apk` (git-ignored); `--tests` runs unit tests, screenshot compare and lint
first, `--install` adb-installs it. Release needs the signing key in `android/local.properties` or the
environment.

## Gotchas

- JDK 21 needed (Robolectric SDK 35).
- `compileSdk { version = release(37) }` needs the SDK package **`platforms;android-37.0`** (platforms carry a
  minor version since 36.1; `platforms;android-37` does not exist):
  `sdkmanager --sdk_root=$ANDROID_HOME "platforms;android-37.0"`. Old command-line tools (9.0) print
  "SDK XML version 4" / `package.xml parsing problem` warnings — harmless, the install still works.
- `android/.gitignore` also ignores `CHANGELOG.md`, `PLAN*.md`, `AGENT_MEMORY.md`, `.claude/`, `/release/`.
- Renaming applicationId breaks LayoutSafetyTest, BaselineProfileGenerator and update compatibility.
- Updater accepts an update only with the same signer and a higher versionCode.
