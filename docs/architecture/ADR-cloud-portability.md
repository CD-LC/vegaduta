# ADR: cloud portability through open protocols

Status: **accepted, partially implemented** (see the matrix). Date: 2026-10. Where this record disagrees with
the code, the code wins.

## Context

VegaDūta is open source and will be run by people who already chose a cloud: AWS, Azure, GCP, a private
Kubernetes cluster, or one server. They will want their provider's managed Postgres, Redis, object store and
Kafka, their identity provider, and their secret store, not the bundled containers. The platform should run
the same everywhere and say plainly where it does not.

## Decision

1. **One production target, one packaging.** Kubernetes with the Helm umbrella chart. Docker Compose stays
   for laptops and single servers. Both run the same signed OCI images.
2. **An open protocol at every boundary, never a cloud SDK in the domain code.** Postgres wire protocol,
   Redis protocol, S3 API, Kafka protocol, OIDC/JWT, OTLP, and Kubernetes Secrets. A provider's managed
   service plugs in through configuration. Where a cloud has no such protocol (Azure Blob), the access sits
   behind a port (`FileStorageService`) with an adapter per implementation.
3. **Twelve-factor configuration.** Everything through environment variables and Secrets, nothing baked
   into images; Helm values map one-to-one to those variables.
4. **No long-lived cloud credentials where the cloud offers workload identity.** Prefer IRSA / Pod Identity,
   Azure Workload Identity and GKE Workload Identity; keep static keys as the portable fallback.
5. **A dependency is optional unless the product cannot work without it.** A feature that needs a rare
   extension or service (Apache AGE for graph memory) degrades to "off" instead of blocking startup.
6. **Say what is verified.** Each row below states what has been run, not what should work.

## Dependency matrix

Status: **works** (implemented and exercised as stated), **partial**, **open** (not implemented). Provider
extension and service availability changes; confirm it in the provider's current documentation before relying
on a row.

| Boundary | Protocol | Bundled default | AWS | Azure | GCP | Status |
|---|---|---|---|---|---|---|
| PostgreSQL + pgvector | Postgres wire | CloudNativePG | RDS / Aurora | Flexible Server | Cloud SQL / AlloyDB | **works** with `global.postgresql.host`, `sslMode` |
| Apache AGE (graph memory) | extension | in the bundled image | not offered | offered on Flexible Server (check version and region) | not offered | **works**: optional since the AGE-optional change; `global.postgresql.age=false` on RDS/Cloud SQL/AlloyDB. `AgeOptionalIT` runs the startup migration and a tenant schema against `pgvector/pgvector:pg16` (no AGE) and shows plain Flyway failing on V44 there; a full core boot on such a server has not been run |
| Database login | password | generated Secret | IAM auth token | Entra auth token | Cloud SQL IAM | **works in code, not yet against a real cloud**: `DB_AUTH=aws-iam \| azure-entra \| gcp-iam` (Helm `global.postgresql.auth`) supplies a fresh token per new connection to Flyway, tenant migrations and JPA; TLS required (`require` encrypts but does not authenticate the server; CA handling for `verify-*` is untested); password stays the default. Helm adds the NetworkPolicy egress the token endpoints need. Unit-tested (wiring with Boot's DataSource auto-configuration, the real AWS signer offline, TLS guard against the real pgjdbc parser, SDK `sts` classpath canary for IRSA) and Helm-rendered; Azure and GCP token sources are untested; no RDS, Entra or Cloud SQL instance has been connected to and no workload identity exercised. Direct connections only (no PgBouncer in front) |
| Redis protocol | Redis | Valkey | ElastiCache / MemoryDB | Azure Cache for Redis | Memorystore | **works** for password, TLS and ACL user (`global.valkey.tls/username`) against a single node: property binding is tested, and the tenant purge is integration-tested against Valkey (`RedisTenantDataPurgeIT`); no managed instance, replica set or Sentinel was run. Redis Cluster: **not supported** (2 known blockers - cross-slot Lua - and 5 template `SCAN` sites whose cluster behaviour is unverified, plus a cluster-aware purge scan that is unit-tested with mocks only; see [Redis Cluster and Sentinel readiness](#redis-cluster-and-sentinel-readiness)). Sentinel: **not verified** (no Sentinel configuration is exposed by Compose or Helm) |
| Object storage | S3 API | SeaweedFS | S3 | Blob has no S3 API | GCS (S3 interop, HMAC keys) | AWS **works** without stored keys (`credentials=default`, IRSA / Pod Identity; the SDK chain itself is untested here); GCS works with HMAC keys; Azure Blob **works** through `app.storage.type=azure-blob` (Helm `type: azure-blob`), integration-tested against the Azurite emulator; workload identity (`DefaultAzureCredential`) is built but not run against a real Azure account. Known Azure differences from S3: no `demo-images/` lifecycle expiry (it needs an ARM-plane policy created out-of-band, see docs/configuration.md); LiveKit call recording needs S3 with static keys |
| Events | Kafka | Redpanda (off by default) | MSK | Event Hubs (Kafka endpoint) | Managed Kafka | **works** for SASL_SSL with PLAIN or SCRAM (`global.kafka.*`); binding is tested, a managed cluster is not. MSK IAM auth **open** |
| Identity | OIDC / JWT | Keycloak | Cognito | Entra ID | Google Identity | **partial**: token validation is generic but expects Keycloak-shaped claims (`realm_access.roles`, `tenant_id`); identity administration (signup, users, roles, SSO and social providers) now goes through two ports, `IdentityUserAdminPort` and `IdentityProviderAdminPort`, with `KeycloakAdminClient` as the only implementation; an ArchUnit rule keeps callers off the Keycloak class. No second adapter exists, the user model is still the `Keycloak*Representation` records, and the claim names are not configurable. Treat Keycloak as required until an adapter is written ([how](../extending.md#7-add-an-identity-provider-adapter)) |
| Secrets | Kubernetes Secret | generated | External Secrets Operator to Secrets Manager | to Key Vault | to Secret Manager | chart reads `existingSecret` everywhere; an External Secrets example is **open** |
| Encryption key | AES-256-GCM key | `APP_ENCRYPTION_KEY` | KMS | Key Vault | Cloud KMS | **partial**: `app.crypto.kms.provider=aws\|azure\|gcp` (module `crypto-kms`) wraps new envelope-encrypted data keys with KMS / Key Vault / Cloud KMS; only the payment signing keys use the envelope path, and about 41 classes still use the static `APP_ENCRYPTION_KEY` (**open**). Unit-tested against fakes and an in-process HTTP fake; **no real KMS, Key Vault or Cloud KMS has been called** |
| Secrets | Kubernetes Secret | generated | External Secrets Operator to Secrets Manager | to Key Vault | to Secret Manager | chart reads `existingSecret` everywhere; External Secrets Operator examples per cloud in `deploy/helm/examples/external-secrets/` (**rendered and schema-checked only**: the CRDs were not validated, ESO and the secret stores were not run) |
| Telemetry | OTLP | Langfuse / Grafana stack | CloudWatch, X-Ray | Azure Monitor | Cloud Operations | **works** (`global.otel.tracesEndpoint`) |
| Workflows | Temporal | not bundled | Temporal Cloud | Temporal Cloud | Temporal Cloud | **works** (`global.temporal.*`) |

Infrastructure as code: Terraform reference modules exist for Azure, GCP and AWS (`infra/terraform/modules`,
community-supported, not exercised by CI). The AWS set (`aws-network`, `aws-eks`, `aws-rds-postgres`,
`aws-elasticache`, `aws-s3`, `aws-irsa-core`, `aws-secrets`) is `tofu validate`d only: never planned or applied
against a real AWS account.

## Redis Cluster and Sentinel readiness

Status: **audited; the tenant-purge `KEYS` replaced; 2 known cluster blockers (cross-slot Lua) and 5 unverified template `SCAN` sites remain; Redis Cluster is not supported.**
No Redis Cluster or Sentinel deployment was available. What is stated below was found by reading the code
(`core/*/src/main`), by reading the bytecode of spring-data-redis 3.5.13 and Lettuce 6.6.0 (the versions this reactor
resolves), by unit tests with mocked connections, and, for the single-node path only, by `RedisTenantDataPurgeIT`
against Valkey 8. Nothing was run against a cluster.

What changed in the tenant purge (`core/tenancy/.../RedisTenantDataPurgeService.java`, the only two uses of the
blocking, O(N) `KEYS` in main code; none remains):

- **One cursor-based `SCAN` pass** over `agentic:*` (`COUNT 500`), with the tenant's key families matched client-side
  and deleted in batches of 500 as the cursor advances. The first version issued seven passes (one per family); SCAN
  costs a walk of the whole keyspace however narrow the `MATCH`, so one pass is about a seventh of the server work.
  What it costs instead: every `agentic:*` key name crosses the wire once, which was not measured.
- **Cluster-aware scan** (`RedisKeyspaceScanner`). In spring-data-redis 3.5.13 `LettuceClusterKeyCommands` fans `keys(...)`
  out to every master, but does not override `scan(ScanOptions)`; it only has `scan(RedisClusterNode, ScanOptions)`.
  How a plain template scan behaves on a cluster connection is therefore NOT established here: the Spring class gives
  no per-node fan-out for it, and whether Lettuce's own cluster support then scans every node, one node, or fails was
  not verified against a real cluster (the message "Scan is not supported across multiple nodes" is from the Jedis
  driver, not Lettuce). Scanning each master explicitly is correct under every one of those behaviours, which is why
  the purge does it. When the connection factory yields a cluster connection, the purge scans each master
  with the per-node overload and skips replicas and failed nodes; otherwise it uses the template scan. This path is
  covered by unit tests with a mocked `RedisClusterConnection`, not by a real cluster.
- **Partial failure is reported as partial.** If the scan fails midway, the keys already collected are still deleted
  and the log states how many were removed before the failure and that the rest of the keyspace was not examined
  (the purge stays fail-open: tenant deletion continues).

Test coverage: unit tests for multi-batch scans, duplicates, an empty keyspace, a cursor closed after a failure,
deletion after a mid-scan failure, the cluster per-master path, a single pass, and that only the tenant's keys are
deleted; `RedisTenantDataPurgeIT` runs the purge against real Valkey (single node) with 1500 tenant keys (and as many for a second tenant) over several scan
pages. The mock-based tests do not model SCAN cursor semantics; the IT is the check that does.

Blockers and unverified usages on Redis Cluster (2 known, 5 unverified):

| # | Where | Why it breaks on a cluster |
|---|---|---|
| 1 | `core/agents-turn/.../memory/SessionMemoryService.java:218` (`FOLD_CLAIM_SCRIPT`, called at `:743`) | Lua script reads `KEYS[1]` (`agentic:session:<id>`) and `KEYS[2]` (`agentic:session-folded:<id>`). The prefixes differ and there is no hash tag, so the keys land in different slots: `CROSSSLOT` error. Fixing it means changing key names (e.g. `{<id>}` hash tags), which needs a data migration for live sessions |
| 2 | `core/agents-turn/.../memory/SessionMemoryService.java:241` (`TRIM_SCRIPT`, called at `:785`) | Same two keys, same `CROSSSLOT` problem. The caller falls back to a plain `LTRIM` on error, so history still trims, but the fold-marker alignment is lost |
| 3 | `core/agents/.../FreeTierUsageLimiterService.java:501` | Template `SCAN(ScanOptions)`: cluster behaviour unverified (see above) |
| 4 | `core/agents-budget/.../budget/PerUserBudgetService.java:918` | Same template `SCAN` (cluster behaviour unverified) |
| 5 | `core/agents-interop/.../a2a/A2aTaskStore.java:260` | Same template `SCAN` (cluster behaviour unverified) |
| 6 | `core/platform/.../PlatformCircuitBreakerController.java:134` | Same template `SCAN` (cluster behaviour unverified) |
| 7 | `core/platform/.../abuse/AbuseThrottleService.java:185` | Same template `SCAN` (cluster behaviour unverified) |

Two newer scripts are cross-slot on a cluster too, but both fall back automatically when Redis refuses them and are not counted above: `SessionAppendScript.APPEND_SCRIPT` (three keys per session, falls back to the multi-call append) and `ChatTurnCancelService.DELETE_ALL_SCRIPT` (one key per in-flight turn, falls back to one `DEL` per key). Neither fallback has been run against a real cluster.

Not blockers by reading, but not run on a cluster either:

- The purge's multi-key `DEL`. Lettuce's cluster async commands (`RedisAdvancedClusterAsyncCommandsImpl.del`, 6.6.0)
  partition the keys by slot (`SlotHash.partition`), so it should work; it is not atomic across slots.
- Multi-key reads (`MGET`) in `LandingEventController.java:521` and `X402TenantBudget.java:112`, and `PUBLISH` on the
  A2A cancel channel (`A2aTaskStore.java:339`): not examined for cluster behaviour.
- Single-key Lua scripts (one key in `KEYS[]`, so one slot): `RedisFixedWindowCounter.java:24`,
  `RedisAgentLabRunStore.java:53` and `:73`, `RedisCodingTaskStore.java:38`.

Not used anywhere in main code: `KEYS`, `MULTI`/`WATCH`, `RENAME`, set/zset store operations.

Configuration: Compose and Helm expose only `REDIS_HOST` / `REDIS_PORT` / TLS / username. Nothing here wires
`spring.data.redis.cluster.*` or `spring.data.redis.sentinel.*` and neither was tried.

To close this gap: give the two session-memory keys a shared hash tag (with a migration), move the five template
`SCAN` calls onto the cluster-aware scanner, then run the Redis ITs against a real Redis Cluster (and a Sentinel
setup) and record the result here.

## Consequences

- A customer on AWS can run today on RDS (with `age=false`), ElastiCache (TLS), S3 (IRSA) and MSK (SCRAM)
  with only values changes, subject to the "not booted against a real service" caveats above.
- Azure object storage works through the Blob backend; graph memory needs AGE allow-listed on the server (otherwise it fails clearly), or `age=false` where AGE is not offered.
- A non-Keycloak identity provider needs one adapter class implementing the two identity ports (the platform no longer
  names Keycloak outside `admin.keycloak`), but none is written and the token side still assumes Keycloak-shaped claims.
- The domain code stays free of cloud SDKs; the cost is that managed services with no open protocol need a
  small adapter each.

## What would close the gaps (in order)

1. Boot core (not only its migrations) against a Postgres without AGE, and against managed Redis and Kafka,
   and record the result here. For Redis Cluster, fix the two cross-slot scripts and verify the five template `SCAN` sites against a real cluster first.
2. ~~Database IAM authentication~~ implemented (`app.datasource.auth`, module `dbauth`); remaining: run it against a
   real RDS/Aurora, Azure Flexible Server and Cloud SQL instance and record the result here.
3. ~~KMS providers behind `KeyManagementProvider`~~ (done, unverified against real services); then migrate the
   ~41 callers of the static key to envelope encryption, with a data migration for stored ciphertext, and run the
   providers against a real account per cloud.
4. Run the Azure Blob backend against a real storage account with workload identity.
5. ~~An identity-admin port with Keycloak as the first implementation~~ (done: `IdentityUserAdminPort`,
   `IdentityProviderAdminPort`). Still open: configurable role and tenant claim names, a neutral user model, and a
   second adapter to prove the seam.
6. One scripted install-and-smoke test per cloud, so "portable" is checked, not claimed. (A values file per cloud
   exists: `deploy/helm/examples/values-{aws,azure,gcp}.yaml`, rendered and validated, never installed on a real
   cluster. An AWS Terraform module set exists too; it is only `tofu validate`d by hand, no CI job runs it.)

See also: [Kubernetes architecture](kubernetes.md), [Helm chart README](../../deploy/helm/README.md),
[architecture overview](overview.md).
