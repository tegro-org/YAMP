#!/usr/bin/env bash
#
# Build the YAMP APK and put it where it is easy to pick up:
#
#   dist/YAMP-<versionName>-<variant>.apk      (dist/ is git-ignored)
#
# Usage:
#   scripts/build-apk.sh                 # debug build (default)
#   scripts/build-apk.sh debug
#   scripts/build-apk.sh release         # needs the signing key, see below
#   scripts/build-apk.sh release --tests # run unit tests, screenshots and lint first
#   scripts/build-apk.sh debug --install # adb-install on the connected device after the build
#
# Release signing reads KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD from
# android/local.properties or the environment (never commit them). The build fails fast without them.
# Debug builds use the release key when it is present, otherwise the debug key.
#
set -euo pipefail

VARIANT="${1:-debug}"
shift || true
RUN_TESTS=0; INSTALL=0
for arg in "$@"; do
  case "$arg" in
    --tests) RUN_TESTS=1 ;;
    --install) INSTALL=1 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done
case "$VARIANT" in
  debug|release) ;;
  *) echo "variant must be debug or release, got '$VARIANT'" >&2; exit 2 ;;
esac

# Run from the repo root (this script lives in scripts/).
cd "$(dirname "$0")/.."
ROOT=$(pwd)
DIST="$ROOT/dist"
GRADLE="sh gradlew"   # gradlew is not executable in the repo

Cap=$(printf '%s' "$VARIANT" | sed 's/./\U&/')   # Debug / Release

cd android
if [ "$RUN_TESTS" -eq 1 ]; then
  echo "==> Tests, screenshots and lint"
  $GRADLE testDebugUnitTest verifyRoborazziDebug lintDebug --console=plain -q
fi

echo "==> Building $VARIANT APK"
$GRADLE "assemble$Cap" --console=plain -q

APK=$(ls -t "app/build/outputs/apk/$VARIANT"/*.apk | head -1)
[ -f "$APK" ] || { echo "no APK found under app/build/outputs/apk/$VARIANT" >&2; exit 1; }
# The version is derived from git by the build (D37); read what was actually built.
META="app/build/outputs/apk/$VARIANT/output-metadata.json"
VERSION=$(python3 -c "import json,sys; e=json.load(open(sys.argv[1]))['elements'][0]; print(e['versionName'])" "$META")
CODE=$(python3 -c "import json,sys; e=json.load(open(sys.argv[1]))['elements'][0]; print(e['versionCode'])" "$META")
echo "    version $VERSION (code $CODE)"

mkdir -p "$DIST"
OUT="$DIST/YAMP-$VERSION-$VARIANT.apk"
cp "$APK" "$OUT"
SIZE=$(du -h "$OUT" | cut -f1)
echo "==> Done: $OUT ($SIZE)"
command -v sha256sum >/dev/null && echo "    sha256 $(sha256sum "$OUT" | cut -d' ' -f1)"

if [ "$INSTALL" -eq 1 ]; then
  ADB="${ANDROID_HOME:-$HOME/sdk/android}/platform-tools/adb"
  echo "==> Installing on the connected device"
  "$ADB" install -r "$OUT"
fi
