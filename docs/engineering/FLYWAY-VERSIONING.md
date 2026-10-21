# Flyway versioning in the OSS repository

## One namespace, two folders

`core/app/src/main/resources/db/migration/controlplane` and
`.../db/migration/tenant` are **one** Flyway version namespace.
`spring.flyway.locations` merges both folders into a single ordered run at boot,
so the same version in each folder is a hard boot failure ("Found more than one
migration with version N"), not two independent migrations.
`scripts/ci/flyway-lint.sh` check (c) fails on any duplicate across both folders.

## New migrations take the next free integer

This repository allocates integer versions itself: since `V370` every new migration takes the
next integer version free across **both** folders (the current maximum is `V661`, in
`tenant/`). This is the rule in [CONTRIBUTING.md](../../CONTRIBUTING.md#flyway-migrations).

Rules:

1. Find the current maximum across **both** folders:
   `ls core/app/src/main/resources/db/migration/{controlplane,tenant} | sort -V | tail -3`.
2. Use `V<max+1>__description.sql`. If another branch takes the same number first, move yours to
   the next free one before it is released.
3. Never edit, rename or renumber a migration that has already shipped. Renumbering is an edit of
   an applied migration, and check (a) in the lint rejects it.
4. `out-of-order: true` is already on (`application.yml`). A database that applied a higher
   version before it sees a later-merged lower one still applies it. Every migration here must
   stay additive and `IF NOT EXISTS`-safe so that an out-of-order apply is harmless.
5. Say which folder and why in the migration header, as every existing migration does.

## History: dotted minor versions (`V365.1` to `V369.1`)

While this repository was synced from an upstream (private) repository that kept allocating
integer versions (synced through `V369`), migrations written here took a **dotted minor version
on top of the then-current maximum** so the next upstream sync could not collide with them:
`V365.1` to `V365.5` (written against V365) and `V369.1` (written against V369). Flyway compares
versions numerically part by part, so `365 < 365.1 < 365.2 < 366`; the later upstream `V366` to
`V369` sort after the `V365.x` files, which is harmless with `out-of-order: true`. Those files
keep their versions (a shipped migration is never renumbered). Should a dotted version ever be
needed again, use the dot form (`V365.1`), not the underscore form (`V365_1`): Flyway reads both
as 365.1, but `flyway-lint.sh` parses only the dot form (`^V([0-9]+(\.[0-9]+)*)__`), so an
underscore file is silently left out of the duplicate check.

## Dotted (OSS-local) migrations

| Version | Folder | Purpose |
|---|---|---|
| `V365.1` | controlplane | `control_plane.tenant.version`: optimistic lock (JAVA-007) |
| `V365.2` | tenant | `version` on `agent`, `workflow_definition`, `studio_app` and `payment_transaction` (JAVA-007) |
| `V365.3` | tenant | Six indexes for real query patterns (JAVA-020) |
| `V365.4` | tenant | `tenant_secret.allowed_hosts`: per-secret destination allow-list (SEC-006) |
| `V365.5` | tenant | `expires_at` on `agent_share` and `workflow_share` (SEC-051); `subject_hash` on the semantic response cache (SEC-052) |
| `V369.1` | tenant | `version` on `workflow_comment` (JAVA-007 convention; the table comes from upstream `V367`) |

## Why no `CREATE INDEX CONCURRENTLY`

Flyway runs each migration in a transaction, and `CREATE INDEX CONCURRENTLY`
cannot run inside one. Every index in these folders is a plain
`CREATE INDEX IF NOT EXISTS`. Per-tenant tables are small enough that the brief
`SHARE` lock is acceptable. A large control-plane table would need a separate
non-transactional migration (`-- flyway:executeInTransaction=false`) on its own.
