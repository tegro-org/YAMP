# YAMP — Yet Another Music Player

A multi-source music player for Android, built for car head units and Android Auto as much as for the phone: your
own music first — folders on the device, an SD card or a USB stick, and your self-hosted Jellyfin server — with
Yandex Music as one more, optional source. Multiple persistent queues, radio and mixes, fast folder browsing.

The Yandex Music integration is **unofficial**: it uses the official app's client id and request signing and may
break whenever Yandex changes its API. It needs a Yandex account with an active Yandex Plus; YAMP does not sell,
resell or unlock anything.

## What YAMP is, and how it differs from its origin

YAMP started as a hard fork of [Yandex Music Lite](https://github.com/KorenskoyR/YandexMusicLite) and is no longer
the same app. Upstream is a player for one service; YAMP is a player with several sources.

**Key changes**

- **Queues and providers.** The core of the player was rewritten, not patched: several persistent queues, each
  bound to one account, and a provider layer (This device, Jellyfin, Yandex Music) behind one neutral track model.
  A new source is a provider, not a branch through the player.
- **Sources.** Folders on the device, an SD card or a USB stick; a Jellyfin server (with Quick Connect and
  conversion rules); Yandex Music as one optional source. Several accounts at once, with an account order.
  A first run offers This device and Jellyfin; Yandex Music is added later from Settings → Accounts.
- **Radio** (Yandex stations and waves, a radio queue from a seed, FM), **folder browsing,** Android Auto and a car layout that is first-class next to the phone.
- **Tuned for weak head units.** Much of the work is smooth playback on a slow, low-memory car head unit: a small
  fixed audio output buffer, fades and crossfades with their own durations (transition, start/pause, seek), gapless
  and next-track preparation, recovery from a dead or silent link, stream caching and an offline road reserve.
- **Works with the system equalizer.** Tested on a head unit with Poweramp Equalizer (see below). YAMP has no
  equalizer of its own and none is planned.
- **Diagnostics for a car you cannot plug into:** a playback journal and an optional main-thread watchdog.
- **Widgets** (a line, a player and a cover), a per-account Yandex Plus rule, and an in-app updater.

**The providers**

- **This device (local files).** Folders on the phone, an SD card or a USB stick, no account needed. Tags (ID3, Vorbis,
  MP4, old Russian CP1251), embedded covers or `cover.jpg`; MP3, AAC/M4A, FLAC, Opus, Vorbis and WAV. Built for
  media that comes and goes: a stick that is out stays in the Library, dimmed, and **nothing is deleted**; the queue
  skips its tracks, and if the track that was playing is on the missing stick after a restart the queue waits and
  continues when the stick returns. It rescans by itself (after start, when a drive is mounted, once a day) and only
  reads files that changed. On Android 9–10 head units it reads the stick by its path, since those units don't let
  apps use it the usual way.
- **Jellyfin.** Your own server, signed in with a password or Quick Connect; each server and user is its own account
  with its own queues and cache. The Library, folders (breadcrumbs, filter, pins, play a whole folder),
  favourites, playlists and search together with other sources. Quality is per account: files that Android plays are
  streamed as they are, the rest is converted (MP3, or FLAC for ALAC) and played from one full-speed download so
  seeking works. Favourites can be downloaded for offline playback, plays are reported to the server, and the last
  visit stays readable when the server can't be reached.
- **Yandex Music** is the optional one, added from Settings → Accounts (see the note on it above).

**Caching, buffering, offline and the equalizer — against upstream**

- **Buffering policy:** the same as upstream's (copied). YAMP adds to it the output side: both players are resampled
  to the device's mixer rate and the audio output buffer is a fixed 300 ms (Media3's default is 500 ms), so a skip,
  seek or pause can fade the sound that is already buffered instead of cutting it. Two small changes in
  retries: no retry of a download that is being written to a file, and a start that gets no bytes gives up after one
  retry.
- **Caching:** upstream preloads the next track and keeps a reserve of tracks. YAMP keeps both and adds an on-disk
  stream cache (size is a setting) that every provider shares, and a "growing file" for streams that arrive without a
  length (converted Jellyfin tracks): the track is fetched once at full speed and plays from that file, seekable
  once whole.
- **Offline:** the road reserve and downloads of liked tracks are upstream's idea. YAMP makes them per account
  (Yandex likes, Jellyfin favourites), adds the local provider (always offline) and the return from the device's
  own music to the stream as a trial with a back-off. Not taken from upstream (yet): download storage on a USB
  drive and offline collections.
- **Equalizer:** upstream ships its own equalizer next to the system one. YAMP has **none of its own and none is
  planned**: it announces its audio session to the system equalizer (tested with Poweramp Equalizer) and applies
  its fades to the audio itself, so they work even when an equalizer app takes over the player's volume.

**What is still close to upstream**

- **The Yandex provider** sits between the original code and a proper provider: the API client, its data classes,
  request signing, My Wave and the radio stations follow upstream, and Yandex API fixes are ported from it by hand.
  Behind the provider interface it is separate from the rest of the player, but its insides are not yet rewritten.
- **Buffering for an uneven mobile link** is upstream's work, copied in: the player keeps 2–3 minutes of music
  ahead, starts a track after about 2.5 s of sound (not 1 s) and resumes after a dry spell with 5 s in hand, and
  retries a broken download up to 14 times, failing at once on a real refusal (4xx) so the link is asked again. Most
  of the stutter-free playback on a car's mobile link comes from this.
- **The look of the app** is upstream's: thanks to its author for the design the UI grew from. It already differs
  (new screens, the car layouts, source and queue switchers) and keeps moving away from it.

Upstream changes are reviewed and ported by hand, never merged. The `main` branch mirrors upstream; the app lives
on `yamp`.

## Origin and license

YAMP is a fork of [Yandex Music Lite](https://github.com/KorenskoyR/YandexMusicLite) by KorenskoyR,
imported at upstream commit `fac6af78faec620ae39ef9f2f20e37c39ba1c220` (1.12.2, 2026-11-09).
Ideas were also taken from [YML](https://github.com/Ozo-bro/YML) by Ozo-bro.

Licensed under the GNU General Public License v3.0 — see `LICENSE`. All trademarks and audio content
belong to their respective owners.

## Head units with Poweramp Equalizer

YAMP is tested with the system equalizer, Poweramp Equalizer on a head unit. If it runs there, change two of its per-app options for YAMP (Poweramp Equalizer →
Known Players → YAMP); the other options can stay as they are:

- **«Затухание» (Fade): off.** With it on, the equalizer briefly takes the sound focus whenever a stream changes,
  which pauses YAMP on some head units.
- **«Без DVC»: on.** With it off, the equalizer takes over the volume of YAMP's audio session, so YAMP's own
  fades, crossfades and skips are not applied and the previous track can be heard over the next one.

YAMP shows the same hint in Settings → Playback when Poweramp Equalizer is installed.

## Build

```sh
./gradlew assembleDebug          # debug APK → app/build/outputs/apk/debug/
./gradlew assembleRelease        # needs the signing key in local.properties (never committed)
./gradlew verifyRoborazziDebug lintDebug   # tests, screenshot compare, lint
```

Versions come from git: a build on tag `vX.Y.Z` is `X.Y.Z`, any other build is `X.Y.Z-dev.N+sha`.
Releases are built by GitHub Actions from a pushed `v*` tag; the app updates itself from them.
