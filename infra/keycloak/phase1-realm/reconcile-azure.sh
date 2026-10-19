#!/usr/bin/env bash
# =============================================================================
# reconcile-azure.sh — post-import reconciliation for azure-phase1's Keycloak
# -----------------------------------------------------------------------------
# Azure Container Apps equivalent of deploy/keycloak/reconcile-keycloak.sh
# (that one uses `docker exec` + kcadm.sh; Container Apps has no direct
# docker-exec equivalent worth building for a one-time script, so this calls
# the same Admin REST API directly over HTTPS instead — keycloak's ingress is
# public in phase 1).
#
# Covers 6 steps. This script used to skip reconcile-keycloak.sh's SMTP step
# ("not needed for phase-1 to function, add later if real email/social login
# matters here") — that skip is exactly why signup mail worked on the compose stack and not
# on Azure, and it is now step 6 below. Social-IdP secrets remain out of scope
# (they are per-provider secrets, not a single mailbox); step 7's
# VERIFY_PROFILE disable is deliberately NOT copied, see the note below.
#   1. unmanagedAttributePolicy=ADMIN_EDIT — without this, tenant_id (set on
#      every signup by TenantProvisioningService) is silently dropped and
#      every authenticated /api/tenant/** call 500s.
#   2. registrationAllowed=true — the realm export ships this off.
#   3. agentic-ai-core client secret <- $KEYCLOAK_CORE_CLIENT_SECRET (must
#      match the Key Vault secret Terraform generated — see
#      environments/azure-phase1/terraform.tfvars).
#   4. realm-admin role on the agentic-ai-core service account — without it,
#      core's Admin-API calls (creating a user during signup) 403.
#   5. client redirectUris/webOrigins <- agentic-ai-realm.json next to this
#      script. Added 2026-09-10: `--import-realm` is a NO-OP once the realm
#      exists in Keycloak's database, so every custom-domain host added to
#      the committed JSON after the first boot (the app./apex/admin./academy.
#      entries for your public domain) never reaches a running realm. This
#      step re-applies them without a re-import.
#   6. smtpServer — without it Keycloak cannot send VERIFY_EMAIL and every
#      email+password signup is stranded. Host/sender come from
#      KEYCLOAK_SMTP_* env vars; password read at run time from
#      $KEYCLOAK_SMTP_PASSWORD or Key Vault, never stored in this repo.
#
# DELIBERATELY NOT DONE HERE: reconcile-keycloak.sh's step 7 disables the
# VERIFY_PROFILE required action realm-wide. Do not copy that here. The
# "Update Account Information" screen users are currently stuck on is
# VERIFY_PROFILE correctly reporting that email/firstName/lastName are BLANK
# on those accounts — blanked by KeycloakAdminClient#setUserAttributes sending
# an attributes-only PUT, which Keycloak >= 24.0.4 treats as the full attribute
# set and so drops the root fields absent from the body. Turning VERIFY_PROFILE
# off would hide that data loss, not repair it; the fix belongs in core (and is
# being made there). The affected accounts still need a human-run repair — see
# the runbook.
#
# Usage:
#   KEYCLOAK_URL=https://<keycloak fqdn> \
#   KEYCLOAK_ADMIN_PASSWORD=... \
#   KEYCLOAK_CORE_CLIENT_SECRET=... \
#   bash reconcile-azure.sh
#
# Idempotent: safe to run repeatedly. Requires the keycloak container app to
# be up and the realm already imported (--import-realm on first boot).
# =============================================================================
set -euo pipefail

: "${KEYCLOAK_URL:?set KEYCLOAK_URL (e.g. https://auth.example.com)}"
: "${KEYCLOAK_ADMIN_PASSWORD:?set KEYCLOAK_ADMIN_PASSWORD}"
: "${KEYCLOAK_CORE_CLIENT_SECRET:?set KEYCLOAK_CORE_CLIENT_SECRET}"
REALM=agentic-ai
REALM_JSON="$(cd "$(dirname "$0")" && pwd)/agentic-ai-realm.json"

command -v jq >/dev/null 2>&1 || { echo "ERROR: jq is required but not on PATH" >&2; exit 1; }

echo "Waiting for $KEYCLOAK_URL/realms/$REALM to answer..."
REALM_READY=no
for i in $(seq 1 30); do
  if curl -sf "$KEYCLOAK_URL/realms/$REALM/.well-known/openid-configuration" >/dev/null 2>&1; then
    REALM_READY=yes
    break
  fi
  sleep 5
done
if [ "$REALM_READY" != yes ]; then
  echo "ERROR: $KEYCLOAK_URL/realms/$REALM never answered — is the container app up and the realm imported?" >&2
  exit 1
fi

# Admin token. Deliberately `curl -s` (not -sf) plus an explicit check: with
# -f, a wrong KEYCLOAK_ADMIN_PASSWORD exits non-zero with NO output at all and
# `set -e` aborts silently, which reads as "the script did nothing" rather than
# "the credential is wrong". Keycloak's error body here is
# {"error":"invalid_grant","error_description":"Invalid user credentials"} —
# it echoes no secret, so it is safe to surface.
TOKEN_JSON=$(curl -s -X POST "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" \
  -d "grant_type=password" -d "client_id=admin-cli" \
  -d "username=admin" -d "password=$KEYCLOAK_ADMIN_PASSWORD") \
  || { echo "ERROR: could not reach $KEYCLOAK_URL's token endpoint" >&2; exit 1; }
TOKEN=$(printf '%s' "$TOKEN_JSON" | jq -r '.access_token // empty' | tr -d '\r')
if [ -z "$TOKEN" ]; then
  echo "ERROR: could not obtain an admin token from $KEYCLOAK_URL as user 'admin' — check KEYCLOAK_ADMIN_PASSWORD." >&2
  echo "       Keycloak said: $(printf '%s' "$TOKEN_JSON" | jq -r '.error_description // .error // "no error body"')" >&2
  exit 1
fi
auth=(-H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json")

# SECURITY (audit 2026-09-18): this MUST be ADMIN_EDIT, never ENABLED.
# ENABLED lets any signed-in user write unmanaged attributes on themselves
# through the Account API - i.e. set their own tenant_id to a different tenant
# id and read the schema of that tenant. ADMIN_EDIT still persists tenant_id
# (core only ever writes it through the Admin API) but users cannot touch it.
# 1) unmanagedAttributePolicy = ADMIN_EDIT (GET-merge-PUT — the profile endpoint
#    replaces the whole representation, same as kcadm's own update semantics).
curl -sf "${auth[@]}" "$KEYCLOAK_URL/admin/realms/$REALM/users/profile" \
  | jq '.unmanagedAttributePolicy = "ADMIN_EDIT"' \
  | curl -sf -X PUT "${auth[@]}" -d @- "$KEYCLOAK_URL/admin/realms/$REALM/users/profile"
echo "[1/6] unmanagedAttributePolicy = ADMIN_EDIT"

# 2) registrationAllowed = true (partial realm update — Keycloak merges this).
curl -sf -X PUT "${auth[@]}" -d '{"registrationAllowed": true}' \
  "$KEYCLOAK_URL/admin/realms/$REALM"
echo "[2/6] registrationAllowed = true"

# 3) real agentic-ai-core client secret
CLIENT_UUID=$(curl -sf "${auth[@]}" "$KEYCLOAK_URL/admin/realms/$REALM/clients?clientId=agentic-ai-core" | jq -r '.[0].id' | tr -d '\r')
if [ -z "$CLIENT_UUID" ] || [ "$CLIENT_UUID" = "null" ]; then
  echo "ERROR: agentic-ai-core client not found — was the realm actually imported?" >&2
  exit 1
fi
curl -sf -X PUT "${auth[@]}" -d "{\"secret\": \"$KEYCLOAK_CORE_CLIENT_SECRET\"}" \
  "$KEYCLOAK_URL/admin/realms/$REALM/clients/$CLIENT_UUID"
echo "[3/6] agentic-ai-core secret synced"

# 4) realm-admin on the core service account (idempotent — POSTing an
#    already-assigned role mapping again is a harmless no-op, matches
#    reconcile-keycloak.sh's `|| true` on the kcadm equivalent).
SA_USER_ID=$(curl -sf "${auth[@]}" "$KEYCLOAK_URL/admin/realms/$REALM/clients/$CLIENT_UUID/service-account-user" | jq -r '.id' | tr -d '\r')
RM_CLIENT_UUID=$(curl -sf "${auth[@]}" "$KEYCLOAK_URL/admin/realms/$REALM/clients?clientId=realm-management" | jq -r '.[0].id' | tr -d '\r')
REALM_ADMIN_ROLE=$(curl -sf "${auth[@]}" "$KEYCLOAK_URL/admin/realms/$REALM/clients/$RM_CLIENT_UUID/roles/realm-admin")
curl -sf -X POST "${auth[@]}" -d "[$REALM_ADMIN_ROLE]" \
  "$KEYCLOAK_URL/admin/realms/$REALM/users/$SA_USER_ID/role-mappings/clients/$RM_CLIENT_UUID" || true
echo "[4/6] realm-admin granted to service-account-agentic-ai-core"

# 5) client redirectUris / webOrigins <- agentic-ai-realm.json.
#    UNION-merge, never replace: the live realm has been hand-patched over time
#    and carries hosts this file has never known about. Replacing the live list
#    with the file's would silently revoke a working login redirect; adding to
#    it cannot. `unique` also sorts, which is what makes the "already up to
#    date" comparison below stable across runs.
#    Partial-representation PUT to /clients/{id} is the same shape step 3 above
#    already uses for the secret — Keycloak merges the fields present and
#    leaves the rest of the client alone.
if [ ! -f "$REALM_JSON" ]; then
  echo "ERROR: $REALM_JSON not found — this step reads the committed realm file next to the script" >&2
  exit 1
fi
#    NON-PRODUCTION HOSTS ARE FILTERED OUT (no browser-facing or token-issuer URL
#    may point at localhost/127.0.0.1). The committed realm
#    file carries 14 http://localhost:{3000,3001,4100} entries because the same
#    file seeds a developer's local Keycloak; union-merging them into the LIVE
#    clients would both violate that rule and widen the production clients'
#    redirect surface to anything a developer machine can serve. Only https://
#    entries are eligible, and any localhost/127.0.0.1 host is dropped even if
#    someone later writes it as https. This filter is deliberately allow-list
#    shaped (https + not-loopback) rather than a blocklist of the three ports
#    that happen to be in the file today.
# The realm file uses Keycloak import placeholders for the public domain
# (${env.VEGADUTA_PUBLIC_DOMAIN:example.com}). Keycloak resolves them at
# import time; this script talks to the Admin REST API directly, so resolve
# them here the same way before reading the file.
PUBLIC_DOMAIN="${VEGADUTA_PUBLIC_DOMAIN:-example.com}"
REALM_JSON_RESOLVED=$(mktemp)
trap 'rm -f "$REALM_JSON_RESOLVED"' EXIT
sed -E 's/[$][{]env[.]VEGADUTA_PUBLIC_DOMAIN(:[^}]*)?[}]/'"${PUBLIC_DOMAIN}"'/g' "$REALM_JSON" > "$REALM_JSON_RESOLVED"
echo "[5/6] client redirectUris/webOrigins from $(basename "$REALM_JSON") (https, non-loopback only, domain=$PUBLIC_DOMAIN):"
# `| tr -d '\r'` is load-bearing on Windows, where this script is actually run
# (2026-09-10): jq opens stdout in TEXT mode there, so every line it prints
# ends CRLF. Command substitution strips only the FINAL newline, so in a
# multi-line capture every element except the last keeps a trailing CR - the
# loop then requested clientId=agentic-ai-web<CR>, curl rejected the malformed
# URL, and `set -euo pipefail` turned that into a silent abort halfway through
# step 5. It looked exactly like "the clients do not exist in the live realm"
# (only academy-webapp, the last line, resolved). Single-value captures
# elsewhere in this script are unaffected because $() does strip their sole
# trailing CRLF, but they are hardened the same way below rather than relying
# on that distinction holding.
for CLIENT in $(jq -r '.clients[] | select((.redirectUris // []) != []) | .clientId' "$REALM_JSON_RESOLVED" | tr -d '\r'); do
  WANT=$(jq -c --arg cid "$CLIENT" \
    'def production: map(select(
        startswith("https://")
        and (startswith("https://localhost") | not)
        and (startswith("https://127.0.0.1") | not)
     ));
     .clients[] | select(.clientId == $cid)
     | {redirectUris: ((.redirectUris // []) | production),
        webOrigins:   ((.webOrigins   // []) | production)}' "$REALM_JSON_RESOLVED")
  LIVE=$(curl -sf "${auth[@]}" "$KEYCLOAK_URL/admin/realms/$REALM/clients?clientId=$CLIENT")
  UUID=$(printf '%s' "$LIVE" | jq -r '.[0].id // empty' | tr -d '\r')
  if [ -z "$UUID" ]; then
    echo "  - $CLIENT: not present in the live realm, skipped"
    continue
  fi
  BEFORE=$(printf '%s' "$LIVE" | jq -c \
    '{redirectUris: ((.[0].redirectUris // []) | unique), webOrigins: ((.[0].webOrigins // []) | unique)}')
  MERGED=$(printf '%s' "$LIVE" | jq -c --argjson want "$WANT" \
    '{redirectUris: (((.[0].redirectUris // []) + $want.redirectUris) | unique),
      webOrigins:   (((.[0].webOrigins   // []) + $want.webOrigins)   | unique)}')
  if [ "$MERGED" = "$BEFORE" ]; then
    echo "  - $CLIENT: already up to date"
  else
    printf '%s' "$MERGED" | curl -sf -X PUT "${auth[@]}" -d @- \
      "$KEYCLOAK_URL/admin/realms/$REALM/clients/$UUID"
    echo "  - $CLIENT: redirectUris/webOrigins merged"
  fi
done

# 6) SMTP — the step this script's header used to say it skipped ("add later
#    if real email/social login matters here"). It matters: with no working
#    smtpServer, Keycloak cannot send the VERIFY_EMAIL message that core's
#    KeycloakAdminClient#createUser requires of every email+password signup,
#    so EVERY such user is stranded at the "verify your email" screen forever
#    (Keycloak logs SEND_VERIFY_EMAIL_ERROR / email_send_failed). The
#    compose stack's reconcile-keycloak.sh step 4 configures SMTP from
#    deploy/.env; this is the equivalent step for this platform.
#
#    Defaults below are placeholders - set KEYCLOAK_SMTP_HOST / _PORT / _FROM /
#    _USER for your mail provider. The password is never stored in this repo.
#    It is read at run time from $KEYCLOAK_SMTP_PASSWORD, or failing that
#    (when KEYCLOAK_SMTP_VAULT is set) from that Azure Key Vault's
#    $KEYCLOAK_SMTP_SECRET_NAME secret, under YOUR az login. It is never
#    echoed - the only output is the host/user, and Keycloak's own SMTP test.
SMTP_HOST="${KEYCLOAK_SMTP_HOST:-smtp.example.com}"
SMTP_PORT="${KEYCLOAK_SMTP_PORT:-587}"
SMTP_FROM="${KEYCLOAK_SMTP_FROM:-noreply@example.com}"
SMTP_FROM_DISPLAY="${KEYCLOAK_SMTP_FROM_DISPLAY:-VegaDūta}"
SMTP_USER="${KEYCLOAK_SMTP_USER:-noreply@example.com}"
SMTP_VAULT="${KEYCLOAK_SMTP_VAULT:-}"
SMTP_SECRET_NAME="${KEYCLOAK_SMTP_SECRET_NAME:-core-smtp-password}"

SMTP_PASSWORD="${KEYCLOAK_SMTP_PASSWORD:-}"
if [ -z "$SMTP_PASSWORD" ]; then
  if [ -n "$SMTP_VAULT" ] && command -v az >/dev/null 2>&1; then
    echo "[6/6] reading SMTP password from Key Vault $SMTP_VAULT/$SMTP_SECRET_NAME ..."
    SMTP_PASSWORD=$(az keyvault secret show --vault-name "$SMTP_VAULT" --name "$SMTP_SECRET_NAME" \
      --query value -o tsv 2>/dev/null || true)
  fi
fi
if [ -z "$SMTP_PASSWORD" ]; then
  echo "[6/6] SMTP SKIPPED — no password available." >&2
  echo "      Set KEYCLOAK_SMTP_PASSWORD, or run 'az login' so the secret" >&2
  echo "      $SMTP_VAULT/$SMTP_SECRET_NAME can be read (needs Key Vault get)." >&2
  echo "      Until this is configured, every email+password signup is stuck" >&2
  echo "      at Keycloak's 'verify your email' screen." >&2
else
  # jq --arg keeps the password out of the process table and escapes it
  # correctly for JSON; the body is piped, never passed as an argv string.
  jq -n --arg h "$SMTP_HOST" --arg p "$SMTP_PORT" --arg f "$SMTP_FROM" \
        --arg d "$SMTP_FROM_DISPLAY" --arg u "$SMTP_USER" --arg w "$SMTP_PASSWORD" \
    '{smtpServer: {host: $h, port: $p, from: $f, fromDisplayName: $d, replyTo: $f,
                   auth: "true", user: $u, password: $w, starttls: "true", ssl: "false"}}' \
    | curl -sf -X PUT "${auth[@]}" -d @- "$KEYCLOAK_URL/admin/realms/$REALM"
  echo "[6/6] SMTP configured ($SMTP_HOST:$SMTP_PORT as $SMTP_USER, from $SMTP_FROM)"

  # Keycloak's own connection test — proves the credential actually
  # authenticates instead of waiting for the next signup to find out. A
  # failure here is reported but does NOT abort: every step above has already
  # been applied and is worth keeping.
  TEST=$(jq -n --arg h "$SMTP_HOST" --arg p "$SMTP_PORT" --arg f "$SMTP_FROM" \
              --arg d "$SMTP_FROM_DISPLAY" --arg u "$SMTP_USER" --arg w "$SMTP_PASSWORD" \
    '{host: $h, port: $p, from: $f, fromDisplayName: $d, auth: "true",
      user: $u, password: $w, starttls: "true", ssl: "false"}' \
    | curl -s -o /dev/null -w '%{http_code}' -X POST "${auth[@]}" -d @- \
        "$KEYCLOAK_URL/admin/realms/$REALM/testSMTPConnection" || true)
  if [ "$TEST" = "204" ] || [ "$TEST" = "200" ]; then
    echo "      SMTP test connection OK ($TEST)"
  else
    echo "      WARNING: SMTP test connection returned $TEST — mail may still fail." >&2
    echo "      For Google Workspace the password must be an APP PASSWORD, not" >&2
    echo "      the mailbox login, and 2-step verification must be on." >&2
  fi
fi
unset SMTP_PASSWORD

echo "reconcile-azure: DONE"
