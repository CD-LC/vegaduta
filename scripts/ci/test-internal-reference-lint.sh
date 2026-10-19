#!/usr/bin/env bash
# Self-test for internal-reference-lint.sh and no-telemetry-check.sh: plants an offending
# untracked file, expects each lint to fail on it and pass without it.
# Usage: bash scripts/ci/test-internal-reference-lint.sh
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 1

probe_ref=scripts/ci/zz-lint-probe-ref.md
probe_tel=web/app/zz-lint-probe-telemetry.ts
trap 'rm -f "$probe_ref" "$probe_tel"' EXIT
rc=0
expect() { # name, expected (0|1), command...
  local name=$1 want=$2; shift 2
  "$@" >/dev/null 2>&1
  local got=$?
  if [ "$got" -ne "$want" ]; then echo "FAIL $name: exit $got, wanted $want"; rc=1; else echo "ok   $name"; fi
}

expect "ref: clean tree passes" 0 bash scripts/ci/internal-reference-lint.sh
printf 'Implements Program B per plan §4.\n' > "$probe_ref"
expect "ref: new offender fails" 1 bash scripts/ci/internal-reference-lint.sh
rm -f "$probe_ref"
printf 'This is a program b of sorts.\n' > "$probe_ref"
expect "ref: lowercase program b is not jargon" 0 bash scripts/ci/internal-reference-lint.sh
rm -f "$probe_ref"

expect "telemetry: clean tree passes" 0 bash scripts/ci/no-telemetry-check.sh
printf 'import posthog from "posthog-js";\n' > "$probe_tel"
expect "telemetry: analytics SDK fails" 1 bash scripts/ci/no-telemetry-check.sh
rm -f "$probe_tel"
printf 'fetch("https://api.segment.io/v1/track");\n' > "$probe_tel"
expect "telemetry: analytics host fails" 1 bash scripts/ci/no-telemetry-check.sh
rm -f "$probe_tel"
exit $rc
