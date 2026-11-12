#!/bin/sh
# Device checks for queue persistence (kb/head-unit-dpi-app.md rule 6): the head unit's DPI tool force-stops
# the app with no callback, sometimes a second after launch. Each case kills or disturbs the app and asks you
# to confirm the queue, track and position came back. Needs adb with the device connected; the app installed.
#
#   scripts/device-check.sh              # all cases
#   scripts/device-check.sh kill-playing # one case
set -e
PKG=org.tegro.yamp
ACT=$PKG/.MainActivity

say() { printf '\n==> %s\n' "$1"; }
ask() { printf '    %s [Enter to continue] ' "$1"; read -r _; }
launch() { adb shell am start -n "$ACT" >/dev/null; }
kill_app() { adb shell am force-stop "$PKG"; }
sleep_s() { sleep "$1"; }

case_kill_playing() {
  say "force-stop while playing"
  ask "Start a queue playing, note the track and position"
  kill_app; sleep_s 2; launch
  ask "Same queue, same track, position within 15 s of where it was, paused?"
}

case_kill_at_launch() {
  say "force-stop within 1 s of launch (the DPI tool's timing)"
  kill_app; launch; sleep_s 1; kill_app; sleep_s 1; launch
  ask "Still the same queue, track and position? (nothing lost, nothing emptied)"
}

case_kill_after_edit() {
  say "force-stop right after a queue edit"
  ask "Add a track with 'Add to end of queue' (or reorder one), then press Enter at once"
  kill_app; sleep_s 2; launch
  ask "Is the edit there?"
}

case_density() {
  say "density change while playing (the DPI tool's wm density)"
  ask "Start playing"
  cur=$(adb shell wm density | sed -n 's/.*Physical density: \([0-9]*\).*/\1/p')
  adb shell wm density 200; sleep_s 3; adb shell wm density reset
  ask "Did the music keep playing through the change? (physical density was $cur)"
}

case_clear_task() {
  say "relaunch with --activity-clear-task (the DPI tool's light method)"
  adb shell am start -n "$ACT" --activity-clear-task >/dev/null
  ask "Same queue and track on screen?"
}

case_switch_kill() {
  say "switch queue, kill, restart"
  ask "Open the queue switcher and switch to another queue, then press Enter at once"
  kill_app; sleep_s 2; launch
  ask "Is the queue you switched to active, at its own track and position?"
}

run() {
  case "$1" in
    kill-playing) case_kill_playing ;;
    kill-at-launch) case_kill_at_launch ;;
    kill-after-edit) case_kill_after_edit ;;
    density) case_density ;;
    clear-task) case_clear_task ;;
    switch-kill) case_switch_kill ;;
    *) echo "unknown case $1"; exit 1 ;;
  esac
}

adb get-state >/dev/null 2>&1 || { echo "no device"; exit 1; }
if [ $# -gt 0 ]; then for c in "$@"; do run "$c"; done
else for c in kill-playing kill-at-launch kill-after-edit density clear-task switch-kill; do run "$c"; done; fi
say "done"
