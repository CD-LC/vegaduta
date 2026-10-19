# Flyway versioning in the OSS repository

## One namespace, two folders

`core/app/src/main/resources/db/migration/controlplane` and
`.../db/migration/tenant` are **one** Flyway version namespace.
`spring.flyway.locations` merges both folders into a single ordered run at boot,
so the same version in each folder is a hard boot failure ("Found more than one
migration with version N"), not two independent migrations.
`scripts/ci/flyway-lint.sh` check (c) fails on any duplicate across both folders.

## OSS-local migrations use a dotted minor version: `V<max>.<n>__`

The upstream (private) repository keeps allocating integer versions
(`V366`, `V367`, ... - synced through `V369` so far). A migration written in this repository must never take
the next integer, or the next upstream sync collides with it. Instead it takes a
**dotted minor version on top of the current maximum**:

```
current max across BOTH folders: V369 (synced from upstream)
OSS-local migrations:            V369.1__...   (earlier ones: V365.1 ... V365.5)
next upstream migration:         V370__...
```

Flyway compares versions numerically part by part, so
`365 < 365.1 < 365.2 < 366`. The second upstream sync brought `V366`-`V369`, which sort
*after* the OSS-local `V365.x` files; that is intended (the `V365.x` files were written
against V365 and are independent of the later upstream ones), and `out-of-order: true`
makes it harmless on databases that applied them in the other order. The dotted versions slot in after the migration they
were written against and before anything upstream adds later. They never
collide with an integer version.

Rules:

1. Find the current maximum across **both** folders:
   `ls core/app/src/main/resources/db/migration/{controlplane,tenant} | sort -V | tail -3`.
2. Use `V<max>.<n>__description.sql`, with `<n>` the next free minor under that
   maximum in **either** folder. Use the dot form (`V365.1`), not the underscore
   form (`V365_1`). Flyway reads both as version 365.1, but `flyway-lint.sh`
   parses only the dot form (`^V([0-9]+(\.[0-9]+)*)__`). An underscore file is
   silently left out of the duplicate check.
3. If upstream has since added a higher integer version, new OSS migrations
   move to the new maximum (for example `V367.1__`). Never renumber a
   migration that has already shipped. Renumbering is an edit of an applied
   migration, and check (a) in the lint rejects it.
4. `out-of-order: true` is already on (`application.yml`). A database that
   applied `V366` before it sees a later-merged `V365.x` still applies it.
   Every migration here must stay additive and `IF NOT EXISTS`-safe so that an
   out-of-order apply is harmless.
5. Say which folder and why in the migration header, as every existing
   migration does.

## Current OSS-local migrations

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
