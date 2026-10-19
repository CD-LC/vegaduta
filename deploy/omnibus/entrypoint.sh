#!/usr/bin/env bash
# =============================================================================
# vegaduta-omnibus - the personal tier in one container.
#
# Order of business on every start:
#   1. secrets.env   generated once under /data, reused forever. Holds
#                    APP_ENCRYPTION_KEY, which must NEVER change (every stored
#                    provider key and channel secret is encrypted with it).
#   2. postgres      initdb on first boot, then serve on loopback with the AGE
#                    extension preloaded - the same flags the compose tiers use.
#   3. db + role     the application database and the least-privileged app
#                    role, via deploy/postgres/ensure-app-role.sh (the same
#                    script the compose db-roles service runs).
#   4. redis         bookworm's redis-server on loopback only (no auth - the
#                    port is not reachable from outside this container).
#   5. core          the fat jar, `personal` Spring profile, small heap.
#   6. web           the Next standalone server (the console).
#   7. supervise     first child to exit brings the container down so the
#                    restart policy can bring the whole thing back; TERM/INT
#                    fans out to every child.
#
# Environment the operator can set (docker run -e ...):
#   PUBLIC_API_URL   browser-facing API URL   (default http://localhost:8080)
#   PUBLIC_APP_URL   browser-facing console URL (informational, printed at end)
#   POSTGRES_DB      application database     (default agentic_ai)
#   PERSONAL_JAVA_OPTS_EXTRA  appended to the JVM flags (e.g. "-Xmx1g")
#   SPRING_PROFILES_ACTIVE    full override if the personal set is not wanted
# =============================================================================
set -euo pipefail
umask 077

DATA="${OMNIBUS_DATA_DIR:-/data}"
PGDATA="$DATA/pgdata"
SECRETS_FILE="$DATA/secrets.env"
POSTGRES_DB="${POSTGRES_DB:-agentic_ai}"
APP_DB_USER="${POSTGRES_APP_USER:-vegaduta_app}"
PUBLIC_API_URL="${PUBLIC_API_URL:-http://localhost:8080}"

PIDS=()
log() { printf 'omnibus: %s\n' "$*"; }

shutdown() {
  trap - TERM INT
  [ "${#PIDS[@]}" -gt 0 ] && kill "${PIDS[@]}" 2>/dev/null || true
  wait || true
  exit "${1:-0}"
}
trap 'shutdown 143' TERM
trap 'shutdown 130' INT

wait_for() { # wait_for <seconds> <label> <cmd...>
  local deadline=$((SECONDS + $1)) label=$2
  shift 2
  until "$@" >/dev/null 2>&1; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      log "ERROR: $label not ready after $1s"
      shutdown 1
    fi
    sleep 1
  done
}

# --- 1. secrets -------------------------------------------------------------
install -d -m 0700 "$DATA"
if [ ! -f "$SECRETS_FILE" ]; then
  log "first boot: generating secrets in $SECRETS_FILE (mode 600)"
  {
    printf 'POSTGRES_APP_PASSWORD=%s\n' "$(head -c 16 /dev/urandom | od -A n -t x1 | tr -d ' \n')"
    printf 'APP_ENCRYPTION_KEY=%s\n' "$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
  } > "$SECRETS_FILE"
  chmod 600 "$SECRETS_FILE"
fi
# shellcheck source=/dev/null
. "$SECRETS_FILE"
export APP_ENCRYPTION_KEY
if [ -z "${POSTGRES_APP_PASSWORD:-}" ]; then
  log "ERROR: $SECRETS_FILE exists but has no POSTGRES_APP_PASSWORD"
  exit 1
fi

# --- 2. postgres ------------------------------------------------------------
install -d -o postgres -g postgres -m 0700 "$PGDATA"
if [ ! -s "$PGDATA/PG_VERSION" ]; then
  log "first boot: initdb $PGDATA"
  # local (unix socket) trust is fine: postgres only listens on the container
  # loopback, so every TCP client still needs the app password.
  gosu postgres initdb -D "$PGDATA" --encoding=UTF-8 \
      --auth-local=trust --auth-host=scram-sha-256 >"$DATA/initdb.log" 2>&1
fi

log "starting postgres"
gosu postgres postgres -D "$PGDATA" \
    -c shared_preload_libraries=age \
    -c listen_addresses=127.0.0.1 \
    -c port=5432 >"$DATA/postgres.log" 2>&1 &
PIDS+=($!)
wait_for 120 postgres pg_isready -q -h 127.0.0.1 -p 5432

# --- 3. database + app role (same script the compose db-roles service runs) --
if ! psql -h 127.0.0.1 -U postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$POSTGRES_DB'" | grep -q 1; then
  log "creating database $POSTGRES_DB"
  createdb -h 127.0.0.1 -U postgres "$POSTGRES_DB"
fi
log "ensuring app role $APP_DB_USER"
PGUSER=postgres PGDATABASE="$POSTGRES_DB" PGHOST=127.0.0.1 \
  APP_DB_USER="$APP_DB_USER" APP_DB_PASSWORD="$POSTGRES_APP_PASSWORD" \
  /usr/local/bin/ensure-app-role.sh >/dev/null

# --- 4. redis ----------------------------------------------------------------
install -d -o redis -g redis -m 0750 "$DATA/redis"
log "starting redis (loopback only)"
gosu redis redis-server \
    --bind 127.0.0.1 --protected-mode yes --port 6379 \
    --dir "$DATA/redis" --save '' --appendonly no \
    --maxmemory 128mb --maxmemory-policy allkeys-lru \
    --daemonize no --loglevel warning >"$DATA/redis.log" 2>&1 &
PIDS+=($!)

# --- 5. core -----------------------------------------------------------------
install -d -o agentic -g agentic -m 0700 \
    "$DATA/core-home" "$DATA/core-home/.vegaduta" "$DATA/storage" "$DATA/tmp"
log "starting core (personal profile)"
gosu agentic env \
    HOME=/data/core-home \
    TMPDIR=/data/tmp \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=128m -XX:+ExitOnOutOfMemoryError -Djava.net.preferIPv4Stack=true ${PERSONAL_JAVA_OPTS_EXTRA:-}" \
    SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-security,knowledge,graph-memory,personal}" \
    DB_HOST=127.0.0.1 DB_PORT=5432 \
    POSTGRES_DB="$POSTGRES_DB" \
    POSTGRES_USER="$APP_DB_USER" \
    POSTGRES_PASSWORD="$POSTGRES_APP_PASSWORD" \
    REDIS_HOST=127.0.0.1 REDIS_PORT=6379 \
    APP_ENCRYPTION_KEY="$APP_ENCRYPTION_KEY" \
    APP_STORAGE_TYPE=local \
    APP_STORAGE_LOCAL_PATH=/data/storage \
    PUBLIC_API_URL="$PUBLIC_API_URL" \
    java -Djava.net.preferIPv4Stack=true -jar /opt/core/app.jar >"$DATA/core.log" 2>&1 &
PIDS+=($!)
log "waiting for core readiness (first boot runs migrations)"
wait_for 420 core curl -fsS http://127.0.0.1:8080/actuator/health/readiness

# --- 6. console --------------------------------------------------------------
install -d -o nextjs -g nextjs -m 0700 "$DATA/web-home"
log "starting console"
gosu nextjs env \
    HOME=/data/web-home \
    NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 HOSTNAME=0.0.0.0 \
    NEXT_PUBLIC_API_BASE_URL="$PUBLIC_API_URL" \
    CORE_INTERNAL_URL=http://127.0.0.1:8080 \
    PUBLIC_APP_URL="${PUBLIC_APP_URL:-http://localhost:3000}" \
    node /opt/web/server.js >"$DATA/web.log" 2>&1 &
PIDS+=($!)
wait_for 120 console curl -fsS -o /dev/null http://127.0.0.1:3000/

cat <<EOF
omnibus: VegaDuta is up
  console  ${PUBLIC_APP_URL:-http://localhost:3000}
  api      $PUBLIC_API_URL
  data     $DATA (database, files, secrets - back this up)
EOF

# --- 7. supervise ------------------------------------------------------------
# First exited child wins; the container then stops so Docker's restart
# policy (or the operator) brings the whole stack back together.
status=0
wait -n "${PIDS[@]}" || status=$?
log "a service exited (status $status) - stopping the rest"
shutdown "$status"
