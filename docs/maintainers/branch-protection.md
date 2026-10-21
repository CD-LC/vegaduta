# Branch protection for `main`

Configure a repository **ruleset** (Settings -> Rules -> Rulesets) that targets
the default branch. Rulesets are preferred over classic branch protection:
they apply to administrators too, and OpenSSF Scorecard reads them.

## Required status checks

Require exactly these check names, with "require branches to be up to date"
enabled:

| Check | Workflow | What it covers |
|---|---|---|
| `CI OK` | `ci.yml` | Every CI job the change's paths trigger (core, the Java Agent SDK, SDK clients and consumer contracts, web, Python incl. the lock-drift check and the other Python services, Node services, shellcheck, hadolint, compose, Helm, and `docs links` when docs change), plus the always-on jobs: `Static gates` and `Secret scan (gitleaks)` (from `static-gates.yml`, called by `ci.yml`), `DCO sign-off` and `Dependency review` (pull requests only; skipped while the repository is private, see [repo-setup.md](repo-setup.md)). Jobs skipped by path filters count as passed; a failed or cancelled job fails it. |
| `CodeQL OK` | `codeql.yml` | CodeQL for `java-kotlin`, `javascript-typescript` and `python`. Runs on every pull request, including docs-only changes, so it always reports. While code scanning is unavailable (a private repository without Advanced Security) the analysis is skipped and `CodeQL OK` passes; see [repo-setup.md](repo-setup.md). |

Do **not** require the individual job names (`core (Maven verify, JDK 21)`,
`Analyze (python)`, ...). Path-filtered jobs do not run on every pull request,
and a required check that never reports blocks the merge forever
("Expected - waiting for status to be reported"). The two aggregators above
always report.

Advisory jobs (the http-gateway example suite, the whole-tree ruff run) are
`continue-on-error` and never block.

## Other rules

- Require a pull request before merging; 1 approval; dismiss stale approvals
  when new commits are pushed; require approval of the most recent push;
  require review from code owners (see `.github/CODEOWNERS`).
- Block force pushes and branch deletion. Require linear history if you merge
  with squash or rebase.
- Code scanning: optionally add the "Require code scanning results" rule
  (CodeQL, errors and above) in addition to the `CodeQL OK` check.
- Bypass list: empty. Scorecard's Branch-Protection check scores admin bypass
  lower, and every change should go through the same checks.

## While there is only one maintainer

GitHub does not let an author approve their own pull request. Until a second
maintainer exists (GOVERNANCE.md, "Interim rule"), set the required approvals
to 0 but keep "require a pull request" and the two required checks, so every
change still goes through CI. Restore 1 approval when the second maintainer
is appointed.

## After the first run

A check name appears in the ruleset's picker only after it has reported once.
Push a branch and open a pull request first, then add `CI OK` and `CodeQL OK`.
