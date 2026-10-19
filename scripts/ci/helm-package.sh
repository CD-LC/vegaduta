#!/usr/bin/env bash
# Package the application Helm charts in deploy/helm for a release.
#
#   scripts/ci/helm-package.sh <version> [out-dir]
#
# Packages every chart that scripts/ci/helm-validate.sh would validate (library
# charts, vendored dependencies, deploy/helm/platform and deploy/helm/stateful
# are skipped), minus the names in HELM_RELEASE_EXCLUDE (space-separated chart
# directory names; default: charts that are not part of the open-source
# release). A leading "v" is stripped from <version>.
#
# Version stamping (on the temp copy only): every chart's `version`, and the
# version constraint of every file:// dependency, becomes <version>; `appVersion`
# becomes <version> for every first-party chart, i.e. every chart whose
# appVersion equals the umbrella chart's (charts/vegaduta). Image tags default
# to .Chart.AppVersion inside each subchart, so this is what makes the
# umbrella pull the images of the same release (core, core-worker and
# core-jobs must share one tag). Charts that wrap upstream software keep their
# appVersion. file:// dependencies are vendored into each package, so the
# library chart does not need to be published separately.
#
# Override the chart list with HELM_RELEASE_CHARTS="deploy/helm/charts/vegaduta ...".
# Prints one packaged .tgz path per line on stdout.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

version="${1:?usage: helm-package.sh <version> [out-dir]}"
version="${version#v}"
out="${2:-dist/charts}"
exclude=" ${HELM_RELEASE_EXCLUDE:-} "

mkdir -p "$out"
out="$(cd "$out" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cp -r deploy/helm "$work/helm"

python3 - "$work/helm" "$version" <<'PY'
import pathlib, re, sys
root, version = pathlib.Path(sys.argv[1]), sys.argv[2]
umbrella = root / "charts" / "vegaduta" / "Chart.yaml"
m = re.search(r'^appVersion:\s*"?([^"\n]+)"?', umbrella.read_text(), re.M) if umbrella.exists() else None
first_party = m.group(1).strip() if m else None
for chart in root.rglob("Chart.yaml"):
    lines = chart.read_text().splitlines(keepends=True)
    out, in_deps, dep = [], False, []
    def flush():
        if any("file://" in l for l in dep):
            dep[:] = [re.sub(r'^(\s+version:\s*).*$', r'\g<1>' + version, l.rstrip("\n")) + "\n" for l in dep]
        out.extend(dep)
        dep.clear()
    for line in lines:
        if re.match(r'^dependencies:', line):
            in_deps = True
            out.append(line)
            continue
        if in_deps:
            if re.match(r'^\S', line):
                flush()
                in_deps = False
            else:
                if re.match(r'^\s*-\s', line):
                    flush()
                dep.append(line)
                continue
        if re.match(r'^version:', line):
            line = "version: " + version + "\n"
        elif first_party and re.match(r'^appVersion:\s*"?' + re.escape(first_party) + r'"?\s*$', line):
            line = 'appVersion: "' + version + '"\n'
        out.append(line)
    flush()
    chart.write_text("".join(out))
PY

charts=()
if [[ -n "${HELM_RELEASE_CHARTS:-}" ]]; then
  for c in $HELM_RELEASE_CHARTS; do charts+=("${c#deploy/helm/}"); done
else
  while IFS= read -r chart_yaml; do
    rel="${chart_yaml#deploy/helm/}"
    dir="$(dirname "$rel")"
    case "$dir" in
      platform|platform/*|stateful|stateful/*) continue ;;
      */charts/*/charts/*|*/charts/*/charts) continue ;;
    esac
    grep -Eq '^type:[[:space:]]*library' "$chart_yaml" && continue
    [[ "$exclude" == *" $(basename "$dir") "* ]] && continue
    charts+=("$dir")
  done < <(find deploy/helm -name Chart.yaml | LC_ALL=C sort)
fi

for chart in "${charts[@]}"; do
  path="$work/helm/$chart"
  if grep -q '^dependencies:' "$path/Chart.yaml"; then
    # update, not build: re-resolves file:// dependencies (the umbrella chart
    # vendors every component chart) even if a Chart.lock is stale.
    rm -rf "$path/charts"
    helm dependency update "$path" >&2
  fi
  helm package "$path" --destination "$out" >&2
  name="$(awk '/^name:/ {print $2; exit}' "$path/Chart.yaml")"
  echo "$out/${name}-${version}.tgz"
done
