# User setup & environment

## Devices

- **Car head unit:** Android 9 (API 28), 1920×720. Details of the unit are in the user's Personal repo
  (`~/Projects/Arkady/Personal/Головное устройство/kb`). Upstream `minSdk` = 26, so it is supported.
- **Split screen** is used sometimes (navigator + music): the unit divides the screen **1 : 2**, the left pane
  is the slim one → about 640×720 and 1280×720 (exact sizes and which side the player sits on are not
  measured yet). YAMP must work in both panes; the wide pane equals the app's 1280×720 car grid.
- The head unit runs a per-app DPI tool (MonjaroDPI / "Mdpi") that force-stops and restarts apps to apply
  their DPI — see [head-unit-dpi-app.md](head-unit-dpi-app.md).
- Phone(s): Android — a first-class target (D23), also used for sign-in confirmation (Yandex device flow).

## Removable storage on the head unit

- A USB stick mounts **seconds after a cold boot**; an app that scans or validates its queue/library at start sees
  no volume and must not conclude anything from that (D47). Musicolet lost both its queue and its library this way
  (2026-11-11).

## Music servers

- **Jellyfin** — primary self-hosted backend for YAMP. Must be reached over **HTTPS** with a certificate
  trusted by Android (no cleartext, no self-signed). Server URL/credentials are never committed.
- **Airsonic-Advanced** — serves the **same music folder on disk** as Jellyfin. Used today only as a
  workaround for a second queue (Ultrasonic on Airsonic when driving with the wife, Symfonium on Jellyfin
  when driving alone). Expected to be dropped once YAMP has multiple queues.
- Music folder on the server (Raspberry Pi 5): `/services/_data/music`.

## Music library layout

One music library. The root is a flat list of ~30+ hand-named top folders that do **not** match tags:

- artist: `Pink Floyd`, `The Beatles`, `Louna`
- discography: `Fleur - Дискография (2000-2026)`, `Lindemann - Discography - 2015 - 2026`
- format: `Metallica (mp3)`, `Аквариум и БГ (mp3)`
- split by kind: `Мельница & Хелависа - альбомы` / `Мельница & Хелависа - концерты`
- compilations: `RC - Sorted` (e.g. "Romantic Collection"), `Детские`
- Mixed Latin and Cyrillic names.

Inside: albums, live shows, compilations, sometimes audiobooks.

## Other players tried (lessons for UX)

- **Musicolet** — loved for multiple queues (local files only, closed source).
- **Symfonium** — Jellyfin/Subsonic OK, but folder browsing needs too many taps.
- **Ultrasonic** — old UX; loses the play queue when Android kills the app.

## Repos & access

- Our repo: `https://github.com/tegro-org/YAMP` (public), SSH remote `git@github-arkadym:tegro-org/YAMP.git`.
- `arkadym/YAMP` — earlier GitHub fork created by the user; superseded by `tegro-org/YAMP` (user to delete
  or archive).
- Upstream clones for reading: `~/Projects/Github/YandexMusicLite`, `~/Projects/Github/YML`.
- Android SDK: `~/sdk/android`.
