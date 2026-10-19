## What and why

<!-- What this changes and the problem it solves. Link the issue: "Fixes #123". -->

## How it was verified

<!-- Be exact about what you ran: compiled, unit-tested, booted against Postgres,
     tried in a browser, ran the e2e suite. "Compiled" is not "tested". -->

## Breaking changes

<!-- None, or: what breaks, who is affected, and the migration path
     (config keys, API responses, database, Helm values). -->

## Checklist

- [ ] Every commit is signed off (`git commit -s`), certifying the [DCO](https://developercertificate.org/) (checked by the `DCO sign-off` CI job)
- [ ] Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `docs:` ...)
- [ ] Tests added or updated for the change, and `make test` / the component's tests pass locally
- [ ] `make gates` passes (Flyway lint, bean/entity lint, secret scan, ...)
- [ ] New Flyway migration? Its version is unique across **both** `controlplane/` and `tenant/`, and no applied migration was edited
- [ ] No secrets, tokens or personal data in code, fixtures, logs or screenshots
- [ ] No browser-facing URL hardcoded to `localhost` / `127.0.0.1`
- [ ] Docs, `.env.example` or `CHANGELOG.md` (`## [Unreleased]`) updated if behavior or configuration changed
- [ ] Docs changed? `bash scripts/ci/docs-link-check.sh` passes (relative links and anchors)
