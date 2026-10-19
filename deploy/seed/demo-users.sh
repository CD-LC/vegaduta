#!/usr/bin/env bash
#
# Creates a demo tenant and a set of demo users through the REAL API, so the
# result is a tenant you can actually log into and show someone.
#
#   deploy/seed/demo-users.sh
#
# WHY NOT seed.sh
# ---------------
# `deploy/seed/seed.sh` is a CI fixture: it inserts tenant rows with SQL and
# runs Flyway by hand, deliberately bypassing the app's HTTP layer because it
# has to run before any authenticated call exists. That is right for CI and
# wrong for a demo. This script goes through `POST /api/signup` and
# `POST /api/admin/users` instead, so TenantProvisioningService does the
# provisioning, Keycloak gets the users the way it normally would, roles are
# assigned through the real endpoint, and anything that would break for a real
# signup breaks here too — which is the point of a demo.
#
# WHAT GETS CREATED
# -----------------
#   1. A tenant, via signup, with its tenant-admin.
#   2. Additional users inside that tenant, one per role in DEMO_ROLES.
#
# Every address is under `@example.test` — a reserved domain (RFC 6761) that
# can never route. A demo user therefore cannot receive real mail, which
# matters on a domain whose sending reputation is already worth protecting.
#
# ENVIRONMENT
# -----------
#   CORE_BASE_URL      required. .xyz is STAGING, .ai is PRODUCTION
#                      (repo root CLAUDE.md RULE 0 — they are separate stacks)
#   KEYCLOAK_BASE_URL  required
#   REALM              default agentic-ai
#   CLIENT_ID          default agentic-ai-web
#   DEMO_ORG           default "Demo Org <date>" — must be unique, signup
#                      returns 409 on a duplicate org name
#   DEMO_PASSWORD      default: generated and printed once at the end
#   DEMO_ROLES         default "agent-builder"; space-separated. Only role
#                      names this repo actually uses — `tenant-admin` and
#                      `agent-builder` are the two confirmed in
#                      Agentic-AI-Platform.postman_collection.json. Do not
#                      pass a role you have not verified exists in the realm.
#
# Pass --i-mean-production to confirm a non-local target you consider
# production (the script cannot tell staging from production by hostname).

set -uo pipefail

REALM="${REALM:-agentic-ai}"
CLIENT_ID="${CLIENT_ID:-agentic-ai-web}"
DEMO_ROLES="${DEMO_ROLES:-agent-builder}"
ALLOW_PROD=0
[ "${1:-}" = "--i-mean-production" ] && ALLOW_PROD=1

die() { printf 'demo-users: %s\n' "$1" >&2; exit 1; }
log() { printf '[demo-users] %s\n' "$1"; }

for v in CORE_BASE_URL KEYCLOAK_BASE_URL; do
  [ -n "${!v:-}" ] || die "missing required environment variable: ${v}
  e.g. export CORE_BASE_URL=http://localhost:8080 KEYCLOAK_BASE_URL=http://localhost:8180
       export CORE_BASE_URL=https://api.example.com KEYCLOAK_BASE_URL=https://auth.example.com"
done
command -v curl >/dev/null || die "curl is required"
command -v jq   >/dev/null || die "jq is required"

case "${CORE_BASE_URL}" in
  *localhost*|*127.0.0.1*) log "local target." ;;
  *)
    if [ "${ALLOW_PROD}" != "1" ]; then
      die "CORE_BASE_URL (${CORE_BASE_URL}) is not local. Re-run with --i-mean-production
if you really want demo accounts on that deployment."
    fi
    log "non-local target, explicitly confirmed." ;;
esac

# --- names and credentials -------------------------------------------------

STAMP="$(date +%Y%m%d-%H%M%S)"
DEMO_ORG="${DEMO_ORG:-Demo Org ${STAMP}}"

if [ -n "${DEMO_PASSWORD:-}" ]; then
  GENERATED_PW=0
else
  # A demo login still gets a real password. openssl where available, else
  # /dev/urandom — no fixed default, so a forgotten demo tenant is not a
  # known-credentials account sitting on a public host.
  if command -v openssl >/dev/null; then
    DEMO_PASSWORD="Demo!$(openssl rand -base64 12 | tr -d '/+=' | cut -c1-12)"
  else
    DEMO_PASSWORD="Demo!$(head -c 24 /dev/urandom | base64 | tr -d '/+=' | cut -c1-12)"
  fi
  GENERATED_PW=1
fi

ADMIN_EMAIL="admin-${STAMP}@example.test"

# --- 1. signup -------------------------------------------------------------

log "creating tenant \"${DEMO_ORG}\" with admin ${ADMIN_EMAIL}"
SIGNUP_BODY="$(jq -n \
  --arg org "${DEMO_ORG}" \
  --arg email "${ADMIN_EMAIL}" \
  --arg pw "${DEMO_PASSWORD}" \
  '{orgName:$org, firstName:"Demo", lastName:"Admin", adminEmail:$email, adminPassword:$pw}')"

SIGNUP_RESP="$(curl -sS -w '\n%{http_code}' --max-time 60 \
  -X POST "${CORE_BASE_URL}/api/signup" \
  -H 'Content-Type: application/json' \
  -d "${SIGNUP_BODY}" 2>&1)"
SIGNUP_CODE="$(printf '%s' "${SIGNUP_RESP}" | tail -n1)"
SIGNUP_JSON="$(printf '%s' "${SIGNUP_RESP}" | sed '$d')"

case "${SIGNUP_CODE}" in
  2*) log "tenant created." ;;
  409) die "409 — an organisation named \"${DEMO_ORG}\" already exists. Set DEMO_ORG to something else." ;;
  *)  die "signup failed (HTTP ${SIGNUP_CODE}): ${SIGNUP_JSON}" ;;
esac

# --- 2. log in as the new admin -------------------------------------------

log "authenticating as the new tenant admin"
TOKEN=""
for attempt in 1 2 3 4 5; do
  TOKEN="$(curl -sS --max-time 30 -X POST \
    "${KEYCLOAK_BASE_URL}/realms/${REALM}/protocol/openid-connect/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode "client_id=${CLIENT_ID}" \
    --data-urlencode 'grant_type=password' \
    --data-urlencode "username=${ADMIN_EMAIL}" \
    --data-urlencode "password=${DEMO_PASSWORD}" 2>/dev/null \
    | jq -r '.access_token // empty')"
  [ -n "${TOKEN}" ] && break
  # Provisioning is asynchronous enough that the Keycloak user can lag the
  # signup response by a second or two. Back off rather than failing on the
  # first miss.
  log "  not ready yet (attempt ${attempt}/5), waiting..."
  sleep 3
done
[ -n "${TOKEN}" ] || die "could not log in as ${ADMIN_EMAIL} after signup. The tenant may exist but the Keycloak user did not appear — check core's logs before re-running, or you will create a second tenant."

# --- 3. additional users ---------------------------------------------------

CREATED=""
i=0
for role in ${DEMO_ROLES}; do
  i=$((i+1))
  USERNAME="demo-${role}-${STAMP}"
  EMAIL="${USERNAME}@example.test"
  log "creating ${EMAIL} with role ${role}"
  BODY="$(jq -n \
    --arg u "${USERNAME}" --arg e "${EMAIL}" --arg r "${role}" --arg pw "${DEMO_PASSWORD}" \
    '{username:$u, email:$e, firstName:"Demo", lastName:($r|ascii_upcase), temporaryPassword:$pw, initialRole:$r}')"
  RESP="$(curl -sS -w '\n%{http_code}' --max-time 45 \
    -X POST "${CORE_BASE_URL}/api/admin/users" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "${BODY}" 2>&1)"
  CODE="$(printf '%s' "${RESP}" | tail -n1)"
  JSON="$(printf '%s' "${RESP}" | sed '$d')"
  if [ "${CODE#2}" != "${CODE}" ]; then
    CREATED="${CREATED}${EMAIL}\t${role}\n"
  else
    # Keep going: one bad role name should not cost you the whole tenant.
    printf '[demo-users] FAILED for role "%s" (HTTP %s): %s\n' "${role}" "${CODE}" "${JSON}" >&2
    printf '[demo-users]   if this is "role not found", that role does not exist in realm %s.\n' "${REALM}" >&2
  fi
done

# --- 4. verify -------------------------------------------------------------

log "reading back the tenant's user list"
curl -sS --max-time 30 "${CORE_BASE_URL}/api/admin/users" \
  -H "Authorization: Bearer ${TOKEN}" 2>&1 \
  | jq -r '(if type=="array" then . else (.content // .items // []) end)[]
           | "  - \(.email // .username // "?")  \((.roles // [.initialRole // "?"]) | join(","))"' \
  2>/dev/null || log "  (could not parse the user list — not fatal, the accounts above were created)"

# --- 5. hand back the credentials -----------------------------------------

cat <<EOF

================ demo tenant ready ================
Organisation : ${DEMO_ORG}
Sign in at   : ${CORE_BASE_URL/api./app.}

Tenant admin : ${ADMIN_EMAIL}
Other users  :
$(printf '%b' "${CREATED}" | sed 's/^/  /' | expand -t2)
Password     : ${DEMO_PASSWORD}
===================================================
EOF

if [ "${GENERATED_PW}" = "1" ]; then
  cat <<'EOF'

This password was generated and is printed only here — it is not written to
any file. Copy it now. Set DEMO_PASSWORD yourself if you would rather choose.
EOF
fi

cat <<'EOF'

Every address is @example.test, a reserved domain that cannot route, so none
of these accounts can receive mail. That is intentional: it also means the
password-reset flow will not work for them. Delete the tenant when the demo
is over rather than leaving it live.
EOF
