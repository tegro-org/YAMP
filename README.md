# YAMP — Yet Another Music Player

Android music player for Yandex Music and a self-hosted Jellyfin server, built for the car: multiple
persistent queues, radio and mixes, fast folder browsing.

**Status:** early — the code in `android/` is still the imported upstream app; see `HANDOFF.md` and
`docs/multi-source-player.md`.

## Origin and license

YAMP is a fork of [Yandex Music Lite](https://github.com/KorenskoyR/YandexMusicLite) by KorenskoyR,
imported at upstream commit `fac6af78faec620ae39ef9f2f20e37c39ba1c220` (1.12.2, 2026-11-09).
Ideas were also taken from [YML](https://github.com/Ozo-bro/YML) by Ozo-bro.

Licensed under the GNU General Public License v3.0 — see `LICENSE`. All trademarks and audio content
belong to their respective owners.

## Build

```sh
cd android
./gradlew assembleDebug
```
