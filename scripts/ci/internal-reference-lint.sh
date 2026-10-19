#!/usr/bin/env bash
# Internal-planning-jargon lint (ratchet).
#
# Source, docs and config must read for an outside contributor. Phrases that only mean
# something inside the original private planning documents ("Program B", "plan §3",
# "Build Plan, Stage C", "world's-best") must not appear in NEW code or docs.
#
# Existing offenders are frozen in scripts/ci/internal-reference-baseline.txt as
# "<count><TAB><path>". The baseline only shrinks:
#   - a file not in the baseline that matches            -> FAIL
#   - a baselined file whose match count grew            -> FAIL
#   - a path in the baseline now but not in the baseline at the merge-base -> FAIL
#     (checked only when a merge-base is available: INTERNAL_REF_BASE_REF, else
#     origin/$GITHUB_BASE_REF, else origin/main)
#   - a baselined file that shrank or is clean           -> WARN (run --update to lower it)
#
# Applied Flyway migrations are never edited, so db/migration/ is out of scope, as are
# node_modules, build output, generated files, the changelog and archived documents.
#
#   scripts/ci/internal-reference-lint.sh            check
#   scripts/ci/internal-reference-lint.sh --update   rewrite the baseline (shrink only)
#
# Env: INTERNAL_REF_BASELINE (baseline path), INTERNAL_REF_BASE_REF (see above).
set -euo pipefail

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"
BASELINE=${INTERNAL_REF_BASELINE:-scripts/ci/internal-reference-baseline.txt}

# Case-insensitive phrases, then case-sensitive ones ("Program B" is a name; "program b"
# is not).
CI_PATTERN="plan §|build plan, stage|world'?s[- ]best"
CS_PATTERN="Program [A-D]\\b"

SCOPE=(
  .
  ':!**/db/migration/**'
  ':!**/node_modules/**' ':!**/target/**' ':!**/.next/**' ':!**/dist/**'
  ':!**/generated/**' ':!*.generated.*' ':!*.lock' ':!package-lock.json'
  ':!CHANGELOG.md' ':!docs/**/archive/**' ':!.changes/**'
  ':!scripts/ci/internal-reference-lint.sh' ':!scripts/ci/internal-reference-baseline.txt'
  ':!scripts/ci/test-internal-reference-lint.sh'
)

tmpdir=$(mktemp -d)
trap 'rm -rf "$tmpdir"' EXIT
current="$tmpdir/current"

# git grep -c prints "path:count"; --untracked --exclude-standard also checks files not yet added.
git grep -I -c -i -E --untracked --exclude-standard -e "$CI_PATTERN" -- "${SCOPE[@]}" > "$tmpdir/ci" || true
git grep -I -c -E --untracked --exclude-standard -e "$CS_PATTERN" -- "${SCOPE[@]}" > "$tmpdir/cs" || true
cat "$tmpdir/ci" "$tmpdir/cs" \
  | awk '{ i = match($0, /:[0-9]+$/); p = substr($0, 1, i - 1); n = substr($0, i + 1); c[p] += n }
         END { for (p in c) printf "%d\t%s\n", c[p], p }' \
  | LC_ALL=C sort -t "$(printf '\t')" -k2 > "$current"

if [ "${1:-}" = "--update" ]; then
  # Shrink only: keep a path only if it is still offending and never raise a count.
  if [ -s "$BASELINE" ] && grep -qv '^#' "$BASELINE"; then
    awk -F'\t' 'NR==FNR { if ($0 !~ /^#/ && NF==2) old[$2]=$1; next }
      ($2 in old) { print (($1 < old[$2]) ? $1 : old[$2]) "\t" $2 }' "$BASELINE" "$current" > "$tmpdir/next"
  else
    cp "$current" "$tmpdir/next"
  fi
  {
    echo '# Frozen internal-planning-jargon offenders: "<count><TAB><path>". Shrink-only; see'
    echo '# internal-reference-lint.sh. Reword a file, then run the script with --update.'
    LC_ALL=C sort -t "$(printf '\t')" -k2 "$tmpdir/next"
  } > "$BASELINE.new"
  mv "$BASELINE.new" "$BASELINE"
  echo "baseline: $(grep -vc '^#' "$BASELINE") file(s)"
  exit 0
fi

fail=0
touch "$tmpdir/baseline"
if [ -f "$BASELINE" ]; then
  grep -v '^#' "$BASELINE" > "$tmpdir/baseline" || true
fi

while IFS=$'\t' read -r count path; do
  [ -n "$path" ] || continue
  recorded=$(awk -F'\t' -v p="$path" '$2==p { print $1 }' "$tmpdir/baseline")
  if [ -z "$recorded" ]; then
    echo "FAIL  $path: $count internal planning reference(s), file not in the baseline" >&2
    { git grep -I -n -i -E --untracked --exclude-standard -e "$CI_PATTERN" -- "$path" || true
      git grep -I -n -E --untracked --exclude-standard -e "$CS_PATTERN" -- "$path" || true; } | sed 's/^/        /' >&2
    fail=1
  elif [ "$count" -gt "$recorded" ]; then
    echo "FAIL  $path: $count reference(s), baseline allows $recorded" >&2
    fail=1
  elif [ "$count" -lt "$recorded" ]; then
    echo "WARN  $path: down to $count from $recorded; run $0 --update" >&2
  fi
done < "$current"

while IFS=$'\t' read -r _ p; do
  [ -n "$p" ] || continue
  grep -qF "$(printf '\t')$p" "$current" || echo "WARN  $p: no longer offends; run $0 --update" >&2
done < "$tmpdir/baseline"

# The baseline may not gain paths relative to the merge-base.
BASE_REF=${INTERNAL_REF_BASE_REF:-origin/${GITHUB_BASE_REF:-main}}
if mb=$(git merge-base HEAD "$BASE_REF" 2>/dev/null) \
   && git show "$mb:$BASELINE" > "$tmpdir/old" 2>/dev/null; then
  grep -v '^#' "$tmpdir/old" | cut -f2 > "$tmpdir/old_paths" || true
  while IFS=$'\t' read -r _ p; do
    [ -n "$p" ] || continue
    if ! grep -qxF "$p" "$tmpdir/old_paths"; then
      echo "FAIL  $p was added to $BASELINE; the baseline may only shrink" >&2
      fail=1
    fi
  done < "$tmpdir/baseline"
fi

if [ "$fail" -ne 0 ]; then
  echo "internal-reference-lint: reword for an outside reader (say what the code does, not which internal plan asked for it)." >&2
  exit 1
fi
echo "internal-reference-lint: ok ($(grep -c . "$tmpdir/baseline") baselined file(s))"
