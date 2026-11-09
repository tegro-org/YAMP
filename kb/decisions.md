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
| D7 | ~~Queues follow the Yandex account logic (per account)~~ — **superseded by D22** | — |
| D8 | Playing an album/folder replaces the active queue; queue switching via a popup from a queue button (home, player, car) — no full-screen queue manager | Simple; Musicolet's screen is overkill |
| D9 | Radio/mixes follow YouTube Music: generated list is persistent (go back/forward); re-run = new queue | User preference |
| D10 | Jellyfin radio = Instant Mix first | Never tried; evaluate before building alternatives |
| D11 | Favorites per provider, native semantics (Yandex likes incl. albums/artists; Jellyfin `IsFavorite`) | Each service has its own |
| D12 | Folder browsing on Jellyfin is first-class; ≤ 3 taps to any folder, 1 tap to pinned/recent | Library is organised by folders; Symfonium too slow |
| D13 | Jellyfin over HTTPS only | Security; no cleartext exceptions |
| D14 | Updates from `tegro-org/YAMP` GitHub Releases; own `applicationId` + signing key | Independence from upstream update channel |
| D15 | Don't decompile closed-source players (Musicolet, Symfonium) | Not needed, licensing |
| D16 | Package + `applicationId` `org.tegro.yamp`, renamed once in Phase 0 | Own identity; tegro.org domain |
| D17 | Gradual Gradle module split: new code in modules (`:core:domain`, `:provider:jellyfin`…), Yandex code moves out after wrapping | Compiler-enforced boundaries, faster builds, without a big move |
| D18 | Proper Navigation Compose graph inside `main` (replaces in-VM catalog stack), at the latest with the folder browser | Current hand-built frame doesn't scale to new screens |
| D19 | Several providers signed in at once; UI sections shown per capabilities of the configured providers; upstream extras (FM radio, charts, My Wave) kept | User idea 2026-11-09 |
| D20 | Full localization: English default + Russian, more later; semantic keys; in-app language choice; existing strings migrated in one pass | Proper i18n, gradual like the rest |
| D21 | Search across providers: one merged result list with provider badges | User choice |
| D22 | Queues are app-wide (not per account); entries of a signed-out provider shown unavailable and skipped; My Wave, likes, Yandex playlists stay per account | Yandex has no named multi-queue concept; account-bound queues would hide queues on account switch and fail with Jellyfin-only |
| D23 | Phone is a first-class target, equal to the car head unit — YAMP is not a car-only app. The current Hi-Fi look on the phone (glass, iOS-like buttons) is liked and kept as the visual reference | User uses the app on the phone daily (tested 2026-11-09) |
| D24 | `ARCHITECTURE.md` and `DESIGN_GUIDE.md` approved; their rules are binding | User review 2026-11-09 |
| D25 | Local files (music stored on the device) will be supported as another provider. Only planned: scanning, index storage and track identity are **not decided** and are discussed when that phase starts (`docs/multi-source-player.md` Phase 4d) | User request 2026-11-09; keep the provider abstraction free of server/account assumptions |
| D26 | Queue switching uses the popup everywhere, the car included: 2 taps (open, pick). No queue tiles on car home; the 1-tap car budget stays for pinned/recent folders only | Switching is done once when getting into the car, not repeatedly (user, 2026-11-09) |
| D27 | One reference form for every provider item: `<provider>:<kind>:<id>` with a word for the kind (`yandex:track:12345`, `yandex:album:123`, `jellyfin:folder:…`), tracks included; split at the first two colons, the rest is the provider's id. One server per provider type for now | Letter prefixes glued to the id are ambiguous (Jellyfin ids are hex), ids may contain colons, one parser for seeds/pins/navigation/Android Auto (ARCHITECTURE C1) |
| D28 | `docs/multi-source-player.md` approved as the feature plan (phases 0–6) | User review 2026-11-09 |
