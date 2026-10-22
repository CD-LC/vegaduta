#!/usr/bin/env bash
# Boot a compose stack in CI and wait for it to be healthy.
#
#   COMPOSE_ARGS="-f a.yml -f b.yml" scripts/ci/compose-up-wait.sh <env-file> [extra `up` flags]
#
# Why not a plain `docker compose up -d --wait`: `--wait` fails ("container ...
# exited (0)", exit 1) when ANY service container exits while it waits, even
# cleanly. keycloak-reconcile is a one-shot (restart: "no") that nothing
# depends on, so it exits 0 a few seconds after Keycloak is healthy and turns
# an otherwise healthy boot red (zap.yml run 39586118141, e2e.yml runs
# 39570883969 and 39749611372). One-shots that other services depend on with
# `service_completed_successfully` (db-roles, workspace-perms) are fine: they
# are waited on as dependencies.
#
# So: bring up and wait on everything except the one-shots nobody depends on,
# then run each of those to completion on its own; a non-zero exit still
# fails the boot. Env: COMPOSE_ARGS (compose -f flags, word-split on purpose),
# ONESHOT_SERVICES (default "keycloak-reconcile"), WAIT_TIMEOUT (seconds, 600).
set -euo pipefail

env_file="${1:?usage: compose-up-wait.sh <env-file> [extra up flags]}"
shift
oneshots="${ONESHOT_SERVICES:-keycloak-reconcile}"
timeout="${WAIT_TIMEOUT:-600}"

# shellcheck disable=SC2086
compose() { docker compose ${COMPOSE_ARGS:-} --env-file "$env_file" "$@"; }

services=()
oneshots_present=()
while IFS= read -r svc; do
  [[ -n "$svc" ]] || continue
  if [[ " $oneshots " == *" $svc "* ]]; then
    oneshots_present+=("$svc")
  else
    services+=("$svc")
  fi
done < <(compose config --services)

[[ ${#services[@]} -gt 0 ]] || { echo "compose-up-wait: no services in the compose files" >&2; exit 2; }

compose up -d --wait --wait-timeout "$timeout" "$@" "${services[@]}"

for svc in "${oneshots_present[@]}"; do
  echo "compose-up-wait: running one-shot $svc to completion"
  # --no-deps: its dependencies are already up and healthy from the step above.
  compose run --rm --no-deps -T "$svc"
done
