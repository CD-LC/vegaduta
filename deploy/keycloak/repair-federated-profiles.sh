#!/usr/bin/env bash
# =============================================================================
# repair-federated-profiles.sh — get social-login users off the
# "Update Account Information" screen, and keep the next ones off it.
# -----------------------------------------------------------------------------
# THE BUG, end to end, so nobody re-diagnoses it from the symptom:
#
#   1. A person signs in with Google. Keycloak maps email/firstName/lastName
#      from the provider correctly. Nothing wrong yet.
#   2. core then called KeycloakAdminClient#setUserAttributes to stamp
#      tenant_id on the new user. That sent an attributes-ONLY PUT, and
#      Keycloak >= 24.0.4 treats a PUT body as the whole user representation —
#      so every root field absent from the body (email, firstName, lastName)
#      was DROPPED.
#   3. On the next login VERIFY_PROFILE correctly notices those fields are
#      blank and shows "Update Account Information". The screen is not the
#      bug; it is the bug's alarm.
#
# The cause is fixed in core (setUserAttributes now does a read-then-merge and
# re-asserts the root fields — see KeycloakAdminClient and its test). This
# script is the other half: the accounts already blanked stay blanked, because
# the realm's identity providers were configured syncMode=IMPORT, which maps
# provider data ONCE at first login and never again.
#
# WHAT THIS DOES
#   Step 1  Sets syncMode=FORCE on every identity provider, live, without a
#           realm re-import. FORCE re-maps email/first/last on EVERY login, so
#           an already-blanked account repairs itself the next time that person
#           signs in — and a future regression of the same shape self-heals
#           instead of stranding people. (The committed realm JSONs carry
#           FORCE too, for a fresh import; this step is for the realm that is
#           already running, where an import is a no-op.)
#   Step 2  Reports every user with a blank email or name, so you can see the
#           blast radius and tell those people to just sign in again.
#   Step 3  With --apply-verify-profile-clear, additionally clears a STALE
#           VERIFY_PROFILE required action from users whose profile is now
#           complete. Off by default: if the profile is still blank, that
#           required action is doing its job and clearing it would let a
#           nameless account into the app.
#
# DELIBERATELY NOT DONE: turning VERIFY_PROFILE off realm-wide. That hides
# missing data instead of repairing it, and reconcile-azure.sh already says so.
#
# A re-import is avoided throughout, on purpose: this repo has previously had
# social-IdP client secrets reverted to placeholders by a realm re-import.
# Nothing here writes a secret, and nothing here touches clientSecret.
#
# Usage:
#   KEYCLOAK_URL=https://auth.example.com \
#   KEYCLOAK_ADMIN=admin \
#   KEYCLOAK_ADMIN_PASSWORD=... \
#   bash repair-federated-profiles.sh [--apply-verify-profile-clear]
#
# Idempotent. Read-only apart from step 1 (and step 3 when asked for).
# =============================================================================
set -euo pipefail

: "${KEYCLOAK_URL:?set KEYCLOAK_URL (e.g. https://auth.example.com)}"
: "${KEYCLOAK_ADMIN:=admin}"
: "${KEYCLOAK_ADMIN_PASSWORD:?set KEYCLOAK_ADMIN_PASSWORD}"
REALM="${REALM:-agentic-ai}"
CLEAR_VERIFY_PROFILE=false
[[ "${1:-}" == "--apply-verify-profile-clear" ]] && CLEAR_VERIFY_PROFILE=true

command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }

# Every jq read below is piped through `tr -d '\r'`. Not defensive habit:
# the mingw/Windows jq builds commonly found on a dev box (anaconda ships one)
# emit CRLF, so an alias read straight from jq is "google\r" and the URL built
# from it fails with curl error 3, "Malformed input to a URL function" - after
# the auth step has already succeeded, which makes it look like a server
# problem. Verified on this machine with `jq -r .alias | od -c`.

echo "==> Authenticating to ${KEYCLOAK_URL} as ${KEYCLOAK_ADMIN}"
TOKEN=$(curl -sS -f -X POST \
  "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
  -d "client_id=admin-cli" \
  -d "username=${KEYCLOAK_ADMIN}" \
  --data-urlencode "password=${KEYCLOAK_ADMIN_PASSWORD}" \
  -d "grant_type=password" | jq -r .access_token | tr -d '\r')
[[ -n "${TOKEN}" && "${TOKEN}" != "null" ]] || { echo "could not obtain an admin token" >&2; exit 1; }

api() { curl -sS -f -H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json" "$@"; }

# ---------------------------------------------------------------------------
# Step 1 — syncMode=FORCE on every identity provider
# ---------------------------------------------------------------------------
echo
echo "==> Step 1: identity providers -> syncMode=FORCE"
IDPS=$(api "${KEYCLOAK_URL}/admin/realms/${REALM}/identity-provider/instances")
echo "${IDPS}" | jq -r '.[].alias' | tr -d '\r' | while read -r ALIAS; do
  [[ -n "${ALIAS}" ]] || continue
  CURRENT=$(echo "${IDPS}" | jq -r --arg a "${ALIAS}" '.[] | select(.alias==$a) | .config.syncMode // "unset"' | tr -d '\r')
  if [[ "${CURRENT}" == "FORCE" ]]; then
    echo "    ${ALIAS}: already FORCE"
    continue
  fi
  # Round-trip the WHOLE representation with only syncMode changed, so the
  # provider's clientSecret and everything else survives untouched.
  echo "${IDPS}" | jq --arg a "${ALIAS}" '.[] | select(.alias==$a) | .config.syncMode = "FORCE"' \
    | api -X PUT "${KEYCLOAK_URL}/admin/realms/${REALM}/identity-provider/instances/${ALIAS}" -d @- >/dev/null
  echo "    ${ALIAS}: ${CURRENT} -> FORCE"
done

# ---------------------------------------------------------------------------
# Step 2 — who is currently broken
# ---------------------------------------------------------------------------
echo
echo "==> Step 2: users with a blank email or name"
USERS=$(api "${KEYCLOAK_URL}/admin/realms/${REALM}/users?briefRepresentation=false&max=2000")
BLANK=$(echo "${USERS}" | jq '[.[] | select((.email // "") == "" or (.firstName // "") == "" or (.lastName // "") == "")]')
COUNT=$(echo "${BLANK}" | jq 'length' | tr -d '\r')
echo "    ${COUNT} affected"
echo "${BLANK}" | jq -r '.[] | "      \(.username)  email=\(.email // "-")  first=\(.firstName // "-")  last=\(.lastName // "-")  required=\(.requiredActions // [] | join(","))"'
if [[ "${COUNT}" != "0" ]]; then
  echo
  echo "    With step 1 applied, each of these repairs itself the next time the"
  echo "    person signs in through their provider. Federated users: just ask"
  echo "    them to sign in again. A user with NO federated identity (created by"
  echo "    email+password) cannot self-repair this way — fix those by hand."
fi

# ---------------------------------------------------------------------------
# Step 3 — optional: clear a stale VERIFY_PROFILE
# ---------------------------------------------------------------------------
echo
if [[ "${CLEAR_VERIFY_PROFILE}" != "true" ]]; then
  echo "==> Step 3: skipped (pass --apply-verify-profile-clear to run it)"
  echo "    It only clears VERIFY_PROFILE from users whose profile is ALREADY"
  echo "    complete. A blank profile keeps its required action."
  exit 0
fi
echo "==> Step 3: clearing stale VERIFY_PROFILE from complete profiles"
echo "${USERS}" | jq -c '.[] | select((.requiredActions // []) | index("VERIFY_PROFILE"))' | tr -d '\r' | while read -r U; do
  ID=$(echo "${U}" | jq -r .id | tr -d '\r')
  USERNAME=$(echo "${U}" | jq -r .username | tr -d '\r')
  COMPLETE=$(echo "${U}" | jq -r 'if ((.email // "") != "" and (.firstName // "") != "" and (.lastName // "") != "") then "yes" else "no" end' | tr -d '\r')
  if [[ "${COMPLETE}" != "yes" ]]; then
    echo "    ${USERNAME}: profile still incomplete - LEFT ALONE (the required action is correct)"
    continue
  fi
  echo "${U}" | jq '.requiredActions = ((.requiredActions // []) - ["VERIFY_PROFILE"])' \
    | api -X PUT "${KEYCLOAK_URL}/admin/realms/${REALM}/users/${ID}" -d @- >/dev/null
  echo "    ${USERNAME}: VERIFY_PROFILE cleared"
done
echo
echo "Done."
