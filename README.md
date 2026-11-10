<p align="center"><img src="Design/brand/readme-banner.png" width="560" alt="YAMP — Yet Another Music Player"></p>

# YAMP — Yet Another Music Player

Android music player for Yandex Music and a self-hosted Jellyfin server, for the phone and the car head
unit: multiple persistent queues, radio and mixes, fast folder browsing.

**Status:** early — the app is the imported upstream player under YAMP's own identity (`org.tegro.yamp`);
the multi-source features are being designed and built, see `HANDOFF.md`, `docs/multi-source-player.md` and
the design canvas in `Design/`.

## Origin and license

YAMP is a fork of [Yandex Music Lite](https://github.com/KorenskoyR/YandexMusicLite) by KorenskoyR,
imported at upstream commit `fac6af78faec620ae39ef9f2f20e37c39ba1c220` (1.12.2, 2026-11-09).
Ideas were also taken from [YML](https://github.com/Ozo-bro/YML) by Ozo-bro.

Licensed under the GNU General Public License v3.0 — see `LICENSE`. All trademarks and audio content
belong to their respective owners.

## Build

```sh
scripts/build-apk.sh            # debug APK → dist/YAMP-<version>-debug.apk
scripts/build-apk.sh release    # needs the signing key in android/local.properties (never committed)
cd android && sh gradlew verifyRoborazziDebug lintDebug   # tests, screenshot compare, lint
```

Versions come from git: a build on tag `vX.Y.Z` is `X.Y.Z`, any other build is `X.Y.Z-dev.N+sha`.
Releases are built by GitHub Actions from a pushed `v*` tag; the app updates itself from them.
