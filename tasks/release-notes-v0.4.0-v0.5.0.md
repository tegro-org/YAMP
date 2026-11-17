# Draft release notes (not published)

## YAMP 0.4.0 — stage 1 (Phase 3 + 3b), after the b85 checkpoint rides

- Next is never disabled: it shows when the next track is loading (ring) or failed to load (dimmed; a tap retries)
- The progress bars show how much of the track is loaded
- On-disk stream cache (Settings → Playback → Stream cache, default 500 MB): the next track is downloaded ahead, the
  previous one stays — Back and Next are instant, short network dropouts are covered
- The next track loads early and recovers by itself when the network comes back
- Under the hood: a provider layer that keeps Yandex code separate, ready for more sources

### Known issues
- Station feedback (likes/skips) to Yandex is rejected by the server (HTTP 400)

## YAMP 0.5.0 — stage 2 (Phase 4, Jellyfin)

- Jellyfin: add a server account (password or Quick Connect); several servers and users side by side
- Browse your Jellyfin library — albums, artists, playlists, genres — with album, artist and playlist pages; pages stay
  readable when the server can't be reached
- Play Jellyfin music (original file, or MP3 320 in "compatible" quality) with covers; your server sees what YAMP plays
  (now playing, play counts)
- Search shows Yandex and Jellyfin results together, marked by source
- Each Jellyfin account has its own queue; a queue holds tracks of its own account only

### Not yet
- Jellyfin on Home, merged library rows, Jellyfin downloads and offline play, Jellyfin favourites
