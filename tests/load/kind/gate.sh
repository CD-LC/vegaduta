#!/usr/bin/env bash
# Load-test gate on a kind stage: a warmup, then the 32-user chat benchmark (scripts/turn-overhead.js against the mock
# model) with k6 thresholds. Exits non-zero when p95 turn latency or the failed-turn rate crosses its limit, so CI
# (.github/workflows/load-nightly.yml) and `make load-gate` fail on a latency regression.
#
# Required env: LT_USER LT_PASS LT_AGENT_ID (test user and the mock-model agent; see gate-provision.sh or the README).
# Optional env: VUS=32 WARMUP=20s DURATION=60s
#               GATE_P95_MS=1000 (p95 of the whole turn, ms)  GATE_MAX_FAILED_RATE=0.01  GATE_TTFT_P95_MS= (off)
#               OUT_DIR=load-gate-out  K6_IMAGE=grafana/k6:1.0.0  API_HOST=api.127.0.0.1.nip.io  AUTH_HOST=auth.127.0.0.1.nip.io
#               CTX=kind-vegaduta (must be a kind-* context; only used for the guard)
# How the default thresholds were picked: tests/load/README.md, "Load-test gate".
# Writes OUT_DIR/result.json (compact result, the same shape as bench.sh lines plus "gate") and OUT_DIR/k6-summary.json.
set -euo pipefail
CTX=${CTX:-kind-vegaduta}; case "$CTX" in kind-*) ;; *) echo "refusing: $CTX is not a kind context" >&2; exit 1;; esac
: "${LT_USER:?}" "${LT_PASS:?}" "${LT_AGENT_ID:?}"
VUS=${VUS:-32}; WARMUP=${WARMUP:-20s}; DURATION=${DURATION:-60s}
GATE_P95_MS=${GATE_P95_MS:-1000}; GATE_MAX_FAILED_RATE=${GATE_MAX_FAILED_RATE:-0.01}; GATE_TTFT_P95_MS=${GATE_TTFT_P95_MS:-}
K6_IMAGE=${K6_IMAGE:-grafana/k6:1.0.0}
API_HOST=${API_HOST:-api.127.0.0.1.nip.io}; AUTH_HOST=${AUTH_HOST:-auth.127.0.0.1.nip.io}
HERE=$(cd "$(dirname "$0")/.." && pwd)
OUT_DIR=${OUT_DIR:-load-gate-out}; mkdir -p "$OUT_DIR"; OUT_DIR=$(cd "$OUT_DIR" && pwd)

# $1 duration, $2 label, $3 result file name, then extra docker -e arguments (the gate thresholds).
k6run() {
  local duration=$1 label=$2 out=$3; shift 3
  # --user: on Linux the k6 container must be able to write into OUT_DIR, which belongs to the caller.
  docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp \
    --add-host "$API_HOST:host-gateway" --add-host "$AUTH_HOST:host-gateway" \
    -e BASE_URL="http://$API_HOST" -e KEYCLOAK_URL="http://$AUTH_HOST" \
    -e LOAD_TEST_USERNAME="$LT_USER" -e LOAD_TEST_PASSWORD="$LT_PASS" -e LOAD_TEST_AGENT_ID="$LT_AGENT_ID" \
    -e VUS="$VUS" -e DURATION="$duration" -e LABEL="$label" -e OUT="/out/$out" "$@" \
    -v "$HERE:/load-test:ro" -v "$OUT_DIR:/out" "$K6_IMAGE" run --quiet /load-test/scripts/turn-overhead.js
}

echo ">> load gate: vus=$VUS warmup=$WARMUP duration=$DURATION p95<${GATE_P95_MS}ms failed<=${GATE_MAX_FAILED_RATE}${GATE_TTFT_P95_MS:+ ttft_p95<${GATE_TTFT_P95_MS}ms}" >&2
echo ">> warmup (no thresholds)" >&2
k6run "$WARMUP" "load-gate warmup vus=$VUS" warmup.json >/dev/null

gate_env=(-e GATE_P95_MS="$GATE_P95_MS" -e GATE_MAX_FAILED_RATE="$GATE_MAX_FAILED_RATE" -e SUMMARY_OUT=/out/k6-summary.json)
[[ -n $GATE_TTFT_P95_MS ]] && gate_env+=(-e GATE_TTFT_P95_MS="$GATE_TTFT_P95_MS")
echo ">> measured run" >&2
rm -f "$OUT_DIR/result.json" "$OUT_DIR/k6-summary.json"
rc=0
k6run "$DURATION" "load-gate vus=$VUS" result.json "${gate_env[@]}" >/dev/null || rc=$?
if [[ -f $OUT_DIR/result.json ]]; then cat "$OUT_DIR/result.json"; fi
case $rc in
  0) echo ">> load gate PASSED" >&2 ;;
  99) echo ">> load gate FAILED: a threshold was crossed (see \"gate\" in $OUT_DIR/result.json)" >&2 ;;
  *) echo ">> load gate FAILED: k6 exited $rc (setup or run error, not a threshold)" >&2 ;;
esac
exit "$rc"
