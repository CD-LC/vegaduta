#!/usr/bin/env bash
# boot-check.sh - boot the real core jars (api, jobs, worker, and the api jar in migrate-only mode)
# against a throwaway Postgres (the project image: pgvector + Apache AGE) and Redis before shipping
# a core image or merging a change that touches Spring wiring or migrations.
#
# Why this exists: thousands of green unit tests across the core modules do
# not assemble one Spring context, and core/app's CoreApplicationTests needs a
# live DB and silently skips without one. Bugs only a real boot catches include
# duplicate @Configuration simple names, two constructors with no @Autowired,
# and a phantom duplicate Flyway version from a stale target/ (AGENTS.md rules
# 3 and 9). The jobs and worker shapes scan no REST controllers, so a component that needs a bean
# only a controller provides (ApiGuideToolSource and ApiSpecProvider, v0.1.9) boots in the api and
# crash-loops in core-jobs and core-worker: they are booted too, not just the api.
#
# What it does (about 6 minutes):
#   1. mvn -o -q -pl app,app-jobs,app-worker clean, then mvn -o -q -pl app,app-jobs,app-worker -am
#      -Dmaven.test.skip=true package (the clean is NOT optional: a stale
#      target/classes/db/migration/ once produced a phantom "Found more than one migration with
#      version 216").
#   2. starts a throwaway $BOOTCHECK_PG_IMAGE (CREATE EXTENSION vector + age)
#      and redis:7-alpine --requirepass on random free high ports.
#   3. api: java -jar core/app/target/app-*.jar with the full production Spring profile set and
#      dummy secrets, waits for "Started CoreApplication" (or a crash), probes
#      /actuator/health/readiness (200, UP, incl. tenantMigration), reports the Flyway
#      "now at version" line and the start time, and probes routes that must answer 401 to an
#      anonymous caller. The database it migrated is reused by the shapes below.
#   4. jobs, worker: the app-jobs and app-worker jars with the profiles docker-compose.yml gives
#      core-jobs and core-worker (`shape-jobs,jobs` / `worker`), against that database: wait for
#      "Started JobsApplication" / "Started WorkerApplication" and readiness UP. The worker runs
#      with the Temporal worker off (there is no Temporal server here).
#   5. migrate: the api jar with APP_TENANCY_MIGRATE_ONLY=true (the Helm migrate Job, `core-migrate`)
#      against a second, empty database: must exit 0 (75 = lock or permit not free, retried up to 6 times).
#   6. ALWAYS (trap) kills the jar and removes both containers, pass or fail.
#
# Usage:
#   bash scripts/ci/boot-check.sh [options]
#     --skip-build            reuse the existing core/app/target/app-*.jar
#     --online                drop mvn's -o (offline) flag
#     --routes "/a /b"        routes expected to answer 401 anonymously
#                             (default: /api/tutor/status /api/research/status /api/memory/export;
#                              or set BOOTCHECK_ROUTES)
#     --shapes "api migrate jobs worker"
#                             which shapes to boot, in order (default: all four; or BOOTCHECK_SHAPES).
#                             jobs and worker reuse the database the api migrated, so list api or
#                             migrate before them
#     --timeout SECONDS       max wait for each shape to start (default 360)
#     --port N                api port (default 18080, moved if busy; the other shapes use free ports)
#     --keep-log              keep the app log after a PASS too (it is always kept on failure); feed it to
#                             scripts/ci/startup-profile.sh for a phase table of the boot
#     BOOTCHECK_JAVA_OPTS     (env) extra JVM options, e.g. "-Xmx1536m"
#     BOOTCHECK_NAME_PREFIX   (env) prefix of the two throwaway container names (default "bootcheck")
#     BOOTCHECK_PORT_MIN / BOOTCHECK_PORT_MAX
#                             (env) range the random free ports are drawn from (default 40000-59999)
#     BOOTCHECK_PG_IMAGE      (env) Postgres image with pgvector + AGE
#                             (default vegaduta-postgres:local, built from deploy/postgres)
#     --flags KEY=VAL ...     extra env for every shape, e.g. a flag-on boot:
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
SHAPES="${BOOTCHECK_SHAPES:-api migrate jobs worker}"
APP_PORT=18080
KEEP_LOG_ALWAYS=0
EXTRA_FLAGS=()

while [ $# -gt 0 ]; do
  case "$1" in
    --skip-build) SKIP_BUILD=1 ;;
    --online) MVN_OFFLINE="" ;;
    --routes) shift; ROUTES="${1:-}" ;;
    --timeout) shift; START_TIMEOUT="${1:-360}" ;;
    --shapes) shift; SHAPES="${1:-}" ;;
    --port) shift; APP_PORT="${1:-18080}" ;;
    --keep-log) KEEP_LOG_ALWAYS=1 ;;
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

# ------------------------------------------------------------------ state

TMP_DIR="$(mktemp -d 2>/dev/null || mktemp -d -t bootcheck)"
APP_LOG="$TMP_DIR/app.log"
BUILD_LOG="$TMP_DIR/build.log"
: > "$APP_LOG"
RUN_ID="$$-$(date +%s)"
NAME_PREFIX="${BOOTCHECK_NAME_PREFIX:-bootcheck}"
PORT_MIN="${BOOTCHECK_PORT_MIN:-40000}"
PORT_MAX="${BOOTCHECK_PORT_MAX:-59999}"
PG_NAME="$NAME_PREFIX-pg-$RUN_ID"
REDIS_NAME="$NAME_PREFIX-redis-$RUN_ID"
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
    p=$(( PORT_MIN + (RANDOM % (PORT_MAX - PORT_MIN + 1)) ))
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

# ------------------------------------------------------------------ shapes

# Which jar each shape boots, the Spring profiles (what docker-compose.yml gives core, core-jobs and
# core-worker, plus `mcp` like the api here; STDIO MCP stays off below) and the line that says it started.
# jobs and worker need a migrated database: the api (or the migrate shape) must come first.
API_PROFILES="security,knowledge,workflow,graph-memory,mcpserver,mcp,temporal"
JOBS_PROFILES="$API_PROFILES,shape-jobs,jobs"
WORKER_PROFILES="$API_PROFILES,worker"

NEED_APP_JAR=0; NEED_JOBS_JAR=0; NEED_WORKER_JAR=0; HAVE_DB_SHAPE=0
for shape in $SHAPES; do
  case "$shape" in
    api|migrate) NEED_APP_JAR=1; HAVE_DB_SHAPE=1 ;;
    jobs) NEED_JOBS_JAR=1
      [ "$HAVE_DB_SHAPE" = 1 ] || { echo "boot-check: shape 'jobs' needs a migrated database: list 'api' or 'migrate' before it (--shapes '$SHAPES')" >&2; exit 2; } ;;
    worker) NEED_WORKER_JAR=1
      [ "$HAVE_DB_SHAPE" = 1 ] || { echo "boot-check: shape 'worker' needs a migrated database: list 'api' or 'migrate' before it (--shapes '$SHAPES')" >&2; exit 2; } ;;
    *) echo "boot-check: unknown shape '$shape' (api, migrate, jobs, worker)" >&2; exit 2 ;;
  esac
done
[ -n "$SHAPES" ] || { echo "boot-check: --shapes is empty" >&2; exit 2; }

# ------------------------------------------------------------------ build

# find_jar MODULE: core/MODULE/target/MODULE-<version>.jar, never a sources/javadoc/tests/.original jar
find_jar() {
  local f
  for f in "$CORE_DIR/$1/target/$1"-*.jar; do
    [ -f "$f" ] || continue
    case "$f" in *-sources.jar|*-javadoc.jar|*-tests.jar|*.original) continue ;; esac
    echo "$f"; return 0
  done
  return 1
}

MODULES=""
[ "$NEED_APP_JAR" = 1 ] && MODULES="app"
[ "$NEED_JOBS_JAR" = 1 ] && MODULES="${MODULES:+$MODULES,}app-jobs"
[ "$NEED_WORKER_JAR" = 1 ] && MODULES="${MODULES:+$MODULES,}app-worker"

if [ "$SKIP_BUILD" = 0 ]; then
  command -v mvn >/dev/null 2>&1 || { echo "boot-check: mvn not found on PATH" >&2; exit 2; }
  log "building: mvn ${MVN_OFFLINE:+$MVN_OFFLINE }-q -pl $MODULES clean, then package -am (tests skipped)"
  (
    cd "$CORE_DIR" || exit 1
    # shellcheck disable=SC2086
    mvn $MVN_OFFLINE -q -pl "$MODULES" clean || exit 1
    # shellcheck disable=SC2086
    mvn $MVN_OFFLINE -q -pl "$MODULES" -am "-Dmaven.test.skip=true" package || exit 1
  ) > "$BUILD_LOG" 2>&1
  if [ $? -ne 0 ]; then
    KEEP_LOG=1
    fail "maven build failed (last 40 lines of $BUILD_LOG):"
    tail -40 "$BUILD_LOG"
    echo "[boot-check] RESULT: FAIL"
    exit 1
  fi
fi
JAR=""; JOBS_JAR=""; WORKER_JAR=""
if [ "$NEED_APP_JAR" = 1 ]; then
  JAR="$(find_jar app)" || { echo "boot-check: no core/app/target/app-*.jar (run without --skip-build)" >&2; exit 2; }
  log "api jar:    ${JAR#"$REPO_ROOT"/}"
fi
if [ "$NEED_JOBS_JAR" = 1 ]; then
  JOBS_JAR="$(find_jar app-jobs)" || { echo "boot-check: no core/app-jobs/target/app-jobs-*.jar (run without --skip-build, or drop 'jobs' from --shapes)" >&2; exit 2; }
  log "jobs jar:   ${JOBS_JAR#"$REPO_ROOT"/}"
fi
if [ "$NEED_WORKER_JAR" = 1 ]; then
  WORKER_JAR="$(find_jar app-worker)" || { echo "boot-check: no core/app-worker/target/app-worker-*.jar (run without --skip-build, or drop 'worker' from --shapes)" >&2; exit 2; }
  log "worker jar: ${WORKER_JAR#"$REPO_ROOT"/}"
fi

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

# Every shape's environment; SPRING_PROFILES_ACTIVE, the port and (migrate) POSTGRES_DB are added per shape.
APP_ENV=(
  "DB_HOST=127.0.0.1" "DB_PORT=$PG_PORT"
  "POSTGRES_USER=agentic" "POSTGRES_PASSWORD=$PG_PASSWORD" "DB_PASSWORD=$PG_PASSWORD" "POSTGRES_DB=agentic_ai"
  "REDIS_HOST=127.0.0.1" "REDIS_PORT=$REDIS_PORT" "REDIS_PASSWORD=$REDIS_PASSWORD"
  # EncryptionService refuses to boot without a 32-byte key; a fresh random one per run.
  "APP_ENCRYPTION_KEY=$BOOT_ENCRYPTION_KEY"
  # Dummies for the vars docker-compose marks required, so the context can start.
  "KEYCLOAK_CLIENT_SECRET=bootcheck-dummy"
  # No localhost default exists for the token issuer or the public console origin (AGENTS.md rule 4):
  # with the `security` profile active the app refuses to start without them. The .invalid TLD is
  # reserved and never resolves; the issuer is only contacted when a token is validated.
  "KEYCLOAK_ISSUER_URI=https://auth.bootcheck.invalid/realms/agentic-ai"
  "APP_WEB_PUBLIC_URL=https://app.bootcheck.invalid"
  "STORAGE_ACCESS_KEY=bootcheck-dummy" "STORAGE_SECRET_KEY=bootcheck-dummy-secret"
  "LIVEKIT_API_KEY=bootcheck-dummy" "LIVEKIT_API_SECRET=bootcheck-dummy-secret-0123456789abcdef"
  "PLATFORM_ALERTS_WEBHOOK_SECRET=bootcheck-dummy"
  # TemporalConfig.workerFactory connects eagerly (there is no Temporal server here, so the worker shape runs
  # with its worker off, the way compose runs core-jobs); the stdio sidecar needs a host that is not here.
  "APP_WORKFLOW_TEMPORAL_WORKER_ENABLED=false"
  "MCP_STDIO_SIDECAR_ENABLED=false"
  "MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=always"
)
if [ ${#EXTRA_FLAGS[@]} -gt 0 ]; then
  APP_ENV+=("${EXTRA_FLAGS[@]}")
  log "extra flags: ${EXTRA_FLAGS[*]}"
fi

APP_LOG=""
SUMMARY=""
SHAPE_DB=""   # the database jobs and worker boot against (migrated by the api, else by migrate)

to_jar_arg() { local j="$1"; command -v cygpath >/dev/null 2>&1 && j="$(cygpath -m "$j")"; echo "$j"; }

# start_jar NAME JAR PROFILES PORT [KEY=VAL ...]: runs the jar in the background (APP_PID, log
# $TMP_DIR/NAME.log = APP_LOG) with APP_ENV plus the shape's profiles/port, the later KEY=VAL winning.
start_jar() {
  local name="$1" jar profiles="$3" port="$4"
  jar="$(to_jar_arg "$2")"
  shift 4
  APP_LOG="$TMP_DIR/$name.log"
  : > "$APP_LOG"
  (
    cd "$TMP_DIR" || exit 1   # keep any files the app writes out of the repo
    # shellcheck disable=SC2163
    for kv in "${APP_ENV[@]}"; do export "$kv"; done
    # SERVER_PORT wins over the shapes' application-jobs.yml / application-worker.yml server.port
    export "SPRING_PROFILES_ACTIVE=$profiles" "SERVER_PORT=$port" "JOBS_HTTP_PORT=$port" "WORKER_HTTP_PORT=$port"
    # shellcheck disable=SC2163
    for kv in "$@"; do export "$kv"; done
    # shellcheck disable=SC2086
    exec "$JAVA_BIN" ${BOOTCHECK_JAVA_OPTS:-} -jar "$jar"
  ) > "$APP_LOG" 2>&1 &
  APP_PID=$!
}

on_failure_dump() {
  KEEP_LOG=1
  echo "----- last 40 log lines ($APP_LOG) -----"
  tail -40 "$APP_LOG"
  echo "-----------------------------------------"
}

# The most useful single lines for the usual causes.
failure_causes() {
  grep -E "Caused by:|ConflictingBeanDefinition|No default constructor|Found more than one migration|FlywayException|UnsatisfiedDependency|BeanCreationException|required a bean of type" "$APP_LOG" 2>/dev/null | head -8
}

# boot_shape NAME JAR PROFILES PORT STARTED_CLASS [KEY=VAL ...]: starts the jar and waits for
# "Started STARTED_CLASS". 0 = started (the jar keeps running, APP_PID); 1 = failed (reported, jar stopped).
boot_shape() {
  local name="$1" jar="$2" profiles="$3" port="$4" cls="$5"; shift 5
  local t0 t1 deadline crashed="" started=0
  log "booting $name on :$port (waiting up to ${START_TIMEOUT}s for 'Started $cls')"
  t0="$(now_ms)"
  start_jar "$name" "$jar" "$profiles" "$port" "$@"
  deadline=$(( $(date +%s) + START_TIMEOUT ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    if grep -q "Started $cls" "$APP_LOG" 2>/dev/null; then started=1; break; fi
    if grep -q "APPLICATION FAILED TO START" "$APP_LOG" 2>/dev/null; then crashed="APPLICATION FAILED TO START"; break; fi
    if ! kill -0 "$APP_PID" 2>/dev/null; then crashed="the java process exited before the context started"; break; fi
    sleep 2
  done
  t1="$(now_ms)"
  if [ "$started" != 1 ]; then
    if [ -n "$crashed" ]; then fail "$name: $crashed"; else fail "$name: no 'Started $cls' within ${START_TIMEOUT}s"; fi
    failure_causes
    on_failure_dump
    kill_app
    SUMMARY="$SUMMARY $name=FAIL"
    return 1
  fi
  SPRING_SECS="$(sed -n "s/.*Started $cls in \([0-9.]*\) seconds.*/\1/p" "$APP_LOG" | tail -1)"
  log "$name started in $(( t1 - t0 )) ms wall clock${SPRING_SECS:+ (Spring reports ${SPRING_SECS}s)}"
  return 0
}

# probe_readiness NAME PORT [tenantMigration]: /actuator/health/readiness must answer 200 and UP
# (the readiness group can lag the "Started" line, so poll briefly); with tenantMigration the group
# must include that component, UP. Returns 1 (after fail) when not.
probe_readiness() {
  local name="$1" port="$2" want_tm="${3:-}" code="" body=""
  for _ in $(seq 1 15); do
    code="$(curl -s -o "$TMP_DIR/ready.json" -w '%{http_code}' "http://127.0.0.1:$port/actuator/health/readiness" 2>/dev/null)"
    body="$(tr -d ' \r\n' < "$TMP_DIR/ready.json" 2>/dev/null)"
    [ "$code" = "200" ] && break
    sleep 2
  done
  if [ "$code" != "200" ]; then
    fail "$name: /actuator/health/readiness answered ${code:-no response} (expected 200)"
    echo "[boot-check]   body: ${body:0:300}"
    return 1
  fi
  case "$body" in
    *'"status":"UP"'*) : ;;
    *) fail "$name: readiness is 200 but not UP: ${body:0:300}"; return 1 ;;
  esac
  if [ -z "$want_tm" ]; then
    log "$name readiness: 200 UP"
    return 0
  fi
  # Key order is not part of the contract: Jackson 3 writes the actuator's properties alphabetically, so
  # "details" can come before "status".
  if printf '%s' "$body" | grep -Eq '"tenantMigration":\{("details":\{[^}]*\},)?"status":"UP"|"tenantMigration":\{"status":"UP"'; then
    log "$name readiness: 200 UP, tenantMigration UP"
    return 0
  fi
  case "$body" in
    *'"tenantMigration"'*) fail "$name: tenantMigration is present but not UP: ${body:0:300}" ;;
    *) fail "$name: tenantMigration is missing from the readiness group: ${body:0:300}" ;;
  esac
  return 1
}

# psql_exec DB SQL...: runs statements in the throwaway postgres
psql_exec() {
  local db="$1"; shift
  local args=() sql
  for sql in "$@"; do args+=(-c "$sql"); done
  "$DOCKER" exec -e "PGPASSWORD=$PG_PASSWORD" "$PG_NAME" psql -v ON_ERROR_STOP=1 -U agentic -d "$db" "${args[@]}" >/dev/null 2>&1
}

shape_api() {
  boot_shape api "$JAR" "$API_PROFILES" "$APP_PORT" CoreApplication || return 1
  local ok=0
  SHAPE_DB="agentic_ai"
  FLYWAY_LINES="$(grep -E "now at version|Schema .* is up to date|Successfully validated" "$APP_LOG" | tail -3)"
  FLYWAY_AT="$(grep -o "now at version v[0-9.]*" "$APP_LOG" | tail -1)"
  if [ -n "$FLYWAY_AT" ]; then
    log "flyway: $FLYWAY_AT"
  else
    log "flyway: no 'now at version' line (schema already current, or Flyway logging is quiet)"
    [ -n "$FLYWAY_LINES" ] && echo "$FLYWAY_LINES" | sed 's/^/[boot-check]   /'
  fi
  probe_readiness api "$APP_PORT" tenantMigration || ok=1
  local route code
  for route in $ROUTES; do
    code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$APP_PORT$route" 2>/dev/null)"
    if [ "$code" = "401" ]; then
      log "route $route -> 401 (anonymous, as expected)"
    else
      fail "api: route $route answered ${code:-no response} to an anonymous caller (expected 401)"; ok=1
    fi
  done
  if [ "$ok" = 0 ]; then
    SUMMARY="$SUMMARY api=ok(${FLYWAY_AT:-flyway current}, ${SPRING_SECS:-?}s)"
  else
    on_failure_dump; SUMMARY="$SUMMARY api=FAIL"
  fi
  kill_app
  return $ok
}

# jobs and worker: the app-jobs / app-worker jars, which scan no REST controllers, against the migrated database
shape_background() {
  local name="$1" jar="$2" profiles="$3" cls="$4" port ok=0
  port="$(free_port)" || { fail "$name: no free port"; return 1; }
  boot_shape "$name" "$jar" "$profiles" "$port" "$cls" "POSTGRES_DB=$SHAPE_DB" || return 1
  probe_readiness "$name" "$port" || ok=1
  if [ "$ok" = 0 ]; then
    SUMMARY="$SUMMARY $name=ok(${SPRING_SECS:-?}s)"
  else
    on_failure_dump; SUMMARY="$SUMMARY $name=FAIL"
  fi
  kill_app
  return $ok
}

# migrate: the api jar with APP_TENANCY_MIGRATE_ONLY=true (the Helm migrate Job) on an empty database; it exits by itself.
shape_migrate() {
  local db="bootcheck_migrate" attempt rc port deadline
  psql_exec postgres "CREATE DATABASE $db" \
    && psql_exec "$db" "CREATE EXTENSION IF NOT EXISTS vector" "CREATE EXTENSION IF NOT EXISTS age" \
    || { fail "migrate: could not create the empty database $db"; SUMMARY="$SUMMARY migrate=FAIL"; return 1; }
  port="$(free_port)" || { fail "migrate: no free port"; return 1; }
  for attempt in 1 2 3 4 5 6; do
    log "booting migrate-only (attempt $attempt) against the empty database $db (waiting up to ${START_TIMEOUT}s for it to exit)"
    start_jar migrate "$JAR" "$API_PROFILES" "$port" "POSTGRES_DB=$db" "APP_TENANCY_MIGRATE_ONLY=true"
    deadline=$(( $(date +%s) + START_TIMEOUT ))
    while kill -0 "$APP_PID" 2>/dev/null && [ "$(date +%s)" -lt "$deadline" ]; do sleep 2; done
    if kill -0 "$APP_PID" 2>/dev/null; then
      fail "migrate: still running after ${START_TIMEOUT}s (migrate-only must exit by itself)"
      on_failure_dump; kill_app; SUMMARY="$SUMMARY migrate=FAIL"; return 1
    fi
    wait "$APP_PID"; rc=$?
    APP_PID=""
    # 75 = the advisory lock is held or no held-lock permit is free (PgAdvisoryJobLock caps the locked
    # jobs per JVM and the api's own startup ticks can take them all): retried, as the Helm Job's backoff does
    [ "$rc" = 75 ] || break
    log "migrate-only exited 75 (lock held), retrying"
    sleep 3
  done
  if [ "$rc" = 0 ] && grep -q "Migrate-only run complete" "$APP_LOG"; then
    log "migrate-only: exit 0, control plane and tenant schemas migrated on an empty database"
    [ -n "$SHAPE_DB" ] || SHAPE_DB="$db"
    SUMMARY="$SUMMARY migrate=ok"
    return 0
  fi
  fail "migrate: exited $rc (expected 0 and 'Migrate-only run complete')"
  failure_causes
  on_failure_dump
  SUMMARY="$SUMMARY migrate=FAIL"
  return 1
}

for shape in $SHAPES; do
  case "$shape" in
    api)     shape_api || FAILED=1 ;;
    migrate) shape_migrate || FAILED=1 ;;
    jobs)    shape_background jobs "$JOBS_JAR" "$JOBS_PROFILES" JobsApplication || FAILED=1 ;;
    worker)  shape_background worker "$WORKER_JAR" "$WORKER_PROFILES" WorkerApplication || FAILED=1 ;;
  esac
  # jobs and worker cannot boot without a migrated database: say so instead of cascading
  if [ "$FAILED" = 1 ] && [ -z "$SHAPE_DB" ]; then
    fail "no migrated database to boot the remaining shapes against"
    break
  fi
done

# ------------------------------------------------------------------ result

if [ "$FAILED" = 0 ]; then
  echo "[boot-check] RESULT: PASS (${SUMMARY# })"
  exit 0
fi
echo "[boot-check] shapes: ${SUMMARY# }"
echo "[boot-check] RESULT: FAIL"
exit 1
