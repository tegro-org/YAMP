# Lessons

- **2026-11-09 — Rushing toward implementation.** During the design discussion every answer ended with
  "approve and start Phase 0 / give me your GitHub username", while the user was still gathering
  requirements and planning the repo, folder, session and architecture rules. Rule: in design discussions
  answer the question asked and don't push the next step; the user sets the pace. Unknown details
  (names, logins) — leave a placeholder, don't ask for them prematurely.

- **2026-11-09 — Verify paths before cloning.** The user wrote `~/Projects/Guthub` (typo for `Github`);
  `cd` failed and the clones landed in the current repo. Rule: `ls -d` the target first and chain with
  `&&`, never `;`, after a `cd`.

- **2026-11-09 — "Commit" is not "push".** Asked to "update handoff and commit", I chained `git push` into
  the same command (blocked by the permission check). Rule: push only when the user literally says "push";
  never chain push onto a commit.

- **2026-11-09 — Don't name the user's other projects in this repo.** I wrote "copied from <other project>" and
  "like <other project>'s script" into the README, todo and decisions. The repo is public and those projects are
  unrelated to it. Rule: docs, comments and commit messages describe YAMP only; where something came from
  another project of the user's, don't mention it unless the user asks for the reference.

- **2026-11-11 — "Let's commit" is not "commit everything".** After the user said "lets commit and do U2" I made
  three commits in one command without staging each group, showing the message and waiting. Rule: every group,
  every time — stage, report the file list and the message, wait for the user's word, then commit; a general
  go-ahead covers only the first group's staging. The user reviews staged diffs in the IDE, not commits.
