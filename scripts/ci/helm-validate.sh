#!/usr/bin/env bash
# helm lint + helm template + kubeconform (strict) for the charts in deploy/helm.
#
# If the chart tree ships its own validator (deploy/helm/validate.sh: per-chart
# lint with shared lint globals, umbrella scenarios, rendered-manifest rule
# checks, kubeconform) that is what runs, so CI and the chart authors use one
# implementation. Set HELM_VALIDATE_GENERIC=1 (or HELM_VALIDATE_CHARTS) to use
# the generic path below instead.
#
# Generic path:
# Chart discovery: every Chart.yaml under deploy/helm except
#   - library charts (`type: library`) - they render nothing on their own
#   - vendored dependencies (*/charts/*/charts/*)
#   - deploy/helm/platform and deploy/helm/stateful (cluster add-ons / drafts that
#     need environment values; they have their own validate scripts)
# Override with HELM_VALIDATE_CHARTS="deploy/helm/charts/vegaduta deploy/helm/charts/core".
#
# Values: deploy/helm/examples/lint-globals.yaml (if present) is always applied.
# If a chart has a ci/ directory (the chart-testing convention), the chart is
# linted and rendered once per ci/*-values.yaml file on top of that; otherwise
# with its defaults. Charts named in HELM_VALIDATE_EXCLUDE (default: charts not
# in the open-source release) are skipped.
#
# The deploy/helm tree is copied to a temp dir first, so `helm dependency build`
# never writes into the checkout.
#
# Tools: helm (required), kubeconform (on PATH, else via Docker image
# ghcr.io/yannh/kubeconform:$KUBECONFORM_VERSION). KUBERNETES_VERSION selects
# the schema set.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

KUBECONFORM_VERSION="${KUBECONFORM_VERSION:-v0.7.0}"
KUBERNETES_VERSION="${KUBERNETES_VERSION:-1.31.0}"

if [[ ! -d deploy/helm ]]; then
  echo "helm-validate: deploy/helm not found, nothing to do"
  exit 0
fi
if [[ -f deploy/helm/validate.sh && -z "${HELM_VALIDATE_GENERIC:-}" && -z "${HELM_VALIDATE_CHARTS:-}" ]]; then
  echo "helm-validate: delegating to deploy/helm/validate.sh"
  exec bash deploy/helm/validate.sh
fi
exclude=" ${HELM_VALIDATE_EXCLUDE:-} "
if ! command -v helm >/dev/null 2>&1; then
  if [[ "${CI:-}" == "true" ]]; then echo "helm-validate: helm is not installed" >&2; exit 1; fi
  echo "helm-validate: SKIP (helm not installed)"
  exit 0
fi

kubeconform_cmd=()
if command -v kubeconform >/dev/null 2>&1; then
  kubeconform_cmd=(kubeconform)
elif command -v docker >/dev/null 2>&1; then
  kubeconform_cmd=(docker run --rm -i "ghcr.io/yannh/kubeconform:${KUBECONFORM_VERSION}")
else
  echo "helm-validate: WARNING kubeconform unavailable (no binary, no docker) - schema check skipped" >&2
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cp -r deploy/helm "$work/helm"

charts=()
if [[ -n "${HELM_VALIDATE_CHARTS:-}" ]]; then
  for c in $HELM_VALIDATE_CHARTS; do charts+=("${c#deploy/helm/}"); done
else
  while IFS= read -r chart_yaml; do
    rel="${chart_yaml#deploy/helm/}"
    dir="$(dirname "$rel")"
    case "$dir" in
      platform|platform/*|stateful|stateful/*) continue ;;
      */charts/*/charts/*|*/charts/*/charts) continue ;;
    esac
    if grep -Eq '^type:[[:space:]]*library' "$chart_yaml"; then continue; fi
    [[ "$exclude" == *" $(basename "$dir") "* ]] && continue
    charts+=("$dir")
  done < <(find deploy/helm -name Chart.yaml -not -path '*/node_modules/*' | LC_ALL=C sort)
fi

if [[ ${#charts[@]} -eq 0 ]]; then
  echo "helm-validate: no application charts found"
  exit 0
fi

fail=0
for chart in "${charts[@]}"; do
  path="$work/helm/$chart"
  echo "::group::$chart"
  if [[ ! -f "$path/Chart.yaml" ]]; then
    echo "FAIL: $chart has no Chart.yaml" >&2; fail=1; echo "::endgroup::"; continue
  fi
  if grep -q '^dependencies:' "$path/Chart.yaml"; then
    if ! helm dependency build "$path" >/dev/null; then
      echo "FAIL: helm dependency build $chart" >&2; fail=1; echo "::endgroup::"; continue
    fi
  fi

  value_sets=("")
  if compgen -G "$path/ci/*values.yaml" >/dev/null; then
    value_sets=()
    for v in "$path"/ci/*values.yaml; do value_sets+=("$v"); done
  fi

  globals=()
  [[ -f "$work/helm/examples/lint-globals.yaml" ]] && globals=(-f "$work/helm/examples/lint-globals.yaml")
  for v in "${value_sets[@]}"; do
    vargs=("${globals[@]}"); label="defaults"
    if [[ -n "$v" ]]; then vargs+=(-f "$v"); label="$(basename "$v")"; fi
    if ! helm lint --strict "$path" "${vargs[@]}"; then
      echo "FAIL: helm lint $chart ($label)" >&2; fail=1; continue
    fi
    out="$work/rendered-$(echo "$chart" | tr '/' '_')-$label.yaml"
    if ! helm template ci-release "$path" "${vargs[@]}" --namespace vegaduta >"$out"; then
      echo "FAIL: helm template $chart ($label)" >&2; fail=1; continue
    fi
    if [[ ${#kubeconform_cmd[@]} -gt 0 ]]; then
      if ! "${kubeconform_cmd[@]}" -strict -summary -ignore-missing-schemas \
          -kubernetes-version "$KUBERNETES_VERSION" \
          -schema-location default \
          -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json' \
          - <"$out"; then
        echo "FAIL: kubeconform $chart ($label)" >&2; fail=1; continue
      fi
    fi
    echo "OK: $chart ($label)"
  done
  echo "::endgroup::"
done

exit "$fail"
