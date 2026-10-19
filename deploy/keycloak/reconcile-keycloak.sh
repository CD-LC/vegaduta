#!/usr/bin/env bash
# =============================================================================
# reconcile-keycloak.sh — idempotent post-import fixes for the platform realm
#
# `scripts/vegaduta up` runs this automatically once Keycloak is healthy.
# Manual use (repo root):
#   COMPOSE="docker compose -f deploy/compose/lite.yml --env-file deploy/compose/.env" \
#     bash deploy/keycloak/reconcile-keycloak.sh
#
# The realm import cannot carry everything signup needs:
#   1. userProfile unmanagedAttributePolicy=ADMIN_EDIT — Keycloak 24+ drops
#      undeclared attributes (incl. tenant_id) by default, which breaks every
#      tenant-scoped API call after login. ADMIN_EDIT (not ENABLED) so users
#      cannot edit their own tenant_id.
#   2. agentic-ai-core client secret = KEYCLOAK_CLIENT_SECRET (re-synced in
#      case the secret was rotated after first import).
#   3. least-privilege realm-management roles on the agentic-ai-core service
#      account (core creates users at signup and maps realm roles through the
#      Admin API); a realm-admin grant left by older installs is removed.
#   4. SMTP from KEYCLOAK_SMTP_* (optional).
#   5. Google/GitHub/Microsoft login from *_CLIENT_ID/_SECRET (optional).
#   6. First-broker-login "Review Profile" + VERIFY_PROFILE off (social
#      sign-ups land straight in the app).
# The kcadm.sh steps live in reconcile-realm.kcadm.sh and run INSIDE the
# keycloak container; nothing is exposed.
# =============================================================================
set -eu

COMPOSE="${COMPOSE:-docker compose -f deploy/compose/docker-compose.yml --env-file deploy/compose/.env}"
REALM="${KEYCLOAK_REALM:-agentic-ai}"

# shellcheck disable=SC2086  # COMPOSE is intentionally word-split
$COMPOSE exec -T \
  -e REALM="$REALM" \
  -e SH="${KEYCLOAK_SMTP_HOST:-}" -e SP="${KEYCLOAK_SMTP_PORT:-587}" \
  -e SF="${KEYCLOAK_SMTP_FROM:-}" -e SD="${KEYCLOAK_SMTP_FROM_DISPLAY:-}" \
  -e SU="${KEYCLOAK_SMTP_USER:-}" -e SW="${KEYCLOAK_SMTP_PASSWORD:-}" \
  -e ST="${KEYCLOAK_SMTP_STARTTLS:-true}" \
  -e GGID="${GOOGLE_CLIENT_ID:-}" -e GGSEC="${GOOGLE_CLIENT_SECRET:-}" \
  -e GHID="${GITHUB_CLIENT_ID:-}" -e GHSEC="${GITHUB_CLIENT_SECRET:-}" \
  -e MSID="${MICROSOFT_CLIENT_ID:-}" -e MSSEC="${MICROSOFT_CLIENT_SECRET:-}" \
  keycloak bash -s < "$(dirname "$0")/reconcile-realm.kcadm.sh"
