#!/bin/sh
# Module-size ratchet (ADR-0023 section 6).
#
# Counts two things over tracked files:
#   core/<module>                    main Java files of each core/* Maven module
#                                    (core/<module>/src/main/java/**/*.java);
#   package:com.agenticai.core.agents  classes directly in the root package
#                                    com.agenticai.core.agents, across all modules
#                                    (core/*/src/main/java/com/agenticai/core/agents/*.java).
#
# Limits: a module may hold at most MODULE_SIZE_MAX main files (default 300);
# the root agents package should hold none (limit 0). An entry above its limit
# fails unless it is in scripts/ci/module-size-baseline.txt, which records
# today's count for every entry over its limit and may only shrink:
#   - a baselined entry may not grow past its recorded count (FAIL);
#   - a baselined entry that shrank, or dropped to its limit, only WARNs
#     (lower or drop the entry with --update);
#   - a new module (or one not in the baseline) above the limit FAILs: split it;
#   - the baseline may not gain entries in a change: an entry that is in the
#     baseline now but was not in it at the merge-base with the base branch
#     FAILs. Checked only when a merge-base is available (base ref:
#     MODULE_SIZE_BASE_REF, else origin/$GITHUB_BASE_REF, else origin/main) and
#     the merge-base baseline exists with the same "# max:" line; otherwise
#     skipped silently.
#
#   scripts/ci/check-module-size.sh            check
#   scripts/ci/check-module-size.sh --update   regenerate the baseline (keeps the
#                                              header comments, rewrites "# max:")
#
# Env: MODULE_SIZE_MAX (default 300), MODULE_SIZE_BASELINE (baseline path),
#      MODULE_SIZE_BASE_REF (see above).
set -eu

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"
MAX=${MODULE_SIZE_MAX:-300}
ROOT_PKG=com.agenticai.core.agents
ROOT_PKG_DIR=com/agenticai/core/agents
BASELINE=${MODULE_SIZE_BASELINE:-scripts/ci/module-size-baseline.txt}
MAX_LINE="# max: $MAX root-package: $ROOT_PKG 0"
TMP=$(mktemp)
trap 'rm -f "$TMP" "$TMP.all" "$TMP.base" "$TMP.mb" "$TMP.hdr"' EXIT

# "<key> <count> <limit>" for every module and the root package, sorted by key.
git ls-files -- 'core/*/src/main/java/*.java' \
  | awk -v max="$MAX" -v pkg="$ROOT_PKG" -v dir="$ROOT_PKG_DIR" '
      { split($0, a, "/"); mod["core/" a[2]]++
        rest = $0; sub(/^core\/[^\/]+\/src\/main\/java\//, "", rest)
        if (index(rest, dir "/") == 1 && index(substr(rest, length(dir) + 2), "/") == 0) root++ }
      END {
        for (m in mod) print m " " mod[m] " " max
        print "package:" pkg " " (root + 0) " 0"
      }' \
  | LC_ALL=C sort > "$TMP.all"
# Entries over their limit: what the baseline records.
awk '$2 + 0 > $3 + 0 { print $1 " " $2 }' "$TMP.all" > "$TMP"

if [ "${1:-}" = "--update" ]; then
  if [ -f "$BASELINE" ]; then
    awk '/^#/ { if ($0 !~ /^# max:/) print; next } { exit }' "$BASELINE" > "$TMP.hdr"
  else
    : > "$TMP.hdr"
  fi
  if [ ! -s "$TMP.hdr" ]; then
    printf '%s\n' \
      "# Module-size baseline for scripts/ci/check-module-size.sh (ADR-0023 section 6)." \
      "# SHRINK-ONLY: never add an entry or raise a count to make a build pass; split" \
      "# the module (or move classes out of the root package) instead." > "$TMP.hdr"
  fi
  { cat "$TMP.hdr"; echo "$MAX_LINE"; cat "$TMP"; } > "$BASELINE"
  echo "Baseline updated: $(wc -l < "$TMP" | tr -d ' ') entries over their limit (module max $MAX, root package 0) -> $BASELINE"
  exit 0
fi

[ -f "$BASELINE" ] || { echo "Missing baseline $BASELINE (run with --update)"; exit 2; }
grep -v '^[[:space:]]*\(#\|$\)' "$BASELINE" > "$TMP.base" || true

FAIL=0
if ! awk '
  FNR==NR { base[$1] = $2; next }
  { k = $1; n = $2 + 0; lim = $3 + 0; seen[k] = 1
    if (k in base) {
      if (n > base[k] + 0) { printf "FAIL: %s has %d main files (baseline max %d) - the baseline may only shrink\n", k, n, base[k]; fail = 1 }
      else if (n <= lim) { printf "WARN: %s is now at %d (limit %d) - drop it from the baseline (--update)\n", k, n, lim }
      else if (n < base[k] + 0) { printf "WARN: %s shrank to %d (baseline %d) - lower the baseline (--update)\n", k, n, base[k] }
    } else if (n > lim) {
      printf "FAIL: %s has %d main files, over the limit of %d and not in the baseline - split it\n", k, n, lim; fail = 1
    } }
  END {
    for (k in base) if (!(k in seen)) printf "WARN: %s no longer exists - drop it from the baseline (--update)\n", k
    exit fail
  }' "$TMP.base" "$TMP.all"; then
  FAIL=1
fi

# Baseline-growth ratchet: no entry may enter the baseline in a change.
BASE_REF=${MODULE_SIZE_BASE_REF:-}
if [ -z "$BASE_REF" ] && [ -n "${GITHUB_BASE_REF:-}" ]; then BASE_REF="origin/$GITHUB_BASE_REF"; fi
[ -n "$BASE_REF" ] || BASE_REF=origin/main
if MB=$(git merge-base HEAD "$BASE_REF" 2>/dev/null) \
   && git show "$MB:$BASELINE" > "$TMP.mb" 2>/dev/null \
   && grep -qxF "$MAX_LINE" "$TMP.mb"; then
  if ! awk '
    FNR==NR { if ($0 !~ /^[[:space:]]*(#|$)/) old[$1] = 1; next }
    { if (!($1 in old)) { printf "FAIL: %s was added to the baseline (not in it at the merge-base) - the baseline may only shrink; split the module\n", $1; fail = 1 } }
    END { exit fail }' "$TMP.mb" "$TMP.base"; then
    FAIL=1
  fi
fi

if [ "$FAIL" -eq 0 ]; then
  echo "module-size ratchet: OK (modules at most $MAX main files, root package $ROOT_PKG empty, unless baselined)"
else
  echo "module-size ratchet: FAILED (see docs/architecture/ADR-0023-agents-module-decomposition.md section 6)"
  exit 1
fi
