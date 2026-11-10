# Upstream tracking

YAMP is a hard fork: no merges. Upstream changes are reviewed in batches and each one gets a decision.

## Sources

| Source | Repo | Local clone (read-only) | Role |
|---|---|---|---|
| Upstream | [KorenskoyR/YandexMusicLite](https://github.com/KorenskoyR/YandexMusicLite) | `~/Projects/Github/YandexMusicLite` | Code base; Yandex API fixes |
| Idea source | [Ozo-bro/YML](https://github.com/Ozo-bro/YML) | `~/Projects/Github/YML` | Fork of upstream with car-split features |

## Baseline

- **Imported:** upstream `fac6af78faec620ae39ef9f2f20e37c39ba1c220` — "1.12.2: "Далее" in the car as a
  row of covers…" (2026-11-09 18:44 +1000), via `git archive` (no upstream history in our repo).
- **Last reviewed upstream commit:** `fac6af7` (= baseline).
- **YML last looked at:** `f923e6c` (2026-11-09). It was 35 commits ahead / 22 behind upstream (merge-base
  `23ad3f2f`) and lacked the 1.9.4 token-leak fix.

## Porting note (after Phase 0)

Upstream paths `app/src/*/java/com/korenskoy/yandexmusic/` map to `android/app/src/*/java/org/tegro/yamp/`
(same class names; `package`/`import` lines rewritten). Identity strings and constants (app name, applicationId,
updater repo, User-Agent, wake-lock tags, widget actions, version numbers) are never ported. Upstream's version
bumps are irrelevant: YAMP's version comes from git tags (D37).

## How to review

```sh
cd ~/Projects/Github/YandexMusicLite && git pull --ff-only
git log --reverse --format='%h %ci %s' <last-reviewed>..origin/main
git show <sha>          # per commit
```

For each commit decide and log below:

- **port** — copy as directly as possible (Yandex API, stream decryption `Encraw*`, `LosslessSigner`,
  download-info, sign-in, security fixes). These live behind our Yandex provider in nearly the same shape.
- **reimplement** — take the idea, write it our way (player/download bug fixes, UI ideas).
- **skip** — not relevant (their UI polish, website, features we removed), with a one-line reason.

Then update "Last reviewed upstream commit".

## YML features worth considering (not ported)

- Car mode adapted to a split screen (navigator + music), 1920×720, big tiles without captions.
- Playlists (own, liked) directly from car mode.
- Shuffle for any queue except My Wave, remembered between launches.
- Synced lyrics shown instead of the cover when available.
- Offline wave cache with a size limit (0.5–4 GB) — upstream now has its own "road reserve".
- Download whole playlists for offline.
- Lightweight graphics for weak head units (no blur/grain/background animation).

## Review log

| Date | Range | Commit | Decision | Note |
|---|---|---|---|---|
| — | — | — | — | No reviews yet |
