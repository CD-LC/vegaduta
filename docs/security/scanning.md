# Scan reports and dashboards

Every scanner in CI reports to the same three places: GitHub code scanning,
the run's artifacts, and (optionally) a DefectDojo instance. This page is the
map.

## Where findings land

| Scanner | Workflow | Code scanning tool | Artifact | DefectDojo scan type |
|---|---|---|---|---|
| CodeQL | `codeql.yml` | CodeQL | — | — |
| OpenSSF Scorecard | `scorecard.yml` | ossf-scorecard | `scorecard-sarif` | — |
| Trivy fs (vuln + IaC misconfig) | `trivy.yml` | Trivy | `trivy-report` | `Trivy Scan` (JSON) |
| OWASP ZAP baseline | `zap.yml` | OWASP ZAP | `zap-report` | `ZAP Scan` (JSON) |
| grype (release images) | `release.yml` | grype-`<image>` | — | `SARIF` |
| ScanCode (vendored-file licenses) | `license-scan.yml` | — | `scancode-report` | `ScanCode Scan` |
| SonarQube | `sonarqube.yml` | — | — | server-side pull (below) |
| dependency-review (PR gate) | `ci.yml` | — | — | — |
| gitleaks (secrets) | `static-gates.yml` | — | — | — |

**GitHub code scanning** (Security → Code scanning) is the single list for
everything that emits SARIF: filter by tool to isolate one scanner. Trivy,
ZAP, grype, CodeQL and Scorecard all land there.

**SonarQube** findings stay on the SonarQube dashboard itself
(`vars.SONAR_HOST_URL`); there is no SARIF path for them. The workflow only
reports the quality-gate result as a check.

**License violations** on *dependencies* are a PR gate, not a report: the
`dependency-review` job in `ci.yml` comments and fails on denied licenses.
The `license-scan` workflow covers the files we redistribute (vendored JS,
bundled fonts, the Keycloak theme) and fails when ScanCode detects a license
id outside `scripts/ci/license-allowlist.txt`.

## DefectDojo (all findings, one dashboard)

[DefectDojo](https://www.defectdojo.com/) is the optional aggregation layer:
it ingests every scanner's report, dedupes findings across runs, and tracks
mitigated/reopened state — including SonarQube, which the other sinks cannot
show.

### Run one locally

```bash
# in deploy/compose/.env (or the shell): DD_ADMIN_PASSWORD, DD_SECRET_KEY,
# DD_CREDENTIAL_AES_256_KEY, DD_DATABASE_PASSWORD
docker compose -f deploy/compose/defectdojo.yml \
  --env-file deploy/compose/.env up -d --wait
# http://localhost:8082 — log in as `admin`
```

Get an API token via the user menu → **API v2 Key**.

### Point CI at it

- Repository variable: `DEFECTDOJO_URL` (e.g. `https://dojo.example.com`)
- Repository secret: `DEFECTDOJO_API_KEY`

While either is unset, `scripts/ci/defectdojo-import.sh` skips with a notice
and every workflow stays green — the same opt-in pattern as
`vars.SONAR_HOST_URL`.

Each workflow imports into its own engagement (`trivy-fs`, `zap-baseline`,
`license-scan`, `Release <version>`) under an auto-created `VegaDuta`
product; reimports dedupe and close findings that disappear between runs.
The ZAP baseline and license scans set `DEFECTDOJO_CLOSE_OLD_FINDINGS=false`
because their coverage varies between runs.

### SonarQube → DefectDojo

SonarQube has no report file to push. Instead, on the product in DefectDojo,
add a **Tool Configuration** of type *SonarQube* pointing at your SonarQube
host and token; the **SonarQube API Import** scan type pulls findings
server-side.

## Report artifacts

| Artifact | Contents |
|---|---|
| `trivy-report` | `trivy.sarif` + `trivy.json` |
| `zap-report` | `report_json.json`, `report_md.md`, `report_html.html`, `zap.sarif` |
| `zap_scan` | same files, uploaded by the baseline action itself |
| `scancode-report` | `scancode.json` |
| `scorecard-sarif` | `results.sarif` |
| `core-coverage` | per-module JaCoCo reports (also feeds SonarQube) |
| `sdk-sbom` / `sbom-<image>` | CycloneDX / SPDX SBOMs |
