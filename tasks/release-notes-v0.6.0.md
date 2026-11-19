## YAMP 0.6.0

Updates 0.5.1 in place.

### Jellyfin folders
- Browse your Jellyfin library by folders: breadcrumbs, a filter on every level, natural sort (2 before 10).
- Pin folders: they stay on top and show on Home.
- Play a whole folder, with every subfolder, in tree order. The folder menu has Play now, Play next, Add to end,
  Add to queue…, Shuffle and Pin.
- On the phone, in landscape and on the head unit.

### Home and Library
- Home shows Jellyfin sections: Recently added, Pinned folders, Favourites. Home scrolls.
- Library has one section per signed-in account, each with its own rows, including «Скачанное» (downloaded).
  With a single account there is no account header.

### Head unit
- ⋮ → «Музыка» (the last item) opens the library of the current queue's account: albums, playlists, liked or
  favourites, and folders for Jellyfin.
- Tap an album for its page with large buttons and the track list; hold it for the quick menu.
- A car-sized "Add to queue…". Everything mirrors for left- and right-hand drive.

### Offline and downloads
- A fully cached track plays without network, after a restart too, for every account.
- Jellyfin downloads: your server favourites are saved at the account's quality.
- Auto-download of liked tracks (favourites for Jellyfin) is now set per account, in the account's sheet.

### Android Auto
- Every signed-in account has its own folder, Jellyfin included: Favourites, Playlists, Albums, Folders,
  Downloaded; My Wave, Liked, Playlists, Albums, Downloaded for Yandex. Search covers every account.
- YAMP is installed outside Google Play, so turn on "Unknown sources" in Android Auto's developer settings to see it.

### Layout and playback
- 16:10 screens (1280×800) get the "taller" layout: the cover block sits at the right padding.
- Galaxy Z Fold 8 cover and inner screens are covered.
- With the Audio Router app installed and a USB audio output connected, tracks change with hard cuts, since its
  volume control breaks fades. Settings → Playback shows a hint.

### Known issues
- Station feedback (likes and skips sent to Yandex) is rejected by the server (HTTP 400).
- A seek in a Yandex track sometimes restarts it at the new position, with a short gap.
- Android Auto is verified on the emulator, not yet in a real car.

**Full Changelog**: https://github.com/tegro-org/YAMP/compare/v0.5.1...v0.6.0
