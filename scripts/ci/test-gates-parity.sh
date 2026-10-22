#!/usr/bin/env bash
# `make gates` says it mirrors CI. Fail when a command under the Makefile's
# `gates:` target has no matching `run:` line in .github/workflows/static-gates.yml,
# so a gate added locally cannot silently stay out of CI (asyncapi.mjs --check and
# sync-platform-guide-docs.sh --check were missing for months). One-way on purpose:
# the workflow may run more (the Docker-based compose self-test, for instance).
#
#   bash scripts/ci/test-gates-parity.sh
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
MAKEFILE="${GATES_PARITY_MAKEFILE:-Makefile}"
WORKFLOW="${GATES_PARITY_WORKFLOW:-.github/workflows/static-gates.yml}"

# Recipe lines (tab-indented) of the gates target, up to the next blank line.
commands="$(awk '/^gates:/ { in_gates = 1; next } in_gates && /^\t/ { sub(/^\t+/, ""); print; next } in_gates { exit }' "$MAKEFILE")"
[[ -n "$commands" ]] || { echo "test-gates-parity: no commands found under gates: in $MAKEFILE" >&2; exit 2; }

# `run:` values of the workflow, one per line (single-line `run: cmd` steps only).
runs="$(sed -n -E 's/^[[:space:]]*(-[[:space:]]+)?run:[[:space:]]+//p' "$WORKFLOW")"

fail=0
count=0
while IFS= read -r cmd; do
  count=$((count + 1))
  if ! grep -qxF -- "$cmd" <<<"$runs"; then
    echo "FAIL: 'make gates' runs \`$cmd\` but $WORKFLOW has no step with that run: line"
    fail=1
  fi
done <<<"$commands"

[[ "$fail" -eq 0 ]] || exit 1
echo "gates parity: OK ($count make gates commands all run in $WORKFLOW)"
