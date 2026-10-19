# ADR-0019: Conformance by construction

- **Status:** Accepted for the first slice (list paging and configuration reference). The rest of the bet stays a proposal.
- **Date:** 2026-10-15
- **Bet:** [Architecture bets, October 2026, section 13](ARCHITECTURE-BETS-2026-10.md)

## Context

Conventions that live only in documents drift. Two have drifted visibly: list `GET` endpoints that return an
unbounded array (151 of the 564 `GET` operations in `docs/api/openapi.json` today return an array and take no
`limit`, `offset`, `page`, `size` or `cursor`), and configuration properties that exist in `application*.yml`,
`@Value` or `@ConfigurationProperties` but appear in no documentation (about 1,240 names today). The repository
already turns other conventions into failing tests (`AuthorizationCoverageTest`, the bean and entity name lints, the
file-size ratchet, the ArchUnit baselines).

## Decision

Convert each convention into a check with a **ratcheting allowlist**: today's violations are listed once, a new
violation fails the build, and an allowlisted entry that has been fixed also fails the build until the line is deleted.
An allowlist can therefore only shrink.

1. **List paging.** `core/app` `ListPagingConformanceTest` reads the committed `docs/api/openapi.json` and fails for
   every list-returning `GET` (a 200, 201 or 202 JSON response whose schema is an array, following `$ref`) without a
   paging query parameter, unless it is in `core/app/src/test/resources/conformance/list-paging-allowlist.txt`.
2. **Configuration reference.** `scripts/ci/gen-config-reference.py` scans `core/**/application*.yml`, `@Value` and
   `@ConfigurationProperties` (prefix plus simple fields) and writes
   `docs/engineering/CONFIG-REFERENCE.generated.md`. `--check` fails when a property is neither mentioned in `docs/`
   (or `deploy/compose/.env.example`, in dotted, kebab or ENV_VAR form, or via a documented dotted ancestor) nor in
   `scripts/ci/config-reference-allowlist.txt`, and when an allowlist line is now documented or gone.

## Consequences

- A new list endpoint must page, or the author has to argue for an allowlist line in review (the file header says it
  must not grow).
- Fixing an endpoint makes the test demand deleting its line, so progress is visible in diffs.
- The OpenAPI-driven check inherits the generator's limits: it sees only what the reflection-based generator emits, and
  it reads the committed snapshot, so a controller change is checked only after the snapshot is regenerated.
- The config scan is textual, not a Spring binder: a property reached only by a computed key is not seen, and a
  property whose name merely appears in prose counts as documented. It is a floor, not a proof.

## Not in this slice

Per-route `@Unbounded(reason)` annotation, tenant-isolation coverage per table, and wiring `--check` into
`make gates` and CI (the integrator owns those files). The existing authorization coverage test already covers
mutating routes.
