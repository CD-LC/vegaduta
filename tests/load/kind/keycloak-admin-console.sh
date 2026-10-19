#!/usr/bin/env bash
# Make the Keycloak admin console usable on a LOCAL kind stage without publishing /admin or the master realm.
#
# Why: the console builds its Keycloak client from the realm's Frontend URL. By default that is the public auth host
# (http://auth.127.0.0.1.nip.io), whose ingress deliberately 404s /realms/master, so the console's login iframe times out
# ("Timeout when waiting for 3rd party check iframe message"). KC_HOSTNAME_ADMIN (keycloak.admin.consoleUrl) does NOT
# change that URL. Setting the MASTER realm's Frontend URL to an address reachable through a port-forward does; the
# application realm and its token issuer are untouched.
#
# Usage:  keycloak-admin-console.sh enable    # sets master Frontend URL, prints the URL to open
#         keycloak-admin-console.sh disable   # removes it again
# Then keep a port-forward running:  kubectl --context kind-vegaduta -n vegaduta port-forward svc/vegaduta-keycloak 18081:8080
# Admin credentials are read from the cluster secret and never printed. Refuses non-kind contexts.
set -euo pipefail
CTX=${CTX:-kind-vegaduta}; NS=${NS:-vegaduta}; PORT=${KC_LOCAL_PORT:-18081}
case "$CTX" in kind-*) ;; *) echo "refusing: $CTX is not a kind context" >&2; exit 1;; esac
mode=${1:?enable|disable}; URL="http://kc-admin.127.0.0.1.nip.io:$PORT"
u=$(kubectl --context "$CTX" -n "$NS" get secret vegaduta-keycloak-admin -o jsonpath='{.data.username}' | base64 -d)
p=$(kubectl --context "$CTX" -n "$NS" get secret vegaduta-keycloak-admin -o jsonpath='{.data.password}' | base64 -d)
kubectl --context "$CTX" -n "$NS" port-forward svc/vegaduta-keycloak "$PORT:8080" >/dev/null 2>&1 &
pf=$!; trap 'kill $pf 2>/dev/null' EXIT
for _ in $(seq 1 30); do curl -fsS -o /dev/null "http://127.0.0.1:$PORT/realms/master" 2>/dev/null && break; sleep 1; done
tok=$(curl -fsS -d grant_type=password -d client_id=admin-cli --data-urlencode "username=$u" --data-urlencode "password=$p" \
  "http://127.0.0.1:$PORT/realms/master/protocol/openid-connect/token" | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
rep=$(curl -fsS -H "Authorization: Bearer $tok" "http://127.0.0.1:$PORT/admin/realms/master")
new=$(MODE=$mode URL=$URL python3 -c '
import sys,json,os
d=json.loads(sys.stdin.read()); a=d.setdefault("attributes",{})
if os.environ["MODE"]=="enable": a["frontendUrl"]=os.environ["URL"]
else: a.pop("frontendUrl",None)
print(json.dumps(d))' <<<"$rep")
curl -fsS -X PUT -H "Authorization: Bearer $tok" -H 'Content-Type: application/json' -d "$new" "http://127.0.0.1:$PORT/admin/realms/master"
if [ "$mode" = enable ]; then echo "enabled. Keep a port-forward running, then open $URL/admin/master/console/"; else echo "disabled."; fi
