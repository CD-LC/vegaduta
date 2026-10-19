#!/usr/bin/env bash
# Marks a LOCAL test user's email verified and clears its required actions, so the password grant can log in.
# Usage: fix-user.sh <email>     Refuses non-kind contexts. Admin password is never printed.
set -euo pipefail
CTX=${CTX:-kind-vegaduta}; NS=${NS:-vegaduta}; REALM=agentic-ai; PORT=${KC_LOCAL_PORT:-18081}
case "$CTX" in kind-*) ;; *) echo "refusing: $CTX is not a kind context" >&2; exit 1;; esac
email=${1:?email}
sec() { kubectl --context "$CTX" -n "$NS" get secret vegaduta-keycloak-admin -o "jsonpath={.data.$1}" | base64 -d; }
u=$(sec username); p=$(sec password)
kubectl --context "$CTX" -n "$NS" port-forward svc/vegaduta-keycloak "$PORT:8080" >/dev/null 2>&1 &
pf=$!; trap 'kill $pf 2>/dev/null' EXIT
for _ in $(seq 1 30); do curl -fsS -o /dev/null "http://127.0.0.1:$PORT/realms/master" 2>/dev/null && break; sleep 1; done
tok=$(curl -fsS -d grant_type=password -d client_id=admin-cli --data-urlencode "username=$u" --data-urlencode "password=$p" \
  "http://127.0.0.1:$PORT/realms/master/protocol/openid-connect/token" | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
A=(-H "Authorization: Bearer $tok")
user=$(curl -fsS "${A[@]}" "http://127.0.0.1:$PORT/admin/realms/$REALM/users?email=$email&exact=true")
echo "$user" | python3 -c 'import sys,json;d=json.load(sys.stdin)[0];print({k:d.get(k) for k in ("enabled","emailVerified","requiredActions","firstName","lastName")})'
id=$(echo "$user" | python3 -c 'import sys,json;print(json.load(sys.stdin)[0]["id"])')
rep=$(echo "$user" | python3 -c 'import sys,json;d=json.load(sys.stdin)[0];d["emailVerified"]=True;d["requiredActions"]=[];print(json.dumps(d))')
curl -fsS -X PUT "${A[@]}" -H 'Content-Type: application/json' -d "$rep" "http://127.0.0.1:$PORT/admin/realms/$REALM/users/$id"
echo fixed
