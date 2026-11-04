#!/usr/bin/env bash
# Memory scorecard ship / no-ship gate (docs/architecture/phase1/04-measurement-scorecard.md, sections 7, 8
# and the "memory-scorecard.json schema" addendum).
#
# Reads a memory-scorecard.json report, recomputes the verdict from the per-gate results with the
# mechanical rules of section 7 and exits accordingly:
#
#   0  SHIP       all of G1..G10 pass (G11 may warn)
#   3  SHIP DARK  G7 and G8 pass, but G1 or G9 is not (yet) proven: feature flag stays off by default
#   1  NO-SHIP    any of G4, G5, G6, G7, G8, G10 failed or is missing, G1 failed with memory default-on,
#                 or any other required gate failed
#   2  invalid    unreadable report, bad schema, or the report's own "verdict" disagrees with its gates
#   4  stale      --max-age-days was given and the report is older than that (or has no usable timestamp)
#
# SHIP DARK is deliberately NOT zero: a caller that only wants "green or red" must opt in to accepting it
# (exit 3), so a feature that is not proven cannot be mistaken for a shippable one.
#
# Usage: memory-scorecard-gate.sh [--max-age-days N] [--quiet] <memory-scorecard.json>
# Needs: bash, jq. No network, no secrets.
set -euo pipefail

max_age_days=""
quiet=0
report=""

usage() {
  sed -n '2,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//' >&2
}

while [ $# -gt 0 ]; do
  case "$1" in
    --max-age-days)
      [ $# -ge 2 ] || { echo "memory-scorecard-gate: --max-age-days needs a value" >&2; exit 2; }
      max_age_days="$2"; shift 2 ;;
    --quiet) quiet=1; shift ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "memory-scorecard-gate: unknown option $1" >&2; exit 2 ;;
    *)
      [ -z "$report" ] || { echo "memory-scorecard-gate: more than one report given" >&2; exit 2; }
      report="$1"; shift ;;
  esac
done

if [ -z "$report" ]; then usage; exit 2; fi
command -v jq >/dev/null 2>&1 || { echo "memory-scorecard-gate: jq is required" >&2; exit 2; }
[ -r "$report" ] || { echo "memory-scorecard-gate: cannot read $report" >&2; exit 2; }
if [ -n "$max_age_days" ] && ! [[ "$max_age_days" =~ ^[0-9]+$ ]]; then
  echo "memory-scorecard-gate: --max-age-days must be a whole number" >&2
  exit 2
fi

say() { [ "$quiet" -eq 1 ] || echo "$@"; }

# Schema check: version 1, a verdict string, and an array of {id, status} gates with known statuses.
if ! jq -e '
  .schemaVersion == 1
  and (.verdict | type == "string")
  and (.gates | type == "array" and length > 0)
  and all(.gates[]; (.id | type == "string") and (.status | IN("PASS","FAIL","WARN","UNPROVEN")))
  and ((.memoryDefaultOn // false) | type == "boolean")
' "$report" >/dev/null 2>&1; then
  echo "memory-scorecard-gate: $report is not a valid schemaVersion 1 memory scorecard" >&2
  exit 2
fi

# status_of ID -> PASS|FAIL|WARN|UNPROVEN ; a gate absent from the report counts as UNPROVEN.
status_of() {
  jq -r --arg id "$1" '[.gates[] | select(.id == $id) | .status] | first // "UNPROVEN"' "$report"
}

default_on="$(jq -r '.memoryDefaultOn // false' "$report")"

computed=""

# 1. NO-SHIP: hard gates must PASS (a missing or unproven hard gate is not a pass).
for g in G4 G5 G6 G7 G8 G10; do
  if [ "$(status_of "$g")" != "PASS" ]; then
    computed="NO-SHIP"
    say "  $g is $(status_of "$g"): hard gate not passed"
  fi
done
# 2. G1 failing outright with memory default-on.
if [ "$(status_of G1)" = "FAIL" ] && [ "$default_on" = "true" ]; then
  computed="NO-SHIP"
  say "  G1 FAIL with memory default-on"
fi
# 3. Any other required gate (G2, G3, G9 when it FAILs, ...) that failed: a FAIL anywhere is a no-ship,
#    except G1/G9 which only mean "not yet proven" when memory is not default-on (handled below). G11 may warn.
if [ -z "$computed" ]; then
  while IFS= read -r g; do
    case "$g" in G1|G9|G11) continue ;; esac
    computed="NO-SHIP"
    say "  $g failed"
  done < <(jq -r '.gates[] | select(.status == "FAIL") | .id' "$report")
fi
if [ -z "$computed" ] && [ "$(status_of G11)" = "FAIL" ]; then
  say "  G11 failed (growth): reported as a warning, not a blocker"
fi

# 4. SHIP vs SHIP DARK among the survivors. G2, G3 and every other G1..G10 must PASS for a full SHIP.
if [ -z "$computed" ]; then
  not_pass=0
  for n in 1 2 3 4 5 6 7 8 9 10; do
    [ "$(status_of "G$n")" = "PASS" ] || not_pass=1
  done
  if [ "$not_pass" -eq 0 ]; then
    computed="SHIP"
  else
    # Only G1 / G9 may be unproven for a dark ship; any other non-PASS gate is a no-ship.
    computed="SHIP DARK"
    for n in 2 3; do
      if [ "$(status_of "G$n")" != "PASS" ]; then
        computed="NO-SHIP"
        say "  G$n is $(status_of "G$n"): required gate not passed"
      fi
    done
  fi
fi

claimed="$(jq -r '.verdict' "$report")"
say "memory scorecard: computed verdict $computed (report claims $claimed)"
if [ "$claimed" != "$computed" ]; then
  echo "memory-scorecard-gate: report verdict '$claimed' disagrees with its gates ('$computed')" >&2
  exit 2
fi

if [ -n "$max_age_days" ]; then
  generated="$(jq -r '.generatedAt // empty' "$report")"
  if ! gen_epoch="$(date -u -d "$generated" +%s 2>/dev/null)" || [ -z "$generated" ]; then
    echo "memory-scorecard-gate: report has no parseable generatedAt, cannot check age" >&2
    exit 4
  fi
  now_epoch="${MEMORY_SCORECARD_NOW_EPOCH:-$(date -u +%s)}"
  age_days=$(( (now_epoch - gen_epoch) / 86400 ))
  if [ "$age_days" -gt "$max_age_days" ]; then
    echo "memory-scorecard-gate: report is $age_days days old (max $max_age_days)" >&2
    exit 4
  fi
fi

case "$computed" in
  "SHIP") exit 0 ;;
  "SHIP DARK") exit 3 ;;
  *) exit 1 ;;
esac
