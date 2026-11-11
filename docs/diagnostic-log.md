# Diagnostic log

Status: **approved in discussion 2026-11-11 (D48); implemented on `fix/upstream-1.14-u2`.**

## Purpose

Nobody brings a laptop into the car for `adb logcat`. What the app went through on a ride — stalls, skips,
recovery decisions, audio focus, pauses with their reason, the service being killed by the head unit's DPI tool
— is written to a file on the device and shared from the app with one tap. The in-memory playback journal
(Settings → About → "Журнал воспроизведения") stays as the quick in-app view of the last hour; the file is the
full record and survives process death.

## What changes for the user

- Settings → About → **"Диагностический журнал"** switch. Default **on in debug builds, off in release**
  (`BuildConfig.DEBUG`); the user flips it either way. Off: nothing is written and the files are deleted.
- Next to it **"Поделиться"** sends the log as a text file to any messenger or mail, and **"Очистить"** empties it.
- Nothing else changes; the log costs a few KB per hour of music.

## Technical design

- `data/diagnostics/DiagnosticLog` (`@Singleton`): `event(tag: String, message: String)` appends one line
  `yyyy-MM-dd HH:mm:ss.SSS  TAG  message` to `filesDir/logs/yamp.log` through a single-thread writer
  (`Dispatchers.IO`-backed channel, append + flush per line — write-through, nothing buffered in memory that a
  kill could lose). Rotation at 1 MB: `yamp.log` → `yamp.1.log`, the older one deleted; about a day of driving.
- Enabled flag from `SettingsRepository.diagnosticLog` (DataStore, default `BuildConfig.DEBUG`); `event()` is a
  no-op while off; switching off deletes the files.
- Every line is passed through a **scrubber** before writing: `OAuth …` / `Authorization` values, `__uid=`,
  `sign=`/`ts=` query parameters of stream URLs and anything matching a Yandex token shape are replaced by
  `‹…›`. Secrets must not reach the file even by a careless caller (J2).
- Sources: `PlaybackJournal.record` mirrors every event into the log (`PLAYBACK`); `PlaybackController`
  recovery decisions and link state; `MusicService` create/destroy, focus, session commands; `PlayerRepositoryImpl`
  player errors; `TrackPreloadManager` resolve failures; `GitHubUpdateManager` check/download results; the
  crash handler writes the stack trace head; app start writes a header: version, device, Android version,
  screen metrics and the chosen UI scale.
- Share: `FileProvider` (existing authority used by the updater) → `ACTION_SEND` with `text/plain` and the
  current file plus the rotated one as a stream; "Clear" truncates both.
- A first-run or start-up header line makes the file self-describing when it lands in a chat.

## Affected files

New `data/diagnostics/DiagnosticLog.kt`; `SettingsRepository(+Impl)`, `SettingsViewModel`, `SettingsModel`,
`SettingsSheet` (About page), `MainTabs`; `PlaybackJournal`, `PlaybackController`, `MusicService`,
`PlayerRepositoryImpl`, `YandexMusicApp` (start header, crash hook), `res/xml/file_paths.xml`, strings; About golden.

## Risks

- Disk: bounded at 2 MB. CPU: one line per event, no per-progress-tick lines.
- Privacy: the scrubber is tested (`DiagnosticLogTest`) with a token, a signed stream URL and a uid.
