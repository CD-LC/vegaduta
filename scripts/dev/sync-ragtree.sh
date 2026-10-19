#!/usr/bin/env bash
# One-way sync of the vendored codebase_rag package:
#
#   code-intel/codebase_rag/  (CANONICAL)  ->  tools/codebase-rag/codebase_rag/  (MIRROR)
#
# code-intel/codebase_rag is canonical because it is the DEPLOYED copy:
# code-intel/app/service.py imports it directly (and calls
# store.hybrid_search()), and code-intel/Dockerfile's build context bakes
# it into the image the platform actually runs. tools/codebase-rag is the
# standalone CLI/library packaging of the same code and follows.
#
# Usage:
#   scripts/dev/sync-ragtree.sh            # sync everything EXCEPT baseline-frozen files
#   scripts/dev/sync-ragtree.sh --all      # also overwrite baseline-frozen files
#
# Baseline-frozen files (scripts/ci/ragtree-sync.baseline) are the two
# files that had ALREADY diverged with independent implementations when
# this tooling landed (2026-08-07). They are skipped by default because
# blindly copying canonical over them would silently discard the mirror's
# implementation before anyone decided which one wins - exactly the class
# of accident this tooling exists to prevent. Once a human reconciles
# them, run with --all and delete the entries from the baseline.
#
# Deliberately cp-based, not rsync: rsync isn't guaranteed present on the
# Windows/git-bash environments this repo is developed on, and the tree is
# ~12 files - per-file cp is fine. File list comes from git (tracked +
# untracked-but-not-ignored), so __pycache__/*.pyc never gets copied.
# Mirror-only files inside the package are DELETED (a one-way mirror that
# never deletes would let removed canonical files linger and fail the CI
# gate as "only in mirror") - baseline-frozen paths excepted, same rule.
# Files OUTSIDE the codebase_rag/ subdir (tools/codebase-rag's own
# README.md, pyproject.toml, requirements.txt) are intentionally
# per-tree and untouched.
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

CANONICAL="code-intel/codebase_rag"
MIRROR="tools/codebase-rag/codebase_rag"
BASELINE_FILE="scripts/ci/ragtree-sync.baseline"

INCLUDE_FROZEN=0
if [[ "${1:-}" == "--all" ]]; then
  INCLUDE_FROZEN=1
elif [[ -n "${1:-}" ]]; then
  echo "usage: $0 [--all]" >&2
  exit 2
fi

declare -A BASELINE
if [[ -f "$BASELINE_FILE" ]]; then
  while IFS= read -r line; do
    line="${line%%#*}"
    line="$(echo -n "$line" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
    [[ -z "$line" ]] && continue
    BASELINE["$line"]=1
  done < "$BASELINE_FILE"
fi

is_frozen() {
  [[ "$INCLUDE_FROZEN" -eq 0 && -n "${BASELINE[$1]:-}" ]]
}

COPIED=0
SKIPPED_FROZEN=()
DELETED=0

# Copy canonical -> mirror.
while IFS= read -r rel; do
  [[ -z "$rel" ]] && continue
  if is_frozen "$rel"; then
    SKIPPED_FROZEN+=("$rel")
    continue
  fi
  if [[ -f "$MIRROR/$rel" ]] && cmp -s "$CANONICAL/$rel" "$MIRROR/$rel"; then
    continue
  fi
  mkdir -p "$(dirname "$MIRROR/$rel")"
  cp "$CANONICAL/$rel" "$MIRROR/$rel"
  echo "sync-ragtree: copied $rel"
  COPIED=$((COPIED + 1))
done < <(git ls-files --cached --others --exclude-standard -- "$CANONICAL" | sed "s|^$CANONICAL/||")

# Delete mirror-only files (removed from canonical).
while IFS= read -r rel; do
  [[ -z "$rel" ]] && continue
  if [[ ! -f "$CANONICAL/$rel" ]]; then
    if is_frozen "$rel"; then
      SKIPPED_FROZEN+=("$rel (mirror-only)")
      continue
    fi
    rm "$MIRROR/$rel"
    echo "sync-ragtree: deleted $rel (no longer in canonical)"
    DELETED=$((DELETED + 1))
  fi
done < <(git ls-files --cached --others --exclude-standard -- "$MIRROR" | sed "s|^$MIRROR/||")

echo "sync-ragtree: $COPIED file(s) copied, $DELETED deleted ($CANONICAL -> $MIRROR)"
if [[ "${#SKIPPED_FROZEN[@]}" -gt 0 ]]; then
  echo "sync-ragtree: SKIPPED baseline-frozen file(s) (independent implementations, human reconciliation pending - see $BASELINE_FILE):"
  printf '  %s\n' "${SKIPPED_FROZEN[@]}"
  echo "sync-ragtree: after reconciling in canonical, rerun with --all and remove the entries from the baseline."
fi
echo "sync-ragtree: verify with: bash scripts/ci/ragtree-sync-check.sh"
