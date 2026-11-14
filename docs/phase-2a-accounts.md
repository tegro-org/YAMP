# Phase 2a — Accounts: every account live, queues bound to an account

Status: **design, awaiting approval** (branch `feature/phase-2a-accounts`). Part of
[multi-source-player.md](multi-source-player.md) (Phase 2a); brings the account half of Phase 3 (C5
`ProviderAccount`, C6 per-provider clients) forward for Yandex. Decisions `Dn` are in `kb/decisions.md`; the new ones
below are proposals until approved. Code paths are relative to `android/app/src/main/java/org/tegro/yamp/`; line
numbers are the design-time ones (`6ef0b17`).

## Purpose

Two radios on one Yandex account converge: the account's taste (likes, rotor feedback, what it played) steers both,
so a "road rock" radio and a "home RC" radio end up suggesting the same tracks (user's ride, 2026-11-14). Separate
tastes need separate accounts, and a queue must then **play, refill and report under its own account** — not under
whichever account happens to be "current". Today the app has exactly one current account (upstream's switcher): a
switch stops playback, empties the HTTP cache and reloads likes, playlists and My Wave. Phase 2a removes that global
switch: all signed-in accounts are live at once, every queue is bound to one of them, and the active queue's account
is the context the rest of the app follows.

## What changes for the user

- **One account (the usual case): nothing visible changes.** No selector, no marker, no new menu item. Under the
  hood every queue is bound to that account.
- **Several accounts:**
  - **No account switch any more.** Settings → Accounts lists the accounts with **Add**, **Remove**, **Default**;
    upstream's switcher and the startup account picker are gone. The **active queue's account is the current
    one**: Home (D49 — its My Wave, its likes, its shelves), Library (likes, playlists), Search (results' like
    state, search history) and the player's like button follow it. Switching queues is switching context.
  - **Every queue is bound to an account at creation** — normal and radio. Its Yandex tracks stream under that
    account (its subscription decides quality), likes from the player go to it, a radio's recommendations and rotor
    feedback come from and go to it.
  - **Every account's My Wave exists from sign-in** (D63): signing in creates the account's My Wave queue at once
    (empty, filled on first activation). It is the account's default queue and the way to reach the account — from
    acc1's radio, opening the switcher and tapping acc2's My Wave makes acc2 the context. On first start the active
    queue is the first account's My Wave.
  - **Choosing the account:** "New queue" and "Start radio" → "New radio" get an **account selector** (chips with the
    account's avatar/initial and name), defaulting to the active queue's account; on first start the default account
    (Settings), else the first. Re-seeding an existing radio **keeps its account**. "Add to queue…" / "Play next"
    never change a queue's account — the tracks join the queue and play under its account.
  - **Queue order and grouping (D64).** Order stays the manual `sortOrder` (Phase 1 reorder); a new queue goes to
    the end. No "recently used" sort. A **"Group" (RU «Группировать»)** switch (Settings → Accounts, shown only with more than
    one account; **default on**):
    - **On:** the switcher (phone sheet, landscape and car panels, the radio picker, "Add to queue…") has a section per
      account with a small header (avatar/initial + name); inside, that account's My Wave first, then its queues and
      radios in manual order. **No account marker on rows** — the header says it. Reordering works within a group
      only. The "Removed accounts · N queues" group stays at the bottom (phone only).
    - **Off:** one flat list in manual order, the **account marker** (avatar/initial disc) on every row.
    - The car queue pill (the active queue only) always shows the marker with more than one account, grouped or not.
      Android Auto's "Queues": one sub-folder per account when grouped; a flat list with the account name in each
      item's subtitle when not. History's queue chips carry the marker with more than one account.
    - No filter control: the queues of signed-in accounts are all shown (D22).
  - **The binding is permanent** (D61): a queue's account is never changed — no menu item for it. To listen to a
    queue's tracks under another account, start a new queue on that account and add them.
  - **Signing out an account** (Settings → Accounts → Remove, D62): its queues — normal, radio and its My Wave — are
    **kept but hidden**. In the phone switcher they collapse into one row at the bottom, **"Removed accounts ·
    N queues"** ("Удалённые аккаунты · N"); a tap expands them, dimmed, with the account marker, their Yandex entries
    unavailable (D22). Per queue only **Delete** (later, entries of other providers still play). **Signing back in to
    the same account** (same uid) brings its queues back automatically — keying by uid (D59) is what makes this
    work. Its cached lists and its downloads (unless another account likes them) are removed. With no Yandex account
    left, every Yandex queue sits in that group until an account is added.
  - Fallback if the collapsed row doesn't work in canvas review: an icon-only toggle at the top of the switcher,
    "show all queues", revealing the signed-out accounts' queues inline.
- **Head unit / car mode:** no text input, so the selector is a row of large chips in the car "New radio" path and
  "+" (new queue) — "+" creates "Queue N" bound to the active queue's account, as today's "+" creates "Queue N".
  The car queue pill shows the marker next to the queue name. **Signed-out accounts' queues are not shown in the car
  or in Android Auto at all** — the collapsed group exists only in the phone switcher.

## Decisions (proposed; recorded in `kb/decisions.md` on approval)

| # | Decision | Why |
|---|---|---|
| D56 | **All signed-in accounts are live at once; no global account switch.** The active queue's account is the context for Home, Library, Search and likes. Upstream's switcher and startup picker retire; Settings keeps add / remove / default | Taste separation needs several accounts used side by side; a switch that stops playback and reloads everything is the opposite (user, 2026-11-14) |
| D57 | **Every queue is bound to one account at creation** (`PlayQueue.account`, normal and radio; WAVE as today). Streaming, likes, radio refill and feedback use the queue's account. Default = the active queue's account; permanent (D61) | A queue plays, refills and reports under one taste (user, 2026-11-14) |
| D58 | **Selectors and markers only with more than one account**; with one account the UI is unchanged | No extra controls without a reason (user, 2026-11-14) |
| D22′ | **Revised:** queues stay app-wide *in visibility* — the queues of every signed-in account are shown, none hidden by a switch — and each has an owner account (D57); a signed-out account's queues are kept, collapsed (D62), their entries unavailable and skipped | D22's reason (nothing hides on a switch) holds; ownership is new |
| D59 | **Accounts are keyed by the Yandex uid**, not by the login-based folder name; `owner` / `account` columns and per-account stores are migrated | `folderName` (login → display name → uid) can collide and changes when a provisional `temp_` account learns its details (`UserAccount.kt:27`) |
| D60 | **One network client per account** (`ProviderAccount` + per-account OkHttp/Retrofit sharing one connection pool, own HTTP cache directory); a call names its account explicitly or takes the context account | No token ever crosses accounts, cached answers stay per account; the same shape Phase 3 needs for every provider (C5, C6) |
| D61 | **The binding is permanent**: a queue's account is set at creation and never changed (no "change account" action anywhere); re-seeding a radio keeps it | A queue's history and suggestions belong to one taste; moving it would mix them (user, 2026-11-14) |
| D62 | **Sign-out hides, it doesn't delete**: the account's queues (incl. its My Wave) are kept and shown only in a collapsed "Removed accounts · N" group at the bottom of the phone switcher (Delete only); not in the car or Android Auto; signing back in to the same uid restores them. Cached lists and unshared downloads are removed | Nothing the user built is lost by a sign-out; the everyday list stays clean (user, 2026-11-14) |
| D63 | **Every Yandex account's My Wave queue exists from sign-in** (and from the migration for already signed-in accounts): created empty at once, filled on first activation; the account's default queue and its entry point in the switcher, car and Android Auto. Re-sign-in of a removed account (same uid) brings back its old WAVE queue with its position (D62), no new one. Today it is created lazily (`PlaybackController.resetForAccount` → `QueueManager.waveQueue`, `QueueManager.kt:224`, only on switch/play) | With no global switch, a queue of the account is the only way into it; My Wave is the natural one (user, 2026-11-14) |
| D64 | **Queue list: manual order, grouped by account by default.** `sortOrder` stays (new queues last, no "recently used" sort). "Group" (RU «Группировать») (Settings → Accounts, only with >1 account, default on): on = a section per account (header avatar + name; its My Wave first, then its queues in manual order; no row markers; reorder within a group); off = one flat list with a marker on every row. Car and Android Auto follow it (AA: a sub-folder per account); the car pill always shows the marker with >1 account. One account: no setting, no headers, no markers | Which account a queue plays under must be visible at a glance without cluttering every row (user, 2026-11-14) |

## Technical design

### 1. Today (the facts that shape this design)

- **One token, read at request time.** `AuthInterceptor` (`data/network/AuthInterceptor.kt:22`) adds
  `authRepository.getToken()` — the *active* account's token — to every Yandex request of the single OkHttp client
  (`di/NetworkModule.kt:42`); one `Retrofit` → one `YandexMusicApi`. Who the call is for is never stated.
- **One shared HTTP cache** (`NetworkModule.kt:54`, `cacheDir/http_cache`); `AccountSessionManager.resetUserData`
  (`data/account/AccountSessionManager.kt:161`) evicts all of it on every switch, because answers belong to the
  account that asked.
- **"Current account" is read in ~10 places** via `getActiveAccount()` / `folderName`: `LibraryRepository` (likes,
  charts; `:75`), `UserPlaylistsRepository` (`:36`), `SearchHistoryRepository` (`:30`, DataStore key by folder),
  `TrackPreloadManager` (quality by `hasPlus`, `:145`), `PlaybackController` (`activeUserFolder` `:307`,
  `resetForAccount` `:771`, `loadUserState` `:1947`), `MainViewModel` (`:58`), `BluetoothAutoplay` (`:74`),
  `AccountSessionManager`. Repositories are `@Singleton`s holding *the* account's lists, guarded by
  "`if (activeFolder != folder) return`" checks against a switch mid-flight.
- **Per-account data today:** cache tables `list_items` / `cache_values` keyed by `owner` = folder name
  (`data/cache/CacheDatabase.kt:25-42`); `queues.account` = folder name, set only for WAVE
  (`data/queue/QueueManager.kt:224`); `WaveQueue` saves its list per folder. Everything else (normal and radio queues,
  History, downloads) is app-wide. Room is **v4**.
- **Downloads** are files keyed by track id, not per account; `clearMusicCache` already protects tracks other
  accounts like (`AccountSessionManager.kt:141`).
- **No play reporting.** `YandexMusicApi` has no `plays` endpoint; the only per-account listening signal YAMP sends
  is rotor feedback (which currently answers 400 for every station). The account's taste comes from its likes and
  that feedback — see Open question (c).

### 2. Accounts (`domain/account/`, `data/repository/AuthRepositoryImpl`)

- `ProviderAccount(provider = YANDEX, id = uid, displayName, avatarUrl, hasPlus)` — the C5 shape; `UserAccount`
  stays as the stored record (token included) behind `AuthRepository`. `AccountId` = `"yandex:<uid>"`.
- A provisional account (`temp_…`, `me`) is not bindable: sign-in completes `refreshAccountStatus` (real uid) before
  the account appears in selectors; a queue never stores a provisional id.
- `AuthRepository` gains `accounts: StateFlow<List<ProviderAccount>>`, `token(accountId)`, `defaultAccount`;
  `switchAccount` / `activeAccount` retire (kept as deprecated shims during the steps, removed at the end).

### 3. Networking per account (D60)

- `YandexClients` (`data/network/`): `api(accountId): YandexMusicApi`, created lazily per account from one base
  `OkHttpClient` via `newBuilder()` (shared connection pool and dispatcher), each with its own `AuthInterceptor`
  bound to that account's token and its own `Cache` in `http_cache/<uid>`. Dropped (cache deleted) on sign-out.
- **How a call names its account:** repository methods take `accountId` explicitly where the caller knows it
  (queue → stream URL, radio refill, rotor feedback, like from the player for the playing track's queue); screens
  (Home, Library, Search) call through `AccountContext.current` — a `StateFlow<AccountId?>` = the active queue's
  account (fallback: default account). With one account both resolve to it: **no behaviour change** (step 1).
- The GitHub update client keeps excluding any Yandex auth (unchanged, `NetworkModule.kt:70`).

### 4. Per-account state

- `LibraryRepository`, `UserPlaylistsRepository`, `SearchHistoryRepository`: state becomes **per account**
  (`Map<AccountId, …>` or a small per-account holder) exposed for `AccountContext.current`; the
  "`activeFolder != folder`" guards become per-account keys. A context change is a view switch, not a reset — no
  eviction, no reload when the data is cached.
- `TrackPreloadManager` / stream resolution: `resolve(trackId, accountId)` — quality by that account's `hasPlus`;
  the reserve and warmed URLs are keyed by account.
- `PlaybackController`: `activeUserFolder` → the active queue's account; `resetForAccount` retires —
  switching to a queue of another account loads that account's wave state lazily (as `loadUserState` does today
  for the one account); `RotorStationSource` and `WaveQueue` receive the queue's `api(accountId)`.
- History (D46) stays app-wide; each row may record the account it played under (nullable, for a later filter) —
  not shown in Phase 2a.

### 5. Queues (`domain/queue/`, `data/queue/`)

- `PlayQueue.account: String?` — already present; now set for **every** queue (`AccountId`), `null` only for queues
  with no Yandex account at all (none today; Jellyfin-only in Phase 4).
- `QueueManager.create(name, kind, account)` and `startRadio(target, …, account)` require it; there is no
  `setAccount` (D61). `queues()` exposes the account so the UI can split live accounts' queues from signed-out ones.
- Re-seeding (`RadioTarget.Existing`) keeps the queue's account; "New radio" takes the picker's.
- **WAVE queue at sign-in (D63):** today `QueueManager.waveQueue(account, name)` creates it lazily, called from
  `PlaybackController.resetForAccount` / `loadUserState` on a switch or a play. Phase 2a calls `waveQueue` from the
  sign-in path (`AuthRepository.saveOrUpdateAccount` once the real uid is known — `AccountSessionManager`) and from
  `AccountKeyMigration` for every stored account. `waveQueue` already returns an existing queue for the account, so a
  re-sign-in with the same uid gets its old WAVE queue (position intact) — no duplicate. First start with no saved
  session: the active queue = the first account's WAVE queue.

### 6. Migration (Room v4 → v5 + a one-time key migration)

- **Room v5:** index on `queues.account`; `history.account` (nullable) added. Room migrations cannot read the
  Keystore account list, so the **value rewrite runs once in code** (`AccountKeyMigration`, before
  `queueManager.load()`, flag in settings):
  - build `folderName → uid` from the stored accounts; rewrite `queues.account`, `list_items.owner`,
    `cache_values.owner`, the search-history DataStore keys and `WaveQueue`'s per-folder list to `yandex:<uid>`;
  - every stored account gets its WAVE queue if it has none (D63);
  - every queue with `account = null` → the default account (else the first); with no account signed in they stay
    `null` and are bound when the first account is added;
  - folders that match no stored account (left over from signed-out accounts) are deleted;
  - collisions (two accounts with the same folder name) cannot be split after the fact: both map to the default
    account's uid, logged once.
- The shared `http_cache` directory is deleted once (per-account caches start empty).

### 7. Sign-out (D62)

`AccountSessionManager.removeAccount(id)`: drop its `YandexClients` entry and cache dir; delete its cached lists;
prune downloads no remaining account likes (the `clearMusicCache` rule). **Its queues are not touched** — normal,
radio and its WAVE queue keep their rows, entries, cursor and position (the WAVE queue is kept hidden too rather
than deleted: consistent with the other queues, and signing back in resumes My Wave where it was). The switcher
derives "signed out" from `queue.account ∉ accounts`; no flag is stored, so signing back in to the same uid (D59)
brings the queues back by itself. If the active queue belonged to the removed account, the controller stops and
switches to the default account's first queue (else My Wave of the next account, else none). `MediaLibraryTree` and
the car panel list only queues of signed-in accounts. Sign-out of the last account: all Yandex queues collapse into
the group (D22).

### 8. UI (`presentation/`)

- `presentation/account/`: `AccountManagementDialog` → a Settings section (list, Add, Remove, Default);
  `StartupAccountPickerDialog` removed; `AccountViewModel` keeps add/remove/default.
- `presentation/queues/`: `QueueNameDialog` gains the account chips (only with >1 account); `QueueSwitcherSheet`
  and the radio-picker mode render account sections (grouped) or markers on rows (flat) per D64; reorder is clamped
  to a group when grouped; the collapsed "Removed accounts · N queues" row at the bottom (phone only) expands to
  dimmed rows with Delete as the only action. No account filter control (fallback in §UX).
- Settings → Accounts: the "Group" (RU «Группировать») switch (`SettingsRepository.groupQueuesByAccount`, default on),
  visible only with more than one account.
- `presentation/car/CarScreen.kt`: pill marker (always with >1 account), panel sections or row markers per D64,
  "New radio" chips in the car picker.
- `data/player/MediaLibraryTree.kt`: "Queues" → one sub-folder per account when grouped, else a flat list with the
  account name as subtitle.
- Home / Library / Search read `AccountContext.current`; the player's like button acts for the playing queue's
  account.

### 9. Size

About 40–50 files: networking/DI (4), accounts/auth (6), repositories and preload (8), player/controller/queues
(8), Room + migration (4), UI (12–15), tests (8–10), plus the canvas boards and docs. The networking and
per-account-state steps are the risky part; UI is mostly additive.

## Canvas (boards under `Design/yamp/`, updated and approved before any UI code)

Every board that shows a queue, a queue list or account management keeps its **one-account** variant as drawn
(D58) and gets its **two-account** variant(s) right next to it, in the same section. The new components and the
variant screens live in `Design/yamp/accounts.jsx` (a components-and-screens module like `queues.jsx`, no
sections of its own); ids `ac-*` (phone) and `acc-*` (head unit, split panes).

| Section (file) | One-account board → two-account variants next to it |
|---|---|
| 1 Foundations (`YAMP.html`) | `f-components` → `ac-components`: account marker (dimmed = removed account), section header, account chips |
| 4 Queues — phone (`YAMP.html`, `queues.jsx`) | `q-sheet` → `ac-sheet-grouped` (headers, My Wave first, no row markers, "Removed accounts" collapsed), `ac-sheet-flat` (marker on every row), `ac-sheet-removed` (expanded, dimmed); `q-menu-radio` → `ac-menu-removed` (Delete only); `q-new` → `ac-new` (account chips); `q-player` → `ac-player` (marker in the header); `q-add` → `ac-add-grouped`, `ac-add-flat` |
| 5 Queues — head unit (`YAMP.html`) | `qc-home` → `acc-home` (pill with the marker); `qc-panel` → `acc-panel-grouped`, `acc-panel-flat` |
| 5b History (`history.jsx`) | `hs-all` → `ac-history` (queue chips with markers) |
| 5c / 5d Radio (`radio.jsx`) | `rd-picker` → `ac-radio-grouped`, `ac-radio-flat` (New radio with account chips; re-seed keeps the account); `rdc-picker` → `acc-radio` (big chips) |
| 8 Split screen (`YAMP.html`) | `sw-queues` → `acc-sw-grouped`, `acc-sw-flat`; `sn-queues` → `acc-sn-grouped`, `acc-sn-flat` |
| 9 Sources / Settings (`signin.jsx`) | `si-sources` → `ac-settings-one`, `ac-settings-multi` (Accounts without the switcher: Add, ⋮ Default / Remove, the "Group" switch only with two) |
| 11a Home (`home.jsx`) | `hm-home-a` → `ac-home` ("as Anna" caption: Home follows the active queue's account) |
| 13 List states (`signin.jsx`) | `si-queue-unavailable` → `ac-removed-open` (a removed account's queue: entries unavailable, no banner) |
| Android Auto | no board; described in section 5's subtitle (sub-folder per account when grouped, account name in the subtitle when flat; removed accounts never shown) |

The setting is labelled **"Group" / «Группировать»** (Settings → Accounts, D64). `search.jsx`, `mix.jsx`,
`folders.jsx`: no queue list of their own — unchanged (`mix.jsx`'s MIX queues get the marker in Phase 6).

## Order of work and verification

1. **Canvas:** the boards above updated (one- and multi-account variants), **reviewed and approved by the user**.
2. **Per-account networking with no behaviour change:** `ProviderAccount`, `YandexClients`, `AccountContext`
   resolving to today's active account; every Yandex call goes through `api(accountId)`. One account → identical
   behaviour; all tests green. Commit.
3. **Keys by uid + Room v5 + `AccountKeyMigration`** (migration test from v4 fixtures with folder-name data). Commit.
4. **Per-account state** in Library / Playlists / SearchHistory / preload / controller; `AccountContext` = the active
   queue's account; `resetForAccount` and the switch path retire. Commit.
5. **Queue binding:** `create` / `startRadio` with the account; radio refill, feedback and streaming by the queue's
   account. Controller tests (two fake accounts). Commit.
6. **UI** per the approved boards: selectors, grouping / markers (D64), menu items, Settings accounts, car, Android Auto; switcher and
   startup picker removed; goldens. Commit.
7. **Sign-out** behaviour + tests. Commit.
8. AVD check with two accounts (the secondary account already on `yamp-headunit` plus one more), phone and unit;
   docs / KB / decisions; HANDOFF.

Each step keeps `testDebugUnitTest verifyRoborazziDebug lintDebug` + detekt green.

## Tests

- Unit: `YandexClients` (token per account, no token to non-Yandex hosts, cache dir per account);
  `AccountKeyMigration` (folder → uid, null → default, orphan folders, collision); `QueueManager` binding /
  re-seed keeps account; controller: two queues on two accounts — refill and feedback hit the right
  account's API, switching queues switches `AccountContext`; sign-out keeps the account's queues (incl. WAVE) but lists them as signed out, re-sign-in with the same uid restores them, the car/Android Auto lists omit them.
- Sign-in → the account's WAVE queue exists (empty) and shows in the switcher, the car panel and Android Auto's
  "Queues"; a second sign-in of the same uid reuses it; first start activates the first account's My Wave.
- D64: grouped list = sections in account order with My Wave first and manual order inside, reorder clamped to the
  group; flat list keeps `sortOrder` across accounts; one account → no headers, no markers, no setting; Android Auto
  sub-folders per account when grouped.
- Migration test v4 → v5 (`CacheDatabaseMigrationTest`).
- Goldens: switcher (one account, grouped, flat), radio picker, New queue, hold menu, Settings accounts, the collapsed and expanded signed-out group — phone, phone
  landscape and **1920×720** — each with one account (must equal today's goldens) and with two.

## Affected files

- New: `domain/account/ProviderAccount.kt`, `data/network/YandexClients.kt`, `data/account/AccountContext.kt`,
  `data/account/AccountKeyMigration.kt`, `schemas/…/5.json`, tests.
- Changed: `di/NetworkModule.kt`, `data/network/AuthInterceptor.kt`, `domain/repository/AuthRepository.kt`,
  `data/repository/AuthRepositoryImpl.kt`, `domain/model/UserAccount.kt`, `data/account/AccountSessionManager.kt`,
  `data/repository/{LibraryRepository,UserPlaylistsRepository,SearchHistoryRepository}.kt`,
  `data/network/TrackPreloadManager.kt`, `data/network/TrackDownloadManager.kt`, `data/cache/{CacheDatabase,
  PlaylistCacheManager}.kt`, `data/queue/{QueueManager,QueueEntities,RoomQueueStore,RotorStationSource}.kt`,
  `domain/queue/PlayQueue.kt`, `data/player/{PlaybackController,WaveQueue,MediaLibraryTree,BluetoothAutoplay}.kt`,
  `data/history/*` (account column), `presentation/account/*` (switcher and startup picker removed),
  `presentation/main/{MainDialogs,MainTabs,MainViewModel,HomeScreen}.kt`, Settings sheet/model and `SettingsRepository` ("Group" (RU «Группировать»)),
  `presentation/queues/*`, `presentation/car/CarScreen.kt`, `presentation/history/*`, strings EN/RU, goldens.
- Canvas: `Design/yamp/{queues,radio,split,home,history,signin,foundations}.jsx`, `Design/YAMP.html` (variants
  registered).
- Docs: `kb/decisions.md` (D22′, D56–D64), `docs/multi-source-player.md` (Phase 2a as built, Phase 3 note: C5/C6
  exist for Yandex), `kb/architecture/*` (networking, accounts).

## Out of scope

- Queues holding entries of several accounts/providers (Phase 3+: the selector becomes multi-select, the marker
  "mixed").
- Jellyfin / Subsonic / local accounts (Phase 4: one account per server + user, per folder for local files — the
  same `ProviderAccount` and `YandexClients`-like client per provider).
- Merging several Yandex accounts into one for search/browse as a setting (discussed; not needed while the context
  account serves search).
- Fixing rotor feedback 400 (separate task).
- **Reporting plays to Yandex** under the queue's account — YAMP (like upstream) sends none, so an account's taste
  learns from likes and rotor feedback only. Planned with the feedback-400 fix (`tasks/todo.md`).

## Risks and side effects

- The per-account networking touches every Yandex call: step 2 must be behaviour-neutral with one account and is
  verified on the AVD before anything builds on it.
- Memory: one Retrofit/OkHttp per account (shared pool) — negligible for 2–3 accounts.
- Two accounts' My Wave and radios can both be warm; only the active queue refills (Phase 2 rule), so request
  volume does not grow.
- The migration rewrites keys in four stores; a failure mid-way must be retryable (idempotent rewrite, flag set
  last).
- Upstream ports touching `AuthRepositoryImpl` / account UI will no longer apply as copies (A3: wrap, don't
  rewrite — the switcher removal is a YAMP divergence, recorded in `kb/upstream-tracking.md`).

## Open questions (decided 2026-11-14)

- (a) Changing a queue's account later — **no, never** (D61).
- (b) Sign-out of an account with queues — **kept, hidden in a collapsed group, restored on re-sign-in** (D62); its
  My Wave queue kept hidden too.
- (c) Play reporting — **not in 2a**; with the feedback-400 fix.
