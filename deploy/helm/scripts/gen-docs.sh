#!/usr/bin/env bash
# Regenerate the values reference tables (charts/vegaduta/VALUES.md and charts/core/VALUES.md)
# with helm-docs, from a container. Only VALUES.md files are written; the hand-written
# READMEs are left alone.
#   deploy/helm/scripts/gen-docs.sh
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="${HELM_DOCS_IMAGE:-jnorwood/helm-docs:v1.14.2}"
MOUNT="$HERE"
command -v cygpath >/dev/null 2>&1 && MOUNT="$(cygpath -m "$HERE")"
export MSYS_NO_PATHCONV=1
for c in vegaduta core; do
  docker run --rm -v "$MOUNT:/helm-docs" -w /helm-docs "$IMAGE" \
    --chart-search-root "charts/$c" --template-files "charts/vegaduta/VALUES.md.gotmpl" \
    --output-file VALUES.md --ignore-non-descriptions=false
done
echo "wrote charts/vegaduta/VALUES.md and charts/core/VALUES.md"
