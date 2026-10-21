# Kubernetes architecture: namespaces, security, scaling

How the Helm charts under [`deploy/helm/`](../../deploy/helm/README.md) lay out a VegaDūta install,
what they enforce, and how to scale it. The chart README is the reference for values and the install
steps; this page explains the design and says what is verified and what is not.

## Layout

One umbrella chart ([`charts/vegaduta`](../../deploy/helm/charts/vegaduta)) composes one chart per
component, all built on a library chart
([`vegaduta-lib`](../../deploy/helm/charts/vegaduta-lib)) that holds the shared workload, Service,
PodDisruptionBudget, HPA, NetworkPolicy and security-context templates. Components fall into three
classes (full table in the [chart README](../../deploy/helm/README.md#essential-vs-plug-and-play)):

- **Essential:** `core` (API), `web`, PostgreSQL (a CloudNativePG `Cluster`), Valkey, Keycloak. Each of
  the three data services is bundled by default or replaced with an external one via `global.*`.
- **Scale shapes of core:** `core-jobs` (background sweeps/pollers), the optional pools of it (`core-ingest`:
  document ingestion and re-embedding; `core-webhooks`: webhook delivery; `core-intake`: inbound email and X
  DM polling; `core-billing`: usage rollup and payment reconciliation), `core-worker` (Temporal), and the migrate Job. They run the same
  image build as the API; the chart refuses to render if the builds differ. How the work is split, and why the
  chat path is not: [ADR-0014](ADR-0014-horizontal-scaling.md), [Deployment shapes](../deploy/DEPLOYMENT-SHAPES.md).
- **Plug-and-play:** sidecar (ML), code-intel, app-builder, rl-trainer, voice-agent, `mcp-stdio-runner`,
  `tool-sandbox`, and `ollama` (a single-node local model server, `ollama.enabled` plus `global.localLlm.*`;
  see [local models](../local-models.md)). Components that execute untrusted code (`tool-sandbox`, `mcp-stdio-runner`) need a
  VM-isolating `RuntimeClass` (Kata, gVisor) and refuse to render without one.

The chat hot path stays inside the `core` pod; nothing is added as a network hop between platform
modules ([overview](overview.md)).

Which managed cloud services each dependency can use, and what is verified, is in the
[cloud portability ADR](ADR-cloud-portability.md).

## Namespaces and isolation

- Install into a **dedicated namespace** (`vegaduta` in the examples). NetworkPolicies are on by default
  and assume it: default-deny, then explicit allows. This needs a CNI that enforces NetworkPolicy.
- [`deploy/helm/examples/namespace.yaml`](../../deploy/helm/examples/namespace.yaml) is a baseline to
  apply before `helm install`: a namespace labelled for Pod Security Admission `restricted`
  (enforce, audit and warn), a `ResourceQuota` and a `LimitRange`.
  **Status: applied to a live kind cluster; every chart pod was admitted under `restricted`** (see
  [verification status](#verification-status)). Components not installed there (the tool sandbox, voice agent,
  code-intel, app-builder) were not exercised.
- The tool sandbox runs tenant code. Give it its own namespace and RuntimeClass rather than loosening the
  main one.
- Peers are scoped to service ports: a component only accepts traffic from the components that call it,
  on the ports they call (`allowFrom` entries are `{name, ports}`). Egress for the core family is derived
  from the integrations you configure (external database/Valkey/S3 or Azure Blob account endpoint, OTLP, Kafka, Temporal, LiveKit, OIDC);
  add SMTP or self-hosted-LLM ports with `global.networkPolicy.coreEgress`.

## Workload security (enforced by the library chart)

Every workload: non-root, `seccompProfile: RuntimeDefault`, all capabilities dropped, no privilege
escalation, read-only root filesystem where the image allows (writable paths are `emptyDir`s with size
limits), requests and limits, startup/liveness/readiness probes, its own ServiceAccount with the token
not mounted, standard `app.kubernetes.io/*` labels and a `helm test` pod. Secrets are never plain env (the core family refuses the known secret names, such as the encryption key, passwords, the Kafka JAAS line and the Azure connection string, as a literal in `env`, `global.core.extraEnv` and the charts' raw `extraEnv` lists; aliases and arbitrary names are not detected):
the charts read them from Kubernetes Secrets (generated once, or yours via `existingSecret`), with one
`APP_ENCRYPTION_KEY` Secret shared by the whole core family. Render-time guards (`test-guards.sh`)
refuse the unsafe combinations listed in the [chart README](../../deploy/helm/README.md#what-the-charts-enforce).
Image blocks carry a `digest` value for pinning by digest instead of tag; `global.imageTag` pins a release.

The rest of the security model: [security architecture](../platform-documentation/security/security-architecture.html),
[threat model](../platform-documentation/security/threat-model.html),
[secrets management](../platform-documentation/security/secrets-management.html),
[authorization](../security/authorization.md), [hardening settings](../engineering/SECURITY-HARDENING.md).

## Data tier

- PostgreSQL: CloudNativePG; `postgres.instances: 3` plus `postgres.backup` / `scheduledBackup` for HA
  and recovery, or a managed server via `global.postgresql.*` (it must provide `vector`; `age` is optional, set
  `global.postgresql.age=false` where the service does not offer it, which turns graph memory off).
  Tenants are Postgres **schemas** selected with `SET search_path`, so any pooler in front must use
  **session** pooling; transaction pooling is refused at render time.
- Schema migrations run in a `post-install,pre-upgrade` Job; API pods run with migrate-on-boot off.
- Valkey: sessions, rate limits and shared stores once you run more than one API replica. Chat history is
  stored in Postgres (the conversation tree, `core.branchingEnabled`, default `true` since v0.1.8).
- Object storage: single-node SeaweedFS by default; use an external S3 bucket in production.
- Per cloud: ready-made values for AWS, Azure and GCP managed services (RDS, ElastiCache, S3/IRSA; Flexible Server,
  Azure Cache, Blob with workload identity; Cloud SQL/AlloyDB, Memorystore, GCS) and External Secrets Operator
  examples live in [`deploy/helm/examples`](../../deploy/helm/examples); see "Cloud values" in the
  [Helm README](../../deploy/helm/README.md#cloud-values-aws-azure-gcp). Rendered and validated only, not installed.

## Scaling and availability

| Component | Replicas | Autoscaling | Notes |
|---|---|---|---|
| core (API) | `replicaCount`, default **1** | `hpa.enabled` (default off): 2–4 replicas, 75% CPU, plus optional extra metrics or KEDA | Raising replicas is **gated**: the chart fails to render until each `replicaReadiness` flag is true (every per-pod store on Valkey, pinned A2A signing keys). With more than one replica it also sets `APP_REPLICA_MODE=multi` and checks the worst-case database connections against the server limit. Rollouts use `maxUnavailable: 0`. SSE chat streams need long ingress timeouts and no buffering; the defaults set them. Scale-in is slow by default. See [Deployment shapes](../deploy/DEPLOYMENT-SHAPES.md#running-the-api-on-more-than-one-replica). |
| web | `replicaCount` | `hpa.enabled` (default off): 2–6 replicas, 75% CPU | Stateless. |
| core-jobs | 1 | no | More than one replica requires `leaderElection=true`; the chart refuses otherwise. Every scheduled job is advisory-locked or marked per-JVM (`ScheduledJobsAreLockedOrAnnotatedTest`). |
| core-ingest | 0 or 1 | no | Optional (`global.components.coreIngest`). Runs only the knowledge pollers, so large uploads do not compete with agent jobs. Extra replicas give failover, not parallelism: both pollers hold one advisory lock. |
| core-webhooks, core-intake, core-billing | 0 or 1 each | no | Optional pools (`global.components.coreWebhooks`, `coreIntake`, `coreBilling`), independent of each other and of `core-ingest`. Each runs only its own advisory-locked jobs; extra replicas give failover. Resource defaults are estimates. |
| core-worker | per `global.temporal` | no | Temporal worker; scale with workflow load. |
| PostgreSQL | `postgres.instances` (1, or 3 for HA) | no | CNPG fails over between instances; writes scale up, not out. |
| Valkey | single instance (bundled chart) | no | Use an external managed Valkey/Redis for HA. |

Every workload gets a PodDisruptionBudget (`minAvailable: 1` once it has more than one replica) and soft
zone/node topology spread, so node drains and zone loss degrade capacity instead of dropping the service.

**Autoscaling signals:** the default HPA scales on CPU. `hpa.metrics` adds any `autoscaling/v2` metric and
`keda.enabled` renders a KEDA `ScaledObject`; `deploy/helm/examples/values-autoscaling.yaml` scales on open
requests per pod (`http_server_requests_active_seconds_count`, which Spring Boot already exports). Both were
rendered and schema-checked, **not run on a cluster**: they need prometheus-adapter or KEDA, which the kind
install does not have. There is still no queue-depth signal for `core-jobs`, and no Cluster Autoscaler or
node-pool guidance. Sizing guidance and load-test numbers are not published; `tests/load` holds the scripts.

## Verification status

| What | State |
|---|---|
| `helm lint --strict`, `helm template` across scenarios, `kubeconform -strict` on Kubernetes 1.28 and 1.31 | scripted in [`deploy/helm/validate.sh`](../../deploy/helm/validate.sh); runs in CI on every pull request |
| Render-time guard tests | [`deploy/helm/test-guards.sh`](../../deploy/helm/test-guards.sh): more than 230 cases at v0.1.9 (the count moves with every guard added; run it for the current number) |
| Install on a kind cluster (Kubernetes 1.37, ingress-nginx, CloudNativePG, kube-prometheus-stack, 16 GiB Docker) | **run** with locally built images: core, web (incl. the merged platform-admin section), Keycloak, PostgreSQL (CNPG), Valkey and SeaweedFS Running and ready; console answers through the ingress |
| Pod Security `restricted` admission ([`examples/namespace.yaml`](../../deploy/helm/examples/namespace.yaml)) | **run**: every pod admitted, no PodSecurity events |
| `helm test` | **run**: 4 of 4 test pods Succeeded |
| Prometheus scraping (`global.serviceMonitor.enabled`, [`values-kind-observability.yaml`](../../deploy/kind/values-kind-observability.yaml)) | **run**: the `vegaduta-core` ServiceMonitor target is up |
| HPA scale-out | **run** for `web` (metrics-server with `--kubelet-insecure-tls`; target set to 20% CPU): 1 to 3 replicas. `core` was not scaled: it stays at 1 replica until its `replicaReadiness` flags are set |
| Multi-replica contract (`values-scale-out.yaml`), request-based autoscaling, `core-ingest`, `core-webhooks`, `core-intake`, `core-billing`, connection-budget guard | **rendered** by `validate.sh` and exercised by the guard cases in `deploy/helm/test-guards.sh`; the Java side (`ReplicaStoreCoverageTest`, `JobsPoolsCoverEveryJobTest`) is **run**. Two API replicas booting Ready under `replicaStrict` with the Valkey stores, and a one-at-a-time rolling upgrade, were **partially run** on kind on 2026-10-14 ([ADR-0014 section 6](ADR-0014-horizontal-scaling.md)); open streams surviving a rollout and scale-in under load were **not** |
| Per-cloud values (`values-aws/azure/gcp.yaml`) and External Secrets examples | **rendered** and `kubeconform`-checked; the External Secrets documents are skipped by kubeconform (CRD schemas not in its catalog). **Not installed** on EKS, AKS or GKE, no cloud service contacted |
| NetworkPolicy enforcement | **not run**: kind's default CNI does not enforce them; use Calico or Cilium to test |
| Without the sidecar and core-jobs (memory) | the kind runs above left both off |

Two things the live install found, both fixed: the migrate hook Job's 2-connection database pool was starved by
the core's scheduled tasks and failed the install (pool is now 8), and the Postgres image for the CloudNativePG
chart must be built with the CNPG base and `FINAL_USER=26` (see `deploy/postgres/Dockerfile`), otherwise `initdb`
fails with "could not look up effective user ID 26".
