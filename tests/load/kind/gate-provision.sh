#!/usr/bin/env bash
# Prepares a FRESH kind stage for the load-test gate (gate.sh): the mock model, a load-test tenant on the UNLIMITED plan
# whose agent talks to the mock, and the password grant on the web client. Used by .github/workflows/load-nightly.yml;
# on a stage that already has a load-test tenant (the README's manual order), skip this and pass LT_* to gate.sh.
#
# Steps: mock-llm ConfigMap + Deployment -> POST /api/signup (generated email and password) -> fix-user.sh (email
# verified) -> ropc.sh enable -> provision-stage-users.sh (platform admin) -> PUT /api/platform/tenants/{id}/plan
# UNLIMITED (signup only creates FREE tenants, whose gateway limit is 30 calls/min and which use the hosted pool, not
# BYOK) -> provision.sh (mock provider + agent).
# Writes LT_USER, LT_PASS, LT_AGENT_ID to GATE_ENV (default tests/load/kind/gate.env, gitignored via *.env; mode 600).
# Passwords are generated here and never printed. With GITHUB_ACTIONS set they are also masked in the job log.
#
# Env: CTX (kind-* context, default kind-vegaduta), NS (vegaduta), API_HOST (api.127.0.0.1.nip.io),
#      AUTH_HOST (auth.127.0.0.1.nip.io), GATE_ENV, STAGE_ENV (admin credentials; default next to GATE_ENV).
# Talks to the ingress on 127.0.0.1 with Host headers, like provision.sh. Refuses non-kind contexts.
set -euo pipefail
CTX=${CTX:-kind-vegaduta}; NS=${NS:-vegaduta}
case "$CTX" in kind-*) ;; *) echo "refusing: $CTX is not a kind context" >&2; exit 1;; esac
API_HOST=${API_HOST:-api.127.0.0.1.nip.io}; AUTH_HOST=${AUTH_HOST:-auth.127.0.0.1.nip.io}; REALM=agentic-ai
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd); repo=$(cd "$here/../../.." && pwd)
GATE_ENV=${GATE_ENV:-$here/gate.env}
export STAGE_ENV=${STAGE_ENV:-$(dirname "$GATE_ENV")/stage.env}
export CTX NS API_HOST
k() { kubectl --context "$CTX" -n "$NS" "$@"; }
gen_password() { LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24 || true; }
mask() { [[ -n ${GITHUB_ACTIONS:-} ]] && echo "::add-mask::$1"; return 0; }
# Password-grant token for $1/$2 from the web client (ropc.sh enable must have run).
token() {
  curl -fsS -H "Host: $AUTH_HOST" -d grant_type=password -d client_id=agentic-ai-web \
    --data-urlencode "username=$1" --data-urlencode "password=$2" \
    "http://127.0.0.1/realms/$REALM/protocol/openid-connect/token" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])'
}

echo "1/6 mock model server" >&2
k create configmap mock-llm-code --from-file=server.js="$repo/tests/load/mock-llm/server.js" --dry-run=client -o yaml | k apply -f - >/dev/null
k apply -f "$here/mock-llm.yaml" >/dev/null
k rollout status deploy/mock-llm --timeout=300s >/dev/null

echo "2/6 sign up the load-test tenant" >&2
stamp=$(date -u +%Y%m%d%H%M%S)
LT_USER="load-gate-$stamp@vegaduta.test"; LT_PASS=$(gen_password); mask "$LT_PASS"
signup=$(EMAIL=$LT_USER PW=$LT_PASS ORG="load-gate-$stamp" python3 -c 'import os,json;print(json.dumps({"orgName":os.environ["ORG"],"adminEmail":os.environ["EMAIL"],"adminPassword":os.environ["PW"],"firstName":"Load","lastName":"Gate"}))' \
  | curl -sS -w '\n%{http_code}' -H "Host: $API_HOST" -H 'Content-Type: application/json' --data @- -X POST http://127.0.0.1/api/signup)
code=${signup##*$'\n'}; body=${signup%$'\n'*}
[[ $code == 201 || $code == 200 ]] || { echo "signup failed: HTTP $code $body" >&2; exit 1; }
tenant_id=$(echo "$body" | python3 -c 'import sys,json;print(json.load(sys.stdin)["tenantId"])')

echo "3/6 verify the user's email and enable the password grant" >&2
bash "$here/fix-user.sh" "$LT_USER" >/dev/null
bash "$here/ropc.sh" enable >/dev/null

echo "4/6 platform admin (provision-stage-users.sh)" >&2
bash "$here/provision-stage-users.sh"
admin_email=$(grep -E '^E2E_ADMIN_EMAIL=' "$STAGE_ENV" | tail -1 | cut -d= -f2-)
admin_pw=$(grep -E '^E2E_ADMIN_PASSWORD=' "$STAGE_ENV" | tail -1 | cut -d= -f2-); mask "$admin_pw"

echo "5/6 tenant plan UNLIMITED" >&2
admin_tok=$(token "$admin_email" "$admin_pw"); unset admin_pw
code=$(curl -sS -o /dev/null -w '%{http_code}' -H "Host: $API_HOST" -H "Authorization: Bearer $admin_tok" \
  -H 'Content-Type: application/json' -X PUT -d '{"plan":"UNLIMITED"}' "http://127.0.0.1/api/platform/tenants/$tenant_id/plan")
[[ $code == 200 ]] || { echo "plan change failed: HTTP $code" >&2; exit 1; }

echo "6/6 mock provider and load-test agent (provision.sh)" >&2
lt_tok=$(token "$LT_USER" "$LT_PASS")
LT_AGENT_ID=$(LT_TOKEN=$lt_tok bash "$here/provision.sh" | tail -1); unset lt_tok
[[ -n $LT_AGENT_ID ]] || { echo "provision.sh returned no agent id" >&2; exit 1; }

touch "$GATE_ENV"; chmod 600 "$GATE_ENV"
printf 'LT_USER=%s\nLT_PASS=%s\nLT_AGENT_ID=%s\n' "$LT_USER" "$LT_PASS" "$LT_AGENT_ID" >"$GATE_ENV"
echo "done; LT_USER, LT_PASS and LT_AGENT_ID are in $GATE_ENV (password not printed)" >&2
