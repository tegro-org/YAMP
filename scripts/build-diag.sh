#!/usr/bin/env bash
# Builds YAMP Diag (docs/diag-tool.md), the on-unit diagnostics collector. Debug tool only: never released, never
# built by CI. The module exists for Gradle only with -Pyamp.diag. It has its own fixed versionCode and does not use
# YAMP's build counter (dist/.build-number) — the file name carries the build time instead.
#
#   scripts/build-diag.sh        → dist/YAMP-diag-<yyyyMMdd-HHmm>-debug.apk
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root/android"
sh gradlew -q -Pyamp.diag :diag:assembleDebug
mkdir -p "$root/dist"
out="$root/dist/YAMP-diag-$(date +%Y%m%d-%H%M)-debug.apk"
cp diag/build/outputs/apk/debug/diag-debug.apk "$out"
echo "$out"
