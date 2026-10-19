#!/usr/bin/env bash
# B9-PR1 — polls every full-stack service's health/readiness endpoint until
# all are up or a 5-minute budget expires. Complements, not replaces,
# `docker compose up --wait`'s own healthcheck-driven wait: most services
# in deploy/docker-compose.yml already have a `healthcheck:` block that
# `--wait` blocks on, but `keycloak` does NOT (no healthcheck: block exists
# for it in that file today — confirmed by reading it; a real gap, not an
# oversight this script silently works around). Without this script,
# `docker compose up --wait` would report success the moment the keycloak
# CONTAINER is running, not once the "agentic-ai" realm is actually
# imported and servable — the realm import (--import-realm) can still be
# in progress after the container itself is "up". This script's
# keycloak-realm check is therefore the one genuinely load-bearing check
# here; the others are cheap extra safety on top of what --wait already
# gates on.
#
# Usage: bash scripts/ci/stack-health-gate.sh
# Exits 0 once every check below returns a 2xx/3xx, non-zero after the
# budget expires (e2e.yml then dumps `docker compose logs` as an
# artifact on this failure).
set -uo pipefail

BUDGET_SECONDS="${HEALTH_GATE_BUDGET_SECONDS:-300}"
INTERVAL_SECONDS=5

# name|url pairs, one per line. Override with HEALTH_GATE_CHECKS (same format,
# newline-separated). The default covers the services every install tier runs
# (core, web, keycloak) on the compose stack's published host ports; add
# sidecar/etc. through the override when the stack under test runs them.
DEFAULT_CHECKS='core|http://localhost:8080/actuator/health/readiness
web|http://localhost:3000/
keycloak-realm|http://localhost:8180/realms/agentic-ai/.well-known/openid-configuration'
CHECKS=()
while IFS= read -r line; do
  [[ -n "${line}" ]] && CHECKS+=("${line}")
done <<<"${HEALTH_GATE_CHECKS:-${DEFAULT_CHECKS}}"

pending=("${CHECKS[@]}")
elapsed=0

while [[ "${#pending[@]}" -gt 0 && "${elapsed}" -lt "${BUDGET_SECONDS}" ]]; do
  next_pending=()
  for entry in "${pending[@]}"; do
    name="${entry%%|*}"
    url="${entry#*|}"
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "${url}" || echo 000)"
    if [[ "${code}" =~ ^[23] ]]; then
      echo "[health-gate] ${name} OK (HTTP ${code}) after ${elapsed}s"
    else
      next_pending+=("${entry}")
    fi
  done
  pending=("${next_pending[@]}")
  if [[ "${#pending[@]}" -gt 0 ]]; then
    sleep "${INTERVAL_SECONDS}"
    elapsed=$((elapsed + INTERVAL_SECONDS))
  fi
done

if [[ "${#pending[@]}" -gt 0 ]]; then
  echo "[health-gate] TIMEOUT after ${BUDGET_SECONDS}s - still not healthy:"
  for entry in "${pending[@]}"; do
    echo "  - ${entry%%|*} (${entry#*|})"
  done
  exit 1
fi

echo "[health-gate] all services healthy after ${elapsed}s"
