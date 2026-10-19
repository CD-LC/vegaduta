#!/usr/bin/env bash
# Copies the documentation the Assistant's setup guide searches (docs/console/*.md plus every page a
# topic in topics.json links) into core/agents-tools/src/main/resources/platform-guide/docs, so every
# install - including a container that has no docs/ folder - can answer from the real docs.
#   bash scripts/ci/sync-platform-guide-docs.sh          # rewrite the bundle
#   bash scripts/ci/sync-platform-guide-docs.sh --check  # fail when the bundle is stale (CI)
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
res="$root/core/agents-tools/src/main/resources/platform-guide"
mode="${1:-}"
list="$(mktemp)"
trap 'rm -f "$list"' EXIT
{
  (cd "$root/docs" && ls console/*.md)
  python3 -I - "$res/topics.json" <<'PY'
import json, sys
for t in json.load(open(sys.argv[1])):
    for d in t.get("docs", []):
        print(d)
PY
} | sort -u > "$list"
want="$(mktemp -d)"
trap 'rm -f "$list"; rm -rf "$want"' EXIT
while IFS= read -r f; do
  [ -f "$root/docs/$f" ] || { echo "topics.json links docs/$f which does not exist" >&2; exit 1; }
  mkdir -p "$want/$(dirname "$f")"
  cp "$root/docs/$f" "$want/$f"
done < "$list"
if [ "$mode" = "--check" ]; then
  diff -r "$want" "$res/docs" >/dev/null || { echo "platform-guide docs are stale: run bash scripts/ci/sync-platform-guide-docs.sh" >&2; exit 1; }
  exit 0
fi
rm -rf "$res/docs"
mkdir -p "$res/docs"
cp -R "$want/." "$res/docs/"
echo "bundled $(wc -l < "$list" | tr -d ' ') docs into $res/docs"
