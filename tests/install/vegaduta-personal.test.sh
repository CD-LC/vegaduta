#!/usr/bin/env bash
# Tests for `scripts/vegaduta personal`, without starting anything.
#
# `personal --dry-run` runs everything up to the docker call (secrets, choices, state file,
# compose arguments) and prints the plan. The compose steps are skipped without Docker.
# Also locks that lite and full are untouched: `up --lite --dry-run` still plans lite.
#
# usage: bash tests/install/vegaduta-personal.test.sh
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CLI="$ROOT/scripts/vegaduta"
PASS=0; FAIL=0

# VEGADUTA_SECRETS_KEEP too: without it every run overwrote the developer's real ~/.config/vegaduta/secrets.env
# (the per-user copy the launcher restores an install's secrets from) with the throwaway secrets of the test.
new_home() { WORK="$(mktemp -d)"; export VEGADUTA_ENV_FILE="$WORK/.env" VEGADUTA_STATE_FILE="$WORK/state" VEGADUTA_SECRETS_KEEP="$WORK/kept-secrets.env"; }
check() { # check NAME CONDITION-EXIT-CODE [DETAIL]
  if [ "$2" = 0 ]; then PASS=$((PASS + 1)); printf '  ok   %s\n' "$1"
  else FAIL=$((FAIL + 1)); printf '  FAIL %s\n%s\n' "$1" "${3:-}"; fi
}
has() { printf '%s' "$1" | grep -q -- "$2"; }
state() { sed -n "s/^$1=//p" "$VEGADUTA_STATE_FILE"; }

echo "container core"
new_home
out="$("$CLI" personal --build --cloud --dry-run </dev/null 2>&1)"
check "plan names the personal tier" "$(has "$out" 'plan: tier=personal native_core=0 profiles=none local_ai=none'; echo $?)" "$out"
check "plan uses personal.yml and its build overlay, not lite or full" "$(has "$out" 'personal.yml' && has "$out" 'personal.build.yml' && ! has "$out" '/lite.yml' && ! has "$out" '/docker-compose.yml'; echo $?)" "$out"
check "the edition switch is written to .env once, as personal" "$([ "$(grep -c '^VEGADUTA_EDITION=' "$VEGADUTA_ENV_FILE")" = 1 ] && [ "$(sed -n 's/^VEGADUTA_EDITION=//p' "$VEGADUTA_ENV_FILE")" = personal ]; echo $?)" "$(grep EDITION "$VEGADUTA_ENV_FILE")"
check "state remembers the tier" "$([ "$(state tier)" = personal ] && [ "$(state native_core)" = 0 ]; echo $?)" "$(cat "$VEGADUTA_STATE_FILE")"
check "secrets were generated, none printed" "$(! has "$out" "$(sed -n 's/^APP_ENCRYPTION_KEY=//p' "$VEGADUTA_ENV_FILE")"; echo $?)"

echo "minimal floor"
new_home
out="$("$CLI" personal --minimal --build --cloud --dry-run </dev/null 2>&1)"
check "--minimal prints its plan instead of exiting in silence" "$(has "$out" 'plan: tier=personal'; echo $?)" "$out"
check "--minimal leaves out the valkey profile" "$(! has "$out" '--profile valkey'; echo $?)" "$out"

echo "native core"
new_home
out="$("$CLI" personal --native-core --build --local-ai=host --local-model qwen3.5:2b --dry-run </dev/null 2>&1)"
check "plan layers the native overlay and host Ollama" "$(has "$out" 'native_core=1' && has "$out" 'personal.native.yml' && has "$out" 'docker-compose.local-ai.host.yml'; echo $?)" "$out"
check "state remembers native core and the model" "$([ "$(state native_core)" = 1 ] && [ "$(state local_model)" = qwen3.5:2b ]; echo $?)" "$(cat "$VEGADUTA_STATE_FILE")"

echo "remembered between runs"
out="$("$CLI" personal --dry-run </dev/null 2>&1)"
check "a second run keeps native core and the model" "$(has "$out" 'native_core=1' && has "$out" 'local_model=qwen3.5:2b'; echo $?)" "$out"
out="$("$CLI" up --dry-run </dev/null 2>&1)"
check "plain 'up' on a personal install stays personal" "$(has "$out" 'plan: tier=personal'; echo $?)" "$out"
out="$("$CLI" personal --container-core --dry-run </dev/null 2>&1)"
check "--container-core switches back" "$(has "$out" 'native_core=0' && ! has "$out" 'personal.native.yml'; echo $?)" "$out"

echo "cloud choice is remembered"
new_home
"$CLI" personal --build --cloud --dry-run </dev/null >/dev/null 2>&1
check "--cloud is saved so a later plain run does not adopt an Ollama by itself" "$([ "$(state cloud)" = 1 ]; echo $?)" "$(cat "$VEGADUTA_STATE_FILE")"
"$CLI" personal --build --local-ai=host --dry-run </dev/null >/dev/null 2>&1
check "choosing a local model clears it" "$([ "$(state cloud)" = 0 ] && [ "$(state local_ai)" = host ]; echo $?)" "$(cat "$VEGADUTA_STATE_FILE")"
new_home
out="$("$CLI" personal --build --cloud --local-model qwen3.5:2b --dry-run </dev/null 2>&1)"
check "--local-model without a local AI says it does nothing" "$(has "$out" 'has no effect'; echo $?)" "$out"
out="$("$CLI" help 2>&1)"
check "help lists restart" "$(has "$out" 'restart'; echo $?)" "$out"

echo "first-run question (real terminal)"
PTY="$ROOT/tests/install/pty_run.py"
new_home
out="$(python3 "$PTY" "-|-" "$CLI" personal --build --dry-run 2>&1)"
check "personal asks which model should power agents" "$(has "$out" 'Which model should power your agents?'; echo $?)" "$out"
check "with no answer a first install stays lean: no local model registered, cloud-key path remembered" "$([ -z "$(state local_ai)" ] && [ -z "$(state local_model)" ] && [ "$(state cloud)" = 1 ]; echo $?)" "$(cat "$VEGADUTA_STATE_FILE")"

new_home
out="$(python3 "$PTY" "3|qwen3:8b|n" "$CLI" personal --build --dry-run 2>&1)"
check "declining the search question on a personal install is remembered, not read as never asked" "$([ "$(state search)" = 0 ] && ! has "$out" '--profile search'; echo $?)" "$(cat "$VEGADUTA_STATE_FILE")"

new_home
out="$(python3 "$PTY" "-|-" "$CLI" personal --build --search --dry-run 2>&1)"
check "--search turns search on and remembers it" "$([ "$(state search)" = 1 ] && has "$out" '--profile search'; echo $?)" "$(cat "$VEGADUTA_STATE_FILE")"

new_home
out="$(python3 "$PTY" "2" "$CLI" personal --build --dry-run 2>&1)"
check "choosing a cloud provider is remembered like --cloud" "$([ "$(state cloud)" = 1 ] && [ -z "$(state local_ai)" ]; echo $?)" "$(cat "$VEGADUTA_STATE_FILE")"

echo "profiles personal does not have"
new_home
out="$("$CLI" personal --profile events --cloud --dry-run </dev/null 2>&1)"
check "an unavailable profile is called out, not silently dropped" "$(has "$out" "profile 'events' is not available in personal"; echo $?)" "$out"
out="$("$CLI" personal --profile rag --cloud --dry-run </dev/null 2>&1)"
check "rag is accepted without a warning" "$(! has "$out" 'is not available in personal'; echo $?)" "$out"

echo "day-one defaults (fresh install)"
new_home
out="$("$CLI" personal --build --cloud --dry-run </dev/null 2>&1)"
check "a fresh install is lean: no web search, no MCP runner, no browser" "$(has "$out" 'search=0 tools=0 browser=0' && ! has "$out" '--profile search' && ! has "$out" '--profile tools' && ! has "$out" '--profile browser' && ! has "$out" '--profile valkey'; echo $?)" "$out"
check "a fresh install says what lean and full cost before anything is pulled" "$(has "$out" 'Lean install: about 3 GB' && has "$out" 'Full: about 8 GB'; echo $?)" "$out"
new_home
out="$("$CLI" personal --build --dry-run </dev/null 2>&1)"
check "with no model flag a fresh install stays cloud-key: no download, and says so" "$(has "$out" 'No local model on a first install' && has "$out" 'local_ai=none' && [ "$(state cloud)" = 1 ]; echo $?)" "$out"
check "tools is a choice, not a profile: the profile list stays empty" "$(has "$out" 'profiles=none'; echo $?)" "$out"
check "the lean answers are remembered as 0, not left empty" "$([ "$(state search)" = 0 ] && [ "$(state tools)" = 0 ] && [ "$(state browser)" = 0 ] && [ "$(state valkey)" = 0 ] && [ "$(state cloud)" = 1 ]; echo $?)" "$(cat "$VEGADUTA_STATE_FILE")"
out="$("$CLI" personal --cloud --dry-run </dev/null 2>&1)"
check "a plain re-run of a lean install stays lean (and does not print the first-install banner)" "$(has "$out" 'search=0 tools=0 browser=0' && ! has "$out" 'Lean install: about'; echo $?)" "$out"
new_home
out="$("$CLI" personal --build --cloud --search --profile tools --browser --valkey --dry-run </dev/null 2>&1)"
check "the add-ons are one flag each: search, tools, browser and valkey plan as before" "$(has "$out" 'search=1 tools=1 browser=1' && has "$out" '--profile search' && has "$out" '--profile tools' && has "$out" '--profile browser' && has "$out" '--profile valkey'; echo $?)" "$out"
check "the opted-in add-ons are remembered" "$([ "$(state search)" = 1 ] && [ "$(state tools)" = 1 ] && [ "$(state browser)" = 1 ] && [ "$(state valkey)" = 1 ]; echo $?)" "$(cat "$VEGADUTA_STATE_FILE")"
out="$("$CLI" personal --cloud --dry-run </dev/null 2>&1)"
check "a later plain run keeps the opted-in add-ons" "$(has "$out" 'search=1 tools=1 browser=1'; echo $?)" "$out"
new_home
out="$("$CLI" personal --profile workspace --cloud --dry-run </dev/null 2>&1)"
check "a caller that passes its own profiles (the Setup app) gets exactly those on top of the lean floor" "$(has "$out" 'profiles=workspace' && has "$out" 'search=0 tools=0' && ! has "$out" '--profile tools'; echo $?)" "$out"
new_home
out="$("$CLI" personal --local-model qwen3.5:2b --local-ai --build --dry-run </dev/null 2>&1)"
check "a model flag opts in and says how big the download is" "$(has "$out" "local model 'qwen3.5:2b' is about" && has "$out" 'GB to download'; echo $?)" "$out"
check "an opted-in model is not remembered as cloud" "$([ "$(state cloud)" != 1 ] && [ -n "$(state local_model)" ]; echo $?)" "$(cat "$VEGADUTA_STATE_FILE")"
out="$("$CLI" personal --no-search --no-tools --cloud --dry-run </dev/null 2>&1)"
check "--no-search --no-tools declines both" "$(has "$out" 'search=0 tools=0' && ! has "$out" '--profile search' && ! has "$out" '--profile tools'; echo $?)" "$out"
out="$("$CLI" personal --browser --cloud --dry-run </dev/null 2>&1)"
check "--browser layers browser.yml and the browser profile" "$(has "$out" 'browser=1' && has "$out" 'browser.yml' && has "$out" '--profile browser'; echo $?)" "$out"
check "the browser choice is remembered by a later plain run" "$([ "$(state browser)" = 1 ]; echo $?)" "$(cat "$VEGADUTA_STATE_FILE")"
out="$("$CLI" personal --cloud --dry-run </dev/null 2>&1)"
check "a plain re-run keeps the browser add-on" "$(has "$out" 'browser=1' && has "$out" 'browser.yml'; echo $?)" "$out"
out="$("$CLI" personal --no-browser --cloud --dry-run </dev/null 2>&1)"
check "--no-browser turns it off again" "$(has "$out" 'browser=0' && ! has "$out" 'browser.yml'; echo $?)" "$out"
out="$("$CLI" personal --profile browser --cloud --dry-run </dev/null 2>&1)"
check "--profile browser is accepted on a personal install and means --browser" "$(has "$out" 'browser=1' && has "$out" 'browser.yml' && ! has "$out" 'not available in personal mode'; echo $?)" "$out"
out="$("$CLI" personal --native-core --browser --cloud --dry-run </dev/null 2>&1)"
check "--browser with a host JVM is refused politely, not planned" "$(has "$out" 'needs the container core' && has "$out" 'browser=0' && ! has "$out" 'browser.yml'; echo $?)" "$out"
"$CLI" personal --container-core --no-browser --cloud --dry-run </dev/null >/dev/null 2>&1
check "the decline is remembered" "$([ "$(state search)" = 0 ] && [ "$(state tools)" = 0 ]; echo $?)" "$(cat "$VEGADUTA_STATE_FILE")"
out="$("$CLI" personal --profile workspace --cloud --dry-run </dev/null 2>&1)"
check "a later plain run does not turn them back on" "$(has "$out" 'search=0 tools=0' && ! has "$out" '--profile tools'; echo $?)" "$out"
out="$("$CLI" personal --search --profile tools --cloud --dry-run </dev/null 2>&1)"
check "--search and --profile tools turn them on again, and tools never enters the profile list" "$(has "$out" 'search=1 tools=1' && ! has "$out" 'profiles=[^ ]*tools'; echo $?)" "$out"

new_home
out="$(VEGADUTA_SEARCH=0 VEGADUTA_TOOLS=0 "$CLI" personal --build --cloud --dry-run </dev/null 2>&1)"
check "VEGADUTA_SEARCH=0 and VEGADUTA_TOOLS=0 in the environment opt out of a fresh install" "$(has "$out" 'search=0 tools=0'; echo $?)" "$out"
new_home
"$CLI" secrets >/dev/null 2>&1
printf 'VEGADUTA_SEARCH=0\nVEGADUTA_TOOLS=0\n' >> "$VEGADUTA_ENV_FILE"
out="$("$CLI" personal --build --cloud --dry-run </dev/null 2>&1)"
check "the same two lines in .env opt out" "$(has "$out" 'search=0 tools=0'; echo $?)" "$out"
out="$("$CLI" personal --build --search --cloud --dry-run </dev/null 2>&1)"
check "a command-line flag beats the .env line" "$(has "$out" 'search=1 tools=0'; echo $?)" "$out"

new_home
printf 'tier=personal\nprofiles=\nbuild=0\nlocal_ai=\nlocal_model=\nsearch=\nnative_core=0\ncloud=1\nprevious_version=\n' > "$VEGADUTA_STATE_FILE"
out="$("$CLI" personal --build --cloud --dry-run </dev/null 2>&1)"
check "an install that predates the defaults (state file, no answer) is left as it was" "$(has "$out" 'search=0 tools=0' && ! has "$out" '--profile tools'; echo $?)" "$out"

new_home
out="$("$CLI" personal --native-core --build --cloud --search --profile tools --dry-run </dev/null 2>&1)"
check "a host JVM plans search but no runner container" "$(has "$out" 'search=1 tools=1' && has "$out" 'docker-compose.search.native.yml' && ! has "$out" '--profile tools'; echo $?)" "$out"

echo "lite and full are unchanged"
new_home
out="$("$CLI" up --lite --build --cloud --dry-run </dev/null 2>&1)"
check "up --lite still plans lite" "$(has "$out" 'plan: tier=lite' && has "$out" 'lite.yml' && ! has "$out" 'personal'; echo $?)" "$out"
check "lite state is written with the same keys plus native_core=0" "$([ "$(state tier)" = lite ] && [ "$(state native_core)" = 0 ]; echo $?)" "$(cat "$VEGADUTA_STATE_FILE")"
out="$("$CLI" personal --build --cloud --dry-run </dev/null 2>&1)"
check "personal after a lite install does not inherit lite profiles" "$(has "$out" 'tier=personal native_core=0 profiles=none'; echo $?)" "$out"

echo "compose project"
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  new_home
  "$CLI" personal --build --cloud --dry-run >/dev/null 2>&1
  if out="$("$CLI" compose config --quiet 2>&1)"; then c=0; else c=1; fi
  check "personal compose files merge into a valid project" "$c" "$out"
  svcs="$("$CLI" compose config --services 2>/dev/null | sort | tr '\n' ' ')"
  check "no Keycloak and no Kafka/Temporal services" "$(! has "$svcs" keycloak && ! has "$svcs" kafka && ! has "$svcs" temporal; echo $?)" "$svcs"
  new_home
  "$CLI" personal --native-core --build --cloud --valkey --dry-run >/dev/null 2>&1
  svcs="$("$CLI" compose config --services 2>/dev/null | sort | tr '\n' ' ')"
  check "native core leaves postgres, valkey and web, and no core container" "$(has "$svcs" postgres && has "$svcs" valkey && has "$svcs" web && ! has "$svcs" core; echo $?)" "$svcs"
  new_home
  "$CLI" personal --native-core --build --cloud --valkey --dry-run >/dev/null 2>&1
  # Even with a LAN-facing console (BIND_ADDR=0.0.0.0) the database and Valkey stay on loopback.
  hosts="$(BIND_ADDR=0.0.0.0 "$CLI" compose config --format json 2>/dev/null | python3 -c 'import json,sys; s=json.load(sys.stdin)["services"]; print(" ".join(p.get("host_ip","") for n in ("postgres","valkey") for p in s[n].get("ports",[])))')"
  check "native overlay publishes postgres and valkey on 127.0.0.1 only" "$([ "$hosts" = "127.0.0.1 127.0.0.1" ]; echo $?)" "host_ips: $hosts"
  new_home
  "$CLI" personal --build --cloud --dry-run >/dev/null 2>&1
  # The console's sign-in proxy runs server-side in the web container: it needs core's INTERNAL address
  # (the browser URL is the container itself there) and the Ollama on this machine must be reachable.
  web_env="$("$CLI" compose config --format json 2>/dev/null | python3 -c 'import json,sys; s=json.load(sys.stdin)["services"]; print(s["web"]["environment"]["CORE_INTERNAL_URL"], s["core"]["environment"]["APP_SIDECAR_ENABLED"], s["core"]["environment"]["APP_LOCAL_LLM_BOOTSTRAP_BASE_URL"], s["core"]["environment"]["APP_SECURITY_OUTBOUND_ALLOWED_PRIVATE_HOSTS"])')"
  check "web reaches core by its service name; sidecar off; host Ollama allow-listed" "$([ "$web_env" = "http://core:8080 false http://host.docker.internal:11434 host.docker.internal:11434" ]; echo $?)" "web/core env: $web_env"
  new_home
  "$CLI" personal --native-core --build --cloud --profile rag --search --dry-run >/dev/null 2>&1
  nweb="$(CORE_HOST_PORT=8081 "$CLI" compose config --format json 2>/dev/null | python3 -c 'import json,sys; s=json.load(sys.stdin)["services"]; print(s["web"]["environment"]["CORE_INTERNAL_URL"], s["web"]["extra_hosts"][0] if isinstance(s["web"].get("extra_hosts"), list) else sorted(s["web"]["extra_hosts"].items())[0], [p.get("host_ip")+":"+str(p.get("published")) for n in ("sidecar","searxng") for p in s[n].get("ports",[])])' 2>&1)"
  check "native core: web reaches the host JVM; sidecar and searxng are published on loopback" "$(has "$nweb" 'http://host.docker.internal:8081' && has "$nweb" '127.0.0.1:8001' && has "$nweb" '127.0.0.1:8888'; echo $?)" "native env: $nweb"
  new_home
  "$CLI" personal --build --cloud --search --profile tools --browser --dry-run >/dev/null 2>&1
  runner="$("$CLI" compose config --format json 2>/dev/null | python3 -c '
import json,sys
c=json.load(sys.stdin); s=c["services"]; r=s.get("mcp-stdio-runner")
e=s["core"]["environment"]
print("runner" if r else "no-runner", e["MCP_STDIO_SIDECAR_ENABLED"], "secret" if e["MCP_STDIO_SIDECAR_SHARED_SECRET"] else "no-secret", e["MCP_STDIO_SIDECAR_URL"],
      sorted(r["networks"]) if r else "-", sorted(s["core"]["networks"]), "ports" if r and r.get("ports") else "no-ports", "volumes" if r and r.get("volumes") else "no-volumes",
      r["deploy"]["resources"]["limits"]["memory"] if r else "-")')"
  # browser-net is core's side of the browser runner (day-one --browser default);
  # sandbox-net is the internal link to tool-sandbox (agent-authored tools ride `tools`).
  check "the runner is on only its own network, publishes nothing, mounts nothing, and core relays to it" "$([ "$runner" = "runner true secret mcp-stdio-runner:8097 ['mcp-stdio-net'] ['browser-net', 'default', 'mcp-stdio-net', 'sandbox-net', 'search-net'] no-ports no-volumes 805306368" ]; echo $?)" "runner: $runner"
  sandbox="$("$CLI" compose config --format json 2>/dev/null | python3 -c 'import json,sys; s=json.load(sys.stdin)["services"]; e=s["core"]["environment"]; t=s.get("tool-sandbox"); print(e["TOOL_SANDBOX_ENABLED"], e["TOOL_SANDBOX_BASE_URL"], sorted(t["networks"]) if t else "-", t["deploy"]["resources"]["limits"]["memory"] if t else "-")')"
  check "tool-sandbox rides the tools profile on personal, on the internal network only" "$([ "$sandbox" = "true http://tool-sandbox:8095 ['sandbox-net'] 1073741824" ]; echo $?)" "sandbox: $sandbox"
  new_home
  "$CLI" personal --build --cloud --no-tools --dry-run >/dev/null 2>&1
  runner="$("$CLI" compose config --format json 2>/dev/null | python3 -c '
import json,sys
s=json.load(sys.stdin)["services"]
print("runner" if "mcp-stdio-runner" in s else "no-runner", s["core"]["environment"]["MCP_STDIO_SIDECAR_ENABLED"])')"
  check "--no-tools: no runner container and core spawns nothing through it" "$([ "$runner" = "no-runner false" ]; echo $?)" "runner: $runner"
  new_home
  "$CLI" personal --native-core --build --cloud --valkey --dry-run >/dev/null 2>&1
  svcs="$("$CLI" compose config --services 2>/dev/null | sort | tr '\n' ' ')"
  check "a host JVM gets no runner container" "$(! has "$svcs" mcp-stdio-runner; echo $?)" "$svcs"
  new_home
  "$CLI" personal --build --cloud --dry-run >/dev/null 2>&1
  profiles="$("$CLI" compose config --format json 2>/dev/null | python3 -c 'import json,sys; e=json.load(sys.stdin)["services"]["core"]["environment"]; print(e["SPRING_PROFILES_ACTIVE"])')"
  check "core runs the personal Spring profile without workflow" "$([ "$profiles" = "security,knowledge,graph-memory,personal" ]; echo $?)" "profiles: $profiles"
  # The container core cannot run the `os` sandbox (no bubblewrap, capabilities dropped), and a required
  # but unavailable sandbox stops core at start-up, so the container default must be `none`.
  sandbox="$("$CLI" compose config --format json 2>/dev/null | python3 -c 'import json,sys; e=json.load(sys.stdin)["services"]["core"]["environment"]; print(e["APP_MCP_STDIO_SANDBOX"], e["APP_AUTH_LOCAL_DATA_DIR"])')"
  check "container core runs no os sandbox and keeps local-auth keys on the storage volume" "$([ "$sandbox" = "none /data/storage/.auth" ]; echo $?)" "sandbox/auth dir: $sandbox"
  out="$("$CLI" make-admin someone@example.com 2>&1 </dev/null)"
  check "make-admin refuses on a personal install instead of calling Keycloak" "$(has "$out" 'no Keycloak' && has "$out" 'APP_AUTH_LOCAL_OWNER_EXTRA_ROLES'; echo $?)" "$out"
else
  echo "  skip docker is not available"
fi

echo "an earlier install is not touched by a run that only prepares files"
new_home
SHIM="$WORK/shim"; mkdir -p "$SHIM"; DOCKER_LOG="$WORK/docker.log"; : > "$DOCKER_LOG"
cat > "$SHIM/docker" <<'EOS'
#!/usr/bin/env bash
# Pretends an earlier personal install is running and records every call. Never touches real Docker.
echo "$*" >> "$DOCKER_LOG"
case "$1 $2" in
  "volume inspect") [ "${3:-}" = vegaduta-personal_postgres-data ] ;;
  "ps -q") echo deadbeef0001 ;;
  "port deadbeef0001") echo "8080/tcp -> 127.0.0.1:8080" ;;
  *) exit 0 ;;
esac
EOS
chmod +x "$SHIM/docker"
out="$(PATH="$SHIM:$PATH" DOCKER_LOG="$DOCKER_LOG" "$CLI" secrets </dev/null 2>&1)"
check "secrets gives the new install its own project name" "$([ "$(sed -n 's/^VEGADUTA_PERSONAL_PROJECT=//p' "$VEGADUTA_ENV_FILE")" = vegaduta-personal-2 ]; echo $?)" "$out"
check "secrets never stops or removes a container" "$(! grep -qE '^(stop|rm|kill|down|compose .* (down|stop|rm))' "$DOCKER_LOG"; echo $?)" "$(cat "$DOCKER_LOG")"
new_home
SHIM2="$WORK/shim"; mkdir -p "$SHIM2"; cp "$SHIM/docker" "$SHIM2/docker"; : > "$DOCKER_LOG"
out="$(PATH="$SHIM2:$PATH" DOCKER_LOG="$DOCKER_LOG" "$CLI" personal --build --cloud --dry-run </dev/null 2>&1)"
check "a dry run never stops or removes a container" "$(! grep -qE '^(stop|rm|kill|down|compose .* (down|stop|rm))' "$DOCKER_LOG"; echo $?)" "$(cat "$DOCKER_LOG")"

# The edition switch: a value the owner set is never replaced by a later run, and personal.yml feeds core and the
# console from that one variable.
new_home
"$CLI" personal --dry-run </dev/null >/dev/null 2>&1
sed -i.bak 's/^VEGADUTA_EDITION=.*/VEGADUTA_EDITION=full/' "$VEGADUTA_ENV_FILE" && rm -f "$VEGADUTA_ENV_FILE.bak"
"$CLI" personal --dry-run </dev/null >/dev/null 2>&1
check "an edition the owner chose survives a later run" "$([ "$(sed -n 's/^VEGADUTA_EDITION=//p' "$VEGADUTA_ENV_FILE")" = full ] && [ "$(grep -c '^VEGADUTA_EDITION=' "$VEGADUTA_ENV_FILE")" = 1 ]; echo $?)" "$(grep EDITION "$VEGADUTA_ENV_FILE")"
check "personal.yml derives core's tier and the console's edition from VEGADUTA_EDITION alone" "$(grep -q 'APP_EDITION_TIER: \${VEGADUTA_EDITION:-personal}' "$ROOT/deploy/compose/personal.yml" && grep -q 'NEXT_PUBLIC_EDITION: \${VEGADUTA_EDITION:-personal}' "$ROOT/deploy/compose/personal.yml"; echo $?)"

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[ "$FAIL" = 0 ]
