#!/usr/bin/env bash
# Validate the VegaDuta Helm charts WITHOUT a cluster, using container images only:
#   1. helm dependency update   (every chart, then the umbrella)
#   2. helm lint --strict       (every chart; the umbrella once per scenario)
#   3. helm template            (umbrella x scenarios: defaults, all optional components,
#                                all external dependencies, kind, local file storage)
#   4. kubeconform -strict      (Kubernetes 1.31 schemas + CRD schemas from the datree
#                                catalog for CloudNativePG / Gateway API / Prometheus Operator;
#                                -ignore-missing-schemas for anything not in the catalog)
#   5. rule checks on the rendered output (no Bitnami, no :latest, no localhost URLs,
#                                one APP_ENCRYPTION_KEY Secret across the core family)
# Works on a temp copy so the repo stays clean.
#
#   deploy/helm/validate.sh            # everything
#   OUT_DIR=/tmp/render deploy/helm/validate.sh   # keep the rendered manifests
#
# Env: HELM_VERSION (3.16.4) / HELM_IMAGE, KUBECONFORM_VERSION (v0.6.7) / KUBECONFORM_IMAGE,
#      K8S_VERSIONS (space-separated; default "1.28.0 1.31.0" = the chart's kubeVersion floor
#      and a current release), OFFLINE=1 (skip the remote CRD catalog; CRDs are then ignored)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DEPLOY="$(cd "$HERE/.." && pwd)"
HELM_IMAGE="${HELM_IMAGE:-alpine/helm:${HELM_VERSION:-3.16.4}}"
KC_IMAGE="${KUBECONFORM_IMAGE:-ghcr.io/yannh/kubeconform:${KUBECONFORM_VERSION:-v0.6.7}-alpine}"
K8S_VERSIONS="${K8S_VERSIONS:-${K8S_VERSION:-1.28.0 1.31.0}}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/helm" "$WORK/kind" "$WORK/out"
cp -r "$HERE/charts" "$HERE/examples" "$WORK/helm/"
cp -r "$REPO_DEPLOY/kind/." "$WORK/kind/" 2>/dev/null || true
MOUNT="$WORK"
command -v cygpath >/dev/null 2>&1 && MOUNT="$(cygpath -m "$WORK")"
export MSYS_NO_PATHCONV=1

SERVICE_CHARTS="core core-jobs core-ingest core-webhooks core-intake core-billing core-worker web sidecar code-intel app-builder rl-trainer tool-sandbox mcp-stdio-runner voice-agent postgres valkey keycloak seaweedfs"

cat > "$WORK/run-helm.sh" <<EOF
set -u
cd /w/helm
fail=0
step() { echo; echo "== \$*"; }
step "helm dependency update"
for c in $SERVICE_CHARTS vegaduta; do
  rm -rf charts/\$c/charts
  helm dependency update charts/\$c >/dev/null || { echo "FAIL dependency update \$c"; fail=1; }
done
step "helm lint --strict (library + service charts)"
helm lint --strict charts/vegaduta-lib >/dev/null && echo "ok   vegaduta-lib" || { helm lint --strict charts/vegaduta-lib; fail=1; }
for c in $SERVICE_CHARTS; do
  out=\$(helm lint --strict charts/\$c -f examples/lint-globals.yaml 2>&1) && echo "ok   \$c" || { echo "\$out"; echo "FAIL lint \$c"; fail=1; }
done
scenario() { # name, extra args...
  n=\$1; shift
  out=\$(helm lint --strict charts/vegaduta "\$@" 2>&1) && echo "ok   lint umbrella/\$n" || { echo "\$out"; echo "FAIL lint umbrella/\$n"; fail=1; }
  helm template vegaduta charts/vegaduta -n vegaduta "\$@" > /w/out/\$n.yaml 2>/w/out/\$n.err \
    && echo "ok   template umbrella/\$n (\$(grep -c '^kind:' /w/out/\$n.yaml) objects)" \
    || { cat /w/out/\$n.err; echo "FAIL template umbrella/\$n"; fail=1; }
}
step "umbrella scenarios"
# helm lint/template render offline: generated secrets are refused there unless
# global.allowGeneratedSecrets=true (INF-006). `external` uses existingSecret only,
# so it renders WITHOUT the flag - the GitOps path.
GEN="--set global.allowGeneratedSecrets=true"
scenario defaults     \$GEN --set global.domain=example.com
scenario all-optional \$GEN -f examples/values-all-optional.yaml
scenario external     -f examples/values-external.yaml
scenario cloud-aws    -f examples/values-aws.yaml
scenario cloud-azure  -f examples/values-azure.yaml
scenario cloud-gcp    -f examples/values-gcp.yaml
scenario kind         \$GEN -f /w/kind/values-kind.yaml
scenario scale-out    \$GEN --set global.domain=example.com -f examples/values-scale-out.yaml
scenario scale-out-autoscaling \$GEN --set global.domain=example.com -f examples/values-scale-out.yaml -f examples/values-autoscaling.yaml
scenario jobs-pools   \$GEN --set global.domain=example.com --set global.components.coreIngest=true --set global.components.coreWebhooks=true --set global.components.coreIntake=true --set global.components.coreBilling=true
scenario local-storage \$GEN --set global.domain=example.com --set global.objectStorage.type=local --set seaweedfs.enabled=false
scenario gateway-only \$GEN --set global.domain=example.com --set global.ingress.enabled=false --set global.gateway.enabled=true --set-json 'global.gateway.parentRefs=[{"name":"public","namespace":"gateway-system"}]' --set global.networkPolicy.gatewayNamespace=envoy-gateway-system

step "rule checks on rendered manifests"
for f in /w/out/*.yaml; do
  n=\$(basename \$f .yaml)
  if grep -niE 'bitnami' \$f; then echo "FAIL \$n: Bitnami reference"; fail=1; fi
  if grep -nE '^\s+image: .*:latest"?\$' \$f; then echo "FAIL \$n: :latest image"; fail=1; fi
  if grep -nE '^\s+- path: /admin|^\s+value: /admin' \$f; then echo "FAIL \$n: Keycloak admin console routed publicly"; fail=1; fi
  if grep -nE 'value: "?(https?|wss?)://(localhost|127\.0\.0\.1)([:/"]|\$)' \$f; then echo "FAIL \$n: localhost URL in env"; fail=1; fi
  keys=\$(awk '/name: APP_ENCRYPTION_KEY\$/{getline; getline; getline; print}' \$f | sort -u | wc -l)
  if [ "\$keys" -gt 1 ]; then echo "FAIL \$n: APP_ENCRYPTION_KEY read from \$keys different Secrets"; fail=1; fi
  echo "ok   rules \$n"
done
exit \$fail
EOF

echo "### helm ($HELM_IMAGE)"
hfail=0
# Run as the calling user: the image defaults to root, and root-owned files it
# writes under $WORK (charts/*/charts/*.tgz) make the EXIT trap's rm -rf fail on
# Linux, which turned a passing run into exit 1. HOME=/tmp gives helm a writable
# cache/config dir for an arbitrary uid.
docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp -v "$MOUNT:/w" --entrypoint sh "$HELM_IMAGE" /w/run-helm.sh || hfail=1

echo
echo "### kubeconform ($KC_IMAGE, Kubernetes $K8S_VERSIONS)"
kfail=0
SCHEMAS=(-schema-location default)
[ "${OFFLINE:-0}" = 1 ] || SCHEMAS+=(-schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json')
for f in "$WORK"/out/*.yaml; do
  n="$(basename "$f")"
  for kv in $K8S_VERSIONS; do
    docker run --rm -v "$MOUNT:/w" "$KC_IMAGE" -strict -summary -kubernetes-version "$kv" \
      "${SCHEMAS[@]}" -ignore-missing-schemas "/w/out/$n" || kfail=1
  done
done

if [ -n "${OUT_DIR:-}" ]; then mkdir -p "$OUT_DIR" && cp "$WORK"/out/*.yaml "$OUT_DIR"/; fi
echo
if [ $hfail -eq 0 ] && [ $kfail -eq 0 ]; then echo "validate: PASS"; else echo "validate: FAIL (helm=$hfail kubeconform=$kfail)"; exit 1; fi
