# Governance

This document describes how VegaDūta is run today and how that is expected to
change as the contributor base grows.

## Model: project lead with maintainers

VegaDūta starts with a **project lead** (a "benevolent dictator for life", or
BDFL, model) supported by **maintainers**. The current people in each role are
listed in [MAINTAINERS.md](MAINTAINERS.md).

- **Contributors**: anyone who opens an issue, discussion or pull request.
- **Maintainers**: review and merge pull requests in the areas they own, triage
  issues, cut releases, and uphold the [Code of Conduct](CODE_OF_CONDUCT.md).
  Maintainers are code owners in [.github/CODEOWNERS](.github/CODEOWNERS).
- **Project lead**: sets direction and the [roadmap](ROADMAP.md), appoints and
  removes maintainers, and makes the final call when consensus fails.

## How decisions are made

1. **Everyday changes** (bug fixes, features within an agreed direction, docs)
   are decided in the pull request. One maintainer approval and green checks
   are enough to merge. Changes to migrations, `core/crypto`, `core/security`,
   CI or the release pipeline need a maintainer who owns that area.

   **Interim rule while there are fewer than two maintainers:** nobody can
   approve their own pull request, so the project lead may merge their own
   pull request once the required checks are green and it has been open for
   at least 48 hours (24 hours for fixes to security issues or broken `main`),
   so that others can comment. Every change still goes through a pull request.
   The branch ruleset matches this (docs/maintainers/branch-protection.md);
   the rule ends when a second maintainer is appointed.
2. **Significant changes** (a new public API, a new service, a breaking change,
   a new required dependency, a change to the data model or to the install
   tiers) start as a GitHub Discussion or an issue labeled `proposal`
   describing the problem, the design and the alternatives. The proposal stays
   open for at least 7 days. Maintainers aim for consensus (no sustained
   objection); the project lead decides if there is none, and records the
   reasoning in the thread.
3. **Governance changes** (this file, the license commitment below) need a
   pull request open for at least 14 days and the project lead's approval.

Decisions and their reasons are recorded in the pull request, the proposal
thread, or an architecture decision record under `docs/`.

## Becoming a maintainer

Maintainership is earned through sustained, high-quality contribution:

- a track record of merged pull requests in an area over roughly three months or more;
- helpful reviews of other people's pull requests and help with issue triage;
- good judgment about compatibility, security and scope, and conduct in line
  with the Code of Conduct.

Any maintainer can nominate a contributor (self-nomination is fine) in a
private maintainers' discussion. The project lead confirms after checking that
no maintainer objects. New maintainers are added to MAINTAINERS.md and
CODEOWNERS in a pull request.

Maintainers who are inactive for six months may be moved to emeritus status,
after being asked. They can return by asking.

## Response targets

Maintainers aim to:

- triage new issues within 7 days;
- give a first review on pull requests within 7 days;
- acknowledge security reports within 3 working days (see [SECURITY.md](SECURITY.md)).

These are targets for a small team, not guarantees.

## License commitment

- **The platform is BSL-1.1 and converts to Apache-2.0.** Everything needed to
  run VegaDūta — the core server, the consoles, the services under
  `services/`, the Compose files and the Helm charts — is licensed under the
  Business Source License 1.1: free for any use except offering the software
  itself to third parties as a competing hosted service, and each version
  converts to the Apache License 2.0 four years after its release.
- **The SDKs stay Apache-2.0.** The `sdk/` directory (the Java and Python
  Agent SDKs and the TypeScript and Python clients) is licensed under Apache-2.0 so the clients can be
  embedded in any application, proprietary or not.
- **No split-licensed server.** There is no "open core" edition in which
  parts of the server (for example SSO, RBAC, audit logging, multi-tenancy or
  scaling features) move to a proprietary license. Monetization comes from
  commercial licenses for hosted offerings, support and add-ons — not from
  withholding platform features.
- Contributions are accepted under the project license via the DCO (see
  [CONTRIBUTING.md](CONTRIBUTING.md)); there is no contributor license
  agreement that would allow relicensing.
- Hosted services, support or separately distributed add-ons may be offered
  by the licensor under a commercial license; offering the platform itself to
  third parties as a hosted service requires that license, per `LICENSE`.

Changing this commitment would require the governance-change process above
and could not apply retroactively to versions already released: every version
becomes Apache-2.0 on its change date regardless.

## Evolution

When there are at least three active maintainers from more than one
organization, the project intends to move from the project-lead model to a
steering committee elected by maintainers, and to document that change here.
