#!/usr/bin/env bash
# Self-test for compose-up-wait.sh on a toy stack (needs Docker; pulls alpine:3).
#
# A fast and a slow (12 s to healthy) long-running service plus a one-shot named
# keycloak-reconcile that nothing depends on and that exits at once, while `--wait` is
# still waiting for the slow one, like the real lite stack. Asserts that
#   1. plain `docker compose up -d --wait` fails on this stack (the CI bug this
#      script exists for), so the toy stack really reproduces it;
#   2. compose-up-wait.sh boots it and exits 0;
#   3. a failing one-shot makes compose-up-wait.sh exit non-zero.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
project="${TEST_COMPOSE_PROJECT:-rr-compose-up-wait}"
trap 'COMPOSE_PROJECT_NAME=$project docker compose -f "$tmp/ok.yml" down -v --remove-orphans >/dev/null 2>&1; COMPOSE_PROJECT_NAME=$project docker compose -f "$tmp/bad.yml" down -v --remove-orphans >/dev/null 2>&1; rm -rf "$tmp"' EXIT
export COMPOSE_PROJECT_NAME="$project"
: > "$tmp/env"

write_stack() { # <file> <one-shot exit code>
  cat > "$1" <<YML
services:
  app:
    image: alpine:3
    command: ["sleep", "3600"]
    healthcheck:
      test: ["CMD", "true"]
      interval: 1s
      retries: 5
    stop_grace_period: 1s
  slow:
    image: alpine:3
    command: ["sh", "-c", "sleep 12; touch /ready; sleep 3600"]
    healthcheck:
      test: ["CMD", "test", "-f", "/ready"]
      interval: 1s
      retries: 60
    stop_grace_period: 1s
  keycloak-reconcile:
    image: alpine:3
    restart: "no"
    network_mode: "service:app"
    depends_on:
      app:
        condition: service_healthy
    command: ["sh", "-c", "exit $2"]
YML
}
write_stack "$tmp/ok.yml" 0
write_stack "$tmp/bad.yml" 1

fail=0
check() { # <description> <expected: 0|nonzero> <actual rc>
  if { [ "$2" = 0 ] && [ "$3" -eq 0 ]; } || { [ "$2" = nonzero ] && [ "$3" -ne 0 ]; }; then
    echo "ok   - $1"
  else
    echo "FAIL - $1 (exit $3)"; fail=1
  fi
}

docker compose -f "$tmp/ok.yml" --env-file "$tmp/env" up -d --wait --wait-timeout 60 >/dev/null 2>&1
check "plain 'up --wait' fails on a stack with an exited one-shot (bug reproduced)" nonzero $?
docker compose -f "$tmp/ok.yml" down -v --remove-orphans >/dev/null 2>&1

COMPOSE_ARGS="-f $tmp/ok.yml" WAIT_TIMEOUT=60 bash "$here/compose-up-wait.sh" "$tmp/env" >/dev/null 2>&1
check "compose-up-wait.sh boots the stack and runs the one-shot" 0 $?
docker compose -f "$tmp/ok.yml" down -v --remove-orphans >/dev/null 2>&1

COMPOSE_ARGS="-f $tmp/bad.yml" WAIT_TIMEOUT=60 bash "$here/compose-up-wait.sh" "$tmp/env" >/dev/null 2>&1
check "compose-up-wait.sh fails when the one-shot fails" nonzero $?

exit "$fail"
