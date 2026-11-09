# Head-unit DPI app (MDPI) and why players lose their queue

The user's head unit runs **MonjaroDPI / "Mdpi" 1.7.2** (`me.alexeyo.mdpi`), which gives each app its own DPI.
It is one real-world cause of "the queue is lost": YouTube Music and Ultrasonic lose it, Yandex Music and
Symfonium survive (user's observation). Analysed 2026-11-09 from the APK (manifest, help texts, decompiled with
jadx; allowed because it is not a music player, D15). The code is obfuscated and its injected scripts are
encrypted, so the method mapping below is a reading of the code, **not observed on the unit**.

## How it works

- An **accessibility service** watches window changes. When an app's window appears it looks up the app's PID
  (`pidof`, `dumpsys window windows`). Known live PID → nothing to do. New or changed PID → "auto-apply DPI".
- It runs shell commands through **local ADB** and **root** (`su 0`).
- Method **Full** (the default for a newly added app):
  1. `wm density <N>` — changes the density of the **whole system**;
  2. short pause;
  3. `am force-stop <package>`;
  4. `am start -n <package>/<main activity>`, then waits for the new PID (retries on failure);
  5. `wm density reset`.
- Methods **Light / Light+**: no kill. It copies a bundled `frida-inject` binary (53 MB asset) to
  `/data/local/tmp/.a/` and injects a script into the running app (`-p <pid> -e`) that overrides the density
  inside the process, optionally followed by `am start … --activity-clear-task` to rebuild the UI.
- A "monitor" script is injected afterwards to check that the DPI is still applied.
- Special cases are hard-coded for `ru.yandex.music`, Yandex Maps and Navigator (incl. patching them to export
  navigation data for the HUD).

## What the music app experiences

| Event | Effect |
|---|---|
| `am force-stop` | Process and services die **with no callback at all** — no `onPause`, `onDestroy`, `onTaskRemoved`. Notification and media session vanish, scheduled work is cancelled. It can land **1–2 s after the app started**, i.e. in the middle of start-up, and also on an app that was already playing in the background under a PID the DPI app had not seen. |
| Immediate restart | A second cold start right after the first one. |
| `wm density` set and reset | A configuration change for **every** running app, twice. |
| `--activity-clear-task` | All activities destroyed and the root one recreated without saved state; the process survives. |
| Frida injection | Foreign code runs inside the app's process (it could read anything the app holds, tokens included). |

Likely reason some players survive (inference, their code was not checked): they save the queue continuously
and atomically; the ones that lose it save on lifecycle callbacks or keep it in memory only.

## Rules for YAMP

1. **No persistence may depend on a lifecycle callback.** Write-through (ARCHITECTURE E3) is the only safe
   model; the periodic position save bounds the loss to its interval.
2. **Start-up must never destroy state.** Restore is read-only; nothing may write an empty or partial queue
   before the restore has finished, because the kill can arrive during start-up.
3. **Every write is atomic** (Room transaction, or write-then-rename for files).
4. **Start-up is idempotent**: two cold starts within seconds must end in the same state.
5. **A density change must not disturb playback** (service and engine are independent of the activity) and
   `DisplayScale` must recompute — check that its automatic scale does not fight an externally forced DPI.
6. **Tests** (Phase 1): `am force-stop` while playing, within 1 s of launch, and right after a queue edit;
   `wm density` change while playing; relaunch with `--activity-clear-task`.
7. Open idea: remember "was playing" with a timestamp and resume automatically when the app is restarted
   within a few seconds of its last heartbeat, so the DPI app's restart is barely audible.

YAMP has its own UI scale, so it can also simply be left without a DPI setting in the DPI app; rule 1–4 must
hold regardless.

## Ideas to make the DPI app harmless (for its author, or for an own tool)

- **Pause before the kill:** send a media pause key and wait about a second before `am force-stop` — most
  players save their state on pause.
- **Do not kill an app that is playing:** check the media session state first and use the injection method (or
  postpone) for such an app.
- **Resume after the restart:** if the app was playing before the kill, send a media play key once the new
  PID is up.
- **Avoid the kill where possible:** it is needed only because the density has to be in place when the process
  starts; starting the app itself (as a launcher action) with the density already set needs no force-stop.
