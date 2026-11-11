#!/bin/sh
# Generate the Android launcher icon resources from one of the SVG sources in this folder.
#
#   Design/icons/make-launcher-icons.sh rings          # or: three-rings
#
# Writes into android/app/src/main/res:
#   drawable-*dpi/ic_launcher_foreground.png   the full 108 dp adaptive-icon canvas (the SVG as is)
#   drawable-*dpi/ic_launcher_monochrome.png   the alpha mask for Android 13+ themed icons (<name>-mono.svg)
#   mipmap-*dpi/ic_launcher.png, ic_launcher_round.png   legacy 48 dp icons (squircle / circle of the visible 72 dp)
#   drawable-nodpi/splash_logo.png             the in-app splash logo: the visible 72 dp as a circle, 576 px,
#                                              transparent around it
# The system splash icon (Android 12+, themes.xml) is ic_launcher_foreground itself: the same 108 dp canvas.
# The adaptive icon XML in mipmap-anydpi-v26 and the solid background drawable stay as they are.
# Needs rsvg-convert and ImageMagick (magick).
set -e
NAME=${1:?icon name (rings | three-rings)}
DIR=$(cd "$(dirname "$0")" && pwd)
RES=$(cd "$DIR/../../android/app/src/main/res" && pwd)
SRC="$DIR/$NAME.svg"; MONO="$DIR/$NAME-mono.svg"
[ -f "$SRC" ] && [ -f "$MONO" ] || { echo "missing $SRC or $MONO" >&2; exit 1; }
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

for d in mdpi:1 hdpi:1.5 xhdpi:2 xxhdpi:3 xxxhdpi:4; do
  q=${d%%:*}; k=${d##*:}
  fg=$(python3 -c "print(round(108*$k))"); lg=$(python3 -c "print(round(48*$k))"); vis=$(python3 -c "print(round(72*$k))")
  mkdir -p "$RES/drawable-$q" "$RES/mipmap-$q"
  rsvg-convert -w "$fg" -h "$fg" "$SRC"  -o "$RES/drawable-$q/ic_launcher_foreground.png"
  rsvg-convert -w "$fg" -h "$fg" "$MONO" -o "$RES/drawable-$q/ic_launcher_monochrome.png"
  # legacy: the visible 72 dp centre of the canvas, masked, scaled to 48 dp
  rsvg-convert -w "$fg" -h "$fg" "$SRC" -o "$TMP/full.png"
  magick "$TMP/full.png" -gravity center -crop "${vis}x${vis}+0+0" +repage "$TMP/vis.png"
  r=$((vis/2)); rr=$((vis/4))
  magick "$TMP/vis.png" \( +clone -fill black -colorize 100 -fill white -draw "roundrectangle 0,0 $((vis-1)),$((vis-1)) $rr,$rr" -alpha off \) \
    -compose CopyOpacity -composite -resize "${lg}x${lg}" "$RES/mipmap-$q/ic_launcher.png"
  magick "$TMP/vis.png" \( +clone -fill black -colorize 100 -fill white -draw "circle $r,$r $r,0" -alpha off \) \
    -compose CopyOpacity -composite -resize "${lg}x${lg}" "$RES/mipmap-$q/ic_launcher_round.png"
done
mkdir -p "$RES/drawable-nodpi"
rsvg-convert -w 864 -h 864 "$SRC" -o "$TMP/big.png"
magick "$TMP/big.png" -gravity center -crop 576x576+0+0 +repage \
  \( +clone -fill black -colorize 100 -fill white -draw "circle 288,288 288,0" -alpha off \) \
  -compose CopyOpacity -composite -strip "$RES/drawable-nodpi/splash_logo.png"
echo "launcher icons generated from $NAME.svg"
