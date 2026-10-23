#!/usr/bin/env bash
# Install end-to-end test for scripts/vegaduta, without Docker running anything.
#
# `up --dry-run` does everything `up` does up to the docker call (secrets, choices, state file,
# compose arguments) and prints the plan, so this exercises the real installer: the flags, the
# first-run model question on a real pseudo-terminal, what is remembered between runs, and that the
# compose files it picks merge into a valid project. A step that needs Docker is skipped without it.
#
# Hermetic: the result must not depend on the machine it runs on. A curl shim hides any Ollama
# answering on 127.0.0.1:11434 (a developer's own), and the checks below that need one provide it
# with FAKE_OLLAMA=1. Compose files are copied, not symlinked, into the one skeleton install, because
# BSD grep -r (macOS) does not descend into a symlinked directory argument. Runs under bash 3.2.
#
# usage: bash tests/install/vegaduta-install.test.sh
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CLI="$ROOT/scripts/vegaduta"
PTY="$ROOT/tests/install/pty_run.py"
PASS=0; FAIL=0

# curl shim, first on PATH: a request for Ollama's port (11434) fails like a closed port, or, with
# FAKE_OLLAMA=1, answers with one pulled model. Everything else goes to the real curl.
REAL_CURL="$(command -v curl || true)"
SHIMS="$(mktemp -d)"; trap 'rm -rf "$SHIMS"' EXIT
cat > "$SHIMS/curl" <<SHIM
#!/bin/sh
case "\$*" in
  *:11434*) [ "\${FAKE_OLLAMA:-0}" = 1 ] && { printf '%s' '{"models":[{"name":"qwen3.5:2b"}]}'; exit 0; }; exit 7 ;;
esac
exec "$REAL_CURL" "\$@"
SHIM
chmod +x "$SHIMS/curl"; export PATH="$SHIMS:$PATH"

new_home() { WORK="$(mktemp -d)"; export VEGADUTA_ENV_FILE="$WORK/.env" VEGADUTA_STATE_FILE="$WORK/state" VEGADUTA_SECRETS_KEEP="$WORK/kept-secrets.env"; }
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

echo "re-install into a new folder"
keep="$VEGADUTA_SECRETS_KEEP"; key1="$(sed -n 's/^APP_ENCRYPTION_KEY=//p' "$VEGADUTA_ENV_FILE")"; pw1="$(sed -n 's/^POSTGRES_PASSWORD=//p' "$VEGADUTA_ENV_FILE")"
check "secrets are kept outside the install folder (0600)" "$([ -f "$keep" ] && [ "$(stat -c %a "$keep" 2>/dev/null || stat -f %Lp "$keep")" = 600 ]; echo $?)"
fresh="$(mktemp -d)"; out="$(VEGADUTA_ENV_FILE="$fresh/.env" "$CLI" secrets 2>&1)"
check "a new folder reuses the database password and encryption key" "$([ "$(sed -n 's/^APP_ENCRYPTION_KEY=//p' "$fresh/.env")" = "$key1" ] && [ "$(sed -n 's/^POSTGRES_PASSWORD=//p' "$fresh/.env")" = "$pw1" ] && has "$out" 'Reused'; echo $?)" "$out"

echo "an earlier install's data, secrets not kept"
saved_env="$VEGADUTA_ENV_FILE" saved_state="$VEGADUTA_STATE_FILE" saved_keep="$VEGADUTA_SECRETS_KEEP"
fake="$(mktemp -d)"; cat > "$fake/docker" <<'FAKE'
#!/bin/sh
case "$1 $2" in
  "volume inspect") [ "$3" = vegaduta-personal_postgres-data ];;
  "ps -q") echo abc123;;
  "stop abc123") echo stopped >> "${FAKE_LOG:-/dev/null}";;
  *) exit 1;;
esac
FAKE
chmod +x "$fake/docker"; new_home
out="$(FAKE_LOG="$WORK/stops" PATH="$fake:$PATH" "$CLI" secrets 2>&1)"
check "the old data is left alone, this install gets its own project, and secrets stops nothing" "$(grep -qx 'VEGADUTA_PERSONAL_PROJECT=vegaduta-personal-2' "$VEGADUTA_ENV_FILE" && [ ! -f "$WORK/stops" ] && has "$out" 'kept untouched'; echo $?)" "$out"
fresh="$(mktemp -d)"; out="$(PATH="$fake:$PATH" VEGADUTA_ENV_FILE="$fresh/.env" "$CLI" secrets 2>&1)"
check "a later re-install reuses that project with its secrets" "$(grep -qx 'VEGADUTA_PERSONAL_PROJECT=vegaduta-personal-2' "$fresh/.env" && ! has "$out" 'kept untouched'; echo $?)" "$out"
export VEGADUTA_ENV_FILE="$saved_env" VEGADUTA_STATE_FILE="$saved_state" VEGADUTA_SECRETS_KEEP="$saved_keep"

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

echo "personal: a local model without asking"
new_home
out="$("$CLI" personal --build --dry-run </dev/null 2>&1)"
check "no terminal still gets the local model container" "$(has "$out" 'local_ai=container local_model=qwen3.5:' && has "$out" 'docker-compose.local-ai.yml --profile local-ai'; echo $?)" "$out"
check "it is remembered" "$([ "$(state local_ai)" = container ] && [ -n "$(state local_model)" ]; echo $?)" "$(cat "$VEGADUTA_STATE_FILE")"
new_home
out="$(FAKE_OLLAMA=1 "$CLI" personal --build --dry-run </dev/null 2>&1)"
check "an Ollama answering on this machine is used instead of the container" "$(has "$out" 'local_ai=host local_model=qwen3.5:' && ! has "$out" '--profile local-ai'; echo $?)" "$out"
new_home
out="$("$CLI" personal --build --cloud --dry-run </dev/null 2>&1)"
check "--cloud opts out" "$(has "$out" 'local_ai=none'; echo $?)" "$out"
out="$("$CLI" personal --build --dry-run </dev/null 2>&1)"
check "a later run keeps --cloud" "$(has "$out" 'local_ai=none'; echo $?)" "$out"
new_home
printf 'tier=personal\nprofiles=\nbuild=1\nlocal_ai=\nlocal_model=\nsearch=1\nnative_core=0\ncloud=0\n' > "$VEGADUTA_STATE_FILE"
out="$("$CLI" personal --dry-run </dev/null 2>&1)"
check "an install that never chose gets the model on its next run" "$(has "$out" 'local_ai=container'; echo $?)" "$out"

echo "secret tables"
# Windows runs vegaduta.ps1, which keeps its own copy of the table; a secret missing there made
# every Windows personal install stop with "WORKSPACE_RUNNER_SECRET is missing a value".
sh_specs="$(sed -n '/^SECRET_SPECS="/,/^"/p' "$CLI" | grep -oE '^[A-Z0-9_]+:[a-z0-9]+' | sort)"
ps_specs="$(tr -d '\r' < "$ROOT/scripts/vegaduta.ps1" | sed -n '/^\$SecretSpecs = /,/^}/p' \
  | grep -oE "[A-Z0-9_]+='[a-z0-9]+'" | sed "s/='/:/; s/'\$//" | sort)"
d="$(diff <(printf '%s\n' "$sh_specs") <(printf '%s\n' "$ps_specs"))"
check "vegaduta.ps1 generates the same secrets as scripts/vegaduta" "$([ -z "$d" ]; echo $?)" "$d"
required="$(grep -hoE '\$\{[A-Z0-9_]+:\?run scripts/vegaduta secrets' "$ROOT"/deploy/compose/*.yml \
  | sed -E 's/^\$\{([A-Z0-9_]+).*/\1/' | sort -u)"
missing="$(comm -23 <(printf '%s\n' "$required") <(printf '%s\n' "$sh_specs" | cut -d: -f1))"
check "every secret the compose files require is generated" "$([ -z "$missing" ]; echo $?)" "not generated: $missing"

echo "secrets the compose files require"
# Belt and braces on top of the table: whatever any compose file marks ${X:?} and looks like a secret
# is generated too (scan), and `up` asks Compose itself what is still missing and fills it (guard).
# No table edit can leave an install stuck on "required variable X is missing a value" again.
new_home; "$CLI" secrets >/dev/null 2>&1
required_secrets="$(grep -rhoE '\$\{[A-Z0-9_]+:\?' "$ROOT/deploy/compose" --include='*.yml' --include='*.yaml' \
  | sed -E 's/^\$\{([A-Z0-9_]+):\?$/\1/' | sort -u | grep -E 'SECRET|_PASSWORD$|_PASS$|_KEY$|_TOKEN$|_AUTH$|_SALT$' | awk '!/_API_KEY$/ || $0 == "LIVEKIT_API_KEY"')"
empty=""; for n in $required_secrets; do [ -n "$(sed -n "s/^$n=//p" "$VEGADUTA_ENV_FILE")" ] || empty="$empty $n"; done
check "every secret any compose file requires has a value after secrets" "$([ -n "$required_secrets" ] && [ -z "$empty" ]; echo $?)" "empty:$empty"
check "the per-user copy holds them too" "$(grep -q '^WHATSAPP_QR_BRIDGE_SECRET=.' "$VEGADUTA_SECRETS_KEEP" && grep -q '^DD_SECRET_KEY=.' "$VEGADUTA_SECRETS_KEEP"; echo $?)"
skel="$(mktemp -d)"; mkdir -p "$skel/scripts" "$skel/deploy/compose"; cp "$ROOT"/deploy/compose/*.yml "$ROOT/deploy/compose/.env.example" "$skel/deploy/compose/"
sed '/^WORKSPACE_RUNNER_SECRET:hex32$/d' "$CLI" > "$skel/scripts/vegaduta"; chmod +x "$skel/scripts/vegaduta"
new_home; "$skel/scripts/vegaduta" secrets >/dev/null 2>&1
check "a secret the table forgot is still generated from the compose files" "$(grep -q '^WORKSPACE_RUNNER_SECRET=.' "$VEGADUTA_ENV_FILE"; echo $?)" "$(grep WORKSPACE_RUNNER "$VEGADUTA_ENV_FILE")"
fake="$(mktemp -d)"; cat > "$fake/docker" <<'FAKE'
#!/bin/sh
# docker compose ... config -q: complains about the first FAKE_REQUIRE variable that is empty in .env
case " $* " in
  *" config "*)
    for n in $FAKE_REQUIRE; do
      grep -q "^$n=." "$VEGADUTA_ENV_FILE" && continue
      echo "required variable $n is missing a value: run scripts/vegaduta secrets" >&2; exit 1
    done; exit 0 ;;
  *" up "*) echo "fake up"; exit 0 ;;
  *) exit 1 ;;
esac
FAKE
chmod +x "$fake/docker"
# COMPOSE_ARGS is never empty in a real run; "${COMPOSE_ARGS[@]}" on an empty array dies under set -u in bash 3.2.
heal() { PATH="$fake:$PATH" VEGADUTA_NO_MAIN=1 bash -c 'source "$1"; COMPOSE_ARGS=(-p heal-test); dc_up_heal up -d' _ "$CLI" 2>&1; }
out="$(FAKE_REQUIRE="EXTRA_BRIDGE_SECRET NEW_SERVICE_PASSWORD" heal)"
check "secrets compose reports missing are generated before up, then up runs" "$(grep -q '^EXTRA_BRIDGE_SECRET=.' "$VEGADUTA_ENV_FILE" && grep -q '^NEW_SERVICE_PASSWORD=.' "$VEGADUTA_ENV_FILE" && has "$out" 'generated 2 secret' && has "$out" 'fake up'; echo $?)" "$out"
check "they are kept for the next install" "$(grep -q '^EXTRA_BRIDGE_SECRET=.' "$VEGADUTA_SECRETS_KEEP"; echo $?)"
out="$(FAKE_REQUIRE="BASE_DOMAIN" heal)"
check "a missing value that is not a secret stops with the name to set" "$(has "$out" 'required by the compose files but empty in .*: BASE_DOMAIN' && ! has "$out" 'fake up'; echo $?)" "$out"

echo "compose project"
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  new_home
  "$CLI" up --lite --build --local-ai --search --dry-run >/dev/null 2>&1
  if out="$("$CLI" compose config --quiet 2>&1)"; then c=0; else c=1; fi
  check "the chosen compose files merge into a valid project" "$c" "$out"
  # Regression: --search on lite once left core on search-net only, so it could not reach postgres.
  nets="$("$CLI" compose config --format json 2>/dev/null | python3 -c 'import json,sys; print(" ".join(sorted(json.load(sys.stdin)["services"]["core"]["networks"])))')"
  check "core keeps the default network when search is added to lite" "$([ "$nets" = "default mcp-stdio-net search-net" ]; echo $?)" "core networks: $nets"
else
  echo "  skip docker is not available"
fi

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[ "$FAIL" = 0 ]
