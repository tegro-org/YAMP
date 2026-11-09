# YAMP — project instructions

The global rules in `~/CLAUDE.md` apply (git, feature workflow, chat style, KB). This file adds what is
specific to YAMP.

## Read first, every session

1. `HANDOFF.md` — current state and next steps.
2. `tasks/lessons.md` — mistakes not to repeat.
3. `kb/README.md` — KB index; open the section you are about to touch.

## What this is

YAMP ("Yet Another Music Player", a nod to YML) is a **hard fork** of
[KorenskoyR/YandexMusicLite](https://github.com/KorenskoyR/YandexMusicLite) (GPL-3.0), being turned into a
multi-source Android player: Yandex Music + Jellyfin, multiple persistent queues, radio/mixes, folder
browsing; phone and car head unit are both first-class (D23). Product design: `docs/multi-source-player.md`.

## Repo layout

| Path | What |
|---|---|
| `android/` | The Gradle project (imported from upstream; build from here) |
| `docs/` | Feature design docs (`docs/<feature-slug>.md`), approved before code |
| `kb/` | Knowledge base (tree, index in `kb/README.md`) |
| `kb/upstream/` | Upstream reference material (their README, CI workflows) — not used by our build |
| `tasks/` | `todo.md`, `lessons.md` |

## Upstream policy

- No merges from upstream, ever. Upstream changes are **reviewed and ported by hand** (copy for Yandex
  API fixes, reimplement for UI/features, or skip). Every review is recorded in `kb/upstream-tracking.md`
  starting from the last reviewed commit.
- Upstream clone for reading: `~/Projects/Github/YandexMusicLite` (`git pull` there, never commit).
- Keep `LICENSE` (GPL-3.0) and the attribution in `README.md`.

## Git

- Remote `origin` = `git@github-arkadym:tegro-org/YAMP.git` (SSH host alias `github-arkadym` in
  `~/.ssh/config`; plain `git@github.com:` has no access).
- The repo is **public**: never commit secrets, keystores, tokens, server URLs/credentials of the user's
  Jellyfin.

## Working with the user

- Design/requirements discussions: answer what was asked, don't push toward the next step; the user sets
  the pace.
- Language of docs and code comments: English.
