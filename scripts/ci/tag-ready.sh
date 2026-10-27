#!/usr/bin/env bash
# Release pre-flight: run this on the commit you are about to tag before
# `git tag` / `gh workflow run release.yml --ref <tag>`.
#
# release.yml's verify job waits for the tagged commit's "CI OK" and
# "CodeQL OK" check runs. Main-push CI serializes on a concurrency group,
# so tagging a merge right after it lands can put the commit's CI behind a
# full earlier run - the release then spends its wait window queuing, and
# has timed out in production doing exactly that. This script tells you in
# one look whether the commit is tag-ready now, or what to wait for.
#
# Usage: scripts/ci/tag-ready.sh <sha>     (defaults to origin/main's tip)
set -euo pipefail

sha="${1:-$(git rev-parse origin/main)}"
repo="$(gh repo view --json nameWithOwner -q .nameWithOwner)"

fail=0
for check in "CI OK" "CodeQL OK"; do
  runs="$(gh api -X GET "repos/${repo}/commits/${sha}/check-runs" \
    -f check_name="$check" -f filter=latest --jq '.check_runs')"
  total="$(jq 'length' <<< "$runs")"
  success="$(jq '[.[] | select(.conclusion == "success")] | length' <<< "$runs")"
  pending="$(jq '[.[] | select(.status != "completed")] | length' <<< "$runs")"
  if [ "$success" -gt 0 ] && [ "$pending" -eq 0 ]; then
    echo "  [ok]   $check: green on ${sha:0:9}"
  elif [ "$pending" -gt 0 ]; then
    echo "  [wait] $check: still running on ${sha:0:9}"
    fail=1
  else
    echo "  [fail] $check: ${total} run(s), none green on ${sha:0:9}"
    fail=1
  fi
done

if [ "$fail" = 0 ]; then
  echo "Tag-ready: every release-gate check is green on ${sha:0:9}."
else
  echo "Not tag-ready on ${sha:0:9} - wait for the commit's CI to finish, or tag a commit that has already run it."
  exit 1
fi
