# Run it locally and see the infrastructure

How to start VegaDūta on one machine, which service listens where, and how to look at the running
infrastructure with Docker (Compose) and with `kubectl` / `k9s` (Kubernetes). For hosting it on a
public domain see [tunnel-hosting.md](tunnel-hosting.md); for the Kubernetes design see
[../architecture/kubernetes.md](../architecture/kubernetes.md).

## 1. Pick a way to run it

| Way | Command | Memory (Docker) | Use it for |
|---|---|---|---|
| Lite (Compose) | `scripts/vegaduta up --lite --build` | 6 GB | try the product on localhost |
| Full (Compose) | `scripts/vegaduta up --full --build` | 12 GB core set | everything except the optional profiles |
| Full + profiles | `scripts/vegaduta up --full --build --profile observability --profile tracing …` | up to 24 GB+ | Grafana, Langfuse, code-intel, workflows |
| kind (Kubernetes) | see [section 4](#4-kubernetes-on-your-machine-kind) | about 8 GB (chart README); the app plus monitoring used about 6 GB on a trimmed install | try the Helm charts, `kubectl`, `k9s` |

`--build` builds the images from source (until a release is published); drop it once images are
published. Docker Desktop's memory limit is a hard cap: Settings → Resources → Memory, then Apply & restart
(or set `MemoryMiB` in `~/Library/Group Containers/group.com.docker/settings-store.json` while Docker is
quit). The figures are from [deploy/README.md](../../deploy/README.md); an idle Full core set measured about 2.5 GB, so treat them as sizing headroom. Running Compose and kind together worked in 16 GB with the optional profiles off.

## 2. Optional profiles (Full tier)

The bare `up --full` runs `postgres`, `pgbouncer`, `valkey`, `seaweedfs` (S3 storage), `keycloak`,
`keycloak-db`, `sidecar`, `core`, `web` (the platform-admin console is part of it under /platform-admin). Add profiles with `--profile NAME` (repeatable).
The CLI also sets the variables each profile needs. Full table: [deploy/README.md](../../deploy/README.md#full-stack-profiles).

| Profile | Adds | Open it |
|---|---|---|
| `observability` | Prometheus, Grafana, Loki, Promtail, Alertmanager | Grafana http://localhost:3003, Prometheus http://localhost:9090 |
| `tracing` | Langfuse (web, worker, its Postgres, ClickHouse, Valkey, S3) | http://localhost:3002 |
| `events` / `temporal` / `jobs` | Redpanda (Kafka API); Temporal and its UI; the background-jobs process | Temporal UI http://localhost:8088 |
| `code-intel` / `builder` | code intelligence + a private Docker-in-Docker executor; the App Builder | http://localhost:8090, http://localhost:8091 |
| `voice` / `rl` / `sandbox` / `tools` | LiveKit + voice agent; RL trainer; tenant-code sandbox (privileged); stdio MCP runner | http://localhost:7880 (LiveKit) |

Object storage is SeaweedFS (S3 on port 8333); there is no MinIO. Langfuse bundles its own S3 store.
Products that are not part of this repository (a WhatsApp QR bridge, an academy site) are not started by
these commands; WhatsApp is an omnichannel adapter inside `core` (configured per tenant in the console).

## 3. See what is running (Compose)

| What | How |
|---|---|
| Service state | `scripts/vegaduta status` |
| Logs | `scripts/vegaduta logs core` (also `keycloak`, `web`, `caddy`, …) |
| Everything Docker runs | Docker Desktop → Containers (grouped by Compose project), or `docker ps` |
| CPU and memory | `docker stats` |
| Health from the host | `curl localhost:<core port>/api/system/status/live` |
| First-run checks | `scripts/vegaduta doctor` |
| Stop, keep data / wipe data | `scripts/vegaduta down` / `scripts/vegaduta down --volumes` |

Host ports default to 3000 (console, incl. /platform-admin), 8080 (API), 8180 (Keycloak), 5432
(Postgres), 6379 (Valkey), 8001 (sidecar), 8333 (S3). Override with the `*_HOST_PORT` variables in
`deploy/compose/.env` to run a second instance next to another stack. The profile ports in section 2 are
fixed. Everything binds to `127.0.0.1`; set `BIND_ADDR` only if you mean to expose it.

## 4. Kubernetes on your machine (kind)

Prerequisites: Docker with about 16 GB, `kind`, `kubectl`, `helm`, `k9s` (`brew install kind helm k9s`).

```bash
kind create cluster --config deploy/kind/kind-config.yaml     # host ports 80/443; edit them if Caddy/Compose uses them
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.11.3/deploy/static/provider/kind/deploy.yaml
helm repo add cnpg https://cloudnative-pg.github.io/charts
helm upgrade --install cnpg cnpg/cloudnative-pg -n cnpg-system --create-namespace --wait
kubectl apply -f deploy/helm/examples/namespace.yaml            # Pod Security "restricted", quota, limits
```

Images: until published, build and load them (`docker build` or `scripts/vegaduta compose build`, then
`kind load docker-image ghcr.io/cd-lc/core:local --name vegaduta`, and `web`). The
PostgreSQL image for the chart is the CloudNativePG flavour:

```bash
docker build --build-arg BASE_IMAGE=ghcr.io/cloudnative-pg/postgresql:16-system-bookworm --build-arg FINAL_USER=26 \
  -t ghcr.io/cd-lc/postgres:16-pgvector-age deploy/postgres
kind load docker-image ghcr.io/cd-lc/postgres:16-pgvector-age --name vegaduta
```

Install (local images, no pulls; skip the heavy optional components):

```bash
helm dependency update deploy/helm/charts/vegaduta       # after updating each component chart, see deploy/helm/validate.sh
helm install vegaduta deploy/helm/charts/vegaduta -n vegaduta -f deploy/kind/values-kind.yaml \
  --set global.imageTag=local --set global.imagePullPolicy=IfNotPresent \
  --set global.components.sidecar=false --set global.components.coreJobs=false --wait --timeout 15m
helm test vegaduta -n vegaduta
```

Observability for the cluster (Prometheus Operator + Grafana):
[`deploy/kind/values-kind-observability.yaml`](../../deploy/kind/values-kind-observability.yaml) has the
commands and the values.

### Redeploy after changing the code

The install uses one mutable tag, `local`, with `imagePullPolicy: IfNotPresent`, so a redeploy is
rebuild → reload into the node → restart the pods. Nothing re-pulls and nothing restarts on its own.

```bash
# 1. Build the images that changed (needs deploy/compose/.env once: scripts/vegaduta secrets).
#    core-jobs also serves the core-ingest / core-webhooks / core-intake / core-billing pools.
VEGADUTA_VERSION=local docker compose --env-file deploy/compose/.env \
  -f deploy/compose/docker-compose.yml -f deploy/compose/docker-compose.build.yml \
  build core core-jobs web sidecar        # or `docker build` single services; web takes ~20 min

# 2. Load them into the kind node (~4 GB; a minute or two).
kind load docker-image ghcr.io/cd-lc/core:local ghcr.io/cd-lc/core-jobs:local \
  ghcr.io/cd-lc/web:local ghcr.io/cd-lc/sidecar:local --name vegaduta

# 3. Apply chart changes, if any, keeping the release's stored values.
helm dependency update deploy/helm/charts/vegaduta
helm upgrade vegaduta deploy/helm/charts/vegaduta -n vegaduta --reuse-values --wait --timeout 15m

# 4. Restart the workloads so they pick up the new image under the same tag.
kubectl -n vegaduta rollout restart deploy/vegaduta-core deploy/vegaduta-core-jobs \
  deploy/vegaduta-core-ingest deploy/vegaduta-core-webhooks deploy/vegaduta-core-intake \
  deploy/vegaduta-core-billing deploy/vegaduta-web deploy/vegaduta-sidecar
kubectl -n vegaduta rollout status deploy/vegaduta-core   # repeat per deploy, or watch get pods -w
```

Notes:

- Only build what you deploy. `values-kind.yaml` runs core, core-jobs, web and sidecar; the bundled
  PostgreSQL (CNPG `16-pgvector-age` image), Keycloak, Valkey and SeaweedFS rarely change.
- `helm upgrade --reuse-values` reuses the values the release was installed with. If your install used
  extra `--set` flags or a second values file (HPA, the ingest/webhooks/intake/billing pools, the
  `mock-llm` outbound allowlist, `vegaduta-a2a-signing` envFrom secret), they are already stored — dump
  them with `helm get values vegaduta -n vegaduta` if you need them in a file.
- `core.migrate.enabled` toggles the schema-migration hook Job; with it off the API pods run Flyway at
  boot themselves, so a slow startup after new migrations is normal — check
  `kubectl -n vegaduta logs deploy/vegaduta-core` before assuming a crash.
- Building on the host (`make build-core`) is faster for iterating on the jar, but the image still has
  to be rebuilt and reloaded for Kubernetes to see it.

### Look at it with kubectl and k9s

```bash
kubectl config current-context          # kind-vegaduta
kubectl get pods -A                     # everything
kubectl -n vegaduta get pods,svc,ingress,hpa,pdb
kubectl -n vegaduta logs deploy/vegaduta-core -f
kubectl -n vegaduta describe pod <pod>  # why a pod is not ready
kubectl -n vegaduta get events --sort-by=.lastTimestamp
kubectl top pods -n vegaduta            # needs metrics-server
k9s -n vegaduta                         # terminal UI
```

In `k9s`: `:` then a resource (`pods`, `deploy`, `svc`, `ingress`, `events`, `hpa`) and Enter; `l` logs, `s`
shell, `d` describe, `y` YAML, `/` filter, `0` all namespaces, `?` help, `Ctrl-C` quit.

Docker Desktop's own **Kubernetes** tab ("Enable Kubernetes") is a different, built-in cluster and will not
show a kind cluster. A kind cluster appears under Containers as one container named `<cluster>-control-plane`;
its pods run inside it.

### Reach the apps

- Through the ingress: `http://app.127.0.0.1.nip.io`, `admin.`, `api.`, `auth.` (add the host port if you
  moved it off 80).
- Grafana: `kubectl -n monitoring port-forward svc/monitoring-grafana 3300:80`, then http://localhost:3300
  (user `admin`; password: `kubectl -n monitoring get secret monitoring-grafana -o jsonpath='{.data.admin-password}' | base64 -d`).
- Prometheus: `kubectl -n monitoring port-forward svc/monitoring-kube-prometheus-prometheus 9091:9090`.
- Any Service: `kubectl -n vegaduta port-forward svc/<name> <local>:<port>`.

Tear down: `helm uninstall vegaduta -n vegaduta` (the data volumes stay until you delete the PVCs), or
`kind delete cluster --name vegaduta` to remove everything.

### Sign-in and sign-out troubleshooting on the kind stage

- **Sign-out on the admin host returns Keycloak 400 "Invalid redirect uri".** The chart renders
  `post.logout.redirect.uris` for the `agentic-ai-web` client as `<app>/* <admin>/*`
  (`deploy/helm/charts/keycloak/templates/_realm.tpl`, checked with `helm template` and
  `global.scheme=http`, `global.domain=127.0.0.1.nip.io`). Keycloak imports the realm only once, so a
  stage created before the admin host was added keeps the old value. Patch the live client (nothing is
  re-imported); `kcadm.sh` runs inside the Keycloak pod and needs the bootstrap admin from the
  `vegaduta-keycloak-admin` secret:

  ```bash
  K="kubectl -n vegaduta exec deploy/vegaduta-keycloak --"
  $K /opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 --realm master --user "$KC_ADMIN_USER" --password "$KC_ADMIN_PASSWORD"
  ID=$($K /opt/keycloak/bin/kcadm.sh get clients -r agentic-ai -q clientId=agentic-ai-web --fields id --format csv --noquote)
  $K /opt/keycloak/bin/kcadm.sh update "clients/$ID" -r agentic-ai \
    -s 'attributes."post.logout.redirect.uris"=http://app.127.0.0.1.nip.io/* http://admin.127.0.0.1.nip.io/*'
  ```

  Export `KC_ADMIN_USER` and `KC_ADMIN_PASSWORD` from the secret in your own shell first; never paste them
  into a file. `http://localhost:8080` is Keycloak's in-pod address, not a browser URL.
- **The admin host signs in with `agentic-ai-web`, not `agentic-ai-platform-admin`.** This is by design:
  the merged platform-admin console is served by the web app on `admin.<domain>`, and the web client lists
  the admin host in its redirect and post-logout URIs. The separate `agentic-ai-platform-admin` client
  exists only for installs still running the legacy console. A spec that expects the platform-admin client
  id on the merged console should be changed, not the deployment.
- **Chat streams cannot resume (no `X-Turn-Id`).** `APP_CHAT_RESUMABLE_STREAMS_ENABLED` is off in core by
  default, and the Valkey store setting has no effect while it is off. Set `core.resumableStreamsEnabled=true`
  in the values and upgrade.
