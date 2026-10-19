#!/bin/sh
# File-size ratchet with a hard cap.
#
# Every tracked source file must stay at or under the near-cap threshold:
# FILE_SIZE_CAP lines (default 700) minus FILE_SIZE_NEAR_CAP_PCT percent
# (default 5), i.e. 665 lines by default. A file is reported as
#   "over cap"                   when it has more than FILE_SIZE_CAP lines, or
#   "within 5% of cap (665-700)" when it is above the threshold but at or
#                                under the cap (stops the "990 of 1000" pattern).
# Either fails unless the file is in scripts/ci/file-size-baseline.txt.
#
# The baseline is shrink-only:
#   - a baselined file may not grow past its recorded line count (FAIL);
#   - a baselined file that shrank, or dropped under the threshold, only WARNs
#     (lower or drop the entry with --update);
#   - the baseline may not gain paths in a change: a path that is in the
#     baseline now but was not in it at the merge-base with the base branch
#     FAILs. Checked only when a merge-base is available (base ref:
#     FILE_SIZE_BASE_REF, else origin/$GITHUB_BASE_REF, else origin/main) and
#     the merge-base baseline was written for the same cap and margin (its
#     "# cap:" line); otherwise skipped silently.
#
#   scripts/ci/check-file-size.sh            check
#   scripts/ci/check-file-size.sh --update   regenerate the baseline (keeps the
#                                            header comments, rewrites "# cap:")
#
# Env: FILE_SIZE_CAP (default 700), FILE_SIZE_NEAR_CAP_PCT (default 5),
#      FILE_SIZE_THRESHOLD (overrides the computed near-cap threshold),
#      FILE_SIZE_BASELINE (baseline path), FILE_SIZE_BASE_REF (see above).
# Generated files (*.generated.ts and anything under a generated/ directory)
# are excluded. Uses one batched `wc -l` (a per-file loop is >120s on Windows).
set -eu

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"
CAP=${FILE_SIZE_CAP:-700}
PCT=${FILE_SIZE_NEAR_CAP_PCT:-5}
THRESHOLD=${FILE_SIZE_THRESHOLD:-$(( CAP - CAP * PCT / 100 ))}
BASELINE=${FILE_SIZE_BASELINE:-scripts/ci/file-size-baseline.txt}
CAP_LINE="# cap: $CAP near-cap-pct: $PCT threshold: $THRESHOLD"
TMP=$(mktemp)
trap 'rm -f "$TMP" "$TMP.base" "$TMP.mb" "$TMP.hdr"' EXIT

# "<path> <lines>" for every tracked source file over the threshold, minus
# excluded areas. Trailing awk drops wc's "total" line(s).
git ls-files -z -- '*.java' '*.ts' '*.tsx' '*.kt' '*.swift' '*.py' '*.css' \
  | grep -zEv '(^|/)(node_modules|generated|public|messages|locales|_to_delete[^/]*)(/|$)|\.generated\.ts$|package-lock\.json|pnpm-lock|yarn\.lock' \
  | xargs -0 wc -l 2>/dev/null \
  | awk -v t="$THRESHOLD" '{ n=$1; sub(/^ *[0-9]+ /,""); if ($0 != "total" && n > t) print $0 " " n }' \
  | LC_ALL=C sort > "$TMP"

if [ "${1:-}" = "--update" ]; then
  # Keep the leading comment block (minus the old cap line); write a default
  # header when the baseline is new.
  if [ -f "$BASELINE" ]; then
    awk '/^#/ { if ($0 !~ /^# cap:/) print; next } { exit }' "$BASELINE" > "$TMP.hdr"
  else
    : > "$TMP.hdr"
  fi
  if [ ! -s "$TMP.hdr" ]; then
    printf '%s\n' \
      "# File-size baseline for scripts/ci/check-file-size.sh. SHRINK-ONLY: never add" \
      "# a path or raise a count to make a build pass; split the file instead." > "$TMP.hdr"
  fi
  { cat "$TMP.hdr"; echo "$CAP_LINE"; cat "$TMP"; } > "$BASELINE"
  echo "Baseline updated: $(wc -l < "$TMP" | tr -d ' ') files over $THRESHOLD lines (cap $CAP, near-cap ${PCT}%) -> $BASELINE"
  exit 0
fi

[ -f "$BASELINE" ] || { echo "Missing baseline $BASELINE (run with --update)"; exit 2; }
grep -v '^[[:space:]]*\(#\|$\)' "$BASELINE" > "$TMP.base" || true

FAIL=0
# Path = everything but the last field (paths may contain spaces).
if ! awk -v t="$THRESHOLD" -v cap="$CAP" -v pct="$PCT" '
  FNR==NR { n=$NF; p=$0; sub(/ [0-9]+$/,"",p); base[p]=n; next }
  { n=$NF; p=$0; sub(/ [0-9]+$/,"",p); seen[p]=1
    if (!(p in base)) {
      if (n+0 > cap+0) printf "FAIL: %s has %d lines: over cap (> %d) and not in the baseline\n", p, n, cap
      else printf "FAIL: %s has %d lines: within %d%% of cap (%d-%d) and not in the baseline\n", p, n, pct, t, cap
      fail=1 }
    else if (n+0 > base[p]+0) { printf "FAIL: %s grew to %d lines (baseline max %d)\n", p, n, base[p]; fail=1 }
    else if (n+0 < base[p]+0) { printf "WARN: %s shrank to %d lines (baseline %d) - lower the baseline (--update)\n", p, n, base[p] } }
  END {
    for (p in base) if (!(p in seen)) printf "WARN: %s is now <= %d lines (or removed) - drop it from the baseline (--update)\n", p, t
    exit fail
  }' "$TMP.base" "$TMP"; then
  FAIL=1
fi

# Baseline-growth ratchet: no path may enter the baseline in a change.
BASE_REF=${FILE_SIZE_BASE_REF:-}
if [ -z "$BASE_REF" ] && [ -n "${GITHUB_BASE_REF:-}" ]; then BASE_REF="origin/$GITHUB_BASE_REF"; fi
[ -n "$BASE_REF" ] || BASE_REF=origin/main
if MB=$(git merge-base HEAD "$BASE_REF" 2>/dev/null) \
   && git show "$MB:$BASELINE" > "$TMP.mb" 2>/dev/null \
   && grep -qxF "$CAP_LINE" "$TMP.mb"; then
  if ! awk '
    FNR==NR { if ($0 !~ /^[[:space:]]*(#|$)/) { p=$0; sub(/ [0-9]+$/,"",p); old[p]=1 } next }
    { p=$0; sub(/ [0-9]+$/,"",p)
      if (!(p in old)) { printf "FAIL: %s was added to the baseline (not in it at the merge-base) - the baseline may only shrink; split the file\n", p; fail=1 } }
    END { exit fail }' "$TMP.mb" "$TMP.base"; then
    FAIL=1
  fi
fi

if [ "$FAIL" -eq 0 ]; then
  echo "file-size ratchet: OK (cap $CAP, fails above $THRESHOLD unless baselined)"
else
  echo "file-size ratchet: FAILED (split the file, or see CONTRIBUTING.md#checks-make-gates)"
  exit 1
fi
