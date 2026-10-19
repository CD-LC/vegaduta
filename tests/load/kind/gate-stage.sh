#!/usr/bin/env bash
# Brings up a THROWAWAY kind stage for the load-test gate: kind cluster (deploy/kind/kind-config.yaml), ingress-nginx,
# the CloudNativePG operator, the locally built images loaded into the node, and the umbrella chart with
# values-kind.yaml + values-loadtest.yaml + values-gate.yaml. Used by .github/workflows/load-nightly.yml; then run
# gate-provision.sh and gate.sh. Not for a laptop that already runs the kind stage: it refuses when the cluster exists.
#
# Required env: IMAGE_TAG   images ghcr.io/cd-lc/{core,core-jobs,web,sidecar,postgres}:$IMAGE_TAG must exist in the local
#                           Docker daemon (the workflow builds them from the commit; nothing is pulled for them).
# Optional env: KIND_CLUSTER=vegaduta  INGRESS_NGINX_VERSION=1.11.3  CNPG_CHART_VERSION=0.29.1  EXTRA_VALUES (more -f args)
#               IMAGE_REGISTRY=ghcr.io/cd-lc (the chart's default registry, so no registry override is needed)
set -euo pipefail
: "${IMAGE_TAG:?}"
KIND_CLUSTER=${KIND_CLUSTER:-vegaduta}; CTX=kind-$KIND_CLUSTER; NS=vegaduta
INGRESS_NGINX_VERSION=${INGRESS_NGINX_VERSION:-1.11.3}; CNPG_CHART_VERSION=${CNPG_CHART_VERSION:-0.29.1}
IMAGE_REGISTRY=${IMAGE_REGISTRY:-ghcr.io/cd-lc}
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd); cd "$repo"
CHARTS=deploy/helm/charts

if kind get clusters 2>/dev/null | grep -qx "$KIND_CLUSTER"; then
  echo "refusing: kind cluster $KIND_CLUSTER already exists (this script only builds a throwaway stage)" >&2; exit 1
fi

echo ">> kind cluster $KIND_CLUSTER" >&2
kind create cluster --name "$KIND_CLUSTER" --config deploy/kind/kind-config.yaml --wait 120s

echo ">> load images (tag $IMAGE_TAG)" >&2
for img in core core-jobs web sidecar postgres; do
  kind load docker-image --name "$KIND_CLUSTER" "$IMAGE_REGISTRY/$img:$IMAGE_TAG"
done

echo ">> ingress-nginx v$INGRESS_NGINX_VERSION (kind provider: binds host ports 80/443)" >&2
kubectl --context "$CTX" apply -f "https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v$INGRESS_NGINX_VERSION/deploy/static/provider/kind/deploy.yaml" >/dev/null
kubectl --context "$CTX" -n ingress-nginx wait --for=condition=complete job -l app.kubernetes.io/component=admission-webhook --timeout=300s >/dev/null
kubectl --context "$CTX" -n ingress-nginx wait --for=condition=ready pod -l app.kubernetes.io/component=controller --timeout=300s >/dev/null

echo ">> CloudNativePG operator (chart $CNPG_CHART_VERSION)" >&2
helm repo add cnpg https://cloudnative-pg.github.io/charts >/dev/null
helm repo update cnpg >/dev/null
helm --kube-context "$CTX" upgrade --install cnpg cnpg/cloudnative-pg --version "$CNPG_CHART_VERSION" \
  -n cnpg-system --create-namespace --wait --timeout 5m >/dev/null

echo ">> chart dependencies" >&2
for c in "$CHARTS"/*/; do
  c=${c%/}; name=${c##*/}
  [[ $name == vegaduta || $name == vegaduta-lib ]] && continue
  if grep -q '^dependencies:' "$c/Chart.yaml"; then helm dependency update "$c" >/dev/null; fi
done
helm dependency update "$CHARTS/vegaduta" >/dev/null

echo ">> helm install vegaduta" >&2
# shellcheck disable=SC2086
helm --kube-context "$CTX" upgrade --install vegaduta "$CHARTS/vegaduta" -n "$NS" --create-namespace \
  -f deploy/kind/values-kind.yaml -f tests/load/kind/values-loadtest.yaml -f tests/load/kind/values-gate.yaml ${EXTRA_VALUES:-} \
  --set core.image.tag="$IMAGE_TAG" --set core-jobs.image.tag="$IMAGE_TAG" --set web.image.tag="$IMAGE_TAG" --set sidecar.image.tag="$IMAGE_TAG" \
  --set postgres.image.tag="$IMAGE_TAG" \
  --wait --timeout 20m
kubectl --context "$CTX" -n "$NS" get pods -o wide >&2
