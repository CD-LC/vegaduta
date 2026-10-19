#!/usr/bin/env bash
# =============================================================================
# dev-up.sh — contributor spin-up (Linux/macOS/WSL/Git Bash; used by
# .devcontainer/). A thin wrapper over scripts/vegaduta.
#
#   bash scripts/dev-up.sh            # infra only: postgres, valkey, keycloak
#                                     # (lite compose), for running core/web
#                                     # yourself with hot reload
#   bash scripts/dev-up.sh --app      # lite stack built from source
#   bash scripts/dev-up.sh --all      # full stack + every profile, from source (heavy)
#   bash scripts/dev-up.sh --env-only # just write deploy/compose/.env
#
# Hot-reload loop after the default (infra) mode:
#   core:  cd core && mvn -pl app -am spring-boot:run     -> http://localhost:8080
#          with SPRING_PROFILES_ACTIVE=security,knowledge,graph-memory,lite and
#          the SAME APP_ENCRYPTION_KEY / KEYCLOAK_CLIENT_SECRET / POSTGRES_PASSWORD /
#          VALKEY_PASSWORD (as REDIS_PASSWORD) as deploy/compose/.env
#   web:   cd web && npm ci && npm run dev                -> http://localhost:3000
# =============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLI="$REPO_ROOT/scripts/vegaduta"

case "${1:-}" in
  --env-only) exec bash "$CLI" secrets ;;
  --app) exec bash "$CLI" up --lite --build ;;
  --all)
    exec bash "$CLI" up --full --build \
      --profile temporal --profile observability --profile tracing \
      --profile code-intel --profile builder --profile sandbox ;;
  "") ;;
  *) echo "Unknown flag: $1 (valid: --app, --all, --env-only)" >&2; exit 2 ;;
esac

bash "$CLI" secrets
# Only the three infra services of the lite stack (Postgres built from
# deploy/postgres); core/web run on the host.
COMPOSE="docker compose --project-directory $REPO_ROOT/deploy/compose --env-file ${VEGADUTA_ENV_FILE:-$REPO_ROOT/deploy/compose/.env} -f $REPO_ROOT/deploy/compose/lite.yml -f $REPO_ROOT/deploy/compose/lite.build.yml"
# shellcheck disable=SC2086  # COMPOSE is intentionally word-split
$COMPOSE up -d --build --wait postgres valkey keycloak
COMPOSE="$COMPOSE" bash "$REPO_ROOT/deploy/keycloak/reconcile-keycloak.sh"

cat <<'EOF'

Infra is up (loopback only):
  Postgres  localhost:5432     Valkey  localhost:6379
  Keycloak  http://localhost:8180  (admin / KEYCLOAK_ADMIN_PASSWORD in deploy/compose/.env)

Now run the apps with hot reload (see this script's header for the env vars):
  core:  cd core && mvn -pl app -am spring-boot:run
  web:   cd web && npm ci && npm run dev
Stop:    scripts/vegaduta down
EOF
