#!/usr/bin/env bash
# LOCAL kind stage only: prepares the Keycloak realm and the extra users the e2e suite needs.
#   1. Relaxes brute-force detection of the stage realm (SE-20): parallel runners sharing one test user got
#      "user_temporarily_disabled" and the login page said "Invalid username or password". Production realms keep the
#      chart defaults (failureFactor 10, 60 s increments); this only touches the realm of the cluster you point CTX at.
#   2. Creates a platform-admin user (SE-44) and writes E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD to the stage env file.
#   3. Optionally creates a second member of the load-test tenant (SE-77, "shared with me") and writes
#      E2E_MEMBER_EMAIL / E2E_MEMBER_PASSWORD. Needs LT_TOKEN (access token of the load-test tenant admin).
# Idempotent: rerunning keeps existing users and reuses the stored passwords.
#
# Env: CTX (kube context, default kind-vegaduta; must be kind-*), NS (default vegaduta), KC_LOCAL_PORT (18081),
#      STAGE_ENV (file the credentials are appended to; default tests/load/kind/stage.env, which .gitignore covers via *.env),
#      LT_TOKEN (optional, enables step 3), API_HOST (default api.127.0.0.1.nip.io).
# Passwords are generated here, written only to STAGE_ENV (mode 600) and never printed or committed.
set -euo pipefail
CTX=${CTX:-kind-vegaduta}; NS=${NS:-vegaduta}; REALM=agentic-ai; PORT=${KC_LOCAL_PORT:-18081}
API_HOST=${API_HOST:-api.127.0.0.1.nip.io}
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
STAGE_ENV=${STAGE_ENV:-$here/stage.env}
ADMIN_EMAIL=${ADMIN_EMAIL:-e2e-platform-admin@vegaduta.test}
MEMBER_EMAIL=${MEMBER_EMAIL:-e2e-member@vegaduta.test}
case "$CTX" in kind-*) ;; *) echo "refusing: $CTX is not a kind context" >&2; exit 1;; esac

sec() { kubectl --context "$CTX" -n "$NS" get secret vegaduta-keycloak-admin -o "jsonpath={.data.$1}" | base64 -d; }
u=$(sec username); p=$(sec password)
kubectl --context "$CTX" -n "$NS" port-forward svc/vegaduta-keycloak "$PORT:8080" >/dev/null 2>&1 &
pf=$!; trap 'kill $pf 2>/dev/null' EXIT
for _ in $(seq 1 30); do curl -fsS -o /dev/null "http://127.0.0.1:$PORT/realms/master" 2>/dev/null && break; sleep 1; done
KC="http://127.0.0.1:$PORT"
tok=$(curl -fsS -d grant_type=password -d client_id=admin-cli --data-urlencode "username=$u" --data-urlencode "password=$p" \
  "$KC/realms/master/protocol/openid-connect/token" | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
unset u p
kc() { curl -fsS -H "Authorization: Bearer $tok" -H 'Content-Type: application/json' "$@"; }

# Reads KEY from STAGE_ENV (empty when missing). The file is a plain KEY=VALUE list.
env_get() { [[ -f $STAGE_ENV ]] && grep -E "^$1=" "$STAGE_ENV" | tail -1 | cut -d= -f2- || true; }
env_set() {
  touch "$STAGE_ENV"; chmod 600 "$STAGE_ENV"
  local tmp; tmp=$(mktemp); grep -vE "^$1=" "$STAGE_ENV" >"$tmp" || true
  echo "$1=$2" >>"$tmp"; cat "$tmp" >"$STAGE_ENV"; rm -f "$tmp"
}
# 24 chars, comfortably above the realm policy (length 12, no username/email, history 3).
gen_password() { LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24; }

# Creates the user when absent (emailVerified, no required actions), sets a fresh non-temporary password when the
# stage env has none, and prints the user id. $1 email, $2 first name, $3 last name, $4 env-var prefix.
ensure_user() {
  local email=$1 first=$2 last=$3 prefix=$4 id pw
  id=$(kc "$KC/admin/realms/$REALM/users?email=$email&exact=true" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d[0]["id"] if d else "")')
  if [[ -z $id ]]; then
    EMAIL=$email FIRST=$first LAST=$last python3 -c 'import os,json;e=os.environ["EMAIL"];print(json.dumps({"username":e,"email":e,"firstName":os.environ["FIRST"],"lastName":os.environ["LAST"],"enabled":True,"emailVerified":True,"requiredActions":[]}))' \
      | kc -X POST --data @- "$KC/admin/realms/$REALM/users" >/dev/null
    id=$(kc "$KC/admin/realms/$REALM/users?email=$email&exact=true" | python3 -c 'import sys,json;print(json.load(sys.stdin)[0]["id"])')
  fi
  pw=$(env_get "${prefix}_PASSWORD")
  if [[ -z $pw ]]; then
    pw=$(gen_password)
    PW=$pw python3 -c 'import os,json;print(json.dumps({"type":"password","value":os.environ["PW"],"temporary":False}))' \
      | kc -X PUT --data @- "$KC/admin/realms/$REALM/users/$id/reset-password" >/dev/null
    env_set "${prefix}_PASSWORD" "$pw"
  fi
  env_set "${prefix}_EMAIL" "$email"
  echo "$id"
}

echo "1/3 realm brute-force settings (stage only)" >&2
kc -X PUT -d '{"bruteForceProtected":true,"failureFactor":1000,"waitIncrementSeconds":1,"maxFailureWaitSeconds":5,"minimumQuickLoginWaitSeconds":1,"quickLoginCheckMilliSeconds":0,"permanentLockout":false}' \
  "$KC/admin/realms/$REALM" >/dev/null

echo "2/3 platform-admin user $ADMIN_EMAIL" >&2
aid=$(ensure_user "$ADMIN_EMAIL" "E2E" "PlatformAdmin" E2E_ADMIN)
role=$(kc "$KC/admin/realms/$REALM/roles/platform-admin")
echo "[$role]" | kc -X POST --data @- "$KC/admin/realms/$REALM/users/$aid/role-mappings/realm" >/dev/null

if [[ -n ${LT_TOKEN:-} ]]; then
  echo "3/3 second tenant member $MEMBER_EMAIL" >&2
  # The tenant admin API stamps tenant_id on the user; 4xx (already exists, seat cap) is tolerated and reported.
  pw=$(env_get E2E_MEMBER_PASSWORD); [[ -n $pw ]] || pw=$(gen_password)
  code=$(MEMBER_EMAIL=$MEMBER_EMAIL PW=$pw python3 -c 'import os,json;e=os.environ["MEMBER_EMAIL"];print(json.dumps({"username":e,"email":e,"firstName":"E2E","lastName":"Member","temporaryPassword":os.environ["PW"],"inviteByEmail":False,"initialRoles":[]}))' \
    | curl -sS -o /dev/null -w '%{http_code}' -H "Host: $API_HOST" -H "Authorization: Bearer $LT_TOKEN" -H 'Content-Type: application/json' \
        --data @- -X POST http://127.0.0.1/api/admin/users)
  echo "  POST /api/admin/users -> HTTP $code" >&2
  mid=$(kc "$KC/admin/realms/$REALM/users?email=$MEMBER_EMAIL&exact=true" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d[0]["id"] if d else "")')
  if [[ -n $mid ]]; then
    # The API sets a temporary password and a verify-email action; make the account log in directly.
    kc -X PUT -d '{"emailVerified":true,"requiredActions":[]}' "$KC/admin/realms/$REALM/users/$mid" >/dev/null
    PW=$pw python3 -c 'import os,json;print(json.dumps({"type":"password","value":os.environ["PW"],"temporary":False}))' \
      | kc -X PUT --data @- "$KC/admin/realms/$REALM/users/$mid/reset-password" >/dev/null
    env_set E2E_MEMBER_EMAIL "$MEMBER_EMAIL"; env_set E2E_MEMBER_PASSWORD "$pw"
  else
    echo "  member user not found after the API call; not written to the stage env" >&2
  fi
else
  echo "3/3 skipped (set LT_TOKEN to create the second tenant member)" >&2
fi
echo "done; credentials are in $STAGE_ENV (not printed)" >&2
