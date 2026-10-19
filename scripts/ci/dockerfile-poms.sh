#!/bin/sh
# Every <module> of core/pom.xml must have a `COPY <module>/pom.xml <module>/` line in
# core/Dockerfile's dependency-cache layer. A module missing there makes the in-image
# `mvn dependency:go-offline` fail ("Child module /build/x does not exist"; its `|| true`
# hides it), so every dependency is re-downloaded in the final package step instead of a
# cached layer: slow builds that fail on any network blip. (json, payments and brief had
# drifted out of the list.)
#
#   scripts/ci/dockerfile-poms.sh
set -eu
ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"
missing=""
for m in $(sed -n 's|.*<module>\([^<]*\)</module>.*|\1|p' core/pom.xml); do
  grep -q "^COPY $m/pom\.xml " core/Dockerfile || missing="$missing $m"
done
if [ -n "$missing" ]; then
  echo "FAIL: core/Dockerfile does not COPY the pom.xml of:$missing"
  echo "      add 'COPY <module>/pom.xml <module>/' next to the others"
  exit 1
fi
echo "dockerfile-poms: OK ($(sed -n 's|.*<module>\([^<]*\)</module>.*|\1|p' core/pom.xml | wc -l | tr -d ' ') modules)"
