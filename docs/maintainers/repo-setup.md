# Repository setup (before the first public push)

A checklist for the maintainers. Items marked **decision** need a person to
choose; everything else is mechanical.

## 1. Identity (decision)

The repository's public identity is recorded in
[`scripts/dev/repo-identity.env`](../../scripts/dev/repo-identity.env). It has
been settled: the repository is `CD-LC/vegaduta` and images and charts are
published under `ghcr.io/cd-lc`. The table lists the current values. The
`TODO(maintainers)` notes still in `SECURITY.md`, `MAINTAINERS.md` and
`.github/CODEOWNERS` mark the values still to confirm (contact address, lead
handle, maintainers team); rerun the script below to change any of them.

| Current value | Used for | Decide |
|---|---|---|
| `CD-LC/vegaduta` | GitHub owner/repository: README badges and clone URL, `install.sh`, CITATION.cff, issue-template links, `pom.xml` `<scm>`, Helm chart metadata, theme links | Who owns the `vegaduta` GitHub account. It must be an **organization** (teams, rulesets, org secrets); a user account can be converted under Settings -> Organizations. |
| `ghcr.io/cd-lc` | image and chart registry namespace | follows the owner |
| `@CD-LC/maintainers` | CODEOWNERS | create the `maintainers` team in the organization, or use a username |
| `punith-naga` | MAINTAINERS.md | the project lead's GitHub handle (the account that also authors commits) |
| `contact@cdandlc.com` | SECURITY.md fallback contact | a monitored address |
| `contact@cdandlc.com` | CODE_OF_CONDUCT.md | a monitored address (can be the same) |

Then rewrite every occurrence in one step and review the diff:

```sh
bash scripts/dev/set-repo-identity.sh --dry-run                  # where each value appears
bash scripts/dev/set-repo-identity.sh --dry-run --repo <org>/<repo> --lead <handle> \
  --security-email <addr> --conduct-email <addr>
bash scripts/dev/set-repo-identity.sh --repo <org>/<repo> --lead <handle> \
  --security-email <addr> --conduct-email <addr>
git diff
```

`--registry` defaults to `ghcr.io/<owner>` and `--team` to
`@<owner>/maintainers`. Afterwards, `grep -rn 'TODO(maintainers)' .` lists the
remaining notes to delete.

## 2. Repository settings

- Create the repository **public** (CodeQL, dependency review and Scorecard
  publishing need a public repository or GitHub Advanced Security; otherwise
  the first runs fail and the README badges show "failing"). While the
  repository is private, those jobs are skipped instead: `Dependency review`
  and CodeQL's analysis (`CodeQL OK` still reports, as passed), and the
  Scorecard analysis. All of them run again on their own once the repository
  is public. With Advanced Security on a private repository, set the Actions
  variable `ADVANCED_SECURITY=true` to run dependency review and CodeQL
  (Scorecard still needs a public repository).
- Settings -> Code security: enable **private vulnerability reporting**
  (SECURITY.md depends on it), Dependabot alerts and security updates, secret
  scanning and push protection.
- Discussions: enable (the issue templates and SUPPORT.md link to them).
- Labels: `bash scripts/dev/create-labels.sh <org>/<repo>` (Dependabot and the
  labeler cannot apply labels that do not exist).
- Branch ruleset: [branch-protection.md](branch-protection.md).
- Actions -> General: "Read repository contents" as the default token
  permission; do not allow Actions to create or approve pull requests.

## 3. History (decision)

- Run a full-history secret scan before the first push:
  `SECRET_SCAN_FULL=1 bash scripts/ci/secret-scan.sh`. Known, accepted findings
  are listed with the reason in `.gitleaksignore`; one of them is a key for the
  hosted site that must be **rotated** before publishing.
- Consider publishing a single, reviewed "initial public commit" (signed off,
  see the DCO in CONTRIBUTING.md) instead of the import history. If you do,
  re-run the scan and regenerate `.gitleaksignore` (fingerprints include the
  commit id).
- Done on 2026-10-19: `main` was force-pushed to a clean linear history (see
  the notice in `AGENTS.md`). Checkouts older than that must reset to
  `origin/main` rather than pull or merge.
- Applied Flyway migrations contain comments that name internal documents and
  people. They are checksum-bound (editing an applied migration fails Flyway
  `validate` on every existing install) and are intentionally left as they are.

## 4. Line endings (one-time)

`.gitattributes` normalizes text files to LF. The index is already LF, so the
one-time renormalization touches only the few files that had mixed endings:

```sh
git add --renormalize .
git status   # expect a handful of files
```

## 5. First release

See [releasing.md](releasing.md): the first tag must be a stable version, and
the GHCR packages must be made public after the first run. Done: the first
stable release was `v0.1.0`.
