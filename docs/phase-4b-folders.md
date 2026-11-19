# Phase 4b folders, Home / Library per account, car Library — as built

Design: `docs/multi-source-player.md` §Phase 4b; canvas `Design/yamp/folders.jsx` (`pf-*`, `cf-*`, `pf-land`),
`homejf.jsx` (`hj-*`), `carbrowse.jsx` (`ca-*`). Todo: "Next: gaps after v0.5.0" — Phase 4b, (e), (f).

## Folder tree (Phase 4b)

- **Domain** (`:core:domain`): `Folder`, `FolderLevel`, the `Folders` capability (`root()`, `level()`, `tracks()`) and
  `NaturalOrder` (case-insensitive, Latin → Cyrillic → other, Ё after Е, numbers by value). `Browse.recentAlbums()`.
- **Jellyfin** (`JellyfinLibrary`): the root is `/UserViews` → the only `music` library's children (with several
  music libraries, the libraries are the top). A level is `/Items?ParentId=…&SortBy=IsFolder,SortName&Fields=ChildCount,RecursiveItemCount`,
  every page, sorted on the device: any `IsFolder` item is a folder (plain, artist or album folder), `Audio` items are
  loose tracks (disc / track order). A folder's tracks are one recursive query (`IncludeItemTypes=Audio`,
  `Fields=Path`, pages of 500) ordered by the file tree: at every level subfolders before loose files, as the level
  pages show them. All of it is cached like the other lists (served while the server is away).
- Fixtures: `user-views`, `folder-root`, `folder-level`, `folder-tracks`, `recent-albums` recorded from the dev
  server and scrubbed (ids, tags, server id). The dev music gained a hand-named tree (`Сборники - разное/Дорога/…` and
  a loose track; `scripts/jellyfin-dev/fetch-test-music.sh`).
- **Pins**: `FolderPins` (plain preferences `folder_pins`), per account key, in pinning order with name and cover key;
  kept across a sign-out like the account's queues (D62). It also remembers the car Library's last tab per account.
- **Phone**: Library → the account → Folders (`FolderPage` on the catalog stack). Root: title, account line, filter,
  "Pinned" (every pin of the account) and "All folders · N". A level: breadcrumbs (the middle collapses to "…" past
  four; a crumb jumps through the stack, or replaces the folder levels when opened from Home), head with art or the
  placeholder, Play / Shuffle, filter on that level, pinned subfolders first, then folders, then "Tracks · N". Row tap
  opens, its play button plays the whole folder in the account's queue, a hold opens the folder menu: Play now ·
  Shuffle · Play next · Add to end (when the active queue fits, D84) · Add to queue… · Add to playlist… · Pin / Unpin.
  Landscape: `pf-land` (pinned column, all folders in two columns; on a level the head on the left).
- **Placeholder**: `FolderLook.monogram` / `tintOf` as the canvas (`fdMonogram`, `fdHue`).

## Home and Library (gap (e))

- **Home**: with a Jellyfin account (portrait) the first tab keeps its hero and adds one group per Jellyfin account:
  Pinned folders (play on each) · Recently added · Favourites (three, Play / Shuffle, "All · N"). The hero ends a
  56 dp strip above its clearance so the first group shows on the first screen; Home scrolls; past 60 % of the hero
  the title collapses into one row with the queue dropdown and the mini player returns. The group header (marker,
  name, J) shows with Yandex or with several Jellyfin accounts. Yandex only: unchanged (no wrapper). Landscape:
  unchanged.
- **Library** (D89): one section per signed-in account (every Yandex account, then the Jellyfin accounts); the
  header is hidden only when there is exactly one account in all. The Yandex account in context uses the Library's
  own Liked / Playlists / Downloaded pages; another Yandex account's open on the catalog stack through its provider
  (its likes, its playlists with `YandexProvider.catalog.playlist`, its downloaded likes) and play in its own queue. Yandex rows: Liked · Playlists · Albums · Artists · Downloaded; Jellyfin: Favourites · Playlists ·
  Albums · Artists · Downloaded · Folders. Yandex Albums / Artists are the account's liked albums / artists
  (`users/{uid}/likes/albums?rich=true`, `…/likes/artists`) through `YandexProvider.browse`. Jellyfin Favourites /
  Downloaded are track pages of the catalog stack. History stays a row under the sections. "Recently added" only
  with one account. Landscape with several accounts: the sections side by side, Play / Shuffle in the title row.

## Car Library (gap (f))

- The car layout's ⋮ gets **«Музыка»** last (`PlayerMenuItem.MUSIC`, only `WideSpec.Car`). It opens the car Library
  over the player on the active queue's account (none: the Yandex account in context), on the tab used last.
- Tabs: Jellyfin Folders · Albums · Playlists · Favourites; Yandex Albums · Playlists · Liked. Wheel side: Back, tabs
  (breadcrumbs inside a folder), Filter, the A–Z rail; far side: the account chip (▾ another account) and the
  now-playing capsule (tap = back to the player). 230 dp tiles; everything mirrors for the right hand.
- **Tap an album → the car album page** (canvas `ca-album-l`, `ca-album-r`, `ca-album-jf-l`; before, a tap played the
  album). The top row shows breadcrumbs "Albums › title" instead of the tabs and Filter ("Albums" and Back return to
  the grid). Wheel side, 620 dp: cover 200, title, artist, "Album · year · N tracks · M min", then the 84 dp buttons
  two per row: Play · Shuffle · Play next · Add to end · Add to queue… · Start radio (`carAlbumButtons`: Play next /
  Add to end only when the active queue fits, D84, as in the menu; Start radio only for Yandex, Jellyfin gets the
  "Phases 5–6" note). Far side: the tracks in 88 dp rows (the playing one marked); a tap plays the album from that
  track in the Library account's queue and returns to the player. Play / Shuffle return to the player too; Play next /
  Add to end stay with a toast; Add to queue… opens the car picker over the page (its back closes it). The tracks load
  through the Library account's `catalog.album`; the buttons run the menu's operations (`MediaActionsViewModel`).
- Hold an album (or folder) → the car menu in a 720 dp panel on the wheel side (`carMediaMenuRows`: Start radio only
  for Yandex, Add to playlist only for Yandex). "Add to queue…" turns the panel into the car picker: "New queue"
  (named after the album) on top, then that account's static queues only (D84), the switcher's 100 dp rows.

## Android Auto (gap (d))

- `MediaLibraryTree` = the accounts' part (`AccountBrowseTree`) + Radio + Queues. Several accounts: a folder per
  account (name, service); one account: its rows at the root. Jellyfin: Favourites · Playlists · Albums · Folders ·
  Downloaded; Yandex: My Wave (the context account, or another account with a wave queue) · Liked · Playlists ·
  Albums · Downloaded. Everything through the account's provider.
- Folders as on the phone: pins first at the root (and pinned subfolders on a level), folders, loose tracks; inside a
  folder "Play all" first. A folder is browsable and playable (a pick plays every track under it).
- A pick plays in its account: the Yandex account in context as its Library does, any other account in its own
  queue (`playInAccount`, D84). A track plays its list from it; lists are remembered 60 s between paging and the pick.
- `onSearch` / `onGetSearchResult`: every account's tracks and albums; voice "play …" tries the stations, then the
  accounts in order (My Wave when nothing matches and a Yandex account is in context).
- Covers: the car loads only `content://` / `android.resource://` art and sends no headers. Remote covers become
  `content://<app>.artwork/<sha256 of the URL>`; `ArtworkProvider` (exported, read-only) serves only URLs the tree
  named, from `cache/car-artwork` (500 files), fetching with the Jellyfin header per hop (`JellyfinImageAuth`). No
  token, server or account in a URI. The now-playing art still comes from the session's own bitmap loader.
- Ids: `n|<kind>|<account>|<arg>` (+ `|<track ref>` for a track). Tests: `AccountBrowseTreeTest`,
  `AndroidAutoBrowseInstrumentedTest` (5556: root → Folders → folder → Play all). Not checked in a real car / DHU.

## Deviations from the boards

- Home hero for Jellyfin only stays the knob page (no "Continue" card, `hj-home-b`); Home landscape has no groups.
- Library keeps History (as a row under the sections) and drops Jellyfin Genres (not on the boards).
- Folder rows show "N tracks" only (Jellyfin gives items-inside and tracks-under, not a folder count).
- Car: a tap on a playlist or a liked track plays it and returns to the player (only albums have a page);
  the filter opens on tap (there is no parked signal); the car menu's Add to playlist opens the phone menu.
- Car album page: Play next / Add to end hide when the active queue can't take the album (D84; the boards draw them
  always); the track list has no pressed ring (the press feedback is the app's usual one).
