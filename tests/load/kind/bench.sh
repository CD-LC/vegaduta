#!/usr/bin/env bash
# Platform-overhead benchmark matrix on a LOCAL kind stage: replicas x hot-path caches on/off x concurrency.
# Each cell: helm upgrade (replicas, cache TTLs) -> warmup -> measured k6 run -> JSON line appended to $OUT_FILE.
#
# Required env:  LT_USER LT_PASS LT_AGENT_ID   (test user and the agent that uses the mock model server)
#                STAGE_VALUES                  (space-separated "-f file" list: the stage's own helm values)
#                CORE_TAG JOBS_TAG WEB_TAG     (image tags already loaded in the cluster)
# Optional env:  REPLICAS="2 1" CACHES="on off" VUS_LIST="1 8 32" WARMUP=20s DURATION=60s OUT_FILE=bench.jsonl
#                CACHE_ON_TTL=3s  CHART=deploy/helm/charts/vegaduta  PLAN_CACHE_ON_TTL (adds the plan cache to "on")
# The per-tenant gateway limit (600 calls/min on the top plan) is raised for the run, or k6 would only measure 429s.
# Refuses non-kind contexts. Run from the repo root. Nothing here is a production load test.
set -euo pipefail
CTX=${CTX:-kind-vegaduta}; NS=${NS:-vegaduta}
case "$CTX" in kind-*) ;; *) echo "refusing: $CTX is not a kind context" >&2; exit 1;; esac
: "${LT_USER:?}" "${LT_PASS:?}" "${LT_AGENT_ID:?}" "${STAGE_VALUES:?}" "${CORE_TAG:?}" "${JOBS_TAG:?}" "${WEB_TAG:?}"
REPLICAS=${REPLICAS:-"2 1"}; CACHES=${CACHES:-"on off"}; VUS_LIST=${VUS_LIST:-"1 8 32"}
WARMUP=${WARMUP:-20s}; DURATION=${DURATION:-60s}; OUT_FILE=${OUT_FILE:-bench.jsonl}
CACHE_ON_TTL=${CACHE_ON_TTL:-3s}; CHART=${CHART:-deploy/helm/charts/vegaduta}
HERE=$(cd "$(dirname "$0")/.." && pwd); WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
K="kubectl --context $CTX -n $NS"

api_pods() { $K get pods -l app.kubernetes.io/name=core,app.kubernetes.io/component=api --field-selector=status.phase=Running -o name | sed 's#pod/##'; }
# sum and count of the server-side time-to-first-token timer (mode=llm), summed across API pods
server_ttft() {
  local sum=0 cnt=0 p m s c
  for p in $(api_pods); do
    m=$($K get --raw "/api/v1/namespaces/$NS/pods/$p:8080/proxy/actuator/prometheus" 2>/dev/null | grep -E '^agentic_chat_ttft_seconds_(sum|count)\{mode="llm"\}' || true)
    s=$(echo "$m" | awk '/_sum/{print $2}'); c=$(echo "$m" | awk '/_count/{print $2}')
    sum=$(python3 -c "print($sum+${s:-0})"); cnt=$(python3 -c "print($cnt+${c:-0})")
  done
  echo "$sum $cnt"
}
k6run() { # $1=duration $2=vus $3=label $4=outfile
  docker run --rm --add-host api.127.0.0.1.nip.io:host-gateway --add-host auth.127.0.0.1.nip.io:host-gateway \
    -e BASE_URL=http://api.127.0.0.1.nip.io -e KEYCLOAK_URL=http://auth.127.0.0.1.nip.io \
    -e LOAD_TEST_USERNAME="$LT_USER" -e LOAD_TEST_PASSWORD="$LT_PASS" -e LOAD_TEST_AGENT_ID="$LT_AGENT_ID" \
    -e VUS="$2" -e DURATION="$1" -e LABEL="$3" -e OUT=/out/"$4" \
    -v "$HERE:/load-test:ro" -v "$WORK:/out" grafana/k6 run --quiet /load-test/scripts/turn-overhead.js >/dev/null 2>"$WORK/k6.err" \
    || { cat "$WORK/k6.err" >&2; return 1; }
}

for r in $REPLICAS; do
  for c in $CACHES; do
    if [[ $c == on ]]; then ttl=$CACHE_ON_TTL; plan=${PLAN_CACHE_ON_TTL:-0s}; else ttl=0s; plan=0s; fi
    echo ">> configure replicas=$r caches=$c (ttl=$ttl plan=$plan)" >&2
    # shellcheck disable=SC2086
    helm --kube-context "$CTX" upgrade vegaduta "$CHART" -n "$NS" $STAGE_VALUES \
      --set core.image.tag="$CORE_TAG" --set core-jobs.image.tag="$JOBS_TAG" --set core-ingest.image.tag="$JOBS_TAG" \
      --set web.image.tag="$WEB_TAG" \
      --set core.hpa.enabled=false --set core.replicaCount="$r" \
      --set-string core.env.APP_CHAT_HOTPATH_AGENT_PROJECTION_TTL="$ttl" \
      --set-string core.env.APP_CHAT_HOTPATH_CONFIG_LOOKUP_TTL="$ttl" \
      --set-string core.env.APP_CHAT_HOTPATH_PLAN_CACHE_TTL="$plan" \
      --set-string core.env.APP_LLM_GATEWAY_RATE_LIMIT_UNLIMITED_MAX_CALLS_PER_MINUTE=1000000 \
      --wait --timeout 10m >/dev/null
    $K rollout status deploy/vegaduta-core --timeout=300s >/dev/null
    sleep 20
    for v in $VUS_LIST; do
      label="replicas=$r caches=$c vus=$v"
      echo "   $label: warmup $WARMUP" >&2
      k6run "$WARMUP" "$v" warmup warmup.json
      read -r s0 n0 < <(server_ttft)
      echo "   $label: measure $DURATION" >&2
      k6run "$DURATION" "$v" "$label" result.json
      read -r s1 n1 < <(server_ttft)
      top=$($K top pod -l app.kubernetes.io/name=core,app.kubernetes.io/component=api --no-headers 2>/dev/null | awk '{printf "%s%s", sep, $2; sep=","}' || true)
      python3 - "$WORK/result.json" "$r" "$c" "$s0" "$n0" "$s1" "$n1" "$top" >>"$OUT_FILE" <<'PY'
import json, sys
f, r, c, s0, n0, s1, n1, top = sys.argv[1:]
d = json.load(open(f)); d["replicas"] = int(r); d["caches"] = c
dn = float(n1) - float(n0)
d["server_ttft_ms_avg"] = round((float(s1) - float(s0)) / dn * 1000, 1) if dn > 0 else None
d["server_turns"] = int(dn); d["core_cpu_after"] = top
print(json.dumps(d))
PY
      tail -1 "$OUT_FILE" >&2
    done
  done
done
