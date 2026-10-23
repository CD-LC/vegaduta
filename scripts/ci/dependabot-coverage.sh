#!/usr/bin/env bash
# Every directory holding a package.json, go.mod or Dockerfile* must be listed in
# .github/dependabot.yml, or be exempted below with a reason (a manifest nobody
# watches is a manifest nobody patches). Text match on "- /dir" and
# "directory: /dir" lines: no YAML parser, so it runs anywhere the gates do.
#
#   bash scripts/ci/dependabot-coverage.sh
#
# Wildcard directories in dependabot.yml are not expanded; none of the three
# ecosystems checked here uses one.
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
CONFIG=.github/dependabot.yml

# <directory> -- <why Dependabot has nothing to do there>
exempt() {
  case "$1" in
    installer) return 0 ;;                       # Go, stdlib only: go.mod has no require lines (add a gomod entry with the first one)
    services/desktop-agent) return 0 ;;          # npm, "dependencies": {}
    services/piece-host/test/fixtures/*) return 0 ;; # test fixtures, not shipped
  esac
  return 1
}

# Normalised entries: "/dir" for every "- /dir", "directory: /dir", "- "/dir"" line.
listed="$(sed -n -E 's#^[[:space:]]*(-|directory:|directories:[[:space:]]*\[)[[:space:]]*"?(/[^]",[:space:]]*)"?.*#\2#p' "$CONFIG")"

missing=0
while IFS= read -r file; do
  dir="$(dirname "$file")"
  [[ "$dir" == . ]] && dir=""
  exempt "$dir" && continue
  if ! grep -qxF "/$dir" <<<"$listed"; then
    echo "FAIL: /$dir ($(basename "$file")) is not covered by $CONFIG (add it, or exempt it in $0 with a reason)"
    missing=1
  fi
done < <(git ls-files -- '*package.json' '*go.mod' '*Dockerfile' '*Dockerfile.*' \
  | grep -Ev '(^|/)node_modules/|\.dockerignore$|(^|/)package-lock\.json$' \
  | grep -E '(^|/)(package\.json|go\.mod|Dockerfile(\.[A-Za-z0-9_.-]+)?)$')

if [[ "$missing" -ne 0 ]]; then
  exit 1
fi
echo "dependabot coverage: OK"
