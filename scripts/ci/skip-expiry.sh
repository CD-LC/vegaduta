#!/usr/bin/env bash
# static-gates: expired test-skip check.
#
# New convention (see CONTRIBUTING.md, "Skipping a test"): any
# test.fixme(...), @Disabled(...), or .skip(...) that represents a KNOWN,
# tracked gap (a product bug filed for later, a flaky spec, an
# environment-dependent test) must carry an `expires:YYYY-MM-DD` token in
# its reason string / annotation value, e.g.:
#   test.fixme(true, "Product bug PLAT-482, expires:2026-08-15");
#   @Disabled("Flaky under load, see INFRA-91, expires:2026-09-01")
# Past the expiry date, this script fails the build - the skip has to be
# either fixed or given a fresh, deliberately-reconsidered date, so a
# "temporary" skip can't silently live forever.
#
# Deliberately does NOT flag every skip/fixme/@Disabled call in the repo -
# only ones that opt into this convention by carrying an expires: token.
# Plenty of existing skips are environment-gated, not "known gap" skips
# (e.g. web/e2e's test.skip(!process.env.E2E_SAMPLE_PDF_PATH, ...) calls,
# which skip only when an optional local fixture path isn't set - those
# aren't tracked debt and don't need an expiry).
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

TODAY="$(date -u +%Y-%m-%d)"

# Matches test.fixme(...), .skip(...) (Playwright/Vitest/Jest-style), and
# @Disabled(...) (JUnit), only when the same line also carries an
# expires:YYYY-MM-DD token - git grep's -o would split these apart, so this
# greps for lines matching both patterns via -E alternation-in-sequence,
# then a second pass extracts the date.
SKIP_MARKER_RE='test\.fixme\(|@Disabled|\.skip\('
EXPIRES_RE='expires:([0-9]{4}-[0-9]{2}-[0-9]{2})'

# --untracked: a PR's new test file (the most likely place a fresh skip
# shows up) is untracked until committed - see the note on
# the same gotcha, confirmed against this repo's own working tree.
MATCHES="$(git grep --untracked -nIE "$SKIP_MARKER_RE" -- '*.java' '*.ts' '*.tsx' '*.js' '*.jsx' \
  ':!**/node_modules/**' ':!**/.next/**' ':!**/target/**' ':!**/dist/**' 2>/dev/null || true)"

FAIL=0
CHECKED=0

if [[ -n "$MATCHES" ]]; then
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    if [[ "$line" =~ $EXPIRES_RE ]]; then
      expiry="${BASH_REMATCH[1]}"
      CHECKED=$((CHECKED + 1))
      if [[ "$expiry" < "$TODAY" ]]; then
        echo "FAIL: expired skip (expires:$expiry, today:$TODAY): $line" >&2
        FAIL=1
      fi
    fi
    # Lines matching the skip marker but with NO expires: token are left
    # alone - see header comment on why not every skip needs one.
  done <<< "$MATCHES"
fi

echo "skip-expiry: checked $CHECKED skip annotation(s) carrying an expires: token"

if [[ "$FAIL" -ne 0 ]]; then
  echo "skip-expiry: FAILED - one or more test skips are past their expiry date. Fix the test or extend the date with a fresh, deliberate reason." >&2
  exit 1
fi
echo "skip-expiry: OK"
