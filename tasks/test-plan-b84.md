# Test plan — b84, Poweramp DVC on vs off

Builds: `YAMP-b84-0.3.0-dev.2+2905ba9-dirty-20261116-2214-debug.apk` (install over the existing YAMP) and
`YAMP-diag-20261116-2214-debug.apk`.

## Fixed for the whole test

**Poweramp Equalizer → Known Players → YAMP**

| Option | Value |
|---|---|
| Enable for app | on |
| Затухание (Fade) | **off** |
| Keep Equalizer Session | on |
| Multisession | on |
| Без DVC | per part (below) |

**YAMP → Settings → Playback**

| Setting | Value |
|---|---|
| Crossfade | on, 3 s |
| Фиксированный формат вывода | on |
| Keep both players running | on |
| Keep audio streams | **off** |
| Продолжать, если ничего не играет | on |
| Keep playing under announcements | on |
| Quality | Совместимое (parts 1–2), FLAC (part 3) |

Restart YAMP once after setting these.

## Part 1 — DVC on ("Без DVC" off), ~15 min

1. Let 3–4 tracks end naturally (crossfade each time).
2. Skip 5–6 times at a normal pace; listen to the first seconds of each new track.
3. Tap 2–3 tracks in the car screen (a pick, not a skip).
4. Skip 4–5 times quickly in a row.
5. Run Diag: Collect → Share.

## Part 2 — DVC off ("Без DVC" on, then restart YAMP), ~15 min

Same steps 1–5, then Diag again.

## Part 3 (optional) — FLAC

Quality → FLAC; a few skips and picks, one burst of quick skips; Diag.

## What to send

- [ ] The 2–3 Diag zips, one per part (`summary.txt` shows DVC yes/no, so parts can't be mixed up).
- [ ] YAMP's log at the end (Settings → About → send; LocalSend works).
- [ ] Short videos with sound (20–40 s): per part one natural-end crossfade and 1–2 skips.
- [ ] A one-line note per part with the time, e.g. "part 1, ~21:05: pop on skip to «…»; crossfade OK".

## What will be compared

- Pop on skips/picks, DVC on vs off — with b84 it should be gone in both.
- Crossfade smoothness — expected clean only with DVC off.
- Stutter at track starts.
- FLAC starts after quick skips: no silent-clip start, `RETRY new link` where needed.
