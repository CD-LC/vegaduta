#!/bin/sh
# Guards for the web (console) image's dependency CVE fixes:
#   1. web/package-lock.json resolves next >= 16.3.8 (GHSA-cjq9-62q9-8jv4, SSRF in the image
#      optimiser) and sharp >= 0.35.5 (GHSA-wq5f-xc86-pv6w). The floors only ever go up.
#   2. web/Dockerfile's runtime stage removes the node base image's npm/corepack/yarn: the app
#      only runs `node`, and their bundled dependencies carry High advisories (brace-expansion,
#      ip-address, picomatch, pacote, sigstore) that no base-image bump has fixed.
#
#   sh scripts/ci/web-image-deps.sh
set -eu
ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"
fail=0

lock_version() {
  node -e 'const l=require("./web/package-lock.json");const p=l.packages["node_modules/"+process.argv[1]];process.stdout.write(p?p.version:"")' "$1"
}

# at_least <package> <floor>: the locked version is >= floor (numeric major.minor.patch compare)
at_least() {
  have=$(lock_version "$1")
  if [ -z "$have" ]; then echo "FAIL: web/package-lock.json has no $1"; fail=1; return; fi
  if node -e 'const a=process.argv[1].split(".").map(Number),b=process.argv[2].split(".").map(Number);for(let i=0;i<3;i++){if(a[i]!==b[i])process.exit(a[i]>b[i]?0:1)}process.exit(0)' "$have" "$2"; then
    echo "ok: $1 $have (>= $2)"
  else
    echo "FAIL: web/package-lock.json resolves $1 $have, need >= $2"; fail=1
  fi
}

at_least next 16.3.8
at_least sharp 0.35.5

# The runtime stage is everything after the last FROM, with line continuations joined.
runtime=$(awk '/^FROM /{buf=""} {buf=buf $0 "\n"} END{printf "%s", buf}' web/Dockerfile | sed -e ':a' -e '/\\$/N; s/\\\n//; ta')
for path in /usr/local/lib/node_modules/npm /usr/local/bin/npm /usr/local/bin/npx; do
  if ! printf '%s\n' "$runtime" | grep -q "rm -rf .*$path"; then
    echo "FAIL: web/Dockerfile runtime stage does not remove $path"; fail=1
  fi
done

if [ "$fail" -ne 0 ]; then exit 1; fi
echo "web-image-deps: OK"
