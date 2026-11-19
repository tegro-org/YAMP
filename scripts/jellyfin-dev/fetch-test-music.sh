#!/usr/bin/env bash
# Fetches a small set of free test music into ./data/media (gitignored) — docs/phase-3-providers.md step 18, D85.
#
# Source: Kevin MacLeod (incompetech.com), licensed CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/).
# Attribution: "<title>" Kevin MacLeod (incompetech.com), Licensed under Creative Commons: By Attribution 4.0.
# The MP3s are used as published (re-tagged into two test albums); the FLAC album is transcoded from them so
# the server also has lossless-format files (FLAC 16-bit, one at 48 kHz) and embedded cover art. The cover is
# generated here (a colour gradient), not taken from anywhere. About 55 MB (+35 MB of downloads). Needs curl, ffmpeg.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
MEDIA="$HERE/data/media"
WORK="$HERE/data/download"
BASE="https://incompetech.com/music/royalty-free/mp3-royaltyfree"
ARTIST="Kevin MacLeod"
YEAR="2008"

MP3_ALBUM="YAMP Test MP3"
MP3_TRACKS=("Sneaky Snitch" "Carefree" "Local Forecast - Elevator")
FLAC_ALBUM="YAMP Test FLAC"
FLAC_TRACKS=("Fluffing a Duck" "Monkeys Spinning Monkeys" "Wallpaper")   # "Fluffing a Duck" is 48 kHz

command -v ffmpeg >/dev/null || { echo "ffmpeg is required" >&2; exit 1; }
mkdir -p "$WORK" "$MEDIA/$ARTIST/$MP3_ALBUM" "$MEDIA/$ARTIST/$FLAC_ALBUM"

fetch() { # title → $WORK/title.mp3
    local title="$1" file="$WORK/$1.mp3"
    [[ -s "$file" ]] && return 0
    local enc
    enc="$(python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$title")"
    echo "  fetch  $title"
    curl -fsSL -o "$file.part" "$BASE/$enc.mp3" && mv "$file.part" "$file"
}

COVER="$WORK/cover.png"
[[ -s "$COVER" ]] || ffmpeg -loglevel error -y -f lavfi -i "gradients=s=600x600:c0=0x1b4965:c1=0x62b6cb:d=1" \
    -frames:v 1 "$COVER"

n=0
for t in "${MP3_TRACKS[@]}"; do
    n=$((n + 1)); fetch "$t"
    out="$MEDIA/$ARTIST/$MP3_ALBUM/$(printf '%02d' "$n") $t.mp3"
    [[ -s "$out" ]] && continue
    ffmpeg -loglevel error -y -i "$WORK/$t.mp3" -map 0:a -c copy -map_metadata -1 \
        -metadata title="$t" -metadata artist="$ARTIST" -metadata album_artist="$ARTIST" \
        -metadata album="$MP3_ALBUM" -metadata track="$n/${#MP3_TRACKS[@]}" -metadata date="$YEAR" \
        -metadata comment="CC BY 4.0 — incompetech.com" -id3v2_version 3 "$out"
done

n=0
for t in "${FLAC_TRACKS[@]}"; do
    n=$((n + 1)); fetch "$t"
    out="$MEDIA/$ARTIST/$FLAC_ALBUM/$(printf '%02d' "$n") $t.flac"
    [[ -s "$out" ]] && continue
    # Keep each source's own sample rate (one 48 kHz, the rest 44.1 kHz); 16-bit; embedded front cover.
    ffmpeg -loglevel error -y -i "$WORK/$t.mp3" -i "$COVER" -map 0:a -map 1:v -c:a flac -sample_fmt s16 \
        -c:v png -disposition:v attached_pic -map_metadata -1 \
        -metadata title="$t" -metadata artist="$ARTIST" -metadata album_artist="$ARTIST" \
        -metadata album="$FLAC_ALBUM" -metadata track="$n/${#FLAC_TRACKS[@]}" -metadata date="$YEAR" \
        -metadata comment="CC BY 4.0 — incompetech.com" "$out"
done

# A hand-named folder tree for folder browsing (Phase 4b): a Cyrillic top folder that matches no tag, a subfolder
# with two tracks, and a loose track next to it. Copies of the MP3s, re-tagged.
TREE="Сборники - разное"
mkdir -p "$MEDIA/$TREE/Дорога"
tag_copy() { # source title, output path, album, track number
    [[ -s "$2" ]] && return 0
    ffmpeg -loglevel error -y -i "$WORK/$1.mp3" -map 0:a -c copy -map_metadata -1 \
        -metadata title="$1" -metadata artist="$ARTIST" -metadata album_artist="$ARTIST" \
        -metadata album="$3" -metadata track="$4" -metadata date="$YEAR" \
        -metadata comment="CC BY 4.0 — incompetech.com" -id3v2_version 3 "$2"
}
tag_copy "Sneaky Snitch" "$MEDIA/$TREE/Дорога/01 Sneaky Snitch.mp3" "Дорога" 1
tag_copy "Wallpaper" "$MEDIA/$TREE/Дорога/02 Wallpaper.mp3" "Дорога" 2
tag_copy "Carefree" "$MEDIA/$TREE/Carefree (demo).mp3" "$TREE" 1

echo "Test music in $MEDIA ($(du -sh "$MEDIA" | cut -f1))"
