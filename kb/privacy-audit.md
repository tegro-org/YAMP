# Privacy audit (2026-11-08)

Audited: Ozo-bro/YML v2.0.53 source + the user's APK `YML-2.0.53.apk`. Re-checked the relevant points
against upstream YandexMusicLite `fac6af7` on 2026-11-09. Re-audit after any change to auth, networking
or the updater.

## How it works

- **Login:** the user signs in on Yandex's own page (`oauth.yandex.ru/authorize?response_type=token`) in
  a WebView, or (upstream 1.12.0+) via OAuth device flow (`data/auth/DeviceSignIn.kt`: code shown on the
  device, confirmed at ya.ru/device). The app never sees the password, only the OAuth token, and accepts
  it only from `https` Yandex hosts (`isTrustedYandexUrl`).
- **client_id** `23cabbbdc6cd418abb4b39c32c41195d` and User-Agent are those of the **official Yandex Music
  Android app**. The token has full Yandex Music scope. ToS risk to the account is low but non-zero.
- **Token storage:** `data/security/KeystoreStringPreferences.kt` (Android Keystore AES key, device-bound).
  Excluded from Auto Backup and device transfer (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`).
  Fallback to plain SharedPreferences only if Keystore is broken on the device.
- **Logs:** OkHttp logging only in debug, `Authorization`/`Cookie` redacted; crash log strips tokens
  (`data/crash/CrashLog.kt`).
- **Hosts contacted:** `api.music.yandex.net`, `oauth.yandex.ru`, `login.yandex.ru`, `avatars.yandex.net`,
  Yandex storage hosts for audio, `api.github.com` / `github.com` (updater). No analytics or trackers.
  Cleartext blocked except a few HTTP-only FM radio domains (`res/xml/network_security_config.xml`).
- **Updater:** GitHub Releases of a hard-coded repo; https + host allow-list
  (`github.com`, `*.githubusercontent.com`), SHA-256 from the release asset digest, signature check vs the
  installed app, then the system installer. Needs `REQUEST_INSTALL_PACKAGES`.

## Findings

| # | Finding | Status |
|---|---|---|
| 1 | Shared OkHttp client sent `Authorization: OAuth <token>` to `api.github.com`/`github.com` on update checks | **Fixed upstream 1.9.4** (`@UpdateClient` without `AuthInterceptor`); present in YML 2.0.53 |
| 2 | Impersonates the official app (client_id, User-Agent) | Accepted risk; inherent to unofficial clients |
| 3 | Self-update trusts whoever controls the release repo | For YAMP: `tegro-org/YAMP` — our own key and repo (Phase 0) |

## APK verification method (for reference)

The user's APK SHA-256 `98b0d3c0…6c6b30da97` matched the GitHub release asset digest of YML v2.0.53
(built by CI from the tag); hosts found in the dex strings matched the source; native libs were only
androidx (`libandroidx.graphics.path.so`, `libdatastore_shared_counter.so`).
