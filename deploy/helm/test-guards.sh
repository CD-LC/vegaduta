#!/usr/bin/env bash
# Negative + structural tests for the render-time guards. Each expect_fail case MUST fail
# `helm template` with the expected message; each expect_ok case must render; the
# structural checks grep the rendered umbrella. Container-based, no cluster.
#   deploy/helm/test-guards.sh
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HELM_IMAGE="${HELM_IMAGE:-alpine/helm:3.16.4}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cp -r "$HERE/charts" "$HERE/examples" "$WORK/"
MOUNT="$WORK"
command -v cygpath >/dev/null 2>&1 && MOUNT="$(cygpath -m "$WORK")"
export MSYS_NO_PATHCONV=1

cat > "$WORK/run-tests.sh" <<'EOF'
set -u
cd "${TG_DIR:-/w}"
for c in core core-jobs core-ingest core-webhooks core-intake core-billing core-worker web sidecar code-intel app-builder rl-trainer tool-sandbox mcp-stdio-runner voice-agent postgres valkey keycloak seaweedfs vegaduta; do
  rm -rf charts/$c/charts; helm dependency update charts/$c >/dev/null || { echo "dependency update failed: $c"; exit 2; }
done
pass=0; failn=0
U="charts/vegaduta"
# Offline renders refuse to GENERATE secrets unless allowed (INF-006); most cases opt in.
D="--set global.domain=example.com --set global.allowGeneratedSecrets=true"
DN="--set global.domain=example.com"
# expect_fail <label> <expected substring> <chart dir> [helm args...]
expect_fail() {
  label="$1"; want="$2"; chart="$3"; shift 3
  out="$(helm template t "$chart" -n vegaduta "$@" 2>&1)" && { echo "FAIL  $label: rendered but should have failed"; failn=$((failn+1)); return; }
  if printf '%s' "$out" | grep -qF -- "$want"; then echo "ok    $label"; pass=$((pass+1)); else echo "FAIL  $label: wrong message:"; printf '%s\n' "$out" | tail -2; failn=$((failn+1)); fi
}
expect_ok() {
  label="$1"; chart="$2"; shift 2
  out="$(helm template t "$chart" -n vegaduta "$@" 2>&1)" && { echo "ok    $label"; pass=$((pass+1)); } || { echo "FAIL  $label: should render:"; printf '%s\n' "$out" | tail -2; failn=$((failn+1)); }
}
check() { # label, command...
  label="$1"; shift
  if "$@" >/dev/null 2>&1; then echo "ok    $label"; pass=$((pass+1)); else echo "FAIL  $label"; failn=$((failn+1)); fi
}
# render <out file> <helm template args...>: a failed render is a failure in its own
# right. Otherwise it leaves an empty file, and every "! grep" check on it passes.
render() {
  out="$1"; shift
  if helm template vegaduta "$@" >"$out" 2>/tmp/render.err; then return 0; fi
  echo "FAIL  render $out:"; tail -2 /tmp/render.err; failn=$((failn+1)); : >"$out"
}

# ExternalSecret example -> "T <target.name>" / "K <target.name> <secretKey>" lines (awk only: the image has no yq).
es_read() {
  awk '/^kind: ExternalSecret/{d=1} /^---/{d=0}
    d&&/^  target:/{ if (match($0,/name: *[^, }]+/)) { t=substr($0,RSTART,RLENGTH); sub(/name: */,"",t); print "T " t; cur=t } else want=1 }
    d&&want&&/^    name:/{ cur=$2; print "T " cur; want=0 }
    d&&/secretKey:/{ k=$0; sub(/.*secretKey: */,"",k); sub(/[ ,}].*/,"",k); print "K " cur " " k }' "$1"
}
# rendered manifests -> "<Secret name> <key>" for every secretKeyRef
refs_read() {
  awk '/secretKeyRef:/{ getline a; getline b; sub(/.*name: */,"",a); sub(/.*key: */,"",b); gsub(/"/,"",a); print a " " b }' "$1" | sort -u
}
# every "<name> <key>" in refs whose name is an ExternalSecret target must be a "K <name> <key>" of the example
es_keys_covered() {
  bad=0
  while read -r n k; do
    grep -q "^T $n\$" "$1" || continue
    grep -q "^K $n $k\$" "$1" || { echo "missing key $k in ExternalSecret $n" >&2; bad=1; }
  done <"$2"
  [ "$bad" -eq 0 ]
}
# the ExternalSecrets for APP_ENCRYPTION_KEY and the internal tokens use creationPolicy Orphan (Owner would let
# Kubernetes garbage-collect the Secret together with the ExternalSecret)
es_orphan() {
  awk '/^kind: ExternalSecret/{d=1} /^---/{d=0; n=""} d&&/name: vegaduta-(core-app|internal)/{n=1} d&&n&&/creationPolicy: Orphan/{ok++} END{exit !(ok>=2)}' "$1"
}

echo "--- umbrella guards"
expect_fail "missing global.domain"                    "global.domain is required" $U
expect_fail "bundled AND external postgres"            "choose ONE" $U $D --set global.postgresql.host=db.example.com
expect_fail "neither bundled nor external postgres"    "postgres.enabled=false and global.postgresql.host is empty" $U $D --set postgres.enabled=false
expect_fail "neither bundled nor external keycloak"    "keycloak.enabled=false and global.oidc.issuerUrl is empty" $U $D --set keycloak.enabled=false
expect_fail "two encryption-key Secrets"               "must read APP_ENCRYPTION_KEY from ONE Secret" $U $D --set core-jobs.appSecret.existingSecret=other
expect_fail "core-jobs image tag skew"                 "core-family images differ" $U $D --set core-jobs.image.tag=9.9.9
expect_ok   "image skew with allowCoreImageSkew"       $U $D --set core-jobs.image.tag=9.9.9 --set allowCoreImageSkew=true
expect_fail "local storage + seaweedfs"                "set seaweedfs.enabled=false" $U $D --set global.objectStorage.type=local
expect_fail "local storage RWO with core-jobs"         "needs global.objectStorage.local.accessModes" $U $D --set global.objectStorage.type=local --set seaweedfs.enabled=false --set-json 'global.objectStorage.local.accessModes=["ReadWriteOnce"]'
expect_ok   "local storage RWO single pod"             $U $D --set global.objectStorage.type=local --set seaweedfs.enabled=false --set-json 'global.objectStorage.local.accessModes=["ReadWriteOnce"]' --set global.components.coreJobs=false
expect_fail "external S3 with static keys needs a Secret" "global.objectStorage.existingSecret is required" $U $D --set seaweedfs.enabled=false --set global.objectStorage.endpoint=https://s3.eu-west-1.amazonaws.com
expect_ok   "external S3 with the SDK credential chain (no Secret)" $U $D --set seaweedfs.enabled=false --set global.objectStorage.endpoint=https://s3.eu-west-1.amazonaws.com --set global.objectStorage.credentials=default
expect_fail "SDK credential chain needs an external endpoint" "credentials=default" $U $D --set global.objectStorage.credentials=default
expect_ok   "external Redis over TLS with an ACL user" $U $D --set valkey.enabled=false --set global.valkey.host=cache.example.com --set global.valkey.tls=true --set global.valkey.username=app --set global.valkey.existingSecret=redis-pw
expect_ok   "managed Kafka over SASL_SSL" $U $D --set global.kafka.bootstrapServers=broker.example.com:9093 --set global.kafka.securityProtocol=SASL_SSL --set global.kafka.saslMechanism=SCRAM-SHA-512 --set global.kafka.existingSecret=kafka-sasl
expect_ok   "Azure Blob with workload identity (no Secret)" $U $D --set global.objectStorage.type=azure-blob --set seaweedfs.enabled=false --set global.objectStorage.credentials=default --set global.objectStorage.azure.endpoint=https://acct.blob.core.windows.net
expect_fail "Azure Blob workload identity needs the account endpoint" "azure.endpoint" $U $D --set global.objectStorage.type=azure-blob --set seaweedfs.enabled=false --set global.objectStorage.credentials=default
expect_fail "Azure Blob with static credentials needs a Secret" "global.objectStorage.azure.existingSecret is required" $U $D --set global.objectStorage.type=azure-blob --set seaweedfs.enabled=false
expect_fail "Azure Blob with bundled SeaweedFS on" "set seaweedfs.enabled=false" $U $D --set global.objectStorage.type=azure-blob --set global.objectStorage.credentials=default --set global.objectStorage.azure.endpoint=https://a.blob.core.windows.net
expect_fail "external postgres without Secret"         "global.postgresql.existingSecret is required" $U $D --set postgres.enabled=false --set global.postgresql.host=db.example.com
X="--set postgres.enabled=false --set global.postgresql.host=db.example.com --set keycloak.database.existingSecret=kc"
expect_fail "database IAM login on the bundled database" "needs a managed database" $U $D --set global.postgresql.auth=aws-iam
expect_fail "database IAM login without sslMode"       "global.postgresql.sslMode" $U $D $X --set global.postgresql.auth=azure-entra
expect_fail "database IAM login with sslMode=prefer"   "global.postgresql.sslMode" $U $D $X --set global.postgresql.auth=gcp-iam --set global.postgresql.sslMode=prefer
expect_fail "unknown database auth mode (schema)"      "must be one of" $U $D $X --set global.postgresql.auth=iam
expect_ok   "database IAM login: no password Secret"   $U $D $X --set global.postgresql.auth=aws-iam --set global.postgresql.sslMode=require
expect_fail "temporal without target"                  "global.temporal.target" $U $D --set global.temporal.enabled=true
expect_fail "tool-sandbox without RuntimeClass"        "VM-isolating RuntimeClass" $U $D --set global.components.toolSandbox=true
expect_fail "mcp-stdio-runner without RuntimeClass"    "VM-isolating RuntimeClass" $U $D --set global.components.mcpStdioRunner=true
expect_fail "voice-agent without LiveKit"              "global.livekit.internalUrl" $U $D --set global.components.voiceAgent=true
expect_fail "gateway without parentRefs"               "global.gateway.parentRefs is required" $U $D --set global.gateway.enabled=true
expect_fail "transaction pooling declared (core chart)" "must be 'session'" charts/core $D --set global.postgresql.poolMode=transaction

echo "--- generated secrets (GitOps safety)"
expect_fail "generated secrets in an offline render"   "without cluster access" $U $DN
expect_fail "offline render explains the opt-out"      "global.allowGeneratedSecrets=true" $U $DN
expect_fail "subchart generated secret offline"        "without cluster access" charts/valkey $DN
expect_ok   "all existingSecret renders offline (GitOps)" $U -f examples/values-external.yaml

echo "--- network policy"
expect_fail "gateway + netpol without gatewayNamespace" "global.networkPolicy.gatewayNamespace is required" $U $D --set global.gateway.enabled=true --set-json 'global.gateway.parentRefs=[{"name":"public","namespace":"gateway-system"}]'
expect_ok   "gateway + netpol with gatewayNamespace"   $U $D --set global.gateway.enabled=true --set-json 'global.gateway.parentRefs=[{"name":"public","namespace":"gateway-system"}]' --set global.networkPolicy.gatewayNamespace=envoy-gateway-system
expect_fail "global ServiceMonitor without monitoringNamespace" "monitoringNamespace is empty" $U $D --set global.serviceMonitor.enabled=true
expect_fail "coreEgress typo rejected by schema"       "coreEgress" $U $D --set global.networkPolicy.coreEgress.smpt=true

echo "--- core family"
expect_fail "core keda without readiness"                "needs every replicaReadiness flag" $U $D --set core.keda.enabled=true --set 'core.keda.triggers[0].type=cpu'
expect_fail "hpa and keda together"                    "both true" $U $D --set core.hpa.enabled=true --set core.keda.enabled=true --set 'core.keda.triggers[0].type=cpu' --set core.replicaReadiness.a2aTaskStoreRedis=true --set core.replicaReadiness.abuseThrottleRedis=true --set core.replicaReadiness.mcpRegistryRedis=true --set core.replicaReadiness.a2aSigningKeysPinned=true
expect_fail "keda without triggers"                    "keda.triggers" $U $D --set core.keda.enabled=true --set core.replicaReadiness.a2aTaskStoreRedis=true --set core.replicaReadiness.abuseThrottleRedis=true --set core.replicaReadiness.mcpRegistryRedis=true --set core.replicaReadiness.a2aSigningKeysPinned=true
expect_ok   "core keda with all flags"                 $U $D --set core.keda.enabled=true --set 'core.keda.triggers[0].type=cpu' --set core.replicaReadiness.a2aTaskStoreRedis=true --set core.replicaReadiness.abuseThrottleRedis=true --set core.replicaReadiness.mcpRegistryRedis=true --set core.replicaReadiness.a2aSigningKeysPinned=true
expect_fail "api autoscaled past the DB connection limit" "exceed the server limit" $U $D --set core.hpa.enabled=true --set core.hpa.maxReplicas=20 --set core.replicaReadiness.a2aTaskStoreRedis=true --set core.replicaReadiness.abuseThrottleRedis=true --set core.replicaReadiness.mcpRegistryRedis=true --set core.replicaReadiness.a2aSigningKeysPinned=true
expect_ok   "api autoscaled with a bigger max_connections" $U $D --set core.hpa.enabled=true --set core.hpa.maxReplicas=20 --set postgres.parameters.max_connections=400 --set core.replicaReadiness.a2aTaskStoreRedis=true --set core.replicaReadiness.abuseThrottleRedis=true --set core.replicaReadiness.mcpRegistryRedis=true --set core.replicaReadiness.a2aSigningKeysPinned=true
expect_fail "external DB limit known and too small"    "exceed the server limit" $U $D --set core.replicaCount=3 --set global.postgresql.maxConnections=60 --set global.postgresql.host=db.example.com --set global.postgresql.existingSecret=pg --set postgres.enabled=false --set keycloak.database.existingSecret=kc --set core.replicaReadiness.a2aTaskStoreRedis=true --set core.replicaReadiness.abuseThrottleRedis=true --set core.replicaReadiness.mcpRegistryRedis=true --set core.replicaReadiness.a2aSigningKeysPinned=true
expect_fail "core replicaCount=2 without readiness"    "needs every replicaReadiness flag" $U $D --set core.replicaCount=2
expect_fail "core hpa without readiness"               "needs every replicaReadiness flag" $U $D --set core.hpa.enabled=true
expect_ok   "core replicaCount=2 with all flags"       $U $D --set core.replicaCount=2 --set core.replicaReadiness.a2aTaskStoreRedis=true --set core.replicaReadiness.abuseThrottleRedis=true --set core.replicaReadiness.mcpRegistryRedis=true --set core.replicaReadiness.a2aSigningKeysPinned=true
expect_fail "core maxUnavailable=1"                    "maxUnavailable must be 0" $U $D --set core.strategy.rollingUpdate.maxUnavailable=1
expect_ok   "core maxUnavailable=1 when allowed"       $U $D --set core.strategy.rollingUpdate.maxUnavailable=1 --set core.allowNonZeroUnavailable=true
expect_fail "ingest pool without core-jobs"           "needs global.components.coreJobs=true" $U $D --set global.components.coreIngest=true --set global.components.coreJobs=false
expect_fail "core-ingest replicaCount=2"               "core-ingest is a singleton" $U $D --set global.components.coreIngest=true --set core-ingest.replicaCount=2
expect_ok   "core-ingest replicaCount=2 + leaderElection" $U $D --set global.components.coreIngest=true --set core-ingest.replicaCount=2 --set core-ingest.leaderElection=true
expect_fail "core-ingest autoscaled"                   "core-ingest must not be autoscaled" $U $D --set global.components.coreIngest=true --set core-ingest.hpa.enabled=true
expect_fail "core-ingest image tag differs from core"  "core-family images differ" $U $D --set global.components.coreIngest=true --set core-ingest.image.tag=other
for p in Webhooks:webhooks Intake:intake Billing:billing; do
  f="${p%%:*}"; n="core-${p##*:}"
  expect_fail "$n pool without core-jobs"              "core$f=true needs global.components.coreJobs=true" $U $D --set global.components.core$f=true --set global.components.coreJobs=false
  expect_fail "$n replicaCount=2"                      "$n is a singleton" $U $D --set global.components.core$f=true --set $n.replicaCount=2
  expect_ok   "$n replicaCount=2 + leaderElection"     $U $D --set global.components.core$f=true --set $n.replicaCount=2 --set $n.leaderElection=true
  expect_fail "$n autoscaled"                          "$n must not be autoscaled" $U $D --set global.components.core$f=true --set $n.hpa.enabled=true
  expect_fail "$n image tag differs from core"         "core-family images differ" $U $D --set global.components.core$f=true --set $n.image.tag=other
  expect_fail "$n uses another APP_ENCRYPTION_KEY Secret" "must read APP_ENCRYPTION_KEY from ONE Secret" $U $D --set global.components.core$f=true --set $n.appSecret.existingSecret=other
done
# The connection budget counts every enabled pool: 12 (api) + 10 (jobs) + 10 (migrate) = 32 fits a limit of 60-20;
# adding all four pools (6+6+6+4) makes 54 and must not.
expect_ok   "budget: 60 connections fit without pools"  $U $D --set postgres.parameters.max_connections=60
expect_fail "budget: all four pools exceed 60"          "exceed the server limit" $U $D --set postgres.parameters.max_connections=60 --set global.components.coreIngest=true --set global.components.coreWebhooks=true --set global.components.coreIntake=true --set global.components.coreBilling=true
expect_ok   "budget: one small pool still fits 60"      $U $D --set postgres.parameters.max_connections=60 --set global.components.coreBilling=true
expect_fail "budget: a pool's replicas are counted"     "exceed the server limit" $U $D --set postgres.parameters.max_connections=60 --set global.components.coreWebhooks=true --set core-webhooks.replicaCount=4 --set core-webhooks.leaderElection=true
expect_fail "core-jobs replicaCount=2"                 "core-jobs is a singleton" $U $D --set core-jobs.replicaCount=2
expect_ok   "core-jobs replicaCount=2 + leaderElection" $U $D --set core-jobs.replicaCount=2 --set core-jobs.leaderElection=true
expect_fail "APP_ENCRYPTION_KEY as plain env"          "APP_ENCRYPTION_KEY" $U $D --set core.env.APP_ENCRYPTION_KEY=literal
expect_fail "APP_ENCRYPTION_KEY via global extraEnv"   "APP_ENCRYPTION_KEY" $U $D --set global.core.extraEnv.APP_ENCRYPTION_KEY=literal
expect_fail "valkey.tls with the bundled Valkey"       "global.valkey.tls=true needs an external" $U $D --set global.valkey.tls=true
expect_fail "valkey.tls as a string is a type error"   "tls" $U $D --set valkey.enabled=false --set global.valkey.host=cache.example.com --set-string global.valkey.tls=false
expect_fail "kafka securityProtocol typo (schema)"     "securityProtocol" $U $D --set global.kafka.bootstrapServers=b:9093 --set global.kafka.securityProtocol=SASL_SLL
expect_fail "kafka saslMechanism without SASL protocol" "needs global.kafka.securityProtocol" $U $D --set global.kafka.bootstrapServers=b:9093 --set global.kafka.saslMechanism=PLAIN
expect_fail "kafka SASL_SSL without a JAAS source"     "needs the JAAS line" $U $D --set global.kafka.bootstrapServers=b:9093 --set global.kafka.securityProtocol=SASL_SSL --set global.kafka.saslMechanism=PLAIN
expect_fail "postgresql.age as a string is a type error" "age" $U $D --set-string global.postgresql.age=perhaps
expect_fail "REDIS_PASSWORD literal in core extraEnv"  "must not carry a literal value" $U $D --set-json 'core.extraEnv=[{"name":"REDIS_PASSWORD","value":"x"}]'
expect_fail "Kafka JAAS literal in core-jobs extraEnv" "must not carry a literal value" $U $D --set-json 'core-jobs.extraEnv=[{"name":"SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG","value":"x"}]'
expect_fail "Azure conn string literal in core-worker extraEnv" "must not carry a literal value" $U $D --set global.temporal.enabled=true --set global.temporal.target=t:7233 --set-json 'core-worker.extraEnv=[{"name":"STORAGE_AZURE_CONNECTION_STRING","value":"x"}]'
expect_fail "SPRING_DATA_REDIS_PASSWORD alias in env"   "SPRING_DATA_REDIS_PASSWORD" $U $D --set core.env.SPRING_DATA_REDIS_PASSWORD=literal
expect_ok   "secret name via valueFrom in core extraEnv is fine" $U $D --set-json 'core.extraEnv=[{"name":"REDIS_PASSWORD","valueFrom":{"secretKeyRef":{"name":"s","key":"k"}}},{"name":"FOO","value":"1"}]'
expect_fail "localhost URL in core env"               "points at localhost" $U $D --set core.env.SOME_URL=http://localhost:3000
expect_fail "localhost URL in web env"                 "points at localhost" $U $D --set web.env.NEXT_PUBLIC_API_BASE_URL=http://127.0.0.1:8080
expect_fail "standalone core: local vs global secret"  "differs from global.core.appSecret.existingSecret" charts/core $D --set appSecret.existingSecret=a --set global.core.appSecret.existingSecret=b
expect_fail "core-worker without Temporal"             "needs global.temporal.enabled=true" charts/core-worker $D

echo "--- generic guards"
expect_fail "image tag latest"                         "'latest' is not allowed" $U $D --set web.image.tag=latest
expect_fail "global image tag latest (schema)"          "imageTag" $U $D --set global.imageTag=latest
expect_fail "transaction pooling rejected by schema"   "poolMode" charts/vegaduta $D --set global.postgresql.poolMode=transaction
expect_fail "component typo rejected by schema"        "coreJob" $U $D --set global.components.coreJob=true
expect_fail "malformed digest"                         "must look like sha256" $U $D --set web.image.digest=sha256:short
expect_fail "localhost ingress host"                   "must not be localhost" $U $D --set-json 'web.ingress.extraHosts=["localhost"]'
expect_fail "resources without limits (web chart)"     "resources.limits is required" charts/web $D --set resources.limits=null
expect_fail "sidecar replicaCount=2"                   "replicaCount must be exactly 1" $U $D --set sidecar.replicaCount=2
expect_fail "sidecar auth disabled"                    "SIDECAR_AUTH_REQUIRED=false" $U $D --set-string sidecar.env.SIDECAR_AUTH_REQUIRED=false
expect_fail "RWO PVC with RollingUpdate"               "must be Recreate" $U $D --set global.components.codeIntel=true --set code-intel.strategy.type=RollingUpdate
expect_fail "sidecar receives APP_ENCRYPTION_KEY"      "must never receive APP_ENCRYPTION_KEY" $U $D --set-json 'sidecar.secretEnv=[{"name":"APP_ENCRYPTION_KEY","secretName":"x"}]'
expect_fail "hostPath volume"                          "hostPath volumes are not allowed" $U $D --set-json 'web.extraVolumes=[{"name":"d","hostPath":{"path":"/var/run/docker.sock"}}]'
S="--set global.components.toolSandbox=true --set tool-sandbox.runtimeClassName=kata"
expect_ok   "tool-sandbox with RuntimeClass"           $U $D $S
expect_fail "tool-sandbox extra secret"                "is not allowed; no platform credential" $U $D $S --set-json 'tool-sandbox.secretEnv=[{"name":"POSTGRES_PASSWORD","secretName":"x"}]'
expect_fail "tool-sandbox egress"                      "NO egress" $U $D $S --set tool-sandbox.networkPolicy.allowExternalEgress=true
expect_fail "tool-sandbox cloud identity"              "no cloud identity" $U $D $S --set tool-sandbox.serviceAccount.annotations.foo=bar

echo "--- structure of the default render"
render /tmp/def.yaml $U -n vegaduta $D
check "password login (default): no DB_AUTH, password from the Secret" sh -c '! grep -q "name: DB_AUTH\|DB_AUTH:" /tmp/def.yaml && grep -q "name: POSTGRES_PASSWORD" /tmp/def.yaml'
render /tmp/iam.yaml $U -n vegaduta $D $X --set global.postgresql.auth=azure-entra --set global.postgresql.sslMode=verify-full
check "database IAM login: DB_AUTH set, URL has sslmode, no POSTGRES_PASSWORD" sh -c 'grep -A1 "name: DB_AUTH" /tmp/iam.yaml | grep -q "azure-entra" && grep -q "sslmode=verify-full" /tmp/iam.yaml && ! grep -q "POSTGRES_PASSWORD" /tmp/iam.yaml'
# A password Secret that is configured but unused in token mode must not be referenced anywhere.
render /tmp/iam-pw.yaml $U -n vegaduta $D $X --set global.postgresql.auth=aws-iam --set global.postgresql.sslMode=require --set global.postgresql.existingSecret=unused-db-pw
check "database IAM login: the configured password Secret is not referenced" sh -c '! grep -q "unused-db-pw" /tmp/iam-pw.yaml'
render /tmp/iam-aws.yaml $U -n vegaduta $D $X --set global.postgresql.auth=aws-iam --set global.postgresql.sslMode=require
render /tmp/iam-gcp.yaml $U -n vegaduta $D $X --set global.postgresql.auth=gcp-iam --set global.postgresql.sslMode=require
# np_has / np_lacks <file> <NetworkPolicy name> <literal>: the named policy does / does not contain it.
np_has()   { awk -v name="$2" -v pat="$3" '/^kind: NetworkPolicy/{n=1} n&&$0=="  name: " name{c=1} c&&index($0,pat){ok=1} /^---/{n=0;c=0} END{exit !ok}' "$1"; }
np_lacks() { ! np_has "$@"; }
check "netpol: aws-iam migrate Job reaches STS (public 443)"            np_has   /tmp/iam-aws.yaml vegaduta-core-migrate 'port: 443'
check "netpol: aws-iam migrate Job reaches the Pod Identity agent"      np_has   /tmp/iam-aws.yaml vegaduta-core-migrate 169.254.170.23/32
check "netpol: aws-iam core pods reach the Pod Identity agent"          np_has   /tmp/iam-aws.yaml vegaduta-core 169.254.170.23/32
check "netpol: aws-iam does NOT open the node IMDS (169.254.169.254)"   np_lacks /tmp/iam-aws.yaml vegaduta-core-migrate 169.254.169.254
check "netpol: gcp-iam migrate Job reaches the GKE metadata server /32" np_has   /tmp/iam-gcp.yaml vegaduta-core-migrate 169.254.169.254/32
check "netpol: azure-entra migrate Job reaches Entra (public 443)"      np_has   /tmp/iam.yaml vegaduta-core-migrate 'port: 443'
check "netpol: azure-entra opens no link-local address"                 np_lacks /tmp/iam.yaml vegaduta-core-migrate 169.254.1
check "netpol: password login (default) opens no link-local address"    np_lacks /tmp/def.yaml vegaduta-core-migrate 169.254.1
check "migrate Job (bundled DB) is post-install,pre-upgrade" grep -q 'helm.sh/hook: post-install,pre-upgrade' /tmp/def.yaml
check "migrate Job is an Argo CD Sync hook in wave 0"    sh -c 'grep -q "argocd.argoproj.io/hook: Sync" /tmp/def.yaml && grep -q "argocd.argoproj.io/sync-wave: \"0\"" /tmp/def.yaml'
check "core Deployment is Argo CD wave 1"                grep -q 'argocd.argoproj.io/sync-wave: "1"' /tmp/def.yaml
check "generated Secrets are Argo CD wave -2"            grep -q 'argocd.argoproj.io/sync-wave: "-2"' /tmp/def.yaml
check "every PDB lets crash-looping pods be evicted"     sh -c '[ "$(grep -c "^kind: PodDisruptionBudget" /tmp/def.yaml)" -eq "$(grep -c "unhealthyPodEvictionPolicy: AlwaysAllow" /tmp/def.yaml)" ]'
check "keycloak Ingress routes the realm path"           grep -q 'path: /realms/agentic-ai' /tmp/def.yaml
check "keycloak Ingress: no /admin, no catch-all /"      sh -c '! awk "/^kind: Ingress/{i=1} i&&/name: vegaduta-keycloak\$/{k=1} k&&/- path: \\/(admin)?\$/{bad=1} /^---/{i=0;k=0} END{exit !bad}" /tmp/def.yaml'
check "postgres preloads age + grants ag_catalog"        sh -c 'grep -q "shared_preload_libraries" /tmp/def.yaml && grep -q "GRANT USAGE ON SCHEMA ag_catalog" /tmp/def.yaml'
check "API runs with APP_TENANCY_MIGRATE_ON_BOOT=false"  grep -q 'name: APP_TENANCY_MIGRATE_ON_BOOT' /tmp/def.yaml
check "migrate Job sets APP_TENANCY_MIGRATE_ONLY"        grep -q 'name: APP_TENANCY_MIGRATE_ONLY' /tmp/def.yaml
check "API has shape-api (core-jobs on)"                 grep -q 'mcpserver,shape-api"' /tmp/def.yaml
# The chart never emits WORKFLOW_KAFKA_ENABLED: a broker bootstrap can arrive from places it cannot read
# (the app Secret, extraEnvFromSecrets, envFrom), so core decides at runtime (no bootstrap resolved -> no
# Kafka; WorkflowKafkaDefaultsTest). kafka_route proves, for every way a broker can be configured, that the
# flag is absent from every workload (it would override a Secret-held bootstrap, since env outranks envFrom).
# kafka_flag_in prints the workloads carrying it.
kafka_flag_in() { awk '/^# Source:/{src=$3} /name: WORKFLOW_KAFKA_ENABLED/{print src}' "$1" | sort | tr '\n' ' '; }
kafka_route() { # label, helm args...
  label="$1"; shift
  render /tmp/kafka-route.yaml $U -n vegaduta $D "$@"
  got="$(kafka_flag_in /tmp/kafka-route.yaml)"
  if [ -z "$got" ]; then echo "ok    $label"; pass=$((pass+1)); else echo "FAIL  $label: chart emits WORKFLOW_KAFKA_ENABLED on [$got]"; failn=$((failn+1)); fi
}
kafka_route "no Kafka configured: chart emits no Kafka switch (core decides at runtime)"
kafka_route "Kafka via global.kafka.bootstrapServers" --set global.kafka.bootstrapServers=b:9092
check "global.kafka.bootstrapServers reaches API, core-jobs and migrate Job" sh -c '[ "$(grep -c "name: WORKFLOW_KAFKA_BOOTSTRAP_SERVERS" /tmp/kafka-route.yaml)" -eq 3 ]'
kafka_route "Kafka via the app Secret (global.core.appSecret.existingSecret)" --set global.core.appSecret.existingSecret=my-app-secret
kafka_route "Kafka via global.core.extraEnv WORKFLOW_KAFKA_BOOTSTRAP_SERVERS" --set global.core.extraEnv.WORKFLOW_KAFKA_BOOTSTRAP_SERVERS=b:9092
kafka_route "Kafka via global.core.extraEnv SPRING_KAFKA_BOOTSTRAP_SERVERS" --set global.core.extraEnv.SPRING_KAFKA_BOOTSTRAP_SERVERS=b:9092
kafka_route "Kafka via global.core.extraEnvFromSecrets" --set-json 'global.core.extraEnvFromSecrets=["kafka-conn"]'
kafka_route "Kafka via core.env WORKFLOW_KAFKA_BOOTSTRAP_SERVERS" --set core.env.WORKFLOW_KAFKA_BOOTSTRAP_SERVERS=b:9092
kafka_route "Kafka via core.env SPRING_KAFKA_BOOTSTRAP_SERVERS" --set core.env.SPRING_KAFKA_BOOTSTRAP_SERVERS=b:9092
kafka_route "Kafka via core.extraEnv list WORKFLOW_KAFKA_BOOTSTRAP_SERVERS" --set-json 'core.extraEnv=[{"name":"WORKFLOW_KAFKA_BOOTSTRAP_SERVERS","value":"b:9092"}]'
kafka_route "Kafka via core.extraEnv list SPRING_KAFKA_BOOTSTRAP_SERVERS" --set-json 'core.extraEnv=[{"name":"SPRING_KAFKA_BOOTSTRAP_SERVERS","value":"b:9092"}]'
kafka_route "Kafka via core.secretEnv (secretKeyRef)" --set-json 'core.secretEnv=[{"name":"WORKFLOW_KAFKA_BOOTSTRAP_SERVERS","secretName":"s","key":"k"}]'
kafka_route "Kafka via core.envFrom" --set-json 'core.envFrom=[{"secretRef":{"name":"x"}}]'
kafka_route "Kafka via core-jobs.extraEnv list" --set-json 'core-jobs.extraEnv=[{"name":"WORKFLOW_KAFKA_BOOTSTRAP_SERVERS","value":"b:9092"}]'
check "slim default: no mcp profile without the runner"  sh -c '! grep -q "mcpserver,mcp" /tmp/def.yaml'
check "slim default: API image is core (not core-full)"  sh -c 'grep -q "image: \"ghcr.io/cd-lc/core:" /tmp/def.yaml && ! grep -q "core-full" /tmp/def.yaml'
check "core-jobs has shape-jobs,jobs"                    grep -q 'shape-jobs,jobs"' /tmp/def.yaml
check "one APP_ENCRYPTION_KEY Secret for all core pods"  sh -c '[ "$(grep -A3 "name: APP_ENCRYPTION_KEY$" /tmp/def.yaml | grep -c "name: vegaduta-core-app")" -eq 3 ]'
check "generated Secrets are kept on uninstall"          sh -c '[ "$(grep -c "helm.sh/resource-policy: keep" /tmp/def.yaml)" -ge 6 ]'
check "NetworkPolicies on by default (default-deny)"     grep -q 'name: vegaduta-default-deny' /tmp/def.yaml
check "core 443 egress excludes the metadata range"      sh -c 'awk "/^kind: NetworkPolicy/{n=1} n&&/name: vegaduta-core\$/{c=1} c&&/169.254.0.0\\/16/{ok=1} /^---/{n=0;c=0} END{exit !ok}" /tmp/def.yaml'
check "every Deployment has a PDB"                       sh -c '[ "$(grep -c "^kind: Deployment" /tmp/def.yaml)" -le "$(grep -c "^kind: PodDisruptionBudget" /tmp/def.yaml)" ]'
check "every workload container drops ALL caps"          sh -c '[ "$(grep -cE "^ {8,12}image: " /tmp/def.yaml)" -gt 0 ] && [ "$(grep -cE "^ {8,12}image: " /tmp/def.yaml)" -le "$(grep -cE "^ +- ALL$" /tmp/def.yaml)" ] && ! grep -q "privileged: true" /tmp/def.yaml'
check "allowFrom peers are limited to service ports"     sh -c 'awk "/^kind: NetworkPolicy/{n=1} n&&/name: vegaduta-seaweedfs\$/{c=1} c&&/^  ingress:/{i=1} i&&/port: http/{ok=1} /^---/{n=0;c=0;i=0} END{exit !ok}" /tmp/def.yaml'
render /tmp/ext.yaml $U -n vegaduta -f examples/values-external.yaml
check "external DB: migrate Job is post-install,pre-upgrade" grep -q 'helm.sh/hook: post-install,pre-upgrade' /tmp/ext.yaml
check "external DB: nothing is a pre-install hook (release SA/Secrets do not exist yet)" sh -c '! grep -q "helm.sh/hook: pre-install" /tmp/ext.yaml'
check "external: derived egress to DB (5432) and Temporal (7233)" sh -c 'for p in 5432 7233; do grep -q "port: $p\$" /tmp/ext.yaml || exit 1; done'
render /tmp/r2.yaml $U -n vegaduta $D --set core.replicaCount=2 --set core.replicaReadiness.a2aTaskStoreRedis=true --set core.replicaReadiness.abuseThrottleRedis=true --set core.replicaReadiness.mcpRegistryRedis=true --set core.replicaReadiness.a2aSigningKeysPinned=true
check "2 replicas: preferred pod anti-affinity on core"  grep -q 'podAntiAffinity' /tmp/r2.yaml
# Multi-replica: the chart must turn on core's boot-time ReplicaModeSafetyCheck and put EVERY
# shared store in Valkey (ReplicaStoreCoverageTest keeps the list in step with the Java check).
check "2 replicas: APP_REPLICA_MODE=multi"               sh -c 'grep -A1 "name: APP_REPLICA_MODE$" /tmp/r2.yaml | grep -q "\"multi\""'
check "2 replicas: every shared store is redis"          sh -c 'for k in APP_ABUSE_THROTTLE_STORE APP_A2A_TASK_STORE APP_MCP_REGISTRY_STORE APP_SANDBOX_DATA_ANALYSIS_STORE APP_SANDBOX_PLATFORM_E2B_QUOTA_STORE APP_CHAT_RESUMABLE_STREAMS_STORE APP_GUARDRAILS_PROCEDURES_SESSION_CALLS_STORE APP_DEMO_STUDIO_APPROVAL_STORE TOOL_EFFECT_JOURNAL_STORE MCP_OAUTH_STORE; do grep -A1 "name: $k\$" /tmp/r2.yaml | grep -q "\"redis\"" || { echo "missing $k"; exit 1; }; done'
check "2 replicas: boot orphan sweep is stale"           sh -c 'grep -A1 "name: APP_QUALITY_BOOT_ORPHAN_SWEEP$" /tmp/r2.yaml | grep -q "\"stale\""'
check "2 replicas: strict is off by default"             sh -c '! grep -q "APP_REPLICA_MODE_STRICT" /tmp/r2.yaml'
render /tmp/r2s.yaml $U -n vegaduta $D --set core.replicaCount=2 --set core.replicaStrict=true --set core.replicaReadiness.a2aTaskStoreRedis=true --set core.replicaReadiness.abuseThrottleRedis=true --set core.replicaReadiness.mcpRegistryRedis=true --set core.replicaReadiness.a2aSigningKeysPinned=true
check "2 replicas + replicaStrict: APP_REPLICA_MODE_STRICT" sh -c 'grep -A1 "name: APP_REPLICA_MODE_STRICT$" /tmp/r2s.yaml | grep -q "\"true\""'
render /tmp/hpa.yaml $U -n vegaduta $D --set core.hpa.enabled=true --set core.replicaReadiness.a2aTaskStoreRedis=true --set core.replicaReadiness.abuseThrottleRedis=true --set core.replicaReadiness.mcpRegistryRedis=true --set core.replicaReadiness.a2aSigningKeysPinned=true --set 'core.hpa.metrics[0].type=Pods' --set 'core.hpa.metrics[0].pods.metric.name=http_server_requests_active' --set 'core.hpa.metrics[0].pods.target.type=AverageValue' --set 'core.hpa.metrics[0].pods.target.averageValue=40'
check "hpa: custom metric is appended after CPU"        sh -c 'awk "/^kind: HorizontalPodAutoscaler/{h=1} h&&/name: cpu/{c=1} h&&c&&/http_server_requests_active/{ok=1} /^---/{h=0;c=0} END{exit !ok}" /tmp/hpa.yaml'
check "hpa: scale-in is slowed by default"              sh -c 'awk "/^kind: HorizontalPodAutoscaler/{h=1} h&&/stabilizationWindowSeconds: 600/{ok=1} /^---/{h=0} END{exit !ok}" /tmp/hpa.yaml'
check "hpa: API Deployment omits spec.replicas"         sh -c '! awk "/^kind: Deployment/{d=1} d&&/name: vegaduta-core\$/{c=1} c&&/^  replicas:/{bad=1} /^---/{d=0;c=0} END{exit !bad}" /tmp/hpa.yaml'
check "hpa: sets APP_REPLICA_MODE=multi"                sh -c 'grep -A1 "name: APP_REPLICA_MODE$" /tmp/hpa.yaml | grep -q "\"multi\""'
render /tmp/keda.yaml $U -n vegaduta $D --set core.keda.enabled=true --set 'core.keda.triggers[0].type=prometheus' --set core.replicaReadiness.a2aTaskStoreRedis=true --set core.replicaReadiness.abuseThrottleRedis=true --set core.replicaReadiness.mcpRegistryRedis=true --set core.replicaReadiness.a2aSigningKeysPinned=true
check "keda: a ScaledObject targets the API Deployment" sh -c 'awk "/^kind: ScaledObject/{s=1} s&&/name: vegaduta-core\$/{ok=1} /^---/{s=0} END{exit !ok}" /tmp/keda.yaml'
check "keda: no HorizontalPodAutoscaler is rendered for core" sh -c '! awk "/^kind: HorizontalPodAutoscaler/{h=1} h&&/name: vegaduta-core\$/{bad=1} /^---/{h=0} END{exit !bad}" /tmp/keda.yaml'
check "keda: sets APP_REPLICA_MODE=multi"               sh -c 'grep -A1 "name: APP_REPLICA_MODE$" /tmp/keda.yaml | grep -q "\"multi\""'
# Pools of the jobs shape: off by default (one jobs pod runs everything); on, the main pod leaves the two
# knowledge pollers to core-ingest, which runs nothing else (JobsPoolsCoverEveryJobTest proves the split).
check "default: no pool profile is active"               sh -c '! grep -q "shape-jobs-" /tmp/def.yaml && ! grep -q "name: vegaduta-core-ingest\$" /tmp/def.yaml'
render /tmp/ing.yaml $U -n vegaduta $D --set global.components.coreIngest=true
check "ingest: a core-ingest Deployment is rendered"     grep -q 'name: vegaduta-core-ingest$' /tmp/ing.yaml
check "ingest: it runs shape-jobs-ingest"                sh -c '[ "$(grep -c "shape-jobs,shape-jobs-ingest,jobs" /tmp/ing.yaml)" -eq 1 ]'
check "ingest: the main jobs pod offloads exactly ingest" sh -c '[ "$(grep -A1 "name: APP_SCHEDULING_OFFLOADED_POOLS\$" /tmp/ing.yaml | grep -c "\"ingest\"")" -eq 1 ] && ! grep -q "shape-jobs-core" /tmp/ing.yaml'
check "ingest: same image as core-jobs (no new image)"   sh -c '[ "$(grep -cE "image: .*/core-jobs:" /tmp/ing.yaml)" -ge 2 ] && ! grep -q "/core-ingest:" /tmp/ing.yaml'
check "ingest: smaller DB pool than core-jobs"           sh -c 'grep -A1 "name: DB_HIKARI_MAX_POOL_SIZE\$" /tmp/ing.yaml | grep -q "\"6\""'
check "ingest: postgres accepts it through its NetworkPolicy" sh -c 'awk "/^kind: NetworkPolicy/{n=1} n&&/name: vegaduta-postgres\$/{c=1} c&&/app.kubernetes.io\/name: core-ingest/{ok=1} /^---/{n=0;c=0} END{exit !ok}" /tmp/ing.yaml'
check "ingest: has its own PodDisruptionBudget"          sh -c 'awk "/^kind: PodDisruptionBudget/{p=1} p&&/name: vegaduta-core-ingest\$/{ok=1} /^---/{p=0} END{exit !ok}" /tmp/ing.yaml'
# The other pools (webhooks, intake, billing): each is a Deployment on the core-jobs image whose profile is
# shape-jobs-pool plus APP_SCHEDULING_POOL=<name>; the main jobs pod lists every enabled pool in
# APP_SCHEDULING_OFFLOADED_POOLS (JobsPoolsCoverEveryJobTest proves the split for every combination).
check "default: nothing is offloaded and no pool env"    sh -c '! grep -q "APP_SCHEDULING_" /tmp/def.yaml'
render /tmp/pools.yaml $U -n vegaduta $D --set global.components.coreIngest=true --set global.components.coreWebhooks=true --set global.components.coreIntake=true --set global.components.coreBilling=true
check "pools: the main jobs pod offloads all four"       sh -c 'grep -A1 "name: APP_SCHEDULING_OFFLOADED_POOLS\$" /tmp/pools.yaml | grep -q "\"ingest,webhooks,intake,billing\""'
for n in webhooks intake billing; do
  check "pool $n: Deployment vegaduta-core-$n rendered" grep -q "name: vegaduta-core-$n\$" /tmp/pools.yaml
  check "pool $n: APP_SCHEDULING_POOL=$n"              sh -c "[ \"\$(grep -A1 'name: APP_SCHEDULING_POOL\$' /tmp/pools.yaml | grep -c '\"$n\"')\" -eq 1 ]"
  check "pool $n: has its own PodDisruptionBudget"     sh -c "awk '/^kind: PodDisruptionBudget/{p=1} p&&/name: vegaduta-core-$n\$/{ok=1} /^---/{p=0} END{exit !ok}' /tmp/pools.yaml"
  check "pool $n: postgres accepts it through its NetworkPolicy" sh -c "awk '/^kind: NetworkPolicy/{n=1} n&&/name: vegaduta-postgres\$/{c=1} c&&/app.kubernetes.io\\/name: core-$n/{ok=1} /^---/{n=0;c=0} END{exit !ok}' /tmp/pools.yaml"
  check "pool $n: same image as core-jobs"             sh -c "! grep -q '/core-$n:' /tmp/pools.yaml"
done
check "pools: shape-jobs-pool on exactly three pods"     sh -c '[ "$(grep -c "shape-jobs,shape-jobs-pool,jobs" /tmp/pools.yaml)" -eq 3 ]'
check "pools: intake and billing pools get 8 (locked jobs pin up to 2 each)" sh -c '[ "$(grep -A1 "name: DB_HIKARI_MAX_POOL_SIZE\$" /tmp/pools.yaml | grep -c "\"8\"")" -ge 2 ]'
# Review F9: the pools that run no agent turn (webhooks, billing) get no egress to the tool executors; intake, which
# runs whole agent turns, keeps it. All of them keep postgres.
np_peer() { awk -v name="$2" -v peer="$3" '/^kind: NetworkPolicy/{n=1} n&&$0=="  name: " name{c=1} c&&$0 ~ ("app.kubernetes.io/name: " peer "$"){ok=1} /^---/{n=0;c=0} END{exit !ok}' "$1"; }
np_nopeer() { ! np_peer "$@"; }
for n in webhooks billing; do
  for peer in sidecar code-intel tool-sandbox; do
    check "pool $n: NetworkPolicy has no egress to $peer"  np_nopeer /tmp/pools.yaml vegaduta-core-$n $peer
  done
  check "pool $n: NetworkPolicy still reaches postgres"    np_peer /tmp/pools.yaml vegaduta-core-$n postgres
done
for peer in sidecar code-intel tool-sandbox; do
  check "pool intake: NetworkPolicy keeps egress to $peer"  np_peer /tmp/pools.yaml vegaduta-core-intake $peer
done
# Probes (load test: a merely busy core pod was killed by its liveness probe, "context deadline exceeded"). Every
# core-family Deployment must keep a liveness probe that tolerates load (timeout >= 10 s and at least 100 s of
# continuous failure before a restart) and a readiness probe that stays quick (timeout <= 5 s) so a busy pod leaves
# rotation first. The count guards against a render that matched nothing.
probe_guard() { awk '
  /^---/{ kind=""; name=""; sect="" }
  /^kind: /{ kind=$2 }
  kind=="Deployment" && /^  name: vegaduta-core/ && name=="" { name=$2 }
  name!="" && /^          livenessProbe:/  { sect="l" }
  name!="" && /^          readinessProbe:/ { sect="r" }
  name!="" && /^          startupProbe:/   { sect="s" }
  name!="" && sect!="" && /^            (failureThreshold|periodSeconds|timeoutSeconds): / { k=$1; sub(":","",k); v[name SUBSEP sect SUBSEP k]=$2; n[name]=1 }
  END { for (d in n) { lt=v[d SUBSEP "l" SUBSEP "timeoutSeconds"]; lf=v[d SUBSEP "l" SUBSEP "failureThreshold"]; lp=v[d SUBSEP "l" SUBSEP "periodSeconds"]; rt=v[d SUBSEP "r" SUBSEP "timeoutSeconds"]
          if (lt+0 < 10 || lf*lp < 100 || rt+0 > 5 || rt+0 < 1) { print "BAD " d " liveness timeout=" lt " threshold=" lf " period=" lp " readiness timeout=" rt; bad=1 } else good++ }
        print "checked " good+0; exit (bad || good < 5) } ' "$1"; }
check "core family: liveness tolerates load, readiness stays quick" probe_guard /tmp/pools.yaml
render /tmp/pool1.yaml $U -n vegaduta $D --set global.components.coreIntake=true
check "one pool: only that pool is offloaded"            sh -c 'grep -A1 "name: APP_SCHEDULING_OFFLOADED_POOLS\$" /tmp/pool1.yaml | grep -q "\"intake\"" && ! grep -q "name: vegaduta-core-webhooks\$" /tmp/pool1.yaml'
# Topology-aware routing is opt-in (needs Kubernetes 1.31+): absent by default, present when asked.
check "default: no trafficDistribution on any Service"   sh -c '! grep -q "trafficDistribution" /tmp/def.yaml'
render /tmp/td.yaml $U -n vegaduta $D --set core.service.trafficDistribution=PreferClose
check "core Service: trafficDistribution PreferClose"    sh -c 'awk "/^kind: Service\$/{s=1} s&&/name: vegaduta-core\$/{c=1} c&&/trafficDistribution: PreferClose/{ok=1} /^---/{s=0;c=0} END{exit !ok}" /tmp/td.yaml'
render /tmp/ll.yaml $U -n vegaduta $D -f examples/values-low-latency.yaml
check "values-low-latency.yaml: core and sidecar Services prefer close" sh -c '[ "$(grep -c "trafficDistribution: PreferClose" /tmp/ll.yaml)" -ge 2 ]'
expect_fail "trafficDistribution typo rejected by schema" "trafficDistribution" $U $D --set core.service.trafficDistribution=Closest
render /tmp/r1.yaml $U -n vegaduta $D
check "1 replica: replica mode and shared-store switches stay off" sh -c '! grep -qE "name: (APP_REPLICA_MODE|APP_CHAT_RESUMABLE_STREAMS_STORE|TOOL_EFFECT_JOURNAL_STORE|APP_QUALITY_BOOT_ORPHAN_SWEEP)$" /tmp/r1.yaml'
expect_fail "2 replicas with resumableStreamsRedis=false" "resumableStreamsRedis" $U $D --set core.replicaCount=2 --set core.replicaReadiness.a2aTaskStoreRedis=true --set core.replicaReadiness.abuseThrottleRedis=true --set core.replicaReadiness.mcpRegistryRedis=true --set core.replicaReadiness.a2aSigningKeysPinned=true --set core.replicaReadiness.resumableStreamsRedis=false
# web defaults to 2 replicas, so it gets anti-affinity by default; with every workload
# at 1 replica nothing does.
check "web (2 replicas by default): pod anti-affinity"  sh -c 'awk "/^kind: Deployment/{d=1} d&&/name: vegaduta-web\$/{w=1} w&&/podAntiAffinity/{ok=1} /^---/{d=0;w=0} END{exit !ok}" /tmp/def.yaml'
render /tmp/one.yaml $U -n vegaduta $D --set web.replicaCount=1
check "1 replica everywhere: no anti-affinity"           sh -c '! grep -q podAntiAffinity /tmp/one.yaml'
render /tmp/tmp.yaml $U -n vegaduta $D --set global.temporal.enabled=true --set global.temporal.target=t:7233
check "temporal: core-worker deployed"                   grep -q 'name: vegaduta-core-worker$' /tmp/tmp.yaml
check "temporal: only the worker polls"                  sh -c '[ "$(grep -A1 "name: APP_WORKFLOW_TEMPORAL_WORKER_ENABLED" /tmp/tmp.yaml | grep -c "\"true\"")" -eq 1 ]'
check "temporal: engine=temporal everywhere"             sh -c '! grep -A1 "name: WORKFLOW_EXECUTION_ENGINE" /tmp/tmp.yaml | grep -q legacy'
R="--set global.components.mcpStdioRunner=true --set mcp-stdio-runner.runtimeClassName=kata"
render /tmp/mcp.yaml $U -n vegaduta $D $R
check "runner: core relays STDIO MCP (sidecar on)"       sh -c '[ "$(grep -A1 "name: MCP_STDIO_SIDECAR_ENABLED" /tmp/mcp.yaml | grep -c "\"true\"")" -ge 2 ]'
check "runner: every core role gets the mcp profile"     sh -c 'grep -q "mcpserver,mcp,shape-api\"" /tmp/mcp.yaml && grep -q "mcpserver,mcp,shape-jobs,jobs\"" /tmp/mcp.yaml'
check "runner: standard image by default"                sh -c 'grep -q "ghcr.io/cd-lc/mcp-stdio-runner:" /tmp/mcp.yaml && ! grep -q "mcp-stdio-runner-browser" /tmp/mcp.yaml'
render /tmp/mcpb.yaml $U -n vegaduta $D $R --set mcp-stdio-runner.browser=true
check "runner browser=true: browser image"               grep -q 'ghcr.io/cd-lc/mcp-stdio-runner-browser:' /tmp/mcpb.yaml
check "runner browser=true: 2Gi memory limit"            sh -c 'awk "/name: vegaduta-mcp-stdio-runner\$/{f=1} f&&/memory: 2Gi/{ok=1} END{exit !ok}" /tmp/mcpb.yaml'
render /tmp/full.yaml $U -n vegaduta $D --set global.core.mcpToolchain=full
check "mcpToolchain=full: API on core-full with mcp"     sh -c 'grep -q "ghcr.io/cd-lc/core-full:" /tmp/full.yaml && grep -q "mcpserver,mcp,shape-api\"" /tmp/full.yaml'
check "mcpToolchain=full: core-jobs stays slim, no mcp"  sh -c 'grep -q "ghcr.io/cd-lc/core-jobs:" /tmp/full.yaml && grep -q "mcpserver,shape-jobs,jobs\"" /tmp/full.yaml'
render /tmp/all.yaml $U -n vegaduta --set global.allowGeneratedSecrets=true -f examples/values-all-optional.yaml
check "netpol: namespace default-deny rendered"          grep -q 'name: vegaduta-default-deny' /tmp/all.yaml
check "netpol: migrate hook policy rendered"             grep -q 'name: vegaduta-core-migrate$' /tmp/all.yaml
check "netpol: tool-sandbox has no egress"               sh -c 'awk "/name: vegaduta-tool-sandbox\$/{f=1} f&&/egress: \\[\\]/{ok=1} END{exit !ok}" /tmp/all.yaml'
check "netpol: global ServiceMonitor admits monitoring ns" sh -c 'awk "/^kind: NetworkPolicy/{n=1} n&&/name: vegaduta-core\$/{c=1} c&&/kubernetes.io\\/metadata.name: monitoring/{ok=1} /^---/{n=0;c=0} END{exit !ok}" /tmp/all.yaml'
check "netpol: gateway peer is the data-plane namespace" grep -q 'kubernetes.io/metadata.name: envoy-gateway-system' /tmp/all.yaml
check "netpol: scoped extraEgress not on valkey"         sh -c 'awk "/^kind: NetworkPolicy/{n=1} n&&/name: vegaduta-valkey\$/{c=1} c&&/10.20.0.0\\/16/{bad=1} /^---/{n=0;c=0} END{exit bad}" /tmp/all.yaml'
check "netpol: scoped extraEgress on core"               sh -c 'awk "/^kind: NetworkPolicy/{n=1} n&&/name: vegaduta-core\$/{c=1} c&&/10.20.0.0\\/16/{ok=1} /^---/{n=0;c=0} END{exit !ok}" /tmp/all.yaml'
check "netpol: core gets SMTP 587 when coreEgress.smtp"  sh -c 'awk "/^kind: NetworkPolicy/{n=1} n&&/name: vegaduta-core\$/{c=1} c&&/port: 587/{ok=1} /^---/{n=0;c=0} END{exit !ok}" /tmp/all.yaml'
check "netpol: core gets the OTLP port of global.otel"   sh -c 'awk "/^kind: NetworkPolicy/{n=1} n&&/name: vegaduta-core\$/{c=1} c&&/port: 4318/{ok=1} /^---/{n=0;c=0} END{exit !ok}" /tmp/all.yaml'
render /tmp/azn.yaml $U -n vegaduta $D --set global.objectStorage.type=azure-blob --set seaweedfs.enabled=false --set global.objectStorage.credentials=default --set global.objectStorage.azure.endpoint=https://acct.privatelink.blob.core.windows.net:8443
check "netpol: azure-blob account endpoint port reaches core egress" sh -c 'awk "/^kind: NetworkPolicy/{n=1} n&&/name: vegaduta-core\$/{c=1} c&&/port: 8443/{ok=1} /^---/{n=0;c=0} END{exit !ok}" /tmp/azn.yaml'

echo "--- per-cloud values (examples/values-{aws,azure,gcp}.yaml) and External Secrets examples"
for c in aws azure gcp; do
  expect_ok "values-$c.yaml renders offline (existingSecret only, no generated secrets)" $U -f examples/values-$c.yaml
  render /tmp/$c.yaml $U -n vegaduta -f examples/values-$c.yaml
  check "$c: nothing bundled is rendered (no CNPG Cluster, no StatefulSet)" sh -c '! grep -qE "^kind: (Cluster|StatefulSet)$" /tmp/'$c'.yaml'
  check "$c: managed Redis is reached over TLS"             sh -c 'grep -A1 "name: REDIS_SSL" /tmp/'$c'.yaml | grep -q "\"true\""'
  # The chart derives egress for the configured data-service ports to any non-link-local address, private
  # ranges included (so the cloud files need no extraEgress): core egress has the 0.0.0.0/0-except-169.254 rule
  # on the PostgreSQL port, and no rule excludes the private ranges for that port.
  check "$c: core NetworkPolicy allows PostgreSQL egress to private addresses (only link-local excluded)" sh -c 'awk "/^kind: NetworkPolicy/{n=1} n&&/name: vegaduta-core\$/{c=1} c&&/port: 5432/{p=1} c&&p&&/cidr: 0.0.0.0\/0/{g=1} c&&p&&g&&/- 169.254.0.0\/16/{e=1} c&&p&&g&&/- 10.0.0.0\/8/{bad=1} /^---/{if(c)exit} END{exit !(e&&!bad)}" /tmp/'$c'.yaml' 
  check "$c: graph memory is off (no AGE on the managed server)" sh -c '! grep -q "graph-memory" /tmp/'$c'.yaml'
  # The ExternalSecret examples must produce exactly what the chart reads: every target.name is referenced by a
  # secretKeyRef in the render, and every (Secret, key) pair the render references for such a target is one of
  # the ExternalSecret's secretKeys. Kafka and Blob targets are not in the base render and are checked below.
  es_read examples/external-secrets/$c.yaml >/tmp/es-$c.txt
  refs_read /tmp/$c.yaml >/tmp/refs-$c.txt
  check "$c: ExternalSecret examples parse (targets and keys found)" sh -c '[ "$(grep -c "^T " /tmp/es-'$c'.txt)" -ge 5 ] && [ "$(grep -c "^K " /tmp/es-'$c'.txt)" -ge 10 ]'
  for s in $(grep '^T ' /tmp/es-$c.txt | cut -d' ' -f2 | sort -u); do
    case "$s" in vegaduta-kafka|vegaduta-blob) continue;; esac
    check "$c: ExternalSecret target $s is read by the chart" grep -q "^$s " /tmp/refs-$c.txt
  done
  check "$c: every key the chart reads from an ExternalSecret target is provided by it" es_keys_covered /tmp/es-$c.txt /tmp/refs-$c.txt
  check "$c: the APP_ENCRYPTION_KEY and internal-token ExternalSecrets survive deletion (creationPolicy Orphan)" es_orphan examples/external-secrets/$c.yaml
done
check "aws: S3 uses the SDK credential chain (IRSA), no key pair" sh -c 'grep -A1 "name: STORAGE_CREDENTIALS" /tmp/aws.yaml | grep -q "\"default\"" && ! grep -q "name: STORAGE_ACCESS_KEY" /tmp/aws.yaml'
check "aws: IRSA annotation on the core ServiceAccount"   grep -q 'eks.amazonaws.com/role-arn:' /tmp/aws.yaml
check "aws: RDS sslmode in the JDBC URL"                  grep -q 'sslmode=require' /tmp/aws.yaml
check "azure: Blob backend with workload identity, no connection string" sh -c 'grep -A1 "name: APP_STORAGE_TYPE" /tmp/azure.yaml | grep -q azure-blob && ! grep -q "name: STORAGE_AZURE_CONNECTION_STRING" /tmp/azure.yaml'
check "azure: workload identity pod label on core and core-jobs, not the migrate Job" sh -c '[ "$(grep -c "azure.workload.identity/use: \"true\"" /tmp/azure.yaml)" -eq 2 ]'
check "azure: workload identity client-id on the ServiceAccounts" sh -c '[ "$(grep -c "azure.workload.identity/client-id:" /tmp/azure.yaml)" -eq 2 ]'
check "azure: Redis on 6380 with TLS"                     sh -c 'grep -A1 "name: REDIS_PORT" /tmp/azure.yaml | grep -q 6380'
check "gcp: GCS HMAC keys come from a Secret"             sh -c 'grep -q "name: STORAGE_ACCESS_KEY" /tmp/gcp.yaml && grep -q "name: STORAGE_SECRET_KEY" /tmp/gcp.yaml'
check "gcp: no Google identity is bound by default (storage uses HMAC keys)" sh -c '! grep -q "iam.gke.io/gcp-service-account" /tmp/gcp.yaml'
render /tmp/gcpwi.yaml $U -n vegaduta -f examples/values-gcp.yaml --set core.serviceAccount.annotations."iam\.gke\.io/gcp-service-account"=gsa@p.iam.gserviceaccount.com
check "gcp: the Workload Identity annotation can be layered on core's ServiceAccount" grep -q 'iam.gke.io/gcp-service-account:' /tmp/gcpwi.yaml
render /tmp/azw.yaml $U -n vegaduta -f examples/values-azure.yaml --set global.temporal.enabled=true --set global.temporal.target=t:7233
check "azure + temporal: core-worker gets the identity label and annotation" sh -c '[ "$(grep -c "azure.workload.identity/use: \"true\"" /tmp/azw.yaml)" -eq 3 ] && [ "$(grep -c "azure.workload.identity/client-id:" /tmp/azw.yaml)" -eq 3 ]'
render /tmp/awk.yaml $U -n vegaduta -f examples/values-aws.yaml --set global.kafka.bootstrapServers=b-1.msk.example.com:9096 --set global.kafka.securityProtocol=SASL_SSL --set global.kafka.saslMechanism=SCRAM-SHA-512 --set global.kafka.existingSecret=vegaduta-kafka
es_read examples/external-secrets/aws.yaml >/tmp/es-aws.txt
refs_read /tmp/awk.yaml >/tmp/refs-awk.txt
check "aws + MSK SCRAM: the chart reads vegaduta-kafka/saslJaasConfig"  grep -q "^vegaduta-kafka saslJaasConfig\$" /tmp/refs-awk.txt
check "aws + MSK SCRAM: that key is provided by the kafka ExternalSecret" es_keys_covered /tmp/es-aws.txt /tmp/refs-awk.txt
check "aws + MSK SCRAM: JAAS line comes from a Secret"    sh -c 'grep -q "name: SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG" /tmp/awk.yaml && grep -q "SCRAM-SHA-512" /tmp/awk.yaml'
render /tmp/azk.yaml $U -n vegaduta -f examples/values-azure.yaml --set global.kafka.bootstrapServers=ns.servicebus.windows.net:9093 --set global.kafka.securityProtocol=SASL_SSL --set global.kafka.saslMechanism=PLAIN --set global.kafka.existingSecret=vegaduta-kafka
es_read examples/external-secrets/azure.yaml >/tmp/es-azure.txt
refs_read /tmp/azk.yaml >/tmp/refs-azk.txt
check "azure + Event Hubs: the chart reads vegaduta-kafka/saslJaasConfig" grep -q "^vegaduta-kafka saslJaasConfig\$" /tmp/refs-azk.txt
check "azure + Event Hubs: that key is provided by the kafka ExternalSecret" es_keys_covered /tmp/es-azure.txt /tmp/refs-azk.txt
check "azure + Event Hubs: SASL_SSL with PLAIN"           sh -c 'grep -q "SASL_SSL" /tmp/azk.yaml && grep -A1 "SASL_MECHANISM" /tmp/azk.yaml | grep -q PLAIN'
expect_fail "cloud values: AWS file with bundled PostgreSQL turned back on" "choose ONE" $U -f examples/values-aws.yaml --set postgres.enabled=true --set global.allowGeneratedSecrets=true
expect_fail "cloud values: Azure Blob workload identity without the endpoint" "azure.endpoint" $U -f examples/values-azure.yaml --set global.objectStorage.azure.endpoint=
expect_fail "cloud values: GCS (S3 interop) needs the HMAC Secret" "global.objectStorage.existingSecret is required" $U -f examples/values-gcp.yaml --set global.objectStorage.existingSecret=
expect_fail "cloud values: AWS file with no S3 endpoint and SeaweedFS off" "global.objectStorage.endpoint is empty" $U -f examples/values-aws.yaml --set global.objectStorage.endpoint=

echo "passed=$pass failed=$failn"
[ "$failn" -eq 0 ]
EOF

# As the calling user (HOME=/tmp for helm's cache): root-owned files under $WORK would make
# the EXIT trap's rm -rf fail on Linux (same as validate.sh).
if [ -n "${HELM_LOCAL:-}" ]; then
  # No Docker: use the helm on PATH (version may differ from HELM_IMAGE).
  TG_DIR="$WORK" sh "$WORK/run-tests.sh"
else
  docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp -v "$MOUNT:/w" --entrypoint sh "$HELM_IMAGE" /w/run-tests.sh
fi
