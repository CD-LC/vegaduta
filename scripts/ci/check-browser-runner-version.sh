#!/usr/bin/env bash
# The browser-runner container is a Playwright server and Playwright refuses a client of a different
# major.minor ("428 Precondition Required, Playwright version mismatch"). Three places must therefore
# carry the SAME version, and drifting one of them gives a container that reports healthy (its
# healthcheck only tests the TCP port) while every browser_* call fails:
#
#   services/browser-runner/package.json       "playwright": "<exact>"  (the server `run-server` runs)
#   services/browser-runner/package-lock.json  playwright and playwright-core entries
#   services/browser-runner/Dockerfile         ARG PLAYWRIGHT_IMAGE=...playwright:v<version>-<distro> (the Chromium build)
#   core/agents-browser/pom.xml                com.microsoft.playwright:playwright (the Java client)
#
# Usage:  bash scripts/ci/check-browser-runner-version.sh          check the repository
#         bash scripts/ci/check-browser-runner-version.sh --self-test   prove the check fails on drift
# BROWSER_RUNNER_CHECK_ROOT overrides the repository root (the self-test points it at fixtures).
set -uo pipefail

check() {
  local root=$1 rc=0
  local pkg=$root/services/browser-runner/package.json
  local lock=$root/services/browser-runner/package-lock.json
  local dockerfile=$root/services/browser-runner/Dockerfile
  local pom=$root/core/agents-browser/pom.xml
  local f
  for f in "$pkg" "$lock" "$dockerfile" "$pom"; do
    [ -f "$f" ] || { echo "FAIL: ${f#"$root"/} not found"; return 1; }
  done

  local pkg_v lock_v lock_core_v image_v pom_v
  pkg_v=$(sed -n 's/^[[:space:]]*"playwright"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$pkg" | head -1)
  lock_v=$(awk '/"node_modules\/playwright"/{f=1} f&&/"version"/{gsub(/[",]/,"",$2); print $2; exit}' "$lock")
  lock_core_v=$(awk '/"node_modules\/playwright-core"/{f=1} f&&/"version"/{gsub(/[",]/,"",$2); print $2; exit}' "$lock")
  image_v=$(sed -n 's/^ARG PLAYWRIGHT_IMAGE=.*playwright:v\([0-9][0-9.]*\)-.*/\1/p' "$dockerfile" | head -1)
  pom_v=$(awk '/<groupId>com\.microsoft\.playwright<\/groupId>/{g=1} g&&/<version>/{gsub(/[^0-9.]/,"",$0); print; exit}' "$pom")

  printf 'browser-runner package.json  playwright      %s\n' "${pkg_v:-<missing>}"
  printf 'browser-runner package-lock  playwright      %s\n' "${lock_v:-<missing>}"
  printf 'browser-runner package-lock  playwright-core %s\n' "${lock_core_v:-<missing>}"
  printf 'browser-runner Dockerfile    image tag       %s\n' "${image_v:-<missing>}"
  printf 'core/agents-browser pom.xml  Java client     %s\n' "${pom_v:-<missing>}"

  if ! printf '%s' "$pkg_v" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    echo "FAIL: package.json must pin playwright to an exact version (got '${pkg_v:-none}'), no ^ or ~ range"
    rc=1
  fi
  local label v
  while IFS='|' read -r label v; do
    if [ -z "$v" ] || [ "$v" != "$pkg_v" ]; then
      echo "FAIL: $label is '${v:-missing}' but services/browser-runner/package.json pins '$pkg_v'"
      rc=1
    fi
  done <<EOT
package-lock.json playwright|$lock_v
package-lock.json playwright-core|$lock_core_v
Dockerfile base-image tag|$image_v
core/agents-browser/pom.xml Java client|$pom_v
EOT
  if [ "$rc" -ne 0 ]; then
    echo "      Bump all of them together: package.json, then 'npm install --package-lock-only' in"
    echo "      services/browser-runner, the Dockerfile base image tag, and core/agents-browser/pom.xml."
    return 1
  fi
  echo "browser-runner-version: OK ($pkg_v)"
}

self_test() {
  local repo tmp rc=0
  repo=$(cd "$(dirname "$0")/../.." && pwd)
  tmp=$(mktemp -d) || return 1
  trap 'rm -rf "$tmp"' RETURN
  expect() { # name, expected exit, root
    local name=$1 want=$2 root=$3 got
    check "$root" >/dev/null 2>&1
    got=$?
    if [ "$got" -ne "$want" ]; then echo "FAIL $name: exit $got, wanted $want"; rc=1; else echo "ok   $name"; fi
  }
  fixture() { # dir
    mkdir -p "$1/services/browser-runner" "$1/core/agents-browser"
    cp "$repo/services/browser-runner/package.json" "$repo/services/browser-runner/package-lock.json" \
       "$repo/services/browser-runner/Dockerfile" "$1/services/browser-runner/"
    cp "$repo/core/agents-browser/pom.xml" "$1/core/agents-browser/"
  }
  # Portable in-place edit: sed -i differs between GNU and BSD.
  edit() { local file=$1 expr=$2; sed "$expr" "$file" > "$file.new" && mv "$file.new" "$file"; }

  expect "repository is consistent" 0 "$repo"

  fixture "$tmp/ok";       expect "fixture copy is consistent" 0 "$tmp/ok"
  fixture "$tmp/pkg";      edit "$tmp/pkg/services/browser-runner/package.json" 's/"playwright": "[^"]*"/"playwright": "1.49.0"/'
  expect "package.json drifted" 1 "$tmp/pkg"
  fixture "$tmp/range";    edit "$tmp/range/services/browser-runner/package.json" 's/"playwright": "\([^"]*\)"/"playwright": "^\1"/'
  expect "package.json caret range" 1 "$tmp/range"
  fixture "$tmp/img";      edit "$tmp/img/services/browser-runner/Dockerfile" 's|playwright:v[0-9.]*-|playwright:v1.49.0-|'
  expect "Dockerfile image tag drifted" 1 "$tmp/img"
  fixture "$tmp/pom";      edit "$tmp/pom/core/agents-browser/pom.xml" '/<artifactId>playwright<\/artifactId>/{n;s|<version>[^<]*</version>|<version>1.49.0</version>|;}'
  expect "pom.xml Java client drifted" 1 "$tmp/pom"
  fixture "$tmp/lock";     edit "$tmp/lock/services/browser-runner/package-lock.json" 's/"version": "1\.63\.0"/"version": "1.49.0"/'
  expect "package-lock.json drifted" 1 "$tmp/lock"
  fixture "$tmp/gone";     rm "$tmp/gone/core/agents-browser/pom.xml"
  expect "missing file fails" 1 "$tmp/gone"
  return $rc
}

if [ "${1:-}" = "--self-test" ]; then
  self_test
  exit $?
fi
root=${BROWSER_RUNNER_CHECK_ROOT:-$(cd "$(dirname "$0")/../.." && pwd)}
check "$root"
