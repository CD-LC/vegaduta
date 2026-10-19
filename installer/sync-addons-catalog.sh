#!/usr/bin/env bash
# Keeps installer/addons/catalog.json byte-identical to the catalog core serves
# (core/providers/src/main/resources/addons/catalog.json). Setup embeds that
# copy; `go test` (TestCatalogMatchesCore) fails when the two differ.
#
#   installer/sync-addons-catalog.sh           copy core's file over the embedded one
#   installer/sync-addons-catalog.sh --check   exit 1 if they differ, change nothing
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
src="$here/../core/providers/src/main/resources/addons/catalog.json"
dst="$here/addons/catalog.json"
[ -f "$src" ] || { echo "missing $src" >&2; exit 2; }
if [ "${1:-}" = "--check" ]; then
  cmp -s "$src" "$dst" && { echo "addons catalog: in sync"; exit 0; }
  echo "addons catalog: installer/addons/catalog.json differs from core; run installer/sync-addons-catalog.sh" >&2
  exit 1
fi
mkdir -p "$here/addons"
cp "$src" "$dst"
echo "addons catalog: copied core's catalog.json into installer/addons/"
