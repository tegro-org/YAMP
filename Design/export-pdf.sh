#!/bin/sh
# Export the design canvas to PDF: one file per language, in Design/out/ (not committed).
#
#   Design/export-pdf.sh            both languages
#   Design/export-pdf.sh ru         only Russian
#
# Needs Chromium (or set CHROMIUM_PATH) and internet access (React, Babel, fonts).
# The page's ?print=1 view lays the artboards out on wide landscape pages.
set -e

DIR=$(cd "$(dirname "$0")" && pwd)
PORT=${PORT:-5529}
OUT="$DIR/out"
LANGS=${*:-en ru}

CHROME=${CHROMIUM_PATH:-$(command -v chromium || command -v chromium-browser || command -v google-chrome || true)}
[ -n "$CHROME" ] || { echo "Chromium not found; set CHROMIUM_PATH" >&2; exit 1; }

mkdir -p "$OUT"
PROFILE=$(mktemp -d)
python3 "$DIR/serve.py" "$PORT" >/dev/null 2>&1 &
SERVER=$!
trap 'kill "$SERVER" 2>/dev/null; rm -rf "$PROFILE"' EXIT
sleep 1

for L in $LANGS; do
  FILE="$OUT/YAMP-design-$L.pdf"
  "$CHROME" --headless=new --disable-gpu --no-first-run --user-data-dir="$PROFILE" \
    --no-pdf-header-footer --virtual-time-budget=60000 \
    --print-to-pdf="$FILE" "http://localhost:$PORT/YAMP.html?print=1&lang=$L" >/dev/null 2>&1
  echo "$FILE"
done
