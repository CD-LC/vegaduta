#!/usr/bin/env bash
# Validate every Docker Compose file shipped in deploy/ with `docker compose config`.
#
# What gets validated:
#   - each base file in deploy/compose on its own (lite.yml, docker-compose.yml)
#   - each overlay layered on its base. An overlay is named <base>.<variant>.yml
#     next to <base>.yml: lite.build.yml -> lite.yml,
#     docker-compose.ci.yml -> docker-compose.yml
#   - fallback when deploy/compose/ does not exist: deploy/docker-compose.yml
# Override the whole list with COMPOSE_VALIDATE_SETS, one set per line, files in
# a set separated by spaces, e.g.
#   COMPOSE_VALIDATE_SETS=$'deploy/compose/lite.yml\ndeploy/compose/docker-compose.yml deploy/compose/overlays/dev-build.yml'
#
# Every profile is enabled (COMPOSE_PROFILES='*') so services behind a profile are
# checked too. Variables declared as required (`${VAR:?msg}` / `${VAR?msg}`) get a
# throwaway value when they are not already set, so the check exercises structure,
# not secrets. A `.env.example` next to the first file of a set is used as the env
# file when there is no `.env`.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
  if [[ "${CI:-}" == "true" ]]; then
    echo "compose-validate: docker compose is not available" >&2
    exit 1
  fi
  echo "compose-validate: SKIP (docker compose not installed)"
  exit 0
fi

sets=()
if [[ -n "${COMPOSE_VALIDATE_SETS:-}" ]]; then
  while IFS= read -r line; do [[ -n "$line" ]] && sets+=("$line"); done <<<"$COMPOSE_VALIDATE_SETS"
elif [[ -d deploy/compose ]]; then
  shopt -s nullglob
  for f in deploy/compose/*.yml deploy/compose/*.yaml; do
    name="$(basename "$f")"; stem="${name%.*}"; ext="${name##*.}"
    # Chain every existing ancestor overlay, base first: for
    # docker-compose.local-ai.full.yml the set is docker-compose.yml +
    # docker-compose.local-ai.yml + the file itself, the same order
    # scripts/vegaduta stacks them (a second-level overlay alone is invalid).
    parts=()
    cur="$stem"
    while [[ "$cur" == *.* ]]; do
      cur="${cur%.*}"
      [[ -f "deploy/compose/$cur.$ext" ]] && parts=("deploy/compose/$cur.$ext" "${parts[@]}")
    done
    # Overlays that only add to services of the personal stack (they set environment on `core`, which has no
    # image of its own) are valid only on top of personal.yml, the way scripts/vegaduta layers them.
    case "$name" in
      browser.yml|whatsapp-qr.yml) parts=("deploy/compose/personal.yml") ;;
    esac
    parts+=("$f")
    sets+=("${parts[*]}")
  done
  shopt -u nullglob
  # The local-AI overlays also stack on the Lite base, which the naming
  # convention above never pairs (vegaduta up --lite --local-ai[=host]).
  sets+=(
    "deploy/compose/lite.yml deploy/compose/docker-compose.local-ai.yml"
    "deploy/compose/lite.yml deploy/compose/docker-compose.local-ai.yml deploy/compose/docker-compose.local-ai.host.yml"
    # personal: local-AI overlays, the native-core overlay (scripts/vegaduta personal --native-core)
    # and both together; the base+build and base+native pairs come from the naming convention.
    "deploy/compose/personal.yml deploy/compose/docker-compose.local-ai.yml"
    "deploy/compose/personal.yml deploy/compose/docker-compose.local-ai.yml deploy/compose/docker-compose.local-ai.host.yml"
    "deploy/compose/personal.yml deploy/compose/personal.native.yml deploy/compose/docker-compose.local-ai.yml deploy/compose/docker-compose.local-ai.host.yml"
    # a host JVM reaches SearXNG and a container Ollama over loopback-published ports
    "deploy/compose/personal.yml deploy/compose/personal.native.yml deploy/compose/docker-compose.local-ai.yml deploy/compose/docker-compose.local-ai.native.yml"
    "deploy/compose/personal.yml deploy/compose/personal.native.yml deploy/compose/docker-compose.search.yml deploy/compose/docker-compose.search.lite.yml deploy/compose/docker-compose.search.native.yml"
  )
elif [[ -f deploy/docker-compose.yml ]]; then
  sets+=("deploy/docker-compose.yml")
fi

if [[ ${#sets[@]} -eq 0 ]]; then
  echo "compose-validate: no compose files found under deploy/"
  exit 0
fi

fail=0
for set in "${sets[@]}"; do
  read -r -a files <<<"$set"
  args=()
  for f in "${files[@]}"; do args+=(-f "$f"); done

  # Throwaway values for required variables that are not set in the environment.
  env_overrides=()
  while IFS= read -r var; do
    [[ -z "$var" ]] && continue
    if [[ -z "${!var:-}" ]]; then env_overrides+=("$var=ci-placeholder-$var"); fi
  done < <(grep -hoE '\$\{[A-Za-z_][A-Za-z0-9_]*:?\?' "${files[@]}" | sed -E 's/^\$\{([A-Za-z0-9_]+).*/\1/' | sort -u)

  dir="$(dirname "${files[0]}")"
  if [[ ! -f "$dir/.env" && -f "$dir/.env.example" ]]; then
    args+=(--env-file "$dir/.env.example")
  fi

  echo "::group::docker compose config: $set"
  if env "${env_overrides[@]}" COMPOSE_PROFILES='*' docker compose "${args[@]}" config --quiet; then
    echo "OK: $set"
  else
    echo "FAIL: $set" >&2
    fail=1
  fi
  echo "::endgroup::"
done

exit "$fail"
