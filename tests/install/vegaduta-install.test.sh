#!/usr/bin/env bash
# Install end-to-end test for scripts/vegaduta, without Docker running anything.
#
# `up --dry-run` does everything `up` does up to the docker call (secrets, choices, state file,
# compose arguments) and prints the plan, so this exercises the real installer: the flags, the
# first-run model question on a real pseudo-terminal, what is remembered between runs, and that the
# compose files it picks merge into a valid project. A step that needs Docker is skipped without it.
#
# usage: bash tests/install/vegaduta-install.test.sh
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CLI="$ROOT/scripts/vegaduta"
PTY="$ROOT/tests/install/pty_run.py"
PASS=0; FAIL=0

new_home() { WORK="$(mktemp -d)"; export VEGADUTA_ENV_FILE="$WORK/.env" VEGADUTA_STATE_FILE="$WORK/state"; }
check() { # check NAME CONDITION-EXIT-CODE [DETAIL]
  if [ "$2" = 0 ]; then PASS=$((PASS + 1)); printf '  ok   %s\n' "$1"
  else FAIL=$((FAIL + 1)); printf '  FAIL %s\n%s\n' "$1" "${3:-}"; fi
}
has() { printf '%s' "$1" | grep -q -- "$2"; }
state() { sed -n "s/^$1=//p" "$VEGADUTA_STATE_FILE"; }

echo "flags"
new_home
out="$("$CLI" up --lite --build --local-ai --local-model qwen3.5:2b --search --dry-run 2>&1)"
check "plan names the choices" "$(has "$out" 'plan: tier=lite profiles=none local_ai=container local_model=qwen3.5:2b search=1'; echo $?)" "$out"
check "plan layers the local-ai and search overlays" "$(has "$out" 'docker-compose.local-ai.yml --profile local-ai' && has "$out" 'docker-compose.search.yml' && has "$out" 'docker-compose.search.lite.yml --profile search'; echo $?)" "$out"
check "state remembers them" "$([ "$(state local_ai)/$(state local_model)/$(state search)" = container/qwen3.5:2b/1 ]; echo $?)"
check "secrets were generated, none printed" "$(! has "$out" "$(sed -n 's/^APP_ENCRYPTION_KEY=//p' "$VEGADUTA_ENV_FILE")"; echo $?)"

echo "remembered between runs"
out="$("$CLI" up --dry-run 2>&1)"
check "a second run without flags keeps local model and search" "$(has "$out" 'local_ai=container local_model=qwen3.5:2b search=1'; echo $?)" "$out"

echo "host mode"
new_home
out="$("$CLI" up --lite --build --local-ai=host --local-model qwen3.5:4b --dry-run 2>&1)"
check "host mode uses the host overlay and no container profile" "$(has "$out" 'docker-compose.local-ai.host.yml' && ! has "$out" '--profile local-ai'; echo $?)" "$out"

echo "no terminal: nothing is asked"
new_home
out="$("$CLI" up --lite --build --dry-run </dev/null 2>&1)"
check "plan is the cloud-key default" "$(has "$out" 'local_ai=none local_model=default search=0'; echo $?)" "$out"

echo "first-run question (real terminal)"
new_home
out="$(python3 "$PTY" "-|-" "$CLI" up --lite --build --dry-run 2>&1)"
check "asks which model should power agents" "$(has "$out" 'Which model should power your agents?'; echo $?)" "$out"
check "default answer picks a local model and search" "$([ -n "$(state local_ai)" ] && [ -n "$(state local_model)" ] && [ "$(state search)" = 1 ]; echo $?)" "$(cat "$VEGADUTA_STATE_FILE")"

new_home
out="$(python3 "$PTY" "2" "$CLI" up --lite --build --dry-run 2>&1)"
check "choosing a cloud provider leaves no local model and no search" "$([ -z "$(state local_ai)" ] && [ -z "$(state search)" ]; echo $?)" "$out"
check "choosing a cloud provider is remembered like --cloud" "$([ "$(state cloud)" = 1 ]; echo $?)" "$(cat "$VEGADUTA_STATE_FILE")"

new_home
out="$(python3 "$PTY" "3|qwen3:8b|n" "$CLI" up --lite --build --dry-run 2>&1)"
check "choosing a catalog model with search declined" "$([ "$(state local_model)" = qwen3:8b ] && [ -z "$(state search)" ]; echo $?)" "$out"

new_home
"$CLI" up --lite --build --cloud --dry-run >/dev/null 2>&1
out="$(python3 "$PTY" "" "$CLI" up --dry-run 2>&1)"
check "--cloud and an existing install are never asked again" "$(! has "$out" 'Which model should power'; echo $?)" "$out"

echo "compose project"
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  new_home
  "$CLI" up --lite --build --local-ai --search --dry-run >/dev/null 2>&1
  if out="$("$CLI" compose config --quiet 2>&1)"; then c=0; else c=1; fi
  check "the chosen compose files merge into a valid project" "$c" "$out"
  # Regression: --search on lite once left core on search-net only, so it could not reach postgres.
  nets="$("$CLI" compose config --format json 2>/dev/null | python3 -c 'import json,sys; print(" ".join(sorted(json.load(sys.stdin)["services"]["core"]["networks"])))')"
  check "core keeps the default network when search is added to lite" "$([ "$nets" = "default search-net" ]; echo $?)" "core networks: $nets"
else
  echo "  skip docker is not available"
fi

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[ "$FAIL" = 0 ]
