# Upstream reference material

Kept as imported from YandexMusicLite `fac6af7`, for reference only — not part of our build.

- `upstream-README.md` — upstream's README (feature list, screenshots refer to their repo).
- `github-workflows-reference/` — upstream `.github/` (CI `ci.yml`, `release.yml`, dependabot). They
  assume the Gradle project at repo root and secrets `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD`; our CI must
  be rebuilt for `android/` (Phase 0).

Not imported: upstream `docs/` (their GitHub Pages website), tags, dependabot branches.
