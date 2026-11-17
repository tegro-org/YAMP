#!/usr/bin/env bash
# Starts the local Jellyfin and finishes its first-run wizard through the API (docs/phase-3-providers.md step 18).
# Creates a dev admin user with a password generated here and kept only in ./data/dev-credentials (gitignored),
# turns Quick Connect on, adds the test music (fetch-test-music.sh) as a "Music" library and scans it.
# Safe to run again: it skips what is already done. Needs docker (compose v2), curl and python3.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
DATA="$HERE/data"
URL="http://localhost:8096"
CREDS="$DATA/dev-credentials"
USER_NAME="yamp-dev"
AUTH_BASE='MediaBrowser Client="YAMP dev setup", Device="setup.sh", DeviceId="yamp-dev-setup", Version="1"'

mkdir -p "$DATA/config" "$DATA/cache" "$DATA/media"
[[ -n "$(ls -A "$DATA/media" 2>/dev/null)" ]] || "$HERE/fetch-test-music.sh"

JF_UID="$(id -u)" JF_GID="$(id -g)" docker compose -f "$HERE/docker-compose.yml" up -d

echo -n "Waiting for Jellyfin"
# /System/Info/Public answers before the server is ready for the wizard (503); /health says Healthy when it is.
for _ in $(seq 1 120); do
    [[ "$(curl -fs "$URL/health" 2>/dev/null)" == "Healthy" ]] && curl -fs "$URL/System/Info/Public" >/dev/null 2>&1 && break
    echo -n "."; sleep 2
done
echo
curl -fs "$URL/System/Info/Public" >/dev/null || { echo "Jellyfin did not start; see: docker logs yamp-jellyfin-dev" >&2; exit 1; }

json() { python3 -c "import sys, json; d = json.load(sys.stdin); print($1)"; }
api() { # method path [body] — authorised once TOKEN is set
    local auth="$AUTH_BASE"
    [[ -n "${TOKEN:-}" ]] && auth="$AUTH_BASE, Token=\"$TOKEN\""
    # A freshly started server may still answer 503 for a few seconds: retry those.
    curl -fsS --retry 15 --retry-delay 2 --retry-all-errors -X "$1" "$URL$2" \
        -H "Authorization: $auth" -H "Content-Type: application/json" ${3:+-d "$3"}
}

if [[ ! -s "$CREDS" ]]; then
    umask 077
    printf 'user=%s\npassword=%s\n' "$USER_NAME" "$(python3 -c 'import secrets; print(secrets.token_urlsafe(18))')" > "$CREDS"
fi
PASSWORD="$(sed -n 's/^password=//p' "$CREDS")"

if [[ "$(curl -fs "$URL/System/Info/Public" | json "d.get('StartupWizardCompleted')")" != "True" ]]; then
    echo "Running the first-run wizard"
    api POST /Startup/Configuration '{"UICulture":"en-US","MetadataCountryCode":"US","PreferredMetadataLanguage":"en"}'
    api GET /Startup/User >/dev/null
    api POST /Startup/User "{\"Name\":\"$USER_NAME\",\"Password\":\"$PASSWORD\"}"
    api POST /Startup/RemoteAccess '{"EnableRemoteAccess":true,"EnableAutomaticPortMapping":false}'
    api POST /Startup/Complete
fi

TOKEN="$(api POST /Users/AuthenticateByName "{\"Username\":\"$USER_NAME\",\"Pw\":\"$PASSWORD\"}" | json "d['AccessToken']")"

# Quick Connect on (the app's sign-in offers it, step 21).
CONFIG="$(api GET /System/Configuration)"
if [[ "$(echo "$CONFIG" | json "d.get('QuickConnectAvailable')")" != "True" ]]; then
    api POST /System/Configuration "$(echo "$CONFIG" | python3 -c 'import sys, json; d = json.load(sys.stdin); d["QuickConnectAvailable"] = True; print(json.dumps(d))')"
fi

if ! api GET /Library/VirtualFolders | json "any(f.get('Name') == 'Music' for f in d)" | grep -q True; then
    echo "Adding the Music library"
    api POST "/Library/VirtualFolders?name=Music&collectionType=music&paths=%2Fmedia&refreshLibrary=true" '{"LibraryOptions":{}}'
fi
api POST /Library/Refresh >/dev/null || true

echo -n "Scanning"
for _ in $(seq 1 60); do
    n="$(api GET "/Items?IncludeItemTypes=MusicAlbum&Recursive=true" | json "d.get('TotalRecordCount', 0)")"
    [[ "$n" -ge 2 ]] && break
    echo -n "."; sleep 2
done
echo

# One playlist for the library pages (step 22): four tracks, created once.
USER_ID="$(api GET /Users/Me | json "d['Id']")"
if [[ "$(api GET "/Users/$USER_ID/Items?IncludeItemTypes=Playlist&Recursive=true" | json "d.get('TotalRecordCount', 0)")" == "0" ]]; then
    echo "Adding the test playlist"
    IDS="$(api GET "/Users/$USER_ID/Items?IncludeItemTypes=Audio&Recursive=true&SortBy=SortName&Limit=4" | json "json.dumps([i['Id'] for i in d['Items']])")"
    api POST /Playlists "{\"Name\":\"YAMP Test Playlist\",\"Ids\":$IDS,\"UserId\":\"$USER_ID\",\"MediaType\":\"Audio\"}" >/dev/null
fi

SERVER="$(curl -fs "$URL/System/Info/Public" | json "d['ServerName'] + ' ' + d['Version'] + ' (id ' + d['Id'] + ')'")"
echo "Jellyfin $SERVER — albums: $n, tracks: $(api GET "/Items?IncludeItemTypes=Audio&Recursive=true" | json "d.get('TotalRecordCount', 0)")"
echo "Host:     $URL"
echo "Emulator: http://10.0.2.2:8096   (user $USER_NAME, password in $CREDS)"
