# Branch protection for `main`

Configure a repository **ruleset** (Settings -> Rules -> Rulesets) that targets
the default branch. Rulesets are preferred over classic branch protection:
they apply to administrators too, and OpenSSF Scorecard reads them.

## Current state (2026-10-22)

**This ruleset is not configured yet.** On `CD-LC/vegaduta` today:

- `gh api repos/CD-LC/vegaduta/rulesets` returns `[]`, and
  `gh api repos/CD-LC/vegaduta/branches/main/protection` returns 404
  "Branch not protected". Nothing stops a push to `main` that skipped CI, a
  force push, or a merge without green checks. The rule in
  [GOVERNANCE.md](../../GOVERNANCE.md) and CONTRIBUTING.md is kept by the
  maintainer, not by GitHub.
- OpenSSF Scorecard reports Branch-Protection 0 and Code-Review 0 for the same
  reason (no ruleset, and changesets without a second reviewer). Creating the
  ruleset raises the first; the second stays low while one maintainer merges
  their own pull requests (see "While there is only one maintainer" below).
- The `@CD-LC/maintainers` team named in `.github/CODEOWNERS` has to exist
  before "require review from code owners" can work. `gh api
  repos/CD-LC/vegaduta/codeowners/errors` reports "Unknown owner" for every
  rule until it does.

What the repository owner must do, in this order:

1. Create the public team `maintainers` in the `CD-LC` organization, add the
   project lead, and give it **Write** on the repository
   ([repo-setup.md](repo-setup.md)). Check:
   `gh api repos/CD-LC/vegaduta/codeowners/errors --jq '.errors | length'`
   prints `0`.
2. Open any pull request so `CI OK` and `CodeQL OK` report once (a check name
   is offered in the picker only after it has reported; see "After the first
   run").
3. Create the ruleset on the default branch as described below.
4. Verify: `gh api repos/CD-LC/vegaduta/rules/branches/main --jq 'length'`
   prints a number above 0, and a direct push to `main` is refused.

Until step 3 is done, treat every statement below as the intended
configuration, not as a description of what GitHub enforces.

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
