# Change coordination

Several contributors work on this repository at the same time. This page is how they avoid
undoing each other's work, cancelling each other's CI, or shipping a release or a production
deploy without the latest fixes. **Read it before you push, merge, tag, release or deploy.**

## Rules

1. **No direct pushes to `main`.** Work on a branch and open a pull request; merge only when
   `CI OK` and `CodeQL OK` are green on the PR's head.
2. **One release at a time, run by one person.** The maintainer pushes the `vX.Y.Z` tag; the
   release owner dispatches `.github/workflows/release.yml` on it. Nobody else dispatches,
   re-runs or re-tags it.
3. **Between a tag and its published release, nothing merges to `main`.** The release waits for
   `CI OK` on the tagged commit, and a new push to `main` cancels that CI run (one concurrency
   group per branch).
4. **Claim before you edit.** For work that spans many files, say which files or area you are
   changing in the pull request or tracking issue, and stay off files someone else has claimed
   until their PR merges, or ask them.
5. **One working tree per task.** Do not share a checkout: a shared tree means a shared
   `git status`, a shared HEAD and conflicting stashes. Use a worktree instead:
   `git worktree add -b <your-branch> ../<repo>-<area> origin/main`. Remove it with
   `git worktree remove` when finished. Never run a blanket `git add -A` in a shared tree;
   stage your own paths only.
6. **Production runs published releases only.** Deploy a release's images by version
   (`VEGADUTA_VERSION=X.Y.Z`); never images built from a branch or from `main`.
7. **Never change `APP_ENCRYPTION_KEY`** on a deployment that has data
   ([ENGINEERING-RULES.md](../../ENGINEERING-RULES.md), rule 5).
