#!/usr/bin/env bash
#
# Push a scan report to a DefectDojo server (API v2 /reimport-scan/).
#
#   scripts/ci/defectdojo-import.sh <scan-type> <report-file>
#
# Opt-in: with DEFECTDOJO_URL or DEFECTDOJO_API_KEY unset the push is skipped
# with a notice, so every caller stays green for forks and installs without a
# DefectDojo (same pattern as vars.SONAR_HOST_URL in sonarqube.yml).
#
# /reimport-scan/ is used for every upload: with auto_create_context it
# creates Product Type -> Product -> Engagement -> Test on the first run, and
# on later runs it dedupes against the same Test instead of piling up
# duplicate findings.
#
#   DEFECTDOJO_URL                  base URL, e.g. http://localhost:8082
#                                   (deploy/compose/defectdojo.yml) or your
#                                   hosted instance
#   DEFECTDOJO_API_KEY              API v2 token (DefectDojo -> user menu ->
#                                   API v2 Key)
#   DEFECTDOJO_PRODUCT_TYPE         default "VegaDuta"
#   DEFECTDOJO_PRODUCT              default: the repository name
#   DEFECTDOJO_ENGAGEMENT           default "CI"; set per workflow so each
#                                   scanner's runs land in their own Test
#   DEFECTDOJO_CLOSE_OLD_FINDINGS   default "true": findings absent from this
#                                   report are marked mitigated. Set "false"
#                                   for scans whose scope varies run to run
#                                   (e.g. the ZAP baseline crawl), or fixed
#                                   findings would falsely reopen.
#   DEFECTDOJO_VERSION              product version field (release scans)
#
# <scan-type> is a DefectDojo parser name: "Trivy Scan", "ZAP Scan",
# "Anchore Grype", "ScanCode Scan", "SARIF" (generic importer), ...
set -euo pipefail

scan_type="${1:?usage: defectdojo-import.sh <scan-type> <report-file>}"
report="${2:?usage: defectdojo-import.sh <scan-type> <report-file>}"

if [[ -z "${DEFECTDOJO_URL:-}" || -z "${DEFECTDOJO_API_KEY:-}" ]]; then
  echo "defectdojo-import: SKIP (DEFECTDOJO_URL / DEFECTDOJO_API_KEY not set)"
  exit 0
fi
[[ -f "$report" ]] || { echo "defectdojo-import: no such report file: $report" >&2; exit 1; }

product="${DEFECTDOJO_PRODUCT:-}"
if [[ -z "$product" ]]; then
  product="${GITHUB_REPOSITORY:-vegaduta}"
  product="${product##*/}"
fi
engagement="${DEFECTDOJO_ENGAGEMENT:-CI}"

args=(
  -sS --fail-with-body -X POST "${DEFECTDOJO_URL%/}/api/v2/reimport-scan/"
  -H "Authorization: Token ${DEFECTDOJO_API_KEY}"
  -F "scan_type=${scan_type}"
  -F "file=@${report}"
  -F "product_type_name=${DEFECTDOJO_PRODUCT_TYPE:-VegaDuta}"
  -F "product_name=${product}"
  -F "engagement_name=${engagement}"
  -F "auto_create_context=true"
  -F "deduplication_on_engagement=true"
  -F "close_old_findings=${DEFECTDOJO_CLOSE_OLD_FINDINGS:-true}"
  -F "scan_date=$(date +%F)"
  -F "tags=ci"
)
# Provenance fields, sent only when set (GitHub Actions provides them; a local
# run simply omits them).
[[ -n "${GITHUB_SHA:-}" ]]      && args+=(-F "commit_hash=${GITHUB_SHA}")
[[ -n "${GITHUB_REF_NAME:-}" ]] && args+=(-F "branch_tag=${GITHUB_REF_NAME}")
[[ -n "${GITHUB_RUN_ID:-}" ]]   && args+=(-F "build_id=${GITHUB_RUN_ID}")
[[ -n "${GITHUB_SERVER_URL:-}" && -n "${GITHUB_REPOSITORY:-}" ]] &&
  args+=(-F "source_code_management_uri=${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}")
[[ -n "${DEFECTDOJO_VERSION:-}" ]] && args+=(-F "version=${DEFECTDOJO_VERSION}")

echo "defectdojo-import: ${scan_type} -> ${DEFECTDOJO_URL%/} (product=${product}, engagement=${engagement})"
curl "${args[@]}"
echo
