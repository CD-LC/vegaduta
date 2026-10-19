#!/usr/bin/env bash
# B9-PR1 — seed runner for the full-stack CI job (.github/workflows/stack.yml).
#
# Provisions two tenant schemas the SAME way TenantProvisioningService does
# at real signup time (see core/tenancy/TenantProvisioningService.java +
# TenantSchemaMigrator.java) — minus the app's own HTTP layer, since this
# runs before/independently of any authenticated call:
#   1. Run the REAL tenant/ Flyway migrations (core/app/.../db/migration/
#      tenant) against a fresh Postgres schema, via the official flyway
#      Docker image — reuses the exact migration source of truth instead of
#      hand-duplicating table DDL here (this repo's global rule: "every new
#      table is a Flyway migration", which cuts both ways — a seed script
#      re-deriving that DDL by hand would drift the moment a new tenant
#      migration lands).
#   2. Insert control_plane.tenant / platform_hosted_llm_provider rows
#      (control-plane-seed.sql).
#   3. Insert business-data fixtures into the tenant schema (seed.sql /
#      tenant-byok-seed.sql).
#   4. Create a matching Keycloak user (same shared "agentic-ai" realm
#      every tenant uses — see Tenant.java's javadoc) with a
#      `tenant_id` attribute set to the schema name itself (NOT the
#      tenant's UUID — confirmed against TenantSchemaResolver.
#      currentSchemaName(), which sanitizes the JWT's tenant_id claim
#      directly as a schema name) and the tenant-admin realm role, so the
#      smoke-curl step in stack.yml can actually log in.
#
# Idempotent: every SQL statement is ON CONFLICT/WHERE NOT EXISTS-guarded
# (see the .sql files), and this script checks for an existing Keycloak
# user by username before creating one. Safe to re-run against an
# already-seeded stack (stack.yml's nightly cron does exactly that, against
# a fresh stack each run, but a local `bash deploy/seed/seed.sh` re-run
# against a still-running stack should also be a safe no-op).
#
# Standing caveat matching every other doc in this repo: this script has
# never been executed against a real Docker daemon in-sandbox (no Docker
# access in the remote-devices bridge environment) — only written and
# manually cross-checked against the live Flyway migrations, entity
# columns, and Keycloak realm export it depends on. `bash -n` syntax-
# checked and reviewed line by line, not run for real. Flag any failure
# clearly; it is expected that the first real CI run surfaces something
# this review missed.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${DEPLOY_DIR}/.." && pwd)"
TENANT_MIGRATIONS_DIR="${REPO_ROOT}/core/app/src/main/resources/db/migration/tenant"

# --- Connection config (matches deploy/docker-compose.yml's published host
# ports and .env.example defaults; override via env for a non-default
# deploy/.env). Deliberately talks to Postgres directly (5432), not through
# pgbouncer (6432) — Flyway's advisory locks / DDL work best against a real
# session-mode connection, not a transaction-pooled one, and this is a
# one-shot admin script, not app traffic pgbouncer needs to protect
# Postgres from.
PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGDATABASE="${POSTGRES_DB:-agentic_ai}"
PGUSER="${POSTGRES_USER:-agentic}"
export PGPASSWORD="${POSTGRES_PASSWORD:-changeme}"

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8180}"
KEYCLOAK_REALM="${KEYCLOAK_REALM:-agentic-ai}"
KEYCLOAK_ADMIN="${KEYCLOAK_ADMIN:-admin}"
KEYCLOAK_ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-changeme}"

# Pinned major.minor, same convention every other image in
# deploy/docker-compose.yml follows (see that file's Keycloak/Redpanda
# comments) — check hub.docker.com/r/flyway/flyway/tags before bumping.
FLYWAY_IMAGE="flyway/flyway:10-alpine"

SEED_USER_PASSWORD="${SEED_USER_PASSWORD:-SeedFixture!2026}"

log() { echo "[seed.sh] $*"; }

psql_run() {
  psql -h "${PGHOST}" -p "${PGPORT}" -U "${PGUSER}" -d "${PGDATABASE}" -v ON_ERROR_STOP=1 "$@"
}

migrate_tenant_schema() {
  local schema="$1"
  log "Flyway-migrating tenant schema '${schema}' (classpath:db/migration/tenant equivalent)..."
  docker run --rm --network host \
    -v "${TENANT_MIGRATIONS_DIR}:/flyway/sql:ro" \
    "${FLYWAY_IMAGE}" \
    -url="jdbc:postgresql://${PGHOST}:${PGPORT}/${PGDATABASE}" \
    -user="${PGUSER}" -password="${PGPASSWORD}" \
    -schemas="${schema}" -createSchemas=true \
    -baselineOnMigrate=false -outOfOrder=true \
    migrate
}

create_keycloak_user() {
  local schema="$1" email="$2" first="$3" last="$4" org="$5"

  local admin_token
  admin_token="$(curl -sf -X POST "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
    -d "client_id=admin-cli" -d "grant_type=password" \
    -d "username=${KEYCLOAK_ADMIN}" -d "password=${KEYCLOAK_ADMIN_PASSWORD}" \
    | jq -r '.access_token')"

  local existing_id
  existing_id="$(curl -sf -H "Authorization: Bearer ${admin_token}" \
    "${KEYCLOAK_URL}/admin/realms/${KEYCLOAK_REALM}/users?username=${email}&exact=true" \
    | jq -r '.[0].id // empty')"

  local user_id
  if [[ -n "${existing_id}" ]]; then
    log "Keycloak user '${email}' already exists (${existing_id}) - reusing (idempotent re-run)."
    user_id="${existing_id}"
  else
    log "Creating Keycloak user '${email}' for tenant schema '${schema}'..."
    curl -sf -X POST "${KEYCLOAK_URL}/admin/realms/${KEYCLOAK_REALM}/users" \
      -H "Authorization: Bearer ${admin_token}" -H "Content-Type: application/json" \
      -d "{
        \"username\": \"${email}\", \"email\": \"${email}\", \"enabled\": true,
        \"emailVerified\": true,
        \"firstName\": \"${first}\", \"lastName\": \"${last}\",
        \"attributes\": {\"tenant_id\": [\"${schema}\"], \"organization_name\": [\"${org}\"]},
        \"credentials\": [{\"type\": \"password\", \"value\": \"${SEED_USER_PASSWORD}\", \"temporary\": false}]
      }"
    user_id="$(curl -sf -H "Authorization: Bearer ${admin_token}" \
      "${KEYCLOAK_URL}/admin/realms/${KEYCLOAK_REALM}/users?username=${email}&exact=true" \
      | jq -r '.[0].id')"
  fi

  local role_repr
  role_repr="$(curl -sf -H "Authorization: Bearer ${admin_token}" \
    "${KEYCLOAK_URL}/admin/realms/${KEYCLOAK_REALM}/roles/tenant-admin")"
  curl -sf -X POST "${KEYCLOAK_URL}/admin/realms/${KEYCLOAK_REALM}/users/${user_id}/role-mappings/realm" \
    -H "Authorization: Bearer ${admin_token}" -H "Content-Type: application/json" \
    -d "[${role_repr}]" >/dev/null || log "role-mapping POST for ${email} returned non-2xx (likely already assigned) - continuing."
}

log "Waiting for Postgres to accept connections..."
for i in $(seq 1 30); do
  if psql_run -c "SELECT 1" >/dev/null 2>&1; then break; fi
  sleep 2
  if [[ "${i}" -eq 30 ]]; then log "Postgres not reachable after 60s - aborting."; exit 1; fi
done

log "Seeding control-plane rows (tenants + platform_hosted_llm_provider)..."
psql_run -f "${SCRIPT_DIR}/control-plane-seed.sql"

migrate_tenant_schema "seed_free_tenant"
migrate_tenant_schema "seed_premium_tenant"

log "Seeding business-data fixtures into seed_free_tenant..."
psql_run -v schema=seed_free_tenant -f "${SCRIPT_DIR}/seed.sql"

log "Seeding stub BYOK provider row into seed_premium_tenant..."
psql_run -v schema=seed_premium_tenant -f "${SCRIPT_DIR}/tenant-byok-seed.sql"

create_keycloak_user "seed_free_tenant" "seed-free-admin@example.test" "Seed" "FreeAdmin" "Seed Free Tenant"
create_keycloak_user "seed_premium_tenant" "seed-premium-admin@example.test" "Seed" "PremiumAdmin" "Seed Premium Tenant"

log "Seed complete. Login for stack.yml's smoke curl: seed-free-admin@example.test / (SEED_USER_PASSWORD env, default set above) via client_id=agentic-ai-core."
