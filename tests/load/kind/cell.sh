#!/usr/bin/env bash
# Benchmark the CURRENTLY DEPLOYED configuration of a local kind stage (no helm changes): warmup + measured k6 run per
# user count, one JSON line each to $OUT_FILE. Use this to test a values overlay; bench.sh is for config matrices.
# Required env: LT_USER LT_PASS LT_AGENT_ID   Optional: VUS_LIST="1 8 32" WARMUP=20s DURATION=60s OUT_FILE=cell.jsonl LABEL=
set -euo pipefail
CTX=${CTX:-kind-vegaduta}; case "$CTX" in kind-*) ;; *) echo "refusing: $CTX is not a kind context" >&2; exit 1;; esac
: "${LT_USER:?}" "${LT_PASS:?}" "${LT_AGENT_ID:?}"
VUS_LIST=${VUS_LIST:-"1 8 32"}; WARMUP=${WARMUP:-20s}; DURATION=${DURATION:-60s}; OUT_FILE=${OUT_FILE:-cell.jsonl}; LABEL=${LABEL:-deployed}
HERE=$(cd "$(dirname "$0")/.." && pwd); WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
run() {
  docker run --rm --add-host api.127.0.0.1.nip.io:host-gateway --add-host auth.127.0.0.1.nip.io:host-gateway \
    -e BASE_URL=http://api.127.0.0.1.nip.io -e KEYCLOAK_URL=http://auth.127.0.0.1.nip.io \
    -e LOAD_TEST_USERNAME="$LT_USER" -e LOAD_TEST_PASSWORD="$LT_PASS" -e LOAD_TEST_AGENT_ID="$LT_AGENT_ID" \
    -e VUS="$2" -e DURATION="$1" -e LABEL="$3" -e OUT=/out/"$4" \
    -v "$HERE:/load-test:ro" -v "$WORK:/out" grafana/k6 run --quiet /load-test/scripts/turn-overhead.js >/dev/null 2>"$WORK/k6.err" \
    || { cat "$WORK/k6.err" >&2; return 1; }
}
for v in $VUS_LIST; do
  run "$WARMUP" "$v" warmup warmup.json
  run "$DURATION" "$v" "$LABEL vus=$v" result.json
  python3 -c "import json,sys; d=json.load(open('$WORK/result.json')); print(json.dumps(d))" | tee -a "$OUT_FILE"
done
