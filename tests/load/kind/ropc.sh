#!/usr/bin/env bash
# Enable or disable the direct (password) grant on the web client of a LOCAL kind stage, so k6 can log in.
# Usage: ropc.sh enable|disable        Env: CTX (kube context, default kind-vegaduta), NS (default vegaduta)
# Admin calls go through a kubectl port-forward to localhost (the ingress does not expose the master realm).
# The admin password is read from the cluster secret and never printed. Refuses any non-local target.
set -euo pipefail
CTX=${CTX:-kind-vegaduta}; NS=${NS:-vegaduta}; REALM=agentic-ai
case "$CTX" in kind-*) ;; *) echo "refusing: $CTX is not a kind context" >&2; exit 1;; esac
mode=${1:?enable|disable}; [[ $mode == enable ]] && val=true || val=false
sec() { kubectl --context "$CTX" -n "$NS" get secret vegaduta-keycloak-admin -o "jsonpath={.data.$1}" | base64 -d; }
u=$(sec "${KC_ADMIN_USER_KEY:-username}" 2>/dev/null || true); p=$(sec "${KC_ADMIN_PASS_KEY:-password}" 2>/dev/null || true)
[[ -n $u && -n $p ]] || { echo "could not read admin credentials; keys: $(kubectl --context "$CTX" -n "$NS" get secret vegaduta-keycloak-admin -o 'jsonpath={.data}' | grep -o '"[^"]*":' | tr -d '":' | tr '\n' ' ')" >&2; exit 1; }
PORT=${KC_LOCAL_PORT:-18081}
kubectl --context "$CTX" -n "$NS" port-forward svc/vegaduta-keycloak "$PORT:8080" >/dev/null 2>&1 &
pf=$!; trap 'kill $pf 2>/dev/null' EXIT
for _ in $(seq 1 30); do curl -fsS -o /dev/null "http://127.0.0.1:$PORT/realms/master" 2>/dev/null && break; sleep 1; done
kc() { curl -fsS "$@"; }
tok=$(kc -d grant_type=password -d client_id=admin-cli --data-urlencode "username=$u" --data-urlencode "password=$p" \
  "http://127.0.0.1:$PORT/realms/master/protocol/openid-connect/token" | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
id=$(kc -H "Authorization: Bearer $tok" "http://127.0.0.1:$PORT/admin/realms/$REALM/clients?clientId=agentic-ai-web" | python3 -c 'import sys,json;print(json.load(sys.stdin)[0]["id"])')
rep=$(kc -H "Authorization: Bearer $tok" "http://127.0.0.1:$PORT/admin/realms/$REALM/clients/$id" | VAL=$val python3 -c 'import sys,json,os;d=json.load(sys.stdin);d["directAccessGrantsEnabled"]=os.environ["VAL"]=="true";print(json.dumps(d))')
kc -X PUT -H "Authorization: Bearer $tok" -H 'Content-Type: application/json' -d "$rep" "http://127.0.0.1:$PORT/admin/realms/$REALM/clients/$id"
echo "agentic-ai-web directAccessGrantsEnabled=$val"
