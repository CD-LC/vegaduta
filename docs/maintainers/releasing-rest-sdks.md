# Releasing the REST client SDKs

The generated REST clients - `sdk/typescript` (npm) and `sdk/python` (PyPI) - release
together on their own cadence by `.github/workflows/rest-sdk-release.yml`, for a tag
`restsdk-vX.Y.Z`. Like the other release workflows it is run by hand from the Actions
tab; a branch is refused. Both packages share the tag's version.

This doc covers the generated REST clients only. The Java Agent SDK has its own
runbook ([releasing-sdk.md](releasing-sdk.md), tag `sdk-v*`); the Python Agent SDK
has `pypi-release.yml` (tag `pysdk-v*`).

## One-time setup (before the first publish)

| Step | Where | Notes |
|---|---|---|
| Reserve the npm name | `npm login` → `npm publish` of a 0.0.0 placeholder, or an org scope | `vegaduta-sdk` is a placeholder; check `npm view vegaduta-sdk` — if it is squatted, pick `@vegaduta/sdk` (requires creating the `vegaduta` npm org first) and update `sdk/typescript/package.json` `name` |
| Reserve the PyPI name | `pypi.org/manage/account` → "Publishing" → pending publisher | `vegaduta-sdk` on PyPI; same squatting check (`pip index versions vegaduta-sdk` or the project page) |
| npm token | npm account → Access Tokens → Granular (publish) | Becomes the `NPM_TOKEN` secret. Classic tokens also work; granular tokens scoped to the one package are safer |
| PyPI trusted publisher | PyPI project → Publishing | Workflow `rest-sdk-release.yml`, environment `pypi-rest-sdk`. No password/token is ever stored |
| GitHub environments | Settings → Environments | Create `npm` and `pypi-rest-sdk`; require a reviewer on each so a publish cannot fire unattended |
| Secrets | Settings → Secrets | `NPM_TOKEN` only (PyPI uses OIDC) |
| Turn publishing on | Settings → Variables (repository) | `SDK_PUBLISH_NPM=true`, `SDK_PUBLISH_PYPI=true` — set each independently. With neither set the workflow still verifies, builds, attests and creates the GitHub Release but skips the registries |
| Flip the npm private flag | `sdk/typescript/package.json` | `"private": true` is a deliberate guard against publishing the unreserved name; the workflow removes it itself (`npm pkg delete private`) - leave it in the repo |

## Releasing

1. Bump both manifests to the same version and commit:

    ```bash
    # sdk/typescript/package.json  -> "version": "0.2.0"
    # sdk/python/pyproject.toml    -> version = "0.2.0"
    # sdk/python/vegaduta_sdk/__init__.py -> __version__ = "0.2.0"
    ```

2. Regenerate the clients if the spec moved (`node sdk/generate.mjs`), update
   `CHANGELOG.md`, and confirm `node sdk/generate.mjs --check` is clean.
3. Tag and push: `git tag -s restsdk-v0.2.0 -m "REST client SDKs 0.2.0" && git push origin restsdk-v0.2.0`.
4. Actions tab → **REST SDK release** → **Run workflow**, **Use workflow from** = the
   tag. Verify asserts the tag equals all three version strings, re-checks the
   generated files against the spec, type-checks, builds and runs every runtime and
   contract test; build packs the npm tarball and the sdist+wheel, checks them into
   `SHA256SUMS`, attests SLSA provenance and creates the GitHub Release.
5. If publishing is armed, the `npm` and `pypi` jobs publish (npm with
   `--provenance`, PyPI via trusted publishing). Both skip existing versions.
6. Verify:

    ```bash
    npm view vegaduta-sdk version
    pip index versions vegaduta-sdk  # or check pypi.org/project/vegaduta-sdk
    gh attestation verify vegaduta-sdk-0.2.0.tgz --repo CD-LC/vegaduta
    ```

Re-running for an existing tag is safe: `skip-existing` on PyPI and npm's refusal to
republish the same version make the jobs idempotent.

## Version policy

Both REST clients track the platform API surface, so release them after the platform
release whose spec they were generated from. Their version does not have to equal the
platform's `v*.*.*` - `restsdk-v0.2.0` can ship for platform `v0.1.9`. Record the
platform version they cover in the release notes (`--generate-notes` + a line under
the tag message is enough for now).
