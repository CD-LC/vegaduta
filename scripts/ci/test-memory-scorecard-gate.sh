#!/usr/bin/env bash
# Self-test for memory-scorecard-gate.sh: runs it on the fixtures in scripts/ci/testdata/memory-scorecard
# (plus small jq mutations of them) and checks the exit code of each. No network, nothing is modified.
# Usage: bash scripts/ci/test-memory-scorecard-gate.sh
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
gate="$here/memory-scorecard-gate.sh"
fx="$here/testdata/memory-scorecard"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
rc=0

expect() { # name, wanted exit code, then gate arguments
  local name=$1 want=$2 got
  shift 2
  bash "$gate" --quiet "$@" >/dev/null 2>&1
  got=$?
  if [ "$got" -eq "$want" ]; then
    echo "ok   $name"
  else
    echo "FAIL $name: wanted exit $want, got $got"
    rc=1
  fi
}

mutate() { # out-file, jq filter, source fixture
  jq "$2" "$3" > "$tmp/$1"
}

expect "ship fixture"                          0 "$fx/ship.json"
expect "ship dark fixture"                     3 "$fx/ship-dark.json"
expect "no-ship fixture (G7 fails)"            1 "$fx/no-ship.json"

# A hard gate failing, whatever the report claims, is a no-ship (and a lying report is invalid).
mutate lying.json '.gates |= map(if .id=="G8" then .status="FAIL" else . end)' "$fx/ship.json"
expect "report claims SHIP but G8 fails"       2 "$tmp/lying.json"
mutate g8.json '.verdict="NO-SHIP" | .gates |= map(if .id=="G8" then .status="FAIL" else . end)' "$fx/ship.json"
expect "G8 fails -> NO-SHIP"                   1 "$tmp/g8.json"
mutate g10.json '.verdict="NO-SHIP" | .gates |= map(if .id=="G10" then .status="FAIL" else . end)' "$fx/ship.json"
expect "G10 fails -> NO-SHIP"                  1 "$tmp/g10.json"

# A missing hard gate is not a pass.
mutate nog7.json '.verdict="NO-SHIP" | .gates |= map(select(.id!="G7"))' "$fx/ship.json"
expect "G7 missing -> NO-SHIP"                 1 "$tmp/nog7.json"

# G1 failing: no-ship only when memory is default-on, otherwise it can still land dark.
mutate g1on.json '.verdict="NO-SHIP" | .gates |= map(if .id=="G1" then .status="FAIL" else . end)' "$fx/ship.json"
expect "G1 fails with memory default-on"       1 "$tmp/g1on.json"
mutate g1off.json '.verdict="SHIP DARK" | .memoryDefaultOn=false | .gates |= map(if .id=="G1" then .status="FAIL" else . end)' "$fx/ship.json"
expect "G1 fails with memory dark"             3 "$tmp/g1off.json"

# G11 may warn or even fail without blocking a ship.
mutate g11.json '.gates |= map(if .id=="G11" then .status="FAIL" else . end)' "$fx/ship.json"
expect "G11 fail is only a warning"            0 "$tmp/g11.json"

# G2/G3 are required: they cannot hide behind a dark ship.
mutate g2.json '.verdict="NO-SHIP" | .gates |= map(if .id=="G2" then .status="FAIL" else . end)' "$fx/ship-dark.json"
expect "G2 fails -> NO-SHIP, not dark"         1 "$tmp/g2.json"

# Invalid input.
echo '{"schemaVersion":2,"verdict":"SHIP","gates":[]}' > "$tmp/badschema.json"
expect "unsupported schemaVersion"             2 "$tmp/badschema.json"
echo 'not json' > "$tmp/garbage.json"
expect "not json"                              2 "$tmp/garbage.json"
mutate badstatus.json '.gates[0].status="GREEN"' "$fx/ship.json"
expect "unknown gate status"                   2 "$tmp/badstatus.json"
expect "missing file"                          2 "$tmp/does-not-exist.json"
expect "no argument"                           2
expect "unknown option"                        2 --nope "$fx/ship.json"

# Staleness: fixtures are generated 2026-11-01T02:00:00Z.
now_fresh=$(date -u -d '2026-11-04T02:00:00Z' +%s)
now_old=$(date -u -d '2026-11-20T02:00:00Z' +%s)
MEMORY_SCORECARD_NOW_EPOCH=$now_fresh expect "fresh report within 7 days"  0 --max-age-days 7 "$fx/ship.json"
MEMORY_SCORECARD_NOW_EPOCH=$now_old expect "stale report beyond 7 days"    4 --max-age-days 7 "$fx/ship.json"
mutate nots.json 'del(.generatedAt)' "$fx/ship.json"
expect "age check without timestamp"           4 --max-age-days 7 "$tmp/nots.json"
expect "non-numeric max age"                   2 --max-age-days soon "$fx/ship.json"

exit "$rc"
