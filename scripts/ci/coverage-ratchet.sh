#!/usr/bin/env bash
# coverage-ratchet.sh - fail when a module's line coverage drops more than 1 percentage point below
# the committed baseline (scripts/ci/coverage-baseline.json).
#
# JaCoCo (mvn -Pcoverage verify) writes <module>/target/site/jacoco/jacoco.csv. This sums
# LINE_MISSED / LINE_COVERED per module and compares the percentage with the baseline.
#
#   bash scripts/ci/coverage-ratchet.sh                   compare (core/ is searched for reports)
#   bash scripts/ci/coverage-ratchet.sh --update          rewrite the baseline from the reports
#   bash scripts/ci/coverage-ratchet.sh --dir DIR         search DIR instead of core/ (e.g. an
#                                                         unpacked core-coverage CI artifact)
#   bash scripts/ci/coverage-ratchet.sh --baseline FILE   use another baseline file
#   COVERAGE_RATCHET_TOLERANCE=1.0                        allowed drop in percentage points
#
# While the baseline's "modules" object is empty this is a no-op (exit 0, with a message): the
# baseline cannot be seeded without a full `mvn -Pcoverage verify`, so the first run on a runner
# (download the core-coverage artifact, run with --update --dir <it>) creates it. Modules that
# have a baseline entry but no report in this run are warned about, not failed (a path-filtered
# or partial run). Modules without a baseline entry are reported as new and never fail.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DIR="$ROOT/core"
BASELINE="$ROOT/scripts/ci/coverage-baseline.json"
TOLERANCE="${COVERAGE_RATCHET_TOLERANCE:-1.0}"
UPDATE=0

while [ $# -gt 0 ]; do
  case "$1" in
    --update) UPDATE=1 ;;
    --dir) DIR="${2:?--dir needs a directory}"; shift ;;
    --baseline) BASELINE="${2:?--baseline needs a file}"; shift ;;
    -h | --help) sed -n '2,19p' "$0"; exit 0 ;;
    *) echo "coverage-ratchet: unknown option $1" >&2; exit 2 ;;
  esac
  shift
done

command -v jq >/dev/null 2>&1 || { echo "coverage-ratchet: jq is required" >&2; exit 2; }

# Current coverage: "module percentage" lines, one per module with at least one instrumented line.
current() {
  find "$DIR" -path '*/target/site/jacoco/jacoco.csv' -type f 2>/dev/null | sort | while IFS= read -r csv; do
    module="${csv%/target/site/jacoco/jacoco.csv}"
    module="${module##*/}"
    awk -F, -v m="$module" '
      NR == 1 { for (i = 1; i <= NF; i++) { if ($i == "LINE_MISSED") mi = i; if ($i == "LINE_COVERED") co = i } next }
      mi && co { missed += $mi; covered += $co }
      END { if (missed + covered > 0) printf "%s %.2f\n", m, 100 * covered / (missed + covered) }
    ' "$csv"
  done
}

CURRENT="$(current)"

if [ "$UPDATE" -eq 1 ]; then
  [ -n "$CURRENT" ] || { echo "coverage-ratchet: no jacoco.csv found under $DIR; run mvn -Pcoverage verify first" >&2; exit 2; }
  printf '%s\n' "$CURRENT" | jq -R -s '
    { description: "Line coverage per core module (percent), refreshed by scripts/ci/coverage-ratchet.sh --update. CI fails a module that drops more than 1 point.",
      modules: (split("\n") | map(select(length > 0) | split(" ") | {key: .[0], value: (.[1] | tonumber)}) | from_entries) }' \
    >"$BASELINE"
  echo "coverage-ratchet: baseline written to $BASELINE ($(printf '%s\n' "$CURRENT" | wc -l | tr -d ' ') modules)"
  exit 0
fi

[ -f "$BASELINE" ] || { echo "coverage-ratchet: baseline $BASELINE not found" >&2; exit 2; }
count="$(jq '.modules | length' "$BASELINE")"
if [ "$count" -eq 0 ]; then
  echo "coverage-ratchet: baseline has no modules yet; nothing to compare (no-op)."
  echo "  Seed it from a runner's core-coverage artifact: bash scripts/ci/coverage-ratchet.sh --update --dir <dir>"
  exit 0
fi
[ -n "$CURRENT" ] || { echo "coverage-ratchet: no jacoco.csv found under $DIR (did the coverage run happen?)" >&2; exit 2; }

fail=0
while read -r module pct; do
  base="$(jq -r --arg m "$module" '.modules[$m] // empty' "$BASELINE")"
  if [ -z "$base" ]; then
    echo "NEW   $module: ${pct}% (no baseline entry; add it with --update)"
    continue
  fi
  if awk -v c="$pct" -v b="$base" -v t="$TOLERANCE" 'BEGIN { exit !(c < b - t) }'; then
    echo "FAIL  $module: ${pct}% < baseline ${base}% minus ${TOLERANCE} point(s)"
    fail=1
  else
    echo "ok    $module: ${pct}% (baseline ${base}%)"
  fi
done <<<"$CURRENT"

while IFS= read -r module; do
  grep -q "^$module " <<<"$CURRENT" || echo "WARN  $module: in the baseline but no report in this run"
done < <(jq -r '.modules | keys[]' "$BASELINE")

if [ "$fail" -ne 0 ]; then
  echo "coverage-ratchet: coverage dropped. Add tests, or if the drop is deliberate refresh the baseline with --update." >&2
  exit 1
fi
echo "coverage-ratchet: OK"
