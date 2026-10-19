#!/usr/bin/env bash
# Step test on a LOCAL kind stage: 2 warm API pods, then a sudden step to VUS long chat streams while the HPA adds pods.
# This is the scenario in which "some requests get no first byte for about a minute" (docs/architecture/HOT-PATH-PERFORMANCE.md 4d).
#
# What it does: removes the core HPA (saved first, restored at the end), scales the API to 2 pods, warms them with 25 s of 8 users,
# makes the mock model slow (TOKEN_MS=200, TOKENS=50, so one reply streams for 10 s), re-creates the HPA, runs VUS users for DURATION
# with EXPECT_EVENTS=50 (a cut stream counts as failed), puts the mock back and prints the k6 summary line plus, per upstream pod,
# how many chat requests ended with which status and how many took over 20 s (the pod that wedged shows up there).
# Optional: PIN_TRACE=1 first adds -Djdk.tracePinnedThreads=full to the core pods (rollout), see pinned-threads.py.
# Required env: LT_USER LT_PASS LT_AGENT_ID.  Optional: VUS=40 DURATION=90s OUT=step.json CTX=kind-vegaduta NS=vegaduta
# Run the password-grant switch first: kind/ropc.sh enable (and disable when finished). Refuses non-kind contexts.
set -euo pipefail
CTX=${CTX:-kind-vegaduta}; NS=${NS:-vegaduta}; case "$CTX" in kind-*) ;; *) echo "refusing: $CTX is not a kind context" >&2; exit 1;; esac
: "${LT_USER:?}" "${LT_PASS:?}" "${LT_AGENT_ID:?}"
VUS=${VUS:-40}; DURATION=${DURATION:-90s}; OUT=${OUT:-step.json}
HERE=$(cd "$(dirname "$0")/.." && pwd); WORK=$(mktemp -d); HPA_JSON=$WORK/hpa.json
k() { kubectl --context "$CTX" -n "$NS" "$@"; }
restore() {
  k set env deploy/mock-llm TOKEN_MS=5 TOKENS=20 >/dev/null || true
  [ -s "$HPA_JSON" ] && ! k get hpa vegaduta-core >/dev/null 2>&1 && k apply -f "$HPA_JSON" >/dev/null || true
  rm -rf "$WORK"
}
trap restore EXIT
k6run() { # duration vus out expect_events
  docker run --rm --add-host api.127.0.0.1.nip.io:host-gateway --add-host auth.127.0.0.1.nip.io:host-gateway \
    -e BASE_URL=http://api.127.0.0.1.nip.io -e KEYCLOAK_URL=http://auth.127.0.0.1.nip.io \
    -e LOAD_TEST_USERNAME="$LT_USER" -e LOAD_TEST_PASSWORD="$LT_PASS" -e LOAD_TEST_AGENT_ID="$LT_AGENT_ID" \
    -e VUS="$2" -e DURATION="$1" -e LABEL=step -e OUT=/out/"$3" -e EXPECT_EVENTS="$4" \
    -v "$HERE:/load-test:ro" -v "$WORK:/out" grafana/k6 run --quiet /load-test/scripts/turn-overhead.js >/dev/null 2>"$WORK/k6.err" \
    || { cat "$WORK/k6.err" >&2; return 1; }
}
if k get hpa vegaduta-core >/dev/null 2>&1; then
  k get hpa vegaduta-core -o json | python3 -c '
import sys, json
d = json.load(sys.stdin); m = d["metadata"]
for key in ("resourceVersion", "uid", "creationTimestamp", "managedFields", "generation"): m.pop(key, None)
(m.get("annotations") or {}).pop("autoscaling.alpha.kubernetes.io/conditions", None)
d.pop("status", None); print(json.dumps(d))' >"$HPA_JSON"
  k delete hpa vegaduta-core >/dev/null
fi
if [ -n "${PIN_TRACE:-}" ]; then
  k set env deploy/vegaduta-core JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Djdk.tracePinnedThreads=full" >/dev/null
fi
k scale deploy/vegaduta-core --replicas=2 >/dev/null
k rollout status deploy/vegaduta-core --timeout=300s >/dev/null
k set env deploy/mock-llm TOKEN_MS=5 TOKENS=20 >/dev/null; k rollout status deploy/mock-llm >/dev/null
k6run 25s 8 warm.json 0
[ -s "$HPA_JSON" ] && k apply -f "$HPA_JSON" >/dev/null
k set env deploy/mock-llm TOKEN_MS=200 TOKENS=50 >/dev/null; k rollout status deploy/mock-llm >/dev/null
sleep 5
START=$(date -u +%H:%M:%S)
k6run "$DURATION" "$VUS" "$OUT" 50
cp "$WORK/$OUT" "./$OUT"
python3 -c "import json,sys; d=json.load(open('$OUT')); print(json.dumps({k: d.get(k) for k in ('turns_per_s','failed_rate','statuses','incomplete_streams','ttft_ms')}))"
echo "--- chat requests per upstream pod (ingress-nginx), since $START UTC"
kubectl --context "$CTX" -n ingress-nginx logs deploy/ingress-nginx-controller --since=10m | START="$START" python3 -c '
import os, re, sys, collections
rx = re.compile(r"\[\d+/\w+/\d+:(\d\d:\d\d:\d\d) \+0000\] \"POST /api/agents/\S+/chat HTTP/1.1\" (\d+) \d+ \"-\" \"[^\"]*\" \d+ ([\d.]+) \[[^\]]*\] \[\] (\S+) ")
st = collections.defaultdict(collections.Counter)
for line in sys.stdin:
    m = rx.search(line)
    if not m or m.group(1) < os.environ["START"]: continue
    t, status, rt, up = m.groups()
    st[up]["n"] += 1; st[up]["s" + status] += 1
    if float(rt) > 20: st[up]["over20s"] += 1
for up in sorted(st): print(up, dict(st[up]))'
