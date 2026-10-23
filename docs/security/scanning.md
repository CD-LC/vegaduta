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

**The web image carries no package manager.** The release gate stops only on a
fixable *critical* finding, so High advisories need their own guard. The node
base image ships npm, corepack and yarn with bundled dependencies that have
fixable High advisories no base-image bump has cleared, and the console never
runs them (`HEALTHCHECK` and `CMD` call `node` only), so the runtime stage of
`web/Dockerfile` deletes them. `scripts/ci/web-image-deps.sh` (in
`static-gates.yml` and `make gates`) fails when that `rm` disappears or when
`web/package-lock.json` resolves `next` or `sharp` below the patched versions
(raise the floors in the script when a newer advisory lands). The Node
service images that must keep npm (`mcp-stdio-runner`, `workspace-runner`,
`browser-runner`) are not covered by this guard.

## What the scanners leave out

Both exclusions live in a file next to the workflow, with the reason beside
each path, so a report can be read as "what ships".

- **CodeQL** reads `.github/codeql/codeql-config.yml`. Its `paths-ignore`
  drops test and tooling code (`tests/`, `web/e2e/`, `**/src/test/`,
  `*.test.ts(x)`, `*.spec.ts`, `web/scripts/`), the bundled documentation
  assets and `examples/`. The workflow still runs on every pull request (it has
  no workflow-level path filter, so `CodeQL OK` always reports); only alerts in
  those paths are not raised. The query suite stays `security-extended`, set in
  `codeql.yml`.
- **Trivy** reads `trivy.yaml` (`trivy-config` on both scan steps), which skips
  `deploy/helm/examples` and `tests/load`: example values files and kind
  load-test manifests, where a replica count or a missing resource limit is not
  a finding. `infra/terraform/modules` is scanned on purpose: those are
  reference modules the operator hardens (see `infra/terraform/README.md`), and
  their findings are that operator's checklist.

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
`license-scan`, `Release <version>`) under an auto-created product named
after the repository (`DEFECTDOJO_PRODUCT`), in the product type `VegaDuta`
(`DEFECTDOJO_PRODUCT_TYPE`); reimports dedupe and close findings that disappear between runs.
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
