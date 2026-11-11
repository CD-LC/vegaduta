#!/usr/bin/env bash
# Web code-quality ratchet. Counts two kinds of debt in web/app and fails when either grows.
#
#   localhost  literal localhost / 127.0.0.1 in non-test web source (ENGINEERING-RULES.md rule 4:
#              a browser-facing URL never defaults to localhost). Detection helpers and
#              dev-only defaults are counted too, so a NEW occurrence has to be justified by
#              lowering another one or by updating the baseline in review.
#   tsescape   eslint-disable, @ts-ignore, @ts-expect-error and `as any`.
#
# Baseline: scripts/ci/web-ratchet-baseline.txt ("<name><TAB><count>"). It only shrinks:
#   scripts/ci/web-ratchet.sh            check
#   scripts/ci/web-ratchet.sh --update   rewrite the baseline (refuses to raise a count)
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
BASELINE=scripts/ci/web-ratchet-baseline.txt

count_localhost() {
  git grep -IhE 'localhost|127\.0\.0\.1' -- 'web/app' ':!*.test.*' ':!*.spec.*' ':!*.md' \
    ':!web/app/lib/i18n-route-namespaces.generated.ts' | wc -l | tr -d ' '
}
count_tsescape() {
  git grep -IhE 'eslint-disable|@ts-ignore|@ts-expect-error|as any' -- 'web/app' ':!*.test.*' ':!*.spec.*' \
    | wc -l | tr -d ' '
}

declare -A NOW=( [localhost]=$(count_localhost) [tsescape]=$(count_tsescape) )

if [[ "${1:-}" == "--update" ]]; then
  for k in localhost tsescape; do
    old=$(awk -F'\t' -v k="$k" '$1==k{print $2}' "$BASELINE" 2>/dev/null || true)
    if [[ -n "$old" && ${NOW[$k]} -gt $old ]]; then
      echo "web-ratchet: refusing to raise $k from $old to ${NOW[$k]}" >&2; exit 1
    fi
  done
  for k in localhost tsescape; do printf '%s\t%s\n' "$k" "${NOW[$k]}"; done > "$BASELINE"
  echo "web-ratchet: baseline written"; exit 0
fi

rc=0
for k in localhost tsescape; do
  base=$(awk -F'\t' -v k="$k" '$1==k{print $2}' "$BASELINE")
  if [[ -z "$base" ]]; then echo "web-ratchet: no baseline for $k" >&2; rc=1; continue; fi
  if (( ${NOW[$k]} > base )); then
    echo "web-ratchet: FAIL $k grew ${base} -> ${NOW[$k]}" >&2; rc=1
  elif (( ${NOW[$k]} < base )); then
    echo "web-ratchet: $k shrank ${base} -> ${NOW[$k]} (run --update to lock it in)"
  else
    echo "web-ratchet: $k ok (${NOW[$k]})"
  fi
done
exit $rc
