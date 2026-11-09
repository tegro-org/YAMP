# Product decisions

Decided with the user on 2026-11-08/09 (design discussion before the repo existed). Detailed design:
`docs/multi-source-player.md`.

| # | Decision | Why |
|---|---|---|
| D1 | Hard fork of upstream YandexMusicLite, not of YML; no merges, manual porting | Upstream is the active source and has fixes YML lacks; rework is too deep for merges |
| D2 | Own standalone repo `tegro-org/YAMP` (not a GitHub fork), code in `android/`, Vantage-style layout | Fork network limits; room for docs/kb/design next to the app |
| D3 | Name **YAMP** (Yet Another Music Player); name clashes exist (hobby projects) but are irrelevant for a personal app with a unique `applicationId` | Nod to YML |
| D4 | Jellyfin first; Subsonic/Airsonic deferred and likely dropped | Jellyfin is more modern; Airsonic only served as a 2nd-queue workaround |
| D5 | No de-duplication between Jellyfin and Airsonic | Jellyfin is primary |
| D6 | Multiple persistent queues (Musicolet-style) — the key feature; queue must never be lost on process death | Main reason for the project |
| D7 | Queues follow the Yandex account logic (per account) | Same as today's session behaviour |
| D8 | Playing an album/folder replaces the active queue; queue switching via a popup from a queue button (home, player, car) — no full-screen queue manager | Simple; Musicolet's screen is overkill |
| D9 | Radio/mixes follow YouTube Music: generated list is persistent (go back/forward); re-run = new queue | User preference |
| D10 | Jellyfin radio = Instant Mix first | Never tried; evaluate before building alternatives |
| D11 | Favorites per provider, native semantics (Yandex likes incl. albums/artists; Jellyfin `IsFavorite`) | Each service has its own |
| D12 | Folder browsing on Jellyfin is first-class; ≤ 3 taps to any folder, 1 tap to pinned/recent | Library is organised by folders; Symfonium too slow |
| D13 | Jellyfin over HTTPS only | Security; no cleartext exceptions |
| D14 | Updates from `tegro-org/YAMP` GitHub Releases; own `applicationId` + signing key | Independence from upstream update channel |
| D15 | Don't decompile closed-source players (Musicolet, Symfonium) | Not needed, licensing |
