#!/usr/bin/env bash
#
# Point this clone's git hooks at .githooks/ (tracked in the repo).
#
# Run once per clone:
#   bash scripts/dev/install-git-hooks.sh
#
# Git does not share hooks automatically - .git/hooks is local and untracked,
# which is exactly why a hook nobody installed is worth nothing. Setting
# core.hooksPath makes the tracked directory authoritative, so a hook added
# later arrives with a normal `git pull` instead of needing another manual
# copy step.
#
# Uninstall:  git config --unset core.hooksPath

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "${REPO_ROOT}"

HOOKS_DIR=".githooks"

if [[ ! -d "${HOOKS_DIR}" ]]; then
  echo "ERROR: ${HOOKS_DIR} does not exist in $(pwd)" >&2
  exit 1
fi

current="$(git config --get core.hooksPath || true)"
if [[ "${current}" == "${HOOKS_DIR}" ]]; then
  echo "already installed: core.hooksPath = ${HOOKS_DIR}"
else
  git config core.hooksPath "${HOOKS_DIR}"
  echo "installed: core.hooksPath = ${HOOKS_DIR}"
  [[ -n "${current}" ]] && echo "  (replaced previous value: ${current})"
fi

# The executable bit does not survive every checkout on Windows. git runs the
# hook through its own shell, so this is belt-and-braces rather than required,
# but a hook that silently does not run is the failure mode being fixed here.
chmod +x "${HOOKS_DIR}"/* 2>/dev/null || true

echo
echo "Active hooks:"
for h in "${HOOKS_DIR}"/*; do
  [[ -f "${h}" ]] || continue
  printf '  %-12s %s\n' "$(basename "${h}")" "$(sed -n '3s/^# *//p' "${h}")"
done

cat <<'MSG'

What pre-push does: runs scripts/ci/flyway-lint.sh when a push touches
core/app/src/main/resources/db/migration, and blocks the push if two
migrations share a version number across controlplane/ and tenant/.

Why it is needed on top of CI: static-gates.yml runs on pull_request and on
push to main. A branch merged LOCALLY into main fires neither in time - the
push-to-main run reports a collision that is already merged. That is how the
V227 collision reached production on 2026-09-13 and kept core down for four
days, six weeks after the CI check for it was added.

Bypass with `git push --no-verify` if you must.
MSG
