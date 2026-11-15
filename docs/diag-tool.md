# YAMP Diag — on-unit diagnostics collector (debug tool)

Status: implemented (2026-11-15), approved by the user. Branch `feature/diag-tool`.

## Purpose

The head-unit problems (new-track stutter, auto-pause on focus loss) need system-side facts that YAMP cannot read
itself: the audio mixer's configuration and active outputs (`dumpsys media.audio_flinger`), the audio-focus stack
(`dumpsys audio`), media sessions, system properties and the system log. Today that needs a laptop with adb in the
car. **YAMP Diag** is a small separate app, installed only on the user's unit, that collects all of it with one tap
before the ride ends and hands one zip to the share sheet.

It is a **debug tool**: never released, never published, not built by CI. YAMP itself stays free of any shell or adb
capability.

## UX

One screen, phone/head-unit agnostic (plain Material, 720 px tall is enough):

- **Status line:** "adb: connected / not authorised / adb over Wi-Fi is off" with a hint ("switch on adb over Wi-Fi,
  e.g. in MConfig+, then tap Connect and allow debugging on the screen").
- **Connect** (shown until authorised): opens the TCP connection; the system shows "Allow debugging?" once — the
  user ticks "Always allow from this computer".
- **Collect** (main button): runs the fixed command set (below) with a progress list — one row per item, ✓ / ✗ with
  a one-line reason; takes a few seconds.
- **Share**: after a run, opens Android's share sheet with the zip; the user picks the destination (Nextcloud,
  Telegram, files…). The last 5 zips are listed and can be shared again or deleted.

No settings, no free-form command input, no network features.

## What is collected

Fixed list, defined in one place (`DiagCommands`), each with a timeout and an output-size cap:

| File in the zip | Source | Why |
|---|---|---|
| `audio_flinger.txt` | `dumpsys media.audio_flinger` | mixer sample rate, outputs, active tracks, underruns, resampling |
| `audio.txt` | `dumpsys audio` | focus stack (who took focus), stream volumes, ducking |
| `media_session.txt` | `dumpsys media_session` | active sessions, which app is "current" |
| `getprop.txt` | `getprop` (full; small) | vendor audio / ro.product / build properties |
| `logcat.txt` | `logcat -d -v threadtime -T '<now − 2 h>'` (capped at 8 MB) | system-side errors around focus losses and stutters during the ride |
| `yamp_package.txt` | `dumpsys package org.tegro.music.yamp` | installed YAMP version, debuggable flag, permissions |
| `yamp/yamp.log`, `yamp/yamp.1.log` | `run-as org.tegro.music.yamp cat files/logs/yamp.log` (and `.1`) | YAMP's journal (`DiagnosticLog`, `filesDir/logs`) |
| `yamp/crashes.txt` | `run-as … sh -c` loop over `files/crashes/crash_*.txt`, each prefixed `=== <name>` | YAMP's crash reports (`CrashLog`, `filesDir/crashes`) |
| `audio_props.txt` | read locally: `AudioManager.getProperty(PROPERTY_OUTPUT_SAMPLE_RATE / PROPERTY_OUTPUT_FRAMES_PER_BUFFER)`, `AudioTrack.getNativeOutputSampleRate(STREAM_MUSIC)` | the same facts without adb, as a cross-check |
| `manifest.txt` | Diag itself | timestamp, device model, Android version, Diag version, YAMP version, per-item status (ok / failed + reason / truncated) |

`run-as` works only for a **debuggable** YAMP build (the debug APKs the user installs from the share); for a release
YAMP the two `yamp/` items fail and the manifest says so — everything else still works. YAMP's journal is already
redacted of tokens by `DiagnosticLog`/`CrashLog`; Diag reads only those two directories, never `shared_prefs`,
databases or other apps' data.

Zip name: `yamp-diag-<yyyyMMdd-HHmmss>.zip` in `cacheDir/diag/`, shared through a `FileProvider`.

## Technical design

- **Module:** `android/diag` (Gradle project `:diag`), package and applicationId `org.tegro.music.yamp.diag`
  (shares the "music" keyword; unrelated to YAMP's signing — it never installs over anything). minSdk 28, target as
  the app. Kotlin + Compose (same version catalog), no Hilt — one Activity, one ViewModel, plain coroutines.
- **ADB client:** **dadb 2.0.0** (`dev.mobile:dadb`, mobile-dev-inc, Apache-2.0 — compatible with GPL-3.0). Chosen
  over libadb-android because libadb-android is not on Maven Central (JitPack only, and the build forbids extra
  repositories), while dadb is on Central, is pure JVM (java.security RSA, sockets, okio — works on API 28) and needs
  no TLS/Conscrypt. Recorded in `kb/architecture/build-and-ci.md`.
- **Key:** an RSA-2048 key pair generated on first start, kept in the app's private storage (`filesDir`), never
  exported; adbd authorises it once ("Always allow").
- **Connection (port rule):** adb over Wi-Fi listens on 5555 or 7777 depending on how it was switched on. Diag probes
  `127.0.0.1:5555`, then `127.0.0.1:7777`, each with a plain TCP connect and a 300 ms timeout; the first that
  accepts is used and remembered, and is probed again only after 3 failed connections in a row. If neither answers,
  the status says adb over Wi-Fi is off. Diag never switches adb over TCP on itself (that changes system properties);
  the user does it, e.g. in MConfig+. The first connection with a new key waits up to 60 s for "Allow debugging".
- **Commands:** each runs through the `shell:` service with `; echo <marker>$?` appended to get the exit code (a
  non-zero exit marks the item failed with the first line of its output), a watchdog that closes the stream after
  the item's timeout (10 s; `logcat` 20 s) and a cap (8 MB per item, marked truncated).
- **AudioManager props** are read in-process (no adb), so `audio_props.txt` exists even when adb is unavailable.

## Build and exclusion from CI

- CI runs `./gradlew verifyRoborazziDebug lintDebug` (ci.yml) and `testDebugUnitTest` / `assembleRelease`
  (release.yml) at the root — task names that would also run in **every included project**. So `:diag` is
  **included only on request**: in `android/settings.gradle.kts`,
  `if (providers.gradleProperty("yamp.diag").isPresent) include(":diag")`. Without `-Pyamp.diag` Gradle doesn't know
  the module — CI, release builds, `scripts/build-apk.sh` and the local gates are untouched.
- Detekt in CI scans `app/src/main/java` only — unaffected.
- **Build by hand:** `scripts/build-diag.sh` → `./gradlew -Pyamp.diag :diag:assembleDebug` →
  `dist/YAMP-diag-<yyyyMMdd-HHmm>-debug.apk` (debug-signed; Diag has no release build type at all).
- No GitHub release, no upload — the user installs the APK on the unit by hand.

## Security

- The repo is public; Diag is a shell-capable app. Mitigations: debug-only, never published or attached to a release;
  the adb key stays in Diag's private storage; **only the fixed command list** runs — no command input, no intents
  that accept commands, no exported components except the launcher activity and the `FileProvider` (not exported,
  grant-on-share only).
- Diag never grants permissions, changes settings, or touches other apps' data; `run-as` is used only for YAMP's
  `files/logs` and `files/crashes`.
- The zip can contain system logs with personal data (Wi-Fi names, other apps' log lines). It's shared only where
  the user sends it; it's never committed (`dist/` and the scratchpad are outside git).

## Affected files

- New: `android/diag/` (`build.gradle.kts`, `src/main/AndroidManifest.xml`, `DiagActivity.kt`, `DiagViewModel.kt`,
  `AdbSession.kt`, `DiagCommands.kt`, `ZipAssembler.kt`, `res/xml/file_paths.xml`, strings EN/RU), unit tests in
  `android/diag/src/test/`, `scripts/build-diag.sh`.
- Changed: `android/settings.gradle.kts` (conditional include), `android/gradle/libs.versions.toml` (dadb),
  `kb/architecture/build-and-ci.md` (the module, how to build, why it's excluded), `kb/README.md` index if needed,
  `.gitignore` only if a new output path appears.

## Tests

- `DiagCommandsTest`: the command list is the fixed set (no command built from input), each has a timeout and cap.
- `ZipAssemblerTest`: entries named as above, manifest lists every item with status, truncation marked, failed items
  don't abort the zip.
- Manual on the unit: Connect → allow → Collect → Share; with adb over Wi-Fi off → clear status, `audio_props.txt`
  and manifest still produced; with a release YAMP → `yamp/` items marked failed.

## Order of work

1. Module skeleton behind `-Pyamp.diag`, `scripts/build-diag.sh`; confirm `./gradlew verifyRoborazziDebug lintDebug`
   without the property doesn't see `:diag`.
2. ADB session (key, connect, run one command) — verify on the AVD (`adb tcpip 5555` on the emulator).
3. Command set, zip, manifest, share sheet; unit tests.
4. On the unit with the user: one full collect after a ride.

## Out of scope

- YAMP's debug build asking Diag for a snapshot right after a focus loss (later: an explicit broadcast to Diag, only
  from YAMP's signature — design then).
- Any upload, scheduling, or free-form shell.
- A release build type, CI, store publishing.

## Resolved questions

- Port: probe 5555, then 7777 (see the port rule above); the status shows which one connected.
- `logcat`: a time window of the last 2 hours (`-T`), capped at 8 MB.

## As built

- Verified on the AVD (2026-11-15): the emulator's adbd only speaks the emulator pipe, so the test used
  `adb reverse tcp:5555 tcp:5555` (guest 127.0.0.1:5555 → the emulator's host adb port). Every item collected; the
  emulator's adbd doesn't ask for key authorisation, so the "Allow debugging" prompt is to be seen on the unit.
- `yamp/yamp.1.log` fails with "No such file" until YAMP's journal has rotated once — expected.
- Diag has its own fixed versionCode; the APK name carries the build time (`YAMP-diag-<yyyyMMdd-HHmm>-debug.apk`),
  YAMP's build counter isn't used.
