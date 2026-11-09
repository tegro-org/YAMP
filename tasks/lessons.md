# Lessons

- **2026-11-09 — Rushing toward implementation.** During the design discussion every answer ended with
  "approve and start Phase 0 / give me your GitHub username", while the user was still gathering
  requirements and planning the repo, folder, session and architecture rules. Rule: in design discussions
  answer the question asked and don't push the next step; the user sets the pace. Unknown details
  (names, logins) — leave a placeholder, don't ask for them prematurely.

- **2026-11-09 — Verify paths before cloning.** The user wrote `~/Projects/Guthub` (typo for `Github`);
  `cd` failed and the clones landed in the current repo. Rule: `ls -d` the target first and chain with
  `&&`, never `;`, after a `cd`.
