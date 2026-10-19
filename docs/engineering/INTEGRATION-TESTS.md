# Integration tests (core, Testcontainers)

Unit tests cannot see the failures that have stopped core images from booting
in the past: a Flyway migration that only breaks on an empty database, a
version collision between `controlplane/` and `tenant/`, tenant DDL that
assumes `public`, an extension missing from the Postgres image, or a bean
that no longer wires. The integration suite in
`core/app/src/test/java/com/agenticai/core/it/` runs core against a real
Postgres with pgvector and Apache AGE, plus a Redis-protocol server, all in
throwaway containers.

## What is covered

| Class | What it proves |
|---|---|
| `MigrationsIT` | Every controlplane + tenant migration applies to an **empty** database with the same Flyway settings core uses at boot (`spring.flyway.*`). A fresh tenant schema then migrates with plain Flyway, so the already-exists repair in `TenantSchemaMigrator` cannot hide a broken script. Two more tenant schemas go through `TenantMigrationService`, the code the boot runner and the migrate-only Job use. Each ends with the same version set, and a second pass changes nothing. It also checks that the `vector` and `age` extensions exist. |
| `ApplicationContextIT` | A full `@SpringBootTest` boot with the deployed profile set `security,knowledge,graph-memory`. Flyway runs at startup, JPA validates every entity (`ddl-auto=validate`) and every bean has to wire. `/actuator/health/readiness` and `/liveness` must return UP. Keycloak is never contacted, because the JWT issuer is resolved only on the first authenticated request. Also checks that a tenant saved through JPA gets a migrated schema. |
| `AzureBlobStorageIT` | `AzureBlobFileStorageService` against the Azurite emulator (the real Azure Blob API): round trip, overwrite, inclusive range reads, missing objects, tenant isolation over the wire, cross-tenant listing. Image override `vegaduta.test.azurite.image`. |
| `AgeOptionalIT` | Apache AGE is optional: against `pgvector/pgvector:pg16` (no `age` extension) the startup migration and a tenant schema apply with V44 recorded as skipped, and plain Flyway is shown to fail there. Uses its own container; image override `vegaduta.test.pgvector.image`. |
| `PaymentConcurrencyIT` | The money races, against real Postgres row locks. Two concurrent FIRST charges on one mandate: the second must block on the `payment_mandate` row lock and then fail `CAP_DAILY` (JAVA-001). A reconciliation expiry racing a webhook capture: the expiry must block on the `payment_transaction` row lock and then leave the row `CAPTURED` with no cap reversal (JAVA-002). The first transaction holds its locks until the test sees the second waiting in `pg_stat_activity`, so the check is deterministic, not timing luck. |
| `KnowledgeVectorSearchIT` | The pgvector path: `<=>` cosine ordering in a tenant schema, the HNSW `SET LOCAL` tuning (requires pgvector 0.8.0 or later), and `KnowledgeSearchService.search` end to end against real SQL. Only the embedding call and the JPA collection lookup are stubbed. A near-identical chunk in another tenant's schema must never appear in the results. |
| `DpdpControlsIT` | The DPDP-oriented controls against Postgres with two migrated tenant schemas, the schema chosen per statement from the `TenantContext`: the consent gate and ledger (hold, agree, language, decline, notice change, withdraw with erasure), nightly retention purge (only the running tenant's expired rows go, an audit event per purge, a failed recording delete is kept for retry), per-contact erase and export (derived knowledge chunks, memory and messages removed, another tenant's identical contact untouched, an incomplete erasure reported), the audit export per tenant and range, and purpose tags. Graph memory, Redis session history and object storage are mocked. |

## Running them

They are excluded from the default build. `mvn test` and `mvn verify` do not
need Docker. The `*IT` classes fall outside surefire's includes, and core/app's
surefire also excludes the `integration` tag.

```bash
cd core
# Build the pgvector+AGE image once and reuse it (recommended)
docker build -t vegaduta-postgres:it ../deploy/postgres
mvn -P it verify -Dvegaduta.test.postgres.image=vegaduta-postgres:it

# Only the integration tests (reactor still compiles the upstream modules)
mvn -P it -pl app -am verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false

# One class
mvn -P it -pl app -am verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=MigrationsIT
```

Use `-pl app -am`, not `-pl app` on its own. Without `-am`, Maven takes the
sibling modules from `~/.m2`, and those jars can be stale.

| System property | Default | Purpose |
|---|---|---|
| `vegaduta.test.postgres.image` | *(unset)* | A pre-built image with pgvector and AGE. When unset, the suite builds `deploy/postgres/Dockerfile` on the fly. This adds a few minutes, and Ryuk deletes the image when the JVM exits. |
| `vegaduta.test.redis.image` | `valkey/valkey:8-alpine` | Any Redis-protocol image, such as `redis:7-alpine`. |

Requirements: a Docker daemon that Testcontainers can reach (Docker Desktop,
Rancher Desktop with dockerd, or Linux dockerd). When no daemon is reachable,
every class is **skipped**, not failed (`@Testcontainers(disabledWithoutDocker = true)`).

Testcontainers is pinned to 1.21.4 in `core/pom.xml` (`testcontainers.version`),
overriding the 1.20.6 that Boot 3.4.4 manages. 1.20.x speaks Docker API 1.32.
Docker Engine 29 rejects that version, and every class then skips as if Docker
were missing.

Running the suite leaves no containers or built images behind. Ryuk removes
them when the test JVM exits. Images that were pulled stay in the local cache:
`testcontainers/ryuk`, the Redis image, and `postgres:16` when the Postgres
image is built on the fly.

## CI

The `core` job in `.github/workflows/ci.yml` already builds
`vegaduta-postgres:ci` from `deploy/postgres` for `CoreApplicationTests`. The
Maven step runs `mvn -Pcoverage,it verify -Dvegaduta.test.postgres.image=vegaduta-postgres:ci`,
so the suite runs after the unit tests in the same reactor pass and reuses that
image. On failure, `failsafe-reports` are uploaded alongside `surefire-reports`.

## Adding a test

1. Put the class in `core/app/src/test/java/com/agenticai/core/it/`, name it
   `*IT`, and annotate it with `@Tag("integration")` and
   `@Testcontainers(disabledWithoutDocker = true)`.
2. Get containers from `IntegrationTestContainers`. It holds one Postgres and
   one Redis for the whole JVM, started lazily.
   - Plain JDBC or Flyway test: call `IntegrationTestContainers.freshDatabase("my_it")`
     to get an empty database of your own, then call `PlatformMigrations.migrateBoot(ds)`
     and `new TenantSchemaMigrator(ds).migrate("tenant_x")` as needed.
   - Spring context test: extend `AbstractContextIT`. It carries the
     `@SpringBootTest` properties and the `@ServiceConnection` containers, so
     every subclass shares one cached context instead of booting core again.
3. Assert on behaviour the database decides, such as SQL, constraints,
   extensions and migrations. Stub only the external services that are not in
   the containers (sidecar, LLM providers, Keycloak).
