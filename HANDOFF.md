# HANDOFF — 2026-11-15

## State

- **Released:** `v0.3.0` — new app id `org.tegro.music.yamp` (D55; fresh install beside the old `org.tegro.yamp`,
  sign in again), Phase 2a accounts, car skip/focus fixes, journal diagnostics, "Start radio" on every queue.
  Before it: `v0.2.1` (queues, History, radio). `main` = the release commit; all feature/fix branches merged.
- **Phase 2a accounts** (`docs/phase-2a-accounts.md`, as built; D22′, D56–D64): all signed-in Yandex accounts live
  at once, no global switch — the active queue's account is the context (Home, Library, Search, likes); every queue
  bound to its account at creation, permanently; every account's My Wave queue exists from sign-in; queue list
  grouped by account ("Группировать", default on) or flat with markers; sign-out hides the account's queues
  ("Удалённые аккаунты"), re-sign-in (same uid) restores them. Per-account `YandexClients` (own token + HTTP
  cache); accounts keyed `yandex:<uid>`; Room v5. Debug builds: Hide/Unhide in Settings → Accounts tests sign-out
  without losing the token.
- **Player** (branch work of 2026-11-14/15): a picked track loads first, then a 0.5 s skip fade (no 3 s
  crossfade); "Keep playing under announcements" (default on, ducks unless a call); "Keep both players running"
  (experimental, off); a preloaded pick starts instantly. Journal lines: `UNDERRUN`, `TIMING`, `FORMAT`, `OUTPUT`,
  `SETTINGS`.
- **Radio (Phase 2)**: many `RADIO` queues, picker, Keep; "Start radio" in the track menu on every queue, "Wave by
  track" stays on My Wave (D65 revises D54).
- **`debug/radio-probe`** (pushed): debug-only rotor-station probe; cherry-pick when needed.
- **Builds:** `scripts/build-apk.sh debug` → `dist/YAMP-b<N>-…-debug.apk`; debug build numbers come from the local
  counter `dist/.build-number` (+1 per build) — never share two APKs with the same number. Release builds use the
  commit count. Uploads: the user's Nextcloud share by WebDAV `PUT`. Releases: tag `vX.Y.Z` → `release.yml`;
  replace the auto notes with `gh release edit`.
- **Head-unit AVD** `yamp-headunit` (1920×720), `org.tegro.music.yamp` with the user's 3 Yandex accounts;
  `adb -s emulator-5554`. Never sign accounts out there (use debug Hide). Tokens are Keystore-encrypted — don't
  extract.
- The real unit's screen is 1760×720 (navigation panel on the left).

## Known issues

- **New-track stutter on the unit**: with "Keep both players running" the current track no longer stutters, the
  new one stutters 1–3 times; no `UNDERRUN` on the active player. Hypothesis: output reconfiguration on track start —
  check `FORMAT`/`OUTPUT` lines in the next log.
- **Auto-pause**: tied to lossless (FLAC) on a slow link — repeated buffering, then a permanent focus loss; gone
  with "compatible" quality. Idea: fall back to a lighter format on a slow link.
- **Rotor feedback 400** for every station; YAMP also never reports plays to Yandex — fix both together (todo).
- App restarted once on the unit without a journal trace (2026-11-15 12:52) — probably killed in the background.

## Next steps (user sets the pace)

1. Read the next unit log (`FORMAT`/`OUTPUT` vs stutter, `SETTINGS`).
2. Next-track buffering state (preload early, Next never disabled, buffered part in the progress bar) — canvas first.
3. Rotor feedback 400 + play reporting under the queue's account.
4. Split `PlayerRepositoryImpl` (in the detekt baseline).
5. Upstream 1.14.2–1.14.4 triage; Phase 1 leftovers; Phase 3 per `docs/multi-source-player.md`.

## Notes

- Workflow: design doc approved before code; stage → report → confirm → commit, one group at a time; push/merge only
  on the user's word. Chat: short. The user sets the pace. Heavy work goes to subagents (keep the session slim).
- New strings: EN in `values/`, RU in `values-ru/`. Gates: `cd android && sh gradlew testDebugUnitTest
  verifyRoborazziDebug lintDebug compileDebugAndroidTestKotlin` + detekt (jar per `.github/workflows/ci.yml`).
- Docs and commit messages describe YAMP only; the repo is public — no server URLs, tokens, keystores.
- User setup: `kb/environment.md`. Upstream clone for reading: `~/Projects/Github/YandexMusicLite`.
