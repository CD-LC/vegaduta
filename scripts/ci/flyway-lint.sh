#!/usr/bin/env bash
# static-gates: Flyway migration lint.
#
# Three independent checks, all scoped to the two locations Flyway is
# actually configured to merge at startup (see application.yml's
# spring.flyway.locations comment for why there are two folders and why
# their version numbers share one namespace):
#   core/app/src/main/resources/db/migration/controlplane
#   core/app/src/main/resources/db/migration/tenant
#
# (a) Applied-migration edit guard. "Applied" isn't queried from a live
#     Flyway schema history table here (this is a static, DB-less CI gate) -
#     it's approximated as "the file already existed on the base branch",
#     which is the right proxy for a PR gate: anything merged to the base
#     branch has shipped to at least one environment already. A file that
#     existed at the merge-base and is missing OR has different content now
#     is forbidden - both content edits AND renames/renumbers count, because
#     renumbering an already-applied migration (giving it a new version) is
#     exactly as dangerous as editing it: Flyway would see the new version
#     as unapplied and try to run it again. See application.yml's own
#     changelog comment for two real prior incidents of accidental version
#     collisions between controlplane/ and tenant/ - this is the same
#     failure class from the opposite direction (an intentional renumber
#     instead of an accidental collision).
#
#     Content comparison uses git's own blob object ID (via `git ls-tree` for
#     the base-branch side, `git hash-object` for the working-tree side) -
#     one subprocess per current file, not the show|sha256sum|cut chain per
#     file this started as, which measured genuinely slow (multiple minutes
#     for ~100 files) under Windows/git-bash's per-fork overhead during local
#     testing. `ubuntu-latest` forks are far cheaper, but there's no reason
#     to pay for 5 subprocesses per file when git already computed the base
#     hash for free as part of `ls-tree`.
#
# (b) Destructive-DDL scan, NEW migrations only (files that do not exist at
#     merge-base). Flags DROP TABLE, DROP COLUMN, and ALTER COLUMN ... SET
#     NOT NULL (tightening an existing column - unlike ADD COLUMN ... NOT
#     NULL, which is safe when paired with DEFAULT, since Postgres 11+
#     backfills the default without a table rewrite). A migration matching
#     any of these must contain the literal marker comment `-- gated:contract`
#     to pass. This is a marker check only, not a reviewer gate - a
#     CODEOWNERS entry routing destructive-DDL migrations to a required
#     human reviewer is a manual follow-up (no CODEOWNERS file exists in
#     this repo yet), not built here.
#
# (c) Duplicate-version check across BOTH folders (added 2026-07-29, after
#     this exact failure recurred for the FOURTH time). controlplane/ and
#     tenant/ are separate directories but NOT separate version namespaces:
#     application.yml lists both under one spring.flyway.locations, so
#     Flyway merges them into a single ordered run and a V<N> in each
#     folder is a hard boot failure ("Found more than one migration with
#     version N") for every core instance. Checks (a)/(b) never caught this
#     because a colliding new file is neither an edit of an applied
#     migration nor destructive DDL. Fails on ANY version number appearing
#     twice - cross-folder (the recurring case) or within one folder.
#
# Also writes scripts/ci/flyway.lock: path + sha256 for every migration file
# currently on disk (one batched `sha256sum` call, not one per file).
# Regenerated fresh on every run (not diffed against a committed prior
# version) - the "new files just append" requirement falls out for free this
# way, since the full current set is what gets written each time, and
# there's no stale lock to keep manually in sync across branches. The
# merge-base comparison in (a) is what actually enforces the no-edit rule;
# the lock file is the human/audit-facing artifact (sha256, not git's
# SHA-1 blob id, so it's independently verifiable without git).
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

MIGRATION_DIRS=(
  "core/app/src/main/resources/db/migration/controlplane"
  "core/app/src/main/resources/db/migration/tenant"
)
LOCK_FILE="scripts/ci/flyway.lock"

# Base ref resolution: GitHub Actions sets GITHUB_BASE_REF to the PR's
# target branch name (no "origin/" prefix, and only present on pull_request
# events - push-to-main runs have no base to diff against, so this script
# is a no-op for edit-detection there, which is correct: main IS the base).
# FLYWAY_LINT_BASE_REF lets local runs override (see this script's own
# header usage note / CONTRIBUTING.md).
#
# Without either, fall back to the default branch: local `main` first, then
# `origin/main`. actions/checkout creates a local branch only for the ref it
# checks out, so a workflow_dispatch (or push) run on any other branch has
# `origin/main` but no `main` - resolving only `main` there skipped the edit
# check and made the destructive-DDL scan treat every released migration as
# new.
BASE_REF="${FLYWAY_LINT_BASE_REF:-}"
if [[ -z "$BASE_REF" && -n "${GITHUB_BASE_REF:-}" ]]; then
  BASE_REF="origin/${GITHUB_BASE_REF}"
fi
if [[ -z "$BASE_REF" ]]; then
  BASE_REF="main"
  for candidate in main origin/main; do
    if git rev-parse --verify --quiet "${candidate}^{commit}" >/dev/null; then
      BASE_REF="$candidate"
      break
    fi
  done
fi

FAIL=0

echo "flyway-lint: migration locations:"
for d in "${MIGRATION_DIRS[@]}"; do
  echo "  - $d"
done

# Resolve merge-base; if the base ref doesn't exist locally (e.g. shallow
# clone without fetching it, or running outside CI with no "main"), skip
# the edit-detection check AND the destructive-DDL scan with a warning
# rather than a hard failure: with no base there is no way to tell new
# migrations from released ones, and released migrations must never be
# edited to add a marker. The duplicate-version check and lock building
# still run.
MERGE_BASE=""
if git rev-parse --verify --quiet "$BASE_REF" >/dev/null; then
  MERGE_BASE="$(git merge-base "$BASE_REF" HEAD 2>/dev/null || true)"
fi

if [[ -z "$MERGE_BASE" ]]; then
  echo "flyway-lint: WARNING - could not resolve base ref '$BASE_REF' (or a merge-base with HEAD); skipping the applied-migration edit check and the new-migration destructive-DDL scan. Set FLYWAY_LINT_BASE_REF or fetch the base branch to run them." >&2
else
  echo "flyway-lint: comparing against merge-base $MERGE_BASE (base ref: $BASE_REF)"
fi

# path -> git blob sha (base tree). Also doubles as the "existed at
# merge-base" set for the destructive-DDL scan below.
declare -A BASE_BLOB

if [[ -n "$MERGE_BASE" ]]; then
  for d in "${MIGRATION_DIRS[@]}"; do
    # `git ls-tree -r` output: "<mode> blob <sha>\t<path>" - one process for
    # the whole directory, and it already carries the base content's blob
    # hash, so no per-file `git show` is needed at all.
    while IFS=$'\t' read -r meta path; do
      [[ -z "$path" ]] && continue
      sha="$(awk '{print $3}' <<< "$meta")"
      BASE_BLOB["$path"]="$sha"
    done < <(git ls-tree -r "$MERGE_BASE" -- "$d" 2>/dev/null | grep -E '\.sql$' || true)
  done

  # Human-reviewed exceptions for a pre-production version renumber whose
  # DDL is verified unchanged - see that file's own header for exactly what
  # this does and doesn't authorize. Same role as this script's
  # `-- gated:contract` marker below: a deliberate override, not something
  # this script verifies the safety of on its own.
  ALLOWED_RENAMES_FILE="scripts/ci/flyway-lint-allowed-renames.txt"
  declare -A ALLOWED_RENAME_TO
  if [[ -f "$ALLOWED_RENAMES_FILE" ]]; then
    while IFS= read -r line; do
      line="${line%%#*}"                       # strip trailing comment
      line="$(echo -n "$line" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
      [[ -z "$line" ]] && continue
      [[ "$line" != *" -> "* ]] && continue
      old_path="${line%% -> *}"
      new_path="${line##* -> }"
      ALLOWED_RENAME_TO["$old_path"]="$new_path"
    done < "$ALLOWED_RENAMES_FILE"
  fi

  # Same mechanism, for an in-place EDIT of a migration that has provably never
  # run anywhere. Format: <path> <resulting-blob-sha>  # justification
  # The blob pin is what keeps this from becoming a permanent bypass.
  ALLOWED_EDITS_FILE="scripts/ci/flyway-lint-allowed-edits.txt"
  declare -A ALLOWED_EDIT_SHA
  if [[ -f "$ALLOWED_EDITS_FILE" ]]; then
    while IFS= read -r line; do
      line="${line%%#*}"
      line="$(echo -n "$line" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
      [[ -z "$line" ]] && continue
      edit_path="$(echo -n "$line" | awk '{print $1}')"
      edit_sha="$(echo -n "$line" | awk '{print $2}')"
      [[ -z "$edit_path" || -z "$edit_sha" ]] && continue
      ALLOWED_EDIT_SHA["$edit_path"]="$edit_sha"
    done < "$ALLOWED_EDITS_FILE"
  fi

  for path in "${!BASE_BLOB[@]}"; do
    if [[ ! -f "$path" ]]; then
      allowed_new="${ALLOWED_RENAME_TO[$path]:-}"
      if [[ -n "$allowed_new" && -f "$allowed_new" ]]; then
        echo "flyway-lint: $path is missing but is an ALLOWLISTED rename to $allowed_new (see $ALLOWED_RENAMES_FILE) - not failing."
        continue
      fi
      echo "FAIL: $path existed at $MERGE_BASE but is missing now (deleted or renamed) - edits/renumbers of an already-applied migration are forbidden. If this is a verified-safe pre-production renumber, add an entry to $ALLOWED_RENAMES_FILE instead of silencing this check." >&2
      FAIL=1
      continue
    fi
    current_sha="$(git hash-object "$path")"
    if [[ "${BASE_BLOB[$path]}" != "$current_sha" ]]; then
      # Human sign-off for a verified-safe PRE-PRODUCTION edit - the same
      # escape hatch the rename case above already has, which this branch was
      # missing. Without it the only route for "this migration has never run
      # anywhere and is provably broken" was to ship the broken version and
      # stack a fixup migration on top of it forever.
      #
      # Pinned to the RESULTING BLOB, deliberately. An entry authorises one
      # specific content hash, so it cannot decay into a blanket "this file may
      # always change" - the next edit needs its own reviewed line.
      allowed_edit_sha="${ALLOWED_EDIT_SHA[$path]:-}"
      if [[ -n "$allowed_edit_sha" && "$allowed_edit_sha" == "$current_sha" ]]; then
        echo "flyway-lint: $path changed but this exact blob ($current_sha) is an ALLOWLISTED pre-production edit (see $ALLOWED_EDITS_FILE) - not failing."
        continue
      fi
      echo "FAIL: $path changed since $MERGE_BASE (blob ${BASE_BLOB[$path]} -> $current_sha) - already-applied migrations must not be edited. Add a new migration instead. If this migration has provably never run in any environment, add a reviewed line to $ALLOWED_EDITS_FILE pinning blob $current_sha." >&2
      FAIL=1
    fi
  done
fi

# Destructive-DDL scan over NEW migrations (on disk now, not present at
# merge-base). DROP TABLE / DROP COLUMN anywhere; ALTER COLUMN ... SET NOT
# NULL (tightening a pre-existing column). Deliberately does NOT flag
# "ADD COLUMN ... NOT NULL" - safe when paired with DEFAULT, which is this
# repo's existing convention for every additive NOT NULL column (see
# application.yml's "IF NOT EXISTS-safe, additive, no destructive backfill"
# migration convention note).
DESTRUCTIVE_RE='DROP[[:space:]]+TABLE|DROP[[:space:]]+COLUMN|ALTER[[:space:]]+COLUMN[[:space:]]+[^;]*[[:space:]]SET[[:space:]]+NOT[[:space:]]+NULL'
MARKER='-- gated:contract'

ALL_SQL_FILES=()
for d in "${MIGRATION_DIRS[@]}"; do
  [[ -d "$d" ]] || continue
  while IFS= read -r -d '' path; do
    ALL_SQL_FILES+=("$path")
    rel="${path#./}"
    if [[ -z "$MERGE_BASE" ]]; then
      continue # no base: can't tell new from released (see warning above)
    fi
    if [[ -n "${BASE_BLOB[$rel]:-}" ]]; then
      continue # pre-existing migration, not this scan's concern
    fi
    if grep -qEi "$DESTRUCTIVE_RE" "$rel"; then
      if ! grep -qF -e "$MARKER" "$rel"; then
        echo "FAIL: $rel contains destructive DDL (DROP TABLE / DROP COLUMN / ALTER COLUMN ... SET NOT NULL) without a literal '$MARKER' marker comment." >&2
        FAIL=1
      else
        echo "flyway-lint: $rel contains destructive DDL but carries the '$MARKER' marker - allowed. (Manual follow-up: no CODEOWNERS-based reviewer gate exists yet to route this to a required human reviewer.)"
      fi
    fi
  done < <(find "$d" -name '*.sql' -print0)
done

# (c) Duplicate Flyway version numbers across the merged namespace. The
# gotcha this exists for, spelled out because it keeps biting: controlplane/
# and tenant/ LOOK like independent folders, but application.yml's
# spring.flyway.locations merges both into ONE Flyway run, so V143 in
# controlplane/ and V143 in tenant/ collide exactly as if they sat in the
# same directory - Flyway refuses to boot core at all ("Found more than one
# migration with version ..."). This has now happened four times; picking
# "the next free number in MY folder" is not enough - the number must be
# free across BOTH folders. Matches Flyway's own versioned-migration naming
# (V<version>__<description>.sql, dotted versions like V1.2 included).
declare -A VERSION_FIRST_PATH
for path in "${ALL_SQL_FILES[@]}"; do
  # ${path##*/}, not basename - no subprocess per file, same per-fork-cost
  # reasoning as this script's header note on the ls-tree/hash-object
  # optimization (measured painfully slow under Windows/git-bash).
  fname="${path##*/}"
  if [[ "$fname" =~ ^V([0-9]+(\.[0-9]+)*)__ ]]; then
    v="${BASH_REMATCH[1]}"
    if [[ -n "${VERSION_FIRST_PATH[$v]:-}" ]]; then
      echo "FAIL: duplicate Flyway version V${v} - ${VERSION_FIRST_PATH[$v]} AND ${path#./}. controlplane/ and tenant/ share ONE version namespace (application.yml's spring.flyway.locations merges both folders into a single Flyway run), so the same V-number in each folder is a hard core boot failure, not two independent migrations. Renumber the NEW file to the next version that is free across BOTH folders." >&2
      FAIL=1
    else
      VERSION_FIRST_PATH["$v"]="${path#./}"
    fi
  fi
done
echo "flyway-lint: duplicate-version check ran over ${#ALL_SQL_FILES[@]} files across the merged controlplane+tenant namespace"

# Rebuild the lock file fresh from every migration file currently on disk -
# one batched sha256sum call across all files, sorted for a stable diff.
if [[ "${#ALL_SQL_FILES[@]}" -gt 0 ]]; then
  sha256sum "${ALL_SQL_FILES[@]}" | sort -k2 > "$LOCK_FILE"
else
  : > "$LOCK_FILE"
fi
echo "flyway-lint: wrote $LOCK_FILE ($(wc -l < "$LOCK_FILE") entries)"

if [[ "$FAIL" -ne 0 ]]; then
  echo "flyway-lint: FAILED" >&2
  exit 1
fi
echo "flyway-lint: OK"
