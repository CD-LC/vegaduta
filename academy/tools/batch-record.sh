#!/usr/bin/env bash
# Serial lesson producer: record -> narrate (if missing) -> assemble -> publish.
# Usage: batch-record.sh <lesson-dir> [<lesson-dir> ...]
set -u
cd "$(dirname "$0")/../.."
export ACADEMY_PASSWORD="${ACADEMY_PASSWORD:-academy-capture-2026}"
LIB="$HOME/vegaduta-academy"
mkdir -p "$LIB"

for L in "$@"; do
  D="academy/lessons/$L"
  [ -f "$D/script.mjs" ] || { echo "SKIP $L (no script)"; continue; }
  echo "=== RECORD $L $(date +%H:%M:%S) ==="
  (cd tests/e2e && node video/record.mjs "../../$D") || { echo "FAIL-RECORD $L"; continue; }
  echo "=== NARRATE $L ==="
  bash academy/tools/narrate.sh "$D" 2>&1 | tail -2
  echo "=== ASSEMBLE $L ==="
  bash academy/tools/assemble.sh "$D" || { echo "FAIL-ASSEMBLE $L"; continue; }
  SLUG=$(node --input-type=module -e "console.log((await import('$PWD/$D/script.mjs')).default.slug)")
  cp -f "$D/dist/$SLUG.mp4" "$D/dist/$SLUG.srt" "$LIB/" 2>/dev/null && echo "PUBLISHED $SLUG"
done
echo "=== BATCH DONE $(date +%H:%M:%S) ==="
