# VegaDuta on Kubernetes (Helm)

One umbrella chart, `charts/vegaduta`, installs the whole platform. Every dependency is either
**bundled** (optional, for evaluation and small installs) or **yours** (managed PostgreSQL, Valkey/Redis,
S3, any OIDC issuer, Temporal, Kafka, OpenTelemetry). The component charts can also be installed on their own.

> Status: the charts lint, render and validate against the Kubernetes 1.31 schemas and the CRD schemas
> of CloudNativePG, Gateway API and the Prometheus Operator (`validate.sh`), and 170 guard test cases cover them
> (`test-guards.sh`). They have **not yet been run end to end against published images**. Treat the first
> release as a preview.

```
charts/
  vegaduta/          umbrella chart (the thing you install)
  vegaduta-lib/      library chart: naming, labels, workloads, networking, core-family env
  core/              API (MODULE=app) + schema-migration hook Job
  core-jobs/         scheduled jobs and pollers (MODULE=app-jobs), singleton
  core-ingest/       optional pool: knowledge ingestion and re-embedding only (core-jobs image)
  core-webhooks/     optional pool: webhook delivery and the workflow event outbox relay only
  core-intake/       optional pool: inbound email and X DM pollers only
  core-billing/      optional pool: usage rollup, payment reconciliation, comp-grant sweeps only
  core-worker/       Temporal worker (MODULE=app-worker), only with global.temporal.enabled
  web/               Next.js console: tenant UI + public pages + the merged
                     platform-admin section (was web-admin/)
  sidecar/           ML sidecar (embeddings, rerank, STT/TTS, rendering)
  code-intel/  app-builder/  rl-trainer/  voice-agent/         optional services
  tool-sandbox/  mcp-stdio-runner/                             optional, run untrusted code
  postgres/          bundled PostgreSQL = CloudNativePG Cluster (pgvector + AGE image)
  valkey/            bundled single-node Valkey
  keycloak/          bundled Keycloak (official image) + realm import + reconcile Job
  seaweedfs/         bundled single-node SeaweedFS S3 gateway
examples/            values for "everything on", "bring your own everything", per cloud (aws, azure, gcp)
                     and External Secrets Operator examples (examples/external-secrets/)
validate.sh          lint + template + kubeconform + rule checks (Docker only, no cluster)
test-guards.sh       negative tests for every render-time guard
scripts/gen-docs.sh  regenerate the values tables (helm-docs)
../kind/             kind cluster config + values for a laptop install
```

No Bitnami images or charts are used anywhere (the free Bitnami catalog was withdrawn in 2025). Images:
`ghcr.io/cd-lc/*` for VegaDuta and its PostgreSQL build, `quay.io/keycloak/keycloak`,
`docker.io/valkey/valkey`, `docker.io/chrislusf/seaweedfs`, `docker.io/curlimages/curl` (helm test only).

---

## Quickstart (kind)

Needs Docker with about 8 GiB of memory, `kind`, `kubectl` and `helm` 3.14+.

```bash
kind create cluster --config deploy/kind/kind-config.yaml

# ingress controller (kind flavour: binds host ports 80/443)
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.11.3/deploy/static/provider/kind/deploy.yaml
kubectl -n ingress-nginx wait --for=condition=ready pod -l app.kubernetes.io/component=controller --timeout=180s

# CloudNativePG operator (the bundled PostgreSQL is a CNPG Cluster)
helm repo add cnpg https://cloudnative-pg.github.io/charts
helm upgrade --install cnpg cnpg/cloudnative-pg -n cnpg-system --create-namespace --wait

# VegaDuta
helm install vegaduta oci://ghcr.io/cd-lc/charts/vegaduta \
  -n vegaduta --create-namespace -f deploy/kind/values-kind.yaml
#   (from a checkout: helm dependency build deploy/helm/charts/vegaduta first,
#    after building each component chart's dependencies - see validate.sh)

kubectl -n vegaduta get pods -w
helm test vegaduta -n vegaduta
```

Then open http://app.127.0.0.1.nip.io (sign up), http://admin.127.0.0.1.nip.io and
http://auth.127.0.0.1.nip.io. The install output (`NOTES.txt`) prints the Keycloak admin password
command. Give your user the realm role `platform-admin` to use the admin console.

## Essential vs plug-and-play

| Component | Class | Value | What it enables |
|---|---|---|---|
| core, web, postgres, valkey, keycloak | essential | on (postgres/valkey/keycloak: bundled or `global.*` external) | the platform |
| core-jobs | scale shape | `global.components.coreJobs` (default true) | sweeps/pollers off the API pods |
| core-ingest | scale shape | `global.components.coreIngest` (default false, needs `coreJobs`) | knowledge ingestion and re-embedding in their own pool (same image as core-jobs) |
| core-webhooks | scale shape | `global.components.coreWebhooks` (default false, needs `coreJobs`) | webhook delivery and the workflow event outbox relay in their own pool |
| core-intake | scale shape | `global.components.coreIntake` (default false, needs `coreJobs`) | inbound email and X DM pollers in their own pool |
| core-billing | scale shape | `global.components.coreBilling` (default false, needs `coreJobs`) | usage rollup, payment reconciliation and comp-grant sweeps in their own pool |
| core-worker | scale shape | `global.temporal.enabled` | Temporal workflow worker |
| migrate Job | scale shape | `core.migrate.enabled` (default true) | Flyway before the API rolls |
| mcp-stdio-runner | plug-and-play | `global.components.mcpStdioRunner` + `mcp-stdio-runner.runtimeClassName` | stdio MCP servers (tenant ones and the built-ins); core gets `MCP_STDIO_SIDECAR_ENABLED=true` and the `mcp` profile on every role |
| runner browser variant | plug-and-play | `mcp-stdio-runner.browser=true` | `mcp-stdio-runner-browser` image (puppeteer / playwright-vision); memory limit 1Gi -> 2Gi |
| core-full | alternative | `global.core.mcpToolchain=full` | API (+ migrate/test pods) on the ~6 GB `core-full` image with `mcp`, no runner needed; core-jobs/core-worker stay slim |
| sidecar | plug-and-play | `global.components.sidecar` (default true) | ingestion, embeddings, rerank, STT/TTS |
| tool-sandbox | plug-and-play | `global.components.toolSandbox` + RuntimeClass | tenant Java/Python tools |
| code-intel, app-builder | plug-and-play | `global.components.codeIntel`, `.appBuilder` | code intelligence, App Builder |
| voice-agent, rl-trainer | plug-and-play | `global.components.voiceAgent` (+ `global.livekit.*`), `.rlTrainer` | voice calls, RL training |

The default `core` image carries no MCP toolchain (no npm/npx, uv or browsers), so without the runner
the `mcp` profile is left out. Setting `mcp` yourself in `global.core.springProfiles` on the slim image
makes core refuse to start with an explanation. Details:
[docs/configuration.md](../../docs/configuration.md#essential-vs-plug-and-play-services).

## Install on a real cluster

```bash
helm install vegaduta oci://ghcr.io/cd-lc/charts/vegaduta \
  --namespace vegaduta --create-namespace \
  --set global.domain=example.com \
  --set global.tls.clusterIssuer=letsencrypt-prod
```

DNS: point `app.`, `admin.`, `api.` and `auth.` (plus `codeintel.` / `builder.` if enabled) of your domain at
the ingress controller. Prerequisites for the defaults: an ingress controller (or a Gateway, see below),
the CloudNativePG operator, a default StorageClass, and cert-manager if you use `global.tls.clusterIssuer`.

### Web images and runtime config

`web` reads its public settings (`NEXT_PUBLIC_API_BASE_URL`, `NEXT_PUBLIC_KEYCLOAK_*`, ...)
from the container environment on every request (`web/docs/RUNTIME-CONFIG.md`), so one published image
works on any domain. The charts compute those values from `global.domain`, `global.hosts` and `global.oidc`.
Override a key with `web.env.<KEY>`.

## Production checklist

- [ ] **Encryption key.** Create the app Secret yourself and set `global.core.appSecret.existingSecret`
      (key `APP_ENCRYPTION_KEY` = `openssl rand -base64 32`). Back it up outside the cluster. It is the only
      key for every stored provider credential and channel secret; changing or losing it makes them unreadable.
      Rotation: set `APP_ENCRYPTION_KEY_PREVIOUS` in the same Secret during the re-encryption window.
- [ ] **GitOps (Argo CD, Flux, `helm template`)**: set every `existingSecret` (`global.core.appSecret`,
      `global.internalSecret`, and for bundled Keycloak `keycloak.admin.existingSecret` plus
      `global.oidc.existingSecret`). Generated Secrets rely on `lookup`, which these tools cannot do, so the
      chart **fails** such a render instead of minting new values (a new `APP_ENCRYPTION_KEY` on every sync
      would destroy all encrypted data). The bundled Valkey, SeaweedFS and the CNPG Keycloak role still
      generate their passwords: under GitOps bring your own Redis/S3 (`global.valkey.*`,
      `global.objectStorage.*`), or accept `global.allowGeneratedSecrets=true` knowing those passwords
      rotate on every render. Argo CD sync waves are preset: generated Secrets -2, database -1, migrate Job
      (Sync hook) 0, core family 1.
- [ ] Pin `global.imageTag` to a release (or per-image digests). core, core-jobs and core-worker must run
      the same build; the chart refuses to render otherwise (`allowCoreImageSkew` exists for emergencies).
- [ ] Managed or HA PostgreSQL: either `postgres.instances: 3` with `postgres.backup` +
      `postgres.scheduledBackup` configured, or a managed server via `global.postgresql.*`. The server must
      provide the `vector` extension; Apache AGE is optional (`global.postgresql.age=false` turns graph memory
      off for RDS/Aurora, Cloud SQL and AlloyDB). Any pooler in front must use **session** pooling.
- [ ] Object storage: an external S3 bucket with lifecycle/backup, not the single-node SeaweedFS.
- [ ] NetworkPolicies are ON by default (dedicated namespace; default-deny plus explicit allows; needs an
      enforcing CNI). The core family's egress to configured integrations (external DB/Valkey/S3, OTLP,
      Kafka, Temporal, LiveKit, OIDC) is derived automatically; add SMTP / self-hosted LLM ports with
      `global.networkPolicy.coreEgress`, and scope `global.networkPolicy.extraEgress` rules with
      `components: [...]`. Gateway API needs `global.networkPolicy.gatewayNamespace`, ServiceMonitors need
      `global.networkPolicy.monitoringNamespace`.
- [ ] Keycloak admin console: not routed publicly (only `/realms/<realm>` and `/resources`); serve it on a
      private host via `keycloak.admin.consoleUrl`.
- [ ] TLS on every host (`global.tls`), HSTS at the ingress.
- [ ] `global.serviceMonitor.enabled: true` if you run the Prometheus Operator; `global.otel.tracesEndpoint`
      for traces.
- [ ] Resource requests/limits sized from real usage; `core.replicaCount` stays 1 until every
      `core.replicaReadiness` flag is true (every per-pod store in Valkey, pinned A2A signing keys) - the chart
      enforces this, sets `APP_REPLICA_MODE=multi` so core reports anything still unsafe at boot, and
      [ADR-0014](../../docs/architecture/ADR-0014-horizontal-scaling.md) explains the model.
- [ ] `tool-sandbox` / `mcp-stdio-runner` only on a node pool with a VM-isolating RuntimeClass (Kata, gVisor).
- [ ] Keycloak: SMTP (`keycloak.realm.smtp`), a real admin Secret, `platform-admin` granted to operators only.

## Bring-your-own dependency matrix

A dependency is bundled when its `global.*` location is empty and `<chart>.enabled=true`. To use your own,
set the location and `<chart>.enabled=false`; the chart fails to render if the two disagree.

| Dependency | Bundled (default) | Bring your own | Notes |
|---|---|---|---|
| PostgreSQL 16 + pgvector (+ AGE, optional) | `postgres` (CloudNativePG `Cluster`, needs the CNPG operator) | `global.postgresql.host/port/database/username/existingSecret`, `sslMode`, `age` | Keycloak then needs `keycloak.database.*` or an external IdP. Session pooling only. |
| Valkey / Redis protocol | `valkey` (single node, AOF) | `global.valkey.host/port/existingSecret/tls/username` | Any Redis-protocol service (Valkey, ElastiCache, Memorystore, Azure Cache). |
| File storage | `seaweedfs` (single node S3) | `global.objectStorage.endpoint/region/bucket/pathStyleAccess/existingSecret/credentials` (`credentials: default` = AWS SDK chain, no keys in the cluster: EKS IRSA / Pod Identity); `type: azure-blob` + `azure.endpoint` for Azure Blob Storage (`credentials: default` = workload identity) | Or `global.objectStorage.type=local`: a PVC shared by the core pods (ReadWriteMany when more than one core pod runs). |
| OIDC issuer | `keycloak` (official image, realm import) | `global.oidc.issuerUrl/internalBaseUrl/clientId/existingSecret` | Token validation works with any OIDC issuer. Signup/tenant provisioning calls the **Keycloak Admin API** today, so a non-Keycloak IdP needs users and the `tenant_id` claim provisioned another way. |
| Temporal | not bundled (off) | `global.temporal.enabled/target/namespace` | Temporal Cloud or the official `temporalio/helm-charts`. Enabling it deploys `core-worker`. |
| Kafka-compatible events | not bundled (off) | `global.kafka.bootstrapServers/securityProtocol/saslMechanism/existingSecret` (the Secret holds the JAAS line under `saslJaasConfig`) | Apache Kafka, Redpanda, MSK, Event Hubs (Kafka endpoint). |
| OpenTelemetry | off | `global.otel.tracesEndpoint`, `samplingProbability` | Any OTLP/HTTP collector (Langfuse, Tempo, Jaeger, vendors). |
| LiveKit (voice) | not bundled | `global.livekit.url/internalUrl/existingSecret` | LiveKit Cloud or the official `livekit/livekit-helm`; needed only for `voiceAgent`. |
| Ingress | Ingress (`global.ingress.className`) | Gateway API: `global.gateway.enabled` + `parentRefs` | Both can be on during a migration. |

`examples/values-external.yaml` shows every dependency brought in from outside.

## Cloud values (AWS, Azure, GCP)

One values file per cloud, using only keys the charts define (`global.postgresql/valkey/objectStorage/kafka.*`,
`<chart>.serviceAccount.annotations`, `<chart>.podLabels`). Layer your own file on top (domain, OIDC, Temporal,
tags, CIDRs):

```bash
helm install vegaduta oci://ghcr.io/cd-lc/charts/vegaduta -n vegaduta --create-namespace \
  -f examples/values-aws.yaml -f my-overrides.yaml
```

| | AWS (`values-aws.yaml`) | Azure (`values-azure.yaml`) | GCP (`values-gcp.yaml`) |
|---|---|---|---|
| PostgreSQL | RDS / Aurora, `age: false`, `sslMode` | Flexible Server, `age: false` (AGE possible with setup), `sslMode: require` | Cloud SQL / AlloyDB private IP, `age: false` |
| Redis | ElastiCache, `tls: true` | Azure Cache for Redis, port 6380, `tls: true` | Memorystore, `tls: true` (CA trust caveat in the file) |
| Object storage | S3, `credentials: default` (IRSA / Pod Identity; the core image ships the SDK `sts` module, the credential path is not run on a cluster) | `type: azure-blob`, `credentials: default` (Workload Identity) | GCS S3 interop, HMAC keys as static credentials |
| Workload identity | `eks.amazonaws.com/role-arn` on the ServiceAccounts | `azure.workload.identity/client-id` on the ServiceAccounts and the `azure.workload.identity/use` pod label | none by default (storage uses HMAC keys); the file shows the `iam.gke.io/gcp-service-account` annotation to add if you need it |
| Kafka (commented) | MSK SASL/SCRAM | Event Hubs, SASL_SSL + PLAIN | use the same `global.kafka.*` keys |
| Secrets | `external-secrets/aws.yaml` (Secrets Manager) | `external-secrets/azure.yaml` (Key Vault) | `external-secrets/gcp.yaml` (Secret Manager) |

Notes that apply to all three:

- ServiceAccounts are `<release>-core`, `<release>-core-jobs` and `<release>-core-worker` (the last only with
  Temporal). The migrate Job runs as core's. Pods run with `automountServiceAccountToken: false`; the cloud
  identity webhooks inject their own projected token volume, so this does not block IRSA or Workload Identity
  (not run on a real cluster here).
- Network policy: the chart derives egress for the configured PostgreSQL, Valkey, object-storage and Kafka ports to
  any address except link-local `169.254.0.0/16` (`vegaduta-lib.integrationEgress`), so managed services on private
  addresses are reachable without extra rules (the rendered core NetworkPolicy is checked in `test-guards.sh`). Only
  the generic internet rule and the optional SMTP rule exclude the private ranges. The cloud files therefore set no
  `extraEgress`; to narrow egress to your data subnets, turn `integrationEgress` off and add scoped rules yourself.
- Identity: the three files point `global.oidc` at an **external Keycloak** (the bundled one is off). Any other OIDC
  issuer validates tokens but cannot provision tenants and users today (see the ADR Identity row).
- External Secrets Operator: the examples use `apiVersion: external-secrets.io/v1`, served only by recent ESO
  releases (older ones: change to `v1beta1`); neither was run. They create exactly the Secrets and keys in
  "Secrets the charts read" below (`test-guards.sh` checks the target names and keys against the rendered chart). The
  `APP_ENCRYPTION_KEY` and internal-token ExternalSecrets use `creationPolicy: Orphan` plus `deletionPolicy: Retain`:
  with the default `Owner` the Secret has an ownerReference to the ExternalSecret and is garbage-collected when the
  ExternalSecret is deleted (`kubectl delete -f`, a GitOps prune), and `deletionPolicy` only governs what happens
  when the value disappears from the store. Generate `APP_ENCRYPTION_KEY` once (`openssl rand -base64 32`) and never
  overwrite it in the store. This follows the ESO documentation and was not run against ESO.
- **Verification**: the three files render without cluster access (every secret is an `existingSecret`), pass
  `kubeconform`, and are exercised by `test-guards.sh` (structure, secret names, negative cases). The External Secrets
  documents are only YAML-checked (`kubeconform -ignore-missing-schemas`, their CRDs are not in its catalog). Nothing
  was installed on EKS, AKS or GKE, and no cloud service, ESO or workload identity was contacted. Hosts, ARNs, GUIDs
  and CIDRs are placeholders. The ADR lists what remains open (database IAM auth, KMS, MSK IAM, non-Keycloak identity).

### Cloud identity for object storage

`credentials: default` removes the stored key pair, but not every identity source is reachable from the pods:

- **Works with the default NetworkPolicy:** EKS IRSA and Azure workload identity. Both read a projected token file
  and call the cloud's token service on public 443.
- **Blocked by default:** EC2 instance-profile / IMDS, EKS Pod Identity (agent on `169.254.170.23:80`) and Azure
  managed identity via IMDS (`169.254.169.254:80`). The core egress policy allows 443 to anything except link-local
  (`169.254.0.0/16`, the metadata range, on purpose). To use one of them add an `extraEgress` rule for the core
  family and accept that those pods can then reach the node's metadata service. Not tested on a cluster.
- **Annotate three ServiceAccounts, not one.** `core`, `core-jobs` (sandbox-artifact sweeps use storage) and
  `core-worker` each have their own: set `core.serviceAccount.annotations`, `core-jobs.serviceAccount.annotations`
  and `core-worker.serviceAccount.annotations` (the migrate Job runs as the release's ServiceAccount and does not
  touch object storage).
- **Azure workload identity** also needs the pod label `azure.workload.identity/use: "true"` (`podLabels` of each
  of those charts) besides the ServiceAccount annotation.
- **Regional S3 endpoints:** set `global.objectStorage.region` to the bucket's region and
  `pathStyleAccess: false`; the defaults (`us-east-1`, path style) do not match a regional AWS endpoint and the
  chart does not check it.

## Secrets the charts read

| Secret (default name) | Keys | Used by | Created by |
|---|---|---|---|
| `global.core.appSecret.existingSecret` (`<release>-core-app`) | `APP_ENCRYPTION_KEY` (+ any extra core env) | core, core-jobs, core-worker, migrate Job | you, or generated once (kept on uninstall) |
| `global.internalSecret.existingSecret` (`<release>-internal`) | `SIDECAR_SHARED_SECRET`, `MCP_STDIO_SIDECAR_SHARED_SECRET`, `CODE_INTEL_INTERNAL_TOKEN`, `TOOL_SANDBOX_INTERNAL_TOKEN`, `APP_BUILDER_CORE_SERVICE_TOKEN`, `APP_BUILDER_ENCRYPTION_KEY`, `RL_TRAINER_INTERNAL_TOKEN` | service-to-service auth; each service gets only its own key | you, or generated |
| `global.postgresql.existingSecret` (`<release>-postgres-app`) | `password` | core family | you, or the CNPG operator |
| `global.valkey.existingSecret` (`<release>-valkey`) | `password` | core family | you, or the valkey chart |
| `global.objectStorage.existingSecret` (`<release>-seaweedfs`) | `accessKeyId`, `secretAccessKey` | core family | you, or the seaweedfs chart |
| `global.oidc.existingSecret` (`<release>-keycloak-core-client`) | `clientSecret` | core family, Keycloak import/reconcile | you, or the keycloak chart |
| `keycloak.admin.existingSecret` (`<release>-keycloak-admin`) | `username`, `password` | Keycloak bootstrap admin, reconcile Job | you, or the keycloak chart |
| `global.livekit.existingSecret` | `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET` | core, voice-agent | you |

Generated Secrets carry `helm.sh/resource-policy: keep`, so `helm uninstall` never deletes them.

## What the charts enforce

Every workload: `runAsNonRoot` (except the tool-sandbox supervisor, which drops to an unprivileged user
per run), `seccompProfile: RuntimeDefault`, `capabilities.drop: [ALL]`, `allowPrivilegeEscalation: false`,
read-only root filesystem wherever the image allows (writable paths are `emptyDir`s), requests and limits,
startup/liveness/readiness probes, a PodDisruptionBudget, soft zone/node spread, a dedicated
ServiceAccount with `automountServiceAccountToken: false`, standard `app.kubernetes.io/*` labels and a
`helm test` pod. Render-time guards (see `test-guards.sh`) refuse, among others: two different
encryption-key Secrets across the core family, core images from different builds, more than one API
replica before the readiness flags are true, replicas or autoscaling whose worst-case database connections
exceed the server limit, more than one core-jobs or pool (core-ingest, core-webhooks, core-intake, core-billing) replica without leader election,
secrets as plain env, localhost URLs, transaction pooling, `:latest`, a sandbox without a RuntimeClass or
with extra secrets/egress, and bundled-plus-external for the same dependency.

Core specifics: the API readiness probe is `/actuator/health/readiness` and the startup probe allows
5 minutes (boot takes 60-140 s). Schema migrations run in a Job with the API image and
`APP_TENANCY_MIGRATE_ONLY=true` (exit 75 = another migrator holds the lock and is retried); API pods run
with `APP_TENANCY_MIGRATE_ON_BOOT=false`. The Job is a `post-install,pre-upgrade` hook for every
database: it runs as the release's ServiceAccount and reads the release's Secrets, which do not exist
yet during `pre-install` (an earlier `pre-install` variant for external databases hung on first install
for that reason). `helm install --wait` still completes, since the API runs the control-plane Flyway
migration on boot and its readiness does not wait for tenant-schema migrations (a fresh install has no
tenants). A wait-for-database init container covers the gap. Under Argo CD the Job is a Sync hook in
wave 0.

## Upgrade

```bash
helm upgrade vegaduta oci://ghcr.io/cd-lc/charts/vegaduta -n vegaduta -f my-values.yaml --version <new>
```

The migrate Job runs first (`pre-upgrade`); if it fails, the release is marked failed and no pod is rolled.
The API rolls with `maxUnavailable: 0`. core-jobs uses `Recreate` (singleton). Read the release notes for
chart-level breaking changes; `helm diff upgrade` (plugin) is recommended.

## Backup and restore

- **PostgreSQL** holds everything that matters (tenants, agents, encrypted credentials). Bundled: configure
  `postgres.backup` (CNPG barman object store) and `postgres.scheduledBackup`; restore by bootstrapping a new
  Cluster from the backup (CNPG `bootstrap.recovery`). Managed: use the provider's PITR.
- **The app Secret** (`APP_ENCRYPTION_KEY`): back it up separately. A database restore without the matching
  key cannot decrypt stored credentials.
- **Object storage**: bucket versioning/replication at the provider; for the bundled SeaweedFS, snapshot its PVC.
- **Keycloak** lives in PostgreSQL (its own database); the realm can be re-imported but users cannot.
- PVCs of code-intel, app-builder and rl-trainer hold re-creatable indexes/workspaces; snapshot if you care.

## Uninstall

```bash
helm uninstall vegaduta -n vegaduta
```

Kept on purpose (delete manually only when you are sure): generated Secrets (`<release>-core-app`,
`<release>-internal`, `<release>-valkey`, `<release>-seaweedfs`, `<release>-keycloak-*`,
`<release>-postgres-keycloak`), the data PVCs of code-intel/app-builder/rl-trainer and
`<release>-core-storage`, and the StatefulSet PVCs of Valkey/SeaweedFS. The CloudNativePG `Cluster` is
deleted with the release and CNPG deletes its PVCs, so **back up first**.

## Validate (Docker only)

```bash
deploy/helm/validate.sh        # dependency update, lint --strict, template x 9 scenarios, kubeconform, rules
deploy/helm/test-guards.sh     # negative tests for every guard + structural checks
deploy/helm/scripts/gen-docs.sh  # regenerate charts/vegaduta/VALUES.md and charts/core/VALUES.md
```

`validate.sh` works on a temp copy (the repo stays clean). Scenarios: defaults, every optional component,
every dependency external, one per cloud (aws, azure, gcp), kind, local file storage, Gateway API only. Set `OFFLINE=1` to skip the remote CRD
schema catalog.

Chart dependencies are `file://` references. `Chart.lock` files are committed; the vendored `charts/*.tgz`
are not (`.gitignore`). A fresh checkout needs `helm dependency build` on each component chart and then on
the umbrella (in that order), as `validate.sh` does. Released charts are published to
`oci://ghcr.io/cd-lc/charts` with all dependencies packaged.

## Values reference

- Umbrella: [`charts/vegaduta/VALUES.md`](charts/vegaduta/VALUES.md) (generated by helm-docs)
- Core API: [`charts/core/VALUES.md`](charts/core/VALUES.md)
- Other components: the commented `values.yaml` in each `charts/<name>/`

The component source lives in `core/`, `web/` and `services/<name>/`.
