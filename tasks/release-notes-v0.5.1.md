## YAMP 0.5.1

The app id is unchanged since 0.3.0 (`org.tegro.music.yamp`), so 0.3.0 users update in place.

### Jellyfin
- Sign in to your own Jellyfin server with a password or Quick Connect. Each server and user is its own account,
  with its own queues and cache.
- Library: albums, artists, playlists and genres, with their pages. The last visit stays readable when the server
  can't be reached.
- Streaming, the original file or MP3 320, with covers. The token is sent only to your server.
- Add tracks, albums and artists to Jellyfin playlists, or create new ones.
- Plays are reported to the server: play count and "now playing".
- Search finds tracks on Yandex and Jellyfin together, with service badges.
- Jellyfin only works too. The first start lets you choose Yandex Music or Jellyfin.

### New player
- One structure on every screen. The top row is ⋮ and the queue dropdown. Search, the sleep timer, add to
  playlist, quality and "tune the wave" or "start radio" are in ⋮.
- Landscape and head unit: a full-height cover with the next track or tracks under it, and the clock in the
  corner. «Крупные кнопки» gives large controls in landscape.
- Next tracks: one, or three cards with the title and artist.
- Like by gestures, if you want it: double tap the cover to like, hold it to dislike, with Undo.
- Swipe up opens the queue, and it follows your finger. Swipe down closes the queue first, then the player.
- Left- or right-hand layout follows the steering-wheel side: the main screen, the player, the Music tab and the
  popups.
- Reopening the app brings back the screen you left.

### Playback
- A tapped track keeps the current one playing until it is ready, then switches with a short fade.
- New "Transition" setting:
  - Crossfade, as before;
  - "Fade then rise": the current track fades out, then the next one rises, never both at once.
- Smooth pause, play and seek, timed from the crossfade length.
- No more pops from the old track when skipping.
- Tracks keep loading as long as data arrives. After 20 s without any data, playback pauses.
- Seeking in a converted Jellyfin stream works: the track is saved to the device first.
- On-disk cache for the previous, current and next track (size in Settings). The next track loads early, so Next
  is instant.
- Next is never disabled. It shows loading, or failed with a retry.

### Albums, artists, radio
- Album and artist menu: Play now, Play next, Add to end, Add to queue…, Add to playlist…, and Start radio
  (Yandex).
- Adding an artist asks first: all albums or popular tracks, optionally with singles.

### Quality per account
- Each account has its own quality for unmetered and metered networks. Mark the car's Wi-Fi as metered in Android
  to use the lighter quality there.

### Head units with Poweramp Equalizer
In Poweramp Equalizer → Known Players → YAMP, set **Затухание (Fade) off** and **Без DVC on**:
- Fade off stops the brief audio-focus grab that paused playback.
- Без DVC on keeps YAMP's own volume working, for clean crossfades and skips.

### Settings and diagnostics
- Playback settings are simpler. Several experimental switches are gone.
- The log records the audio format, output, timings and settings. Logs share as files, so LocalSend works.

### Known issues
- Station feedback (likes and skips sent to Yandex) is rejected by the server (HTTP 400).
- Jellyfin downloads and offline playback aren't available yet.
- Android Auto with Jellyfin accounts hasn't been verified yet.
- A seek in a Yandex track sometimes restarts it at the new position, with a short gap.

**Full Changelog**: https://github.com/tegro-org/YAMP/compare/v0.3.0...v0.5.1
