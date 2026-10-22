#!/usr/bin/env bash
# boot-check.sh - boot the real core app jar against a throwaway Postgres
# (the project image: pgvector + Apache AGE) and Redis before shipping a core
# image or merging a change that touches Spring wiring or migrations.
#
# Why this exists: thousands of green unit tests across the core modules do
# not assemble one Spring context, and core/app's CoreApplicationTests needs a
# live DB and silently skips without one. Bugs only a real boot catches include
# duplicate @Configuration simple names, two constructors with no @Autowired,
# and a phantom duplicate Flyway version from a stale target/ (AGENTS.md rules
# 3 and 9).
#
# What it does (about 3 minutes):
#   1. mvn -o -q -pl app clean, then mvn -o -q -pl app -am -Dmaven.test.skip=true package
#      (the clean is NOT optional: a stale target/classes/db/migration/ once
#      produced a phantom "Found more than one migration with version 216").
#   2. starts a throwaway $BOOTCHECK_PG_IMAGE (CREATE EXTENSION vector + age)
#      and redis:7-alpine --requirepass on random free high ports.
#   3. java -jar core/app/target/app-*.jar with the full production Spring profile set and dummy
#      secrets, waits for "Started CoreApplication" (or a crash).
#   4. probes /actuator/health/readiness (200, UP, incl. tenantMigration),
#      reports the Flyway "now at version" line and the start time, and probes
#      routes that must answer 401 to an anonymous caller.
#   5. ALWAYS (trap) kills the jar and removes both containers, pass or fail.
#
# Usage:
#   bash scripts/ci/boot-check.sh [options]
#     --skip-build            reuse the existing core/app/target/app-*.jar
#     --online                drop mvn's -o (offline) flag
#     --routes "/a /b"        routes expected to answer 401 anonymously
#                             (default: /api/tutor/status /api/research/status /api/memory/export;
#                              or set BOOTCHECK_ROUTES)
#     --timeout SECONDS       max wait for the app to start (default 360)
#     --port N                app port (default 18080, moved if busy)
#     --keep-log              keep the app log after a PASS too (it is always kept on failure); feed it to
#                             scripts/ci/startup-profile.sh for a phase table of the boot
#     BOOTCHECK_JAVA_OPTS     (env) extra JVM options, e.g. "-Xmx1536m"
#     BOOTCHECK_PG_IMAGE      (env) Postgres image with pgvector + AGE
#                             (default vegaduta-postgres:local, built from deploy/postgres)
#     --profiles LIST         Spring profiles to boot with, comma separated (or env BOOTCHECK_PROFILES;
#                             default security,knowledge,workflow,graph-memory,mcpserver,mcp,temporal)
#     --personal              boot the one-person shape instead: profiles
#                             security,knowledge,graph-memory,personal (what scripts/vegaduta personal
#                             sets), local auth (no Keycloak dummies), local file storage and auth data under
#                             the throwaway dir, and the small-heap JVM options deploy/compose/personal.yml
#                             uses (unless BOOTCHECK_JAVA_OPTS is set). Same checks: readiness UP incl.
#                             tenantMigration and the anonymous-401 routes. Not a substitute for a real
#                             personal stack: the database user here is a superuser and Redis is redis:7.
#     --profile-startup       measurement: app.startup.profiling.enabled=true, timeline dumped to a file in the
#                             throwaway dir, then scripts/ci/startup-profile.sh prints the phase table (time to
#                             Started, Flyway, Hibernate bootstrap, bean and repository counts, static
#                             entity/repository counts of the jar). Adds a little recording overhead.
#     --conditions            measurement: boot with DEBUG=true so the CONDITIONS EVALUATION REPORT is logged,
#                             and the table lists which Spring AI MCP-server auto-configurations matched.
#                             DEBUG logging slows the boot: do not read time-to-Started from this run.
#     --info-routes "/a /b"   routes to probe once after the checks, printing the HTTP status only (never a
#                             failure), e.g. "/sse /mcp" to see whether an MCP endpoint answers (curl
#                             gives up after 3 s, so a 200 SSE stream shows as 200).
#     --flags KEY=VAL ...     extra env for the app, e.g. a flag-on boot:
#                               --flags APP_RESEARCH_DEEP_ENABLED=true APP_TUTOR_ENABLED=true
#                             (takes every following KEY=VAL word up to the next --option)
#     -h | --help
#
# Exit: 0 = PASS, 1 = a check failed, 2 = prerequisites missing / bad usage.
# Nothing here touches a real database, a real Keycloak or any deployed host.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CORE_DIR="$REPO_ROOT/core"
PG_IMAGE="${BOOTCHECK_PG_IMAGE:-vegaduta-postgres:local}"

SKIP_BUILD=0
MVN_OFFLINE="-o"
ROUTES="${BOOTCHECK_ROUTES:-/api/tutor/status /api/research/status /api/memory/export}"
START_TIMEOUT=360
APP_PORT=18080
KEEP_LOG_ALWAYS=0
DEFAULT_PROFILES="security,knowledge,workflow,graph-memory,mcpserver,mcp,temporal"
PERSONAL_PROFILES="security,knowledge,graph-memory,personal"
PROFILES="${BOOTCHECK_PROFILES:-$DEFAULT_PROFILES}"
PROFILES_EXPLICIT=0
PERSONAL=0
PROFILE_STARTUP=0
CONDITIONS=0
INFO_ROUTES=""
EXTRA_FLAGS=()

while [ $# -gt 0 ]; do
  case "$1" in
    --skip-build) SKIP_BUILD=1 ;;
    --online) MVN_OFFLINE="" ;;
    --routes) shift; ROUTES="${1:-}" ;;
    --timeout) shift; START_TIMEOUT="${1:-360}" ;;
    --port) shift; APP_PORT="${1:-18080}" ;;
    --keep-log) KEEP_LOG_ALWAYS=1 ;;
    --profiles) shift; PROFILES="${1:-}"; PROFILES_EXPLICIT=1; [ -n "$PROFILES" ] || { echo "boot-check: --profiles needs a value" >&2; exit 2; } ;;
    --personal) PERSONAL=1 ;;
    --profile-startup) PROFILE_STARTUP=1 ;;
    --conditions) CONDITIONS=1 ;;
    --info-routes) shift; INFO_ROUTES="${1:-}" ;;
    --flags)
      shift
      while [ $# -gt 0 ] && [[ "$1" != --* ]]; do
        if [[ "$1" != *=* ]]; then
          echo "boot-check: --flags expects KEY=VAL, got '$1'" >&2
          exit 2
        fi
        EXTRA_FLAGS+=("$1")
        shift
      done
      continue
      ;;
    -h|--help) sed -n '2,/^set -uo/p' "${BASH_SOURCE[0]}" | sed '$d' | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "boot-check: unknown option '$1' (see --help)" >&2; exit 2 ;;
  esac
  shift
done

[ -n "${BOOTCHECK_PROFILES:-}" ] && PROFILES_EXPLICIT=1
if [ "$PERSONAL" = 1 ] && [ "$PROFILES_EXPLICIT" = 0 ]; then PROFILES="$PERSONAL_PROFILES"; fi
if [ "$PERSONAL" = 1 ] && [ -z "${BOOTCHECK_JAVA_OPTS:-}" ]; then
  # The heap and GC flags deploy/compose/personal.yml gives core (JAVA_TOOL_OPTIONS), so the timing is the
  # small-box one. Keep in sync with that file.
  BOOTCHECK_JAVA_OPTS="-XX:InitialRAMPercentage=25 -XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=128m -Djava.net.preferIPv4Stack=true"
fi

# ------------------------------------------------------------------ state

TMP_DIR="$(mktemp -d 2>/dev/null || mktemp -d -t bootcheck)"
APP_LOG="$TMP_DIR/app.log"
BUILD_LOG="$TMP_DIR/build.log"
: > "$APP_LOG"
RUN_ID="$$-$(date +%s)"
PG_NAME="bootcheck-pg-$RUN_ID"
REDIS_NAME="bootcheck-redis-$RUN_ID"
DOCKER=""
APP_PID=""
STARTED_PG=0
STARTED_REDIS=0
FAILED=0
KEEP_LOG="$KEEP_LOG_ALWAYS"

now_ms() {
  local t
  t="$(date +%s%3N 2>/dev/null)"
  case "$t" in
    ''|*[!0-9]*) echo $(( $(date +%s) * 1000 )) ;;
    *) echo "$t" ;;
  esac
}

log()  { echo "[boot-check] $*"; }
fail() { echo "[boot-check] FAIL: $*"; FAILED=1; }

# ---------------------------------------------------------------- cleanup
# Runs on EVERY exit path (success, failed check, Ctrl-C, set -u slip): the jar
# is killed and both containers are removed. Nothing is left running.

kill_app() {
  [ -n "$APP_PID" ] || return 0
  if kill -0 "$APP_PID" 2>/dev/null; then
    # Git Bash: the java.exe child is not a descendant in the MSYS sense, so
    # take the Windows process tree down by pid as well.
    if command -v taskkill >/dev/null 2>&1 && [ -r "/proc/$APP_PID/winpid" ]; then
      taskkill //F //T //PID "$(cat "/proc/$APP_PID/winpid")" >/dev/null 2>&1 || true
    fi
    pkill -TERM -P "$APP_PID" 2>/dev/null || true
    kill -TERM "$APP_PID" 2>/dev/null || true
    for _ in 1 2 3 4 5 6 7 8 9 10; do
      kill -0 "$APP_PID" 2>/dev/null || break
      sleep 1
    done
    if kill -0 "$APP_PID" 2>/dev/null; then
      pkill -KILL -P "$APP_PID" 2>/dev/null || true
      kill -KILL "$APP_PID" 2>/dev/null || true
    fi
  fi
  wait "$APP_PID" 2>/dev/null || true
  APP_PID=""
}

cleanup() {
  local rc=$?
  trap - EXIT INT TERM HUP
  kill_app
  if [ -n "$DOCKER" ]; then
    [ "$STARTED_PG" = 1 ] && "$DOCKER" rm -f "$PG_NAME" >/dev/null 2>&1
    [ "$STARTED_REDIS" = 1 ] && "$DOCKER" rm -f "$REDIS_NAME" >/dev/null 2>&1
  fi
  if [ "$KEEP_LOG" = 1 ]; then
    echo "[boot-check] logs kept in $TMP_DIR"
  else
    rm -rf "$TMP_DIR" 2>/dev/null
  fi
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP

# ----------------------------------------------------------- prerequisites

find_docker() {
  # Several docker clients can be present at once (this host has Rancher Desktop on PATH
  # AND a per-user Docker Desktop that is not on PATH). The engine behind the first one
  # may be stopped, so prefer the first candidate whose engine actually answers, and fall
  # back to the first candidate found so the caller can report "not running".
  local c first="" onpath
  onpath="$(command -v docker 2>/dev/null || true)"
  for c in "$onpath"     /c/Users/*/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe     "/c/Program Files/Docker/Docker/resources/bin/docker.exe"     "/c/Users/"*"/AppData/Local/Programs/Rancher Desktop/resources/resources/win32/bin/docker.exe"     "/c/Program Files/Rancher Desktop/resources/resources/win32/bin/docker.exe"     /usr/local/bin/docker /usr/bin/docker; do
    [ -n "$c" ] && [ -x "$c" ] || continue
    [ -n "$first" ] || first="$c"
    if "$c" info >/dev/null 2>&1; then echo "$c"; return 0; fi
  done
  if [ -n "$first" ]; then echo "$first"; return 0; fi
  return 1
}

java_major() {
  # "java -version" prints e.g.  openjdk version "21.0.4"  /  "24"  /  "1.8.0_402"
  local v first second
  v="$("$1" -version 2>&1 | sed -n 's/.*version "\([0-9]*\)\.\{0,1\}\([0-9]*\).*/\1 \2/p' | head -1)"
  first="${v%% *}"; second="${v##* }"
  if [ "$first" = "1" ]; then echo "$second"; else echo "$first"; fi
}

pick_java_home() {
  local c m
  for c in "${JAVA_HOME:-}" "/c/Program Files/Java/jdk-24" "/c/Program Files/Java/jdk-21" \
           /c/Program\ Files/Eclipse\ Adoptium/jdk-24* /c/Program\ Files/Eclipse\ Adoptium/jdk-21* \
           /usr/lib/jvm/java-24* /usr/lib/jvm/java-21* /usr/lib/jvm/temurin-24* /usr/lib/jvm/temurin-21*; do
    [ -n "$c" ] && [ -x "$c/bin/java" ] || [ -x "$c/bin/java.exe" ] || continue
    m="$(java_major "$c/bin/java")"
    if [ -n "$m" ] && [ "$m" -ge 21 ] 2>/dev/null; then echo "$c"; return 0; fi
  done
  return 1
}

port_in_use() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

free_port() {
  local p tries=0
  while [ $tries -lt 200 ]; do
    p=$(( 40000 + (RANDOM % 20000) ))
    if ! port_in_use "$p"; then echo "$p"; return 0; fi
    tries=$((tries + 1))
  done
  return 1
}

DOCKER="$(find_docker)" || { echo "boot-check: docker not found (PATH, Docker Desktop, Rancher Desktop)" >&2; DOCKER=""; exit 2; }
"$DOCKER" info >/dev/null 2>&1 || { echo "boot-check: docker engine at '$DOCKER' is not running" >&2; exit 2; }
"$DOCKER" image inspect "$PG_IMAGE" >/dev/null 2>&1 || {
  echo "boot-check: image $PG_IMAGE is not on this docker engine." >&2
  echo "  It must be the project image (pgvector + Apache AGE); pgvector/pgvector:pg16 lacks AGE and" >&2
  echo "  V44__graph_memory.sql fails. Build it:  docker build -t $PG_IMAGE deploy/postgres" >&2
  echo "  (Docker Desktop and Rancher Desktop are separate engines - check the one in use.)" >&2
  exit 2
}
command -v curl >/dev/null 2>&1 || { echo "boot-check: curl not found" >&2; exit 2; }

JAVA_HOME_PICKED="$(pick_java_home)" || { echo "boot-check: no JDK >= 21 found (set JAVA_HOME, or install /c/Program Files/Java/jdk-24 or jdk-21)" >&2; exit 2; }
export JAVA_HOME="$JAVA_HOME_PICKED"
JAVA_BIN="$JAVA_HOME/bin/java"
[ -x "$JAVA_BIN" ] || JAVA_BIN="$JAVA_HOME/bin/java.exe"
log "docker: $DOCKER"
log "java:   $JAVA_HOME (major $(java_major "$JAVA_BIN"))"

# ------------------------------------------------------------------ build

JAR=""
find_jar() {
  local f
  for f in "$CORE_DIR"/app/target/app-*.jar; do
    [ -f "$f" ] || continue
    case "$f" in *-sources.jar|*-javadoc.jar|*-tests.jar|*.original) continue ;; esac
    echo "$f"; return 0
  done
  return 1
}

if [ "$SKIP_BUILD" = 0 ]; then
  command -v mvn >/dev/null 2>&1 || { echo "boot-check: mvn not found on PATH" >&2; exit 2; }
  log "building: mvn ${MVN_OFFLINE:+$MVN_OFFLINE }-q -pl app clean, then package -am (tests skipped)"
  (
    cd "$CORE_DIR" || exit 1
    # shellcheck disable=SC2086
    mvn $MVN_OFFLINE -q -pl app clean || exit 1
    # shellcheck disable=SC2086
    mvn $MVN_OFFLINE -q -pl app -am "-Dmaven.test.skip=true" package || exit 1
  ) > "$BUILD_LOG" 2>&1
  if [ $? -ne 0 ]; then
    KEEP_LOG=1
    fail "maven build failed (last 40 lines of $BUILD_LOG):"
    tail -40 "$BUILD_LOG"
    echo "[boot-check] RESULT: FAIL"
    exit 1
  fi
fi
JAR="$(find_jar)" || { echo "boot-check: no core/app/target/app-*.jar (run without --skip-build)" >&2; exit 2; }
log "jar:    ${JAR#"$REPO_ROOT"/}"

# --------------------------------------------------------- infrastructure

PG_PORT="$(free_port)" || { echo "boot-check: no free port for postgres" >&2; exit 2; }
REDIS_PORT="$(free_port)"; while [ "$REDIS_PORT" = "$PG_PORT" ]; do REDIS_PORT="$(free_port)"; done
if port_in_use "$APP_PORT"; then
  OLD_PORT="$APP_PORT"; APP_PORT="$(free_port)"
  log "port $OLD_PORT busy, using $APP_PORT for the app"
fi

PG_PASSWORD="bootcheck-pg-pw"
# Throwaway encryption key, fresh per run (base64 of 32 random bytes); nothing encrypted with it outlives the run.
BOOT_ENCRYPTION_KEY="$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
REDIS_PASSWORD="bootcheck-redis-pw"

log "starting throwaway postgres ($PG_NAME on :$PG_PORT) and redis ($REDIS_NAME on :$REDIS_PORT)"
STARTED_PG=1
"$DOCKER" run -d --name "$PG_NAME" -p "127.0.0.1:$PG_PORT:5432" \
  -e POSTGRES_USER=agentic -e POSTGRES_PASSWORD="$PG_PASSWORD" -e POSTGRES_DB=agentic_ai \
  "$PG_IMAGE" postgres -c shared_preload_libraries=age >/dev/null \
  || { fail "could not start $PG_IMAGE"; KEEP_LOG=1; exit 1; }
STARTED_REDIS=1
"$DOCKER" run -d --name "$REDIS_NAME" -p "127.0.0.1:$REDIS_PORT:6379" \
  redis:7-alpine redis-server --requirepass "$REDIS_PASSWORD" >/dev/null \
  || { fail "could not start redis:7-alpine"; KEEP_LOG=1; exit 1; }

# The postgres entrypoint runs a temporary server and then restarts, so retry
# until the extensions can really be created (they persist across the restart).
EXT_OK=0
for _ in $(seq 1 90); do
  if "$DOCKER" exec "$PG_NAME" psql -v ON_ERROR_STOP=1 -U agentic -d agentic_ai \
       -c "CREATE EXTENSION IF NOT EXISTS vector;" -c "CREATE EXTENSION IF NOT EXISTS age;" >/dev/null 2>&1; then
    EXT_OK=1; break
  fi
  sleep 1
done
if [ "$EXT_OK" != 1 ]; then
  KEEP_LOG=1
  fail "postgres never accepted CREATE EXTENSION vector / age (is $PG_IMAGE the pgvector+AGE image?)"
  "$DOCKER" logs --tail 30 "$PG_NAME" 2>&1
  echo "[boot-check] RESULT: FAIL"
  exit 1
fi
log "postgres ready, extensions vector + age created"

# -------------------------------------------------------------------- boot

APP_ENV=(
  "SPRING_PROFILES_ACTIVE=$PROFILES"
  "SERVER_PORT=$APP_PORT"
  "DB_HOST=127.0.0.1" "DB_PORT=$PG_PORT"
  "POSTGRES_USER=agentic" "POSTGRES_PASSWORD=$PG_PASSWORD" "DB_PASSWORD=$PG_PASSWORD" "POSTGRES_DB=agentic_ai"
  "REDIS_HOST=127.0.0.1" "REDIS_PORT=$REDIS_PORT" "REDIS_PASSWORD=$REDIS_PASSWORD"
  # EncryptionService refuses to boot without a 32-byte key; a fresh random one per run.
  "APP_ENCRYPTION_KEY=$BOOT_ENCRYPTION_KEY"
  # No localhost default exists for the public console origin (AGENTS.md rule 4): with the `security`
  # profile active the app refuses to start without it. The .invalid TLD is reserved and never resolves.
  "APP_WEB_PUBLIC_URL=https://app.bootcheck.invalid"
  "STORAGE_ACCESS_KEY=bootcheck-dummy" "STORAGE_SECRET_KEY=bootcheck-dummy-secret"
  "LIVEKIT_API_KEY=bootcheck-dummy" "LIVEKIT_API_SECRET=bootcheck-dummy-secret-0123456789abcdef"
  "PLATFORM_ALERTS_WEBHOOK_SECRET=bootcheck-dummy"
  # TemporalConfig.workerFactory connects eagerly; the stdio sidecar needs a host that is not here.
  "APP_WORKFLOW_TEMPORAL_WORKER_ENABLED=false"
  "MCP_STDIO_SIDECAR_ENABLED=false"
  "MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=always"
)
if [ "$PERSONAL" = 1 ]; then
  # The personal shape has no Keycloak: application-personal.yml supplies a non-resolving issuer URN, so
  # the Keycloak dummies are deliberately NOT set (setting them would hide a personal-only startup failure).
  # Everything it writes goes under the throwaway dir, like deploy/compose/personal.yml's /data/storage.
  APP_ENV+=(
    "APP_AUTH_LOCAL_DATA_DIR=$TMP_DIR/auth"
    "APP_STORAGE_TYPE=local" "APP_STORAGE_LOCAL_PATH=$TMP_DIR/storage"
    "APP_MCP_STDIO_SANDBOX=none"
  )
  log "shape: personal (profiles $PROFILES, local auth, local storage, no Keycloak dummies)"
else
  # Dummies for the vars docker-compose marks required, so the context can start. The issuer is
  # only contacted when a token is validated.
  APP_ENV+=(
    "KEYCLOAK_CLIENT_SECRET=bootcheck-dummy"
    "KEYCLOAK_ISSUER_URI=https://auth.bootcheck.invalid/realms/agentic-ai"
  )
fi
STARTUP_DUMP="$TMP_DIR/startup-timeline.json"
if [ "$PROFILE_STARTUP" = 1 ]; then
  APP_ENV+=("APP_STARTUP_PROFILING_ENABLED=true" "APP_STARTUP_PROFILING_DUMP_FILE=$STARTUP_DUMP")
  log "measuring: start-up profiling on (timeline -> $STARTUP_DUMP)"
fi
if [ "$CONDITIONS" = 1 ]; then
  APP_ENV+=("DEBUG=true")
  log "measuring: DEBUG=true for the conditions report (do not read time-to-Started from this run)"
fi
if [ ${#EXTRA_FLAGS[@]} -gt 0 ]; then
  APP_ENV+=("${EXTRA_FLAGS[@]}")
  log "extra flags: ${EXTRA_FLAGS[*]}"
fi

JAR_ARG="$JAR"
command -v cygpath >/dev/null 2>&1 && JAR_ARG="$(cygpath -m "$JAR")"

log "booting the jar on :$APP_PORT (waiting up to ${START_TIMEOUT}s for 'Started CoreApplication')"
T0="$(now_ms)"
(
  cd "$TMP_DIR" || exit 1   # keep any files the app writes out of the repo
  for kv in "${APP_ENV[@]}"; do export "$kv"; done
  # shellcheck disable=SC2086
  exec "$JAVA_BIN" ${BOOTCHECK_JAVA_OPTS:-} -jar "$JAR_ARG"
) > "$APP_LOG" 2>&1 &
APP_PID=$!

STARTED=0
CRASHED=""
DEADLINE=$(( $(date +%s) + START_TIMEOUT ))
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  if grep -q "Started CoreApplication" "$APP_LOG" 2>/dev/null; then STARTED=1; break; fi
  if grep -q "APPLICATION FAILED TO START" "$APP_LOG" 2>/dev/null; then CRASHED="APPLICATION FAILED TO START"; break; fi
  if ! kill -0 "$APP_PID" 2>/dev/null; then CRASHED="the java process exited before the context started"; break; fi
  sleep 2
done
T1="$(now_ms)"

on_failure_dump() {
  KEEP_LOG=1
  echo "----- last 40 log lines ($APP_LOG) -----"
  tail -40 "$APP_LOG"
  echo "-----------------------------------------"
}

if [ "$STARTED" != 1 ]; then
  if [ -n "$CRASHED" ]; then fail "$CRASHED"; else fail "no 'Started CoreApplication' within ${START_TIMEOUT}s"; fi
  # The most useful single lines for the usual causes.
  grep -E "Caused by:|ConflictingBeanDefinition|No default constructor|Found more than one migration|FlywayException|UnsatisfiedDependency|BeanCreationException" "$APP_LOG" 2>/dev/null | head -8
  on_failure_dump
  echo "[boot-check] RESULT: FAIL"
  exit 1
fi

SPRING_SECS="$(sed -n 's/.*Started CoreApplication in \([0-9.]*\) seconds.*/\1/p' "$APP_LOG" | tail -1)"
log "started in $(( T1 - T0 )) ms wall clock${SPRING_SECS:+ (Spring reports ${SPRING_SECS}s)}"

# ------------------------------------------------------------------ checks

FLYWAY_LINES="$(grep -E "now at version|Schema .* is up to date|Successfully validated" "$APP_LOG" | tail -3)"
FLYWAY_AT="$(grep -o "now at version v[0-9.]*" "$APP_LOG" | tail -1)"
if [ -n "$FLYWAY_AT" ]; then
  log "flyway: $FLYWAY_AT"
else
  log "flyway: no 'now at version' line (schema already current, or Flyway logging is quiet)"
  [ -n "$FLYWAY_LINES" ] && echo "$FLYWAY_LINES" | sed 's/^/[boot-check]   /'
fi

BASE="http://127.0.0.1:$APP_PORT"

# Readiness: poll briefly (the readiness group can lag the "Started" line).
READY_CODE=""
READY_BODY=""
for _ in $(seq 1 15); do
  READY_CODE="$(curl -s -o "$TMP_DIR/ready.json" -w '%{http_code}' "$BASE/actuator/health/readiness" 2>/dev/null)"
  READY_BODY="$(tr -d ' \r\n' < "$TMP_DIR/ready.json" 2>/dev/null)"
  [ "$READY_CODE" = "200" ] && break
  sleep 2
done
if [ "$READY_CODE" != "200" ]; then
  fail "/actuator/health/readiness answered ${READY_CODE:-no response} (expected 200)"
  echo "[boot-check]   body: ${READY_BODY:0:300}"
else
  case "$READY_BODY" in
    *'"status":"UP"'*) : ;;
    *) fail "readiness is 200 but not UP: ${READY_BODY:0:300}" ;;
  esac
  # Key order is not part of the contract: Jackson 3 writes the actuator's properties alphabetically, so
  # "details" can come before "status".
  if printf '%s' "$READY_BODY" | grep -Eq '"tenantMigration":\{("details":\{[^}]*\},)?"status":"UP"|"tenantMigration":\{"status":"UP"'; then
    log "readiness: 200 UP, tenantMigration UP"
  else
    case "$READY_BODY" in
      *'"tenantMigration"'*) fail "tenantMigration is present but not UP: ${READY_BODY:0:300}" ;;
      *) fail "tenantMigration is missing from the readiness group: ${READY_BODY:0:300}" ;;
    esac
  fi
fi

for route in $ROUTES; do
  code="$(curl -s -o /dev/null -w '%{http_code}' "$BASE$route" 2>/dev/null)"
  if [ "$code" = "401" ]; then
    log "route $route -> 401 (anonymous, as expected)"
  else
    fail "route $route answered ${code:-no response} to an anonymous caller (expected 401)"
  fi
done

# ------------------------------------------------------------- measurements

for route in $INFO_ROUTES; do
  code="$(curl -s -o /dev/null -m 3 -w '%{http_code}' "$BASE$route" 2>/dev/null || true)"
  log "info route $route -> ${code:-no response} (probe only, not a check)"
done

if [ "$PROFILE_STARTUP" = 1 ] || [ "$CONDITIONS" = 1 ]; then
  if [ "$PROFILE_STARTUP" = 1 ]; then
    # The timeline is written at ApplicationReadyEvent, which can land a moment after readiness answers.
    for _ in $(seq 1 15); do
      [ -s "$STARTUP_DUMP" ] && break
      sleep 2
    done
  fi
  PROFILE_ARGS=(--log "$APP_LOG" --jar "$JAR")
  [ -s "$STARTUP_DUMP" ] && PROFILE_ARGS+=(--dump "$STARTUP_DUMP")
  echo "[boot-check] ===== measurements (profiles: $PROFILES) ====="
  bash "$SCRIPT_DIR/startup-profile.sh" "${PROFILE_ARGS[@]}" || log "startup-profile.sh could not print its table"
  echo "[boot-check] ================================================"
fi

# ------------------------------------------------------------------ result

if [ "$FAILED" = 0 ]; then
  echo "[boot-check] RESULT: PASS (${FLYWAY_AT:-flyway current}, ${SPRING_SECS:-?}s to start)"
  exit 0
fi
on_failure_dump
echo "[boot-check] RESULT: FAIL"
exit 1
