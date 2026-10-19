#!/usr/bin/env bash
#
# Secret scan (gitleaks) over the commits being added.
#
#   SECRET_SCAN_BASE_REF  ref to diff against (default origin/main). CI sets it
#                         to the PR base branch, or to the push's "before" sha.
#   SECRET_SCAN_FULL      set to 1 to scan every commit reachable from HEAD.
#
# Range selection:
#   - normal case: merge-base(HEAD, base)..HEAD
#   - the base is empty or all zeros (a push that creates the branch, e.g. the
#     very first push of the repository), is not present in the clone (a
#     force-push whose "before" commit no longer exists), or has no merge-base
#     with HEAD: scan the WHOLE history reachable from HEAD. A "HEAD~1"
#     fallback would leave every earlier commit of such a push unscanned, and
#     fails outright on a single-commit history.
#
# Known historical findings that cannot be removed without rewriting history
# are listed by fingerprint in .gitleaksignore.
#
# Locally it skips when gitleaks isn't installed; in CI (CI=true) that's a
# failure, because a gate that silently doesn't run is worse than none.

set -uo pipefail
cd "$(git rev-parse --show-toplevel)" || exit 1

if ! command -v gitleaks >/dev/null 2>&1; then
  if [[ "${CI:-}" == "true" ]]; then
    echo "secret-scan: gitleaks is not installed" >&2
    exit 1
  fi
  echo "secret-scan: SKIP (gitleaks not installed - see CONTRIBUTING.md)"
  exit 0
fi

# In a shallow clone "the whole history" would silently mean "the last N
# commits"; refuse rather than under-scan.
if [[ "$(git rev-parse --is-shallow-repository 2>/dev/null)" == "true" ]]; then
  echo "secret-scan: shallow clone; fetch the full history (actions/checkout fetch-depth: 0)" >&2
  exit 1
fi

base_ref="${SECRET_SCAN_BASE_REF-origin/main}"
full="${SECRET_SCAN_FULL:-0}"
log_opts=""

if [[ "${full}" != "1" ]]; then
  if [[ -z "${base_ref}" || "${base_ref}" =~ ^0+$ ]]; then
    echo "secret-scan: no base (new branch or first push): scanning the full history"
    full=1
  elif ! git rev-parse --verify --quiet "${base_ref}^{commit}" >/dev/null; then
    echo "secret-scan: base '${base_ref}' is not in this clone (force-push?): scanning the full history"
    full=1
  else
    base="$(git merge-base HEAD "${base_ref}" 2>/dev/null || true)"
    if [[ -z "${base}" ]]; then
      echo "secret-scan: no merge-base between HEAD and ${base_ref}: scanning the full history"
      full=1
    elif [[ "${base}" == "$(git rev-parse HEAD)" ]]; then
      echo "secret-scan: no new commits relative to ${base_ref}"
      exit 0
    else
      log_opts="${base}..HEAD"
      echo "secret-scan: scanning $(git rev-list --count "${log_opts}") commit(s) in ${log_opts}"
    fi
  fi
fi

if [[ "${full}" == "1" ]]; then
  # Everything reachable from HEAD. Not --all: other refs in a CI clone are
  # other branches, which their own runs scan.
  log_opts="HEAD"
  echo "secret-scan: scanning $(git rev-list --count HEAD) commit(s) (full history)"
fi

gitleaks git --config .gitleaks.toml --log-opts="${log_opts}" \
  --redact --no-banner .
