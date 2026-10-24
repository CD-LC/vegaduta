#!/bin/sh
# pgvector's Makefile defaults to OPTFLAGS=-march=native, so a bare `make` bakes the CPU
# of the build machine (a CI runner) into vector.so. On a host whose CPU lacks one of those
# instructions the Postgres backend dies with SIGILL the first time a vector is cast or an
# HNSW index is built (the tenant baseline migration does), and core never boots. A runtime
# smoke cannot catch it: the runner that built the image also runs it. So check the build
# line instead: every `make` of pgvector in deploy/postgres/Dockerfile* must pass OPTFLAGS="",
# and nothing in the repo's Dockerfiles may compile with -march=native.
#
#   scripts/ci/postgres-portable-build.sh [dir-with-Dockerfiles]     (default: deploy/postgres)
set -eu
ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"
dir=${1:-deploy/postgres}
bad=0
n=0
for f in "$dir"/Dockerfile*; do
  [ -f "$f" ] || continue
  # One command per line: split RUN chains on "&&" (the main Dockerfile keeps its RUN on one
  # line), and ignore comments.
  while IFS= read -r line; do
    case "$line" in
      *"make -C /tmp/pgvector"*)
        n=$((n + 1))
        case "$line" in
          *'OPTFLAGS=""'*) ;;
          *) echo "FAIL: $f: pgvector is built without OPTFLAGS=\"\" (defaults to -march=native, SIGILL on other CPUs):"
             echo "      $line"
             bad=1 ;;
        esac ;;
    esac
  done <<EOT
$(grep -v '^[[:space:]]*#' "$f" | awk '{ gsub(/&&/, "\n"); print }')
EOT
done
if [ "$n" -eq 0 ]; then
  echo "FAIL: no pgvector make line found under $dir; update this check if the build moved"
  exit 1
fi
native=$(grep -rIn --include='Dockerfile*' -e '-march=native' deploy services core web 2>/dev/null | grep -Ev ':[0-9]+:[[:space:]]*#' || true)
if [ -n "$native" ]; then
  echo "FAIL: -march=native in a Dockerfile (the image would only run on the CPU that built it):"
  echo "$native"
  bad=1
fi
[ "$bad" -eq 0 ] || exit 1
echo "postgres-portable-build: OK ($n pgvector make lines)"
