# Dependabot pull requests

Dependabot opens the dependency update pull requests configured in
`.github/dependabot.yml` (Maven, npm, pip, Docker, GitHub Actions). Two checks need a
maintainer's attention on them: the DCO check, which skips the bot's commits by design, and
the Python lock check, which Dependabot cannot satisfy by itself.

## DCO sign-off

Dependabot and the GitHub Actions bot never add a `Signed-off-by:` trailer, so the
`DCO sign-off` job (part of the required `CI OK` check) would fail every one of their pull
requests. `scripts/ci/dco-check.sh` therefore skips a commit when both of these hold:

- the pull request was opened by `dependabot[bot]` or `github-actions[bot]` (the login comes
  from the GitHub event, not from the commit), and
- the commit's author is that same bot's noreply identity
  (`<id>+dependabot[bot]@users.noreply.github.com`).

Every other commit is still checked, so a commit you push onto a Dependabot branch needs
`git commit -s`, and a human pull request cannot pass by naming a bot as the commit author.
`scripts/ci/test-dco-check.sh` covers these cases; the `DCO sign-off` job runs it first.

## Regenerating `requirements.lock` on a Dependabot branch

Dependabot edits `requirements.txt` (the direct, exactly pinned dependencies) but cannot
regenerate `requirements.lock`, the hashed file the service images install with
`pip install --require-hashes`. A pip update pull request under `services/` therefore fails
**Python requirements.lock up to date** until a maintainer regenerates the lock and pushes it
onto the branch. The check regenerates every service's lock with uv 0.13.0 and fails on any
difference, so regenerate all of them, as CI does:

```sh
gh pr checkout <number>                         # the Dependabot branch, tracking origin
python3.12 -m venv /tmp/uv-venv && /tmp/uv-venv/bin/pip install uv==0.13.0
PATH=/tmp/uv-venv/bin:$PATH bash services/lock-requirements.sh
git diff --stat -- 'services/*/requirements.lock'   # only the bumped packages should move
git add services/*/requirements.lock
git commit -s -m "build(deps): regenerate requirements.lock"
git push
```

Then run the tests of each service the pull request touches (the table in
[AGENTS.md](../../AGENTS.md)) and wait for CI. Notes:

- Sign the commit (`-s`): it is a human commit, so the DCO job checks it.
- Once someone else has pushed to a Dependabot branch, Dependabot no longer rebases it on
  its own; to hand the pull request back, comment `@dependabot recreate` (this discards your
  commit, so regenerate the lock again afterwards) or merge `main` into the branch yourself.
- If the lock regenerates with changes that are not part of the update (a transitive
  package moved), that is expected: the lock resolves the whole tree. Review the diff for
  anything surprising, and run `pip-audit -r services/<service>/requirements.lock`.
- A major bump (for example `openai` 2 to 3) can need code changes; fix them on the same
  branch or close the pull request and bump by hand (edit `requirements.txt`, run the script,
  commit both files).
