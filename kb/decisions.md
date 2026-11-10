# Product decisions

Decided with the user on 2026-11-08/09 (design discussion before the repo existed). Detailed design:
`docs/multi-source-player.md`.

| # | Decision | Why |
|---|---|---|
| D1 | Hard fork of upstream YandexMusicLite, not of YML; no merges, manual porting | Upstream is the active source and has fixes YML lacks; rework is too deep for merges |
| D2 | Own standalone repo `tegro-org/YAMP` (not a GitHub fork), code in `android/`, with `docs/`, `kb/` and `Design/` next to it | Fork network limits; room for docs/kb/design next to the app |
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
| D29 | Re-running a saved mix replaces that mix's previous queue (amends D9 for mixes; whether radio re-run also replaces is still open) | Otherwise queues pile up (user, 2026-11-10) |
| D30 | A queue may mix providers freely (no "one account per queue" rule). Favourites stay per provider and are not merged into one list; where a single button must act alone (Library Play / Shuffle, the car "Liked" tile) it uses the **first source in the Sources list** | Mixed queues cost nothing in the architecture; a merged "Liked" needs an order rule and plays duplicates (user, 2026-11-10) |
| D31 | Jellyfin sign-in also offers **Quick Connect** (a code shown on the device, confirmed from a signed-in Jellyfin client) as an alternative to user name + password; the password form stays | No password typed on the head unit; same idea as the existing Yandex code sign-in (user, 2026-11-10) |
| D32 | "Driving" = car mode: big targets, never requires typing. "Parked" = the normal landscape interface, where typing lives. No motion detection, nothing is blocked; car mode has a search button that opens the landscape search in one tap. To be tried on the unit and adjusted from feedback | The user wants to be able to search by typing while driving; safety comes from car mode not needing it, not from blocking (user, 2026-11-10) |
| D33 | Car tiles are one priority list — Folders, My Wave, Liked, Mixes, Radio — shown as many as fit (the row scrolls for the rest); a tile exists only while a source supports it. **No Offline tile and no "Downloaded" row in Library:** downloaded music is offered where it is needed — the weak-signal pill in car mode and the offline state of a list play it — and is browsed and managed under Settings → Storage ("Available offline"). On the phone FM radio stays a tab, so Library has no Radio row | Offline is a specific, rare use case that the app mostly handles by itself; the tile row must also fit the split panes (user, 2026-11-10) |
| D34 | "Play downloaded" in an offline list plays the downloaded part of what is open (the current list, queue or wave), not everything on the device; everything downloaded is browsed under Settings → Storage | The two sets differ (user, 2026-11-10) |
| D35 | Queues are bounded by kind: the user's own queues are never touched automatically; My Wave is one per Yandex account; **one radio queue at a time** — starting another radio replaces it, "Keep" copies it into a named queue of the user's; one queue per saved mix (re-run replaces, D29) and a single "Last mix" queue for unsaved builds. Replaces the "keep last N radios" pruning | Otherwise radios and mixes pile up in the queues sheet; the user can always make a new queue when wanted (user, 2026-11-10) |
| D36 | Stream quality: resolve the variants in parallel, start with the one the link can carry (MP3 on a weak link), upgrade to lossless from the next track on; the next track is prepared as early as the current track's buffer and the link allow (not at a fixed point), so most skips are crossfades; a manual skip stops the current track at once with a loading state; recovery retries at a lower quality before giving up. No mid-track stream switching | Both phone problems of 2026-11-10 (skip "hang", FLAC never downgraded); mid-track switching is audible and doubles data (user, 2026-11-10) |
| D37 | Versions are derived from git, never bumped by hand: a release build on tag `vX.Y.Z` is `X.Y.Z`; any other build is `<last tag>-dev.<commits>+<sha>`; `versionCode` = commit count. Releasing = the user creates and pushes the tag (minor for features, patch for fixes). First YAMP tag v0.1.0 | Builds must not edit source files; the installed debug build must say exactly what it is (user, 2026-11-10) |
