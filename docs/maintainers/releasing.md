# Releasing

Releases are built and published by `.github/workflows/release.yml`, run by
hand (`workflow_dispatch`) **on** a `v<major>.<minor>.<patch>[-<pre>]` tag.
Pushing a tag alone starts nothing: the workflow has no `push` trigger, because
a release is a deliberate, human-initiated act. Nothing is built or signed on a
maintainer's machine.

## Cutting a release

1. Make sure the commit you want to release is on `main` and its `CI OK` and
   `CodeQL OK` checks are green. The workflow's `verify` job refuses any other
   commit (it waits up to an hour for checks that are still running).
2. Move the `## [Unreleased]` entries in `CHANGELOG.md` under
   `## [<version>] - <date>` and merge that change. The release notes are taken
   from this section; without one, GitHub generates notes from pull request
   titles (categories in `.github/release.yml`).
3. Tag and push (an annotated, signed tag is preferred):
   ```sh
   git tag -s v0.1.0 -m "v0.1.0"
   git push origin v0.1.0
   ```
4. Start the workflow on that tag: Actions -> **Release** -> **Run workflow**,
   and choose the tag under "Use workflow from" (Tags -> `v0.1.0`), or
   ```sh
   gh workflow run release.yml --ref v0.1.0
   ```
5. Watch the run. The order is: prepare (validate the tag), verify (`CI OK`
   and `CodeQL OK` on the tagged commit), build every image (amd64 + arm64,
   pushed by digest), publish version-tagged manifests (each scanned with
   grype, failing on a fixable critical vulnerability, before it is signed),
   the `omnibus` image, charts, the macOS Setup `.dmg`, the draft release
   (with the Setup binaries), promote floating tags, publish the release.

To re-run a failed release, run the workflow again the same way, on the
**tag**. Runs dispatched from a branch are refused, because their signatures
would carry a `refs/heads/...` identity that the documented verification
commands reject.

## What a release publishes

| Artifact | Where | Tags |
|---|---|---|
| 17 multi-arch images (16 repositories: `postgres-cnpg` is published into `postgres`) + the `omnibus` single-container image | `ghcr.io/<owner>/<image>` (`ghcr.io/cd-lc/<image>` for this repository) | `<version>` always; `latest` and `<major>.<minor>` for stable releases; `postgres` also `16`, and the CloudNativePG variant `16-pgvector-age-<version>` / `16-pgvector-age`. `omnibus` is assembled from the published images after `publish` |
| Helm charts | `oci://ghcr.io/<owner>/charts/<chart>`, and the packaged `.tgz` files as release assets | chart version = `<version>` (packaged by `scripts/ci/helm-package.sh`) |
| Source tarball | release assets `vegaduta-<version>.tar.gz` and `vegaduta.tar.gz` (same content) | the tarball's `deploy/compose/.env.example` has `VEGADUTA_VERSION=<version>` written in |
| Setup app | `VegaDuta-Setup-<version>.dmg` (macOS, universal), `vegaduta-setup-windows-amd64.zip`, `vegaduta-setup-linux-{amd64,arm64}.tar.gz`, `vegaduta-setup-darwin-{amd64,arm64}.tar.gz` (bare binaries) | built from `installer/` by `installer/package.sh`; unsigned / ad-hoc signed (no Apple notarization or Windows code signing yet) |
| Checksums | `SHA256SUMS` | GNU `sha256sum` format (`<sha256>  <file>`, bare file names) over every tarball, zip, dmg, chart and SBOM asset |
| Signatures | `<file>.sigstore.json` (Sigstore bundle), `<file>.sig`, `<file>.pem` for `SHA256SUMS` and both tarballs | keyless, identity `.../.github/workflows/release.yml@refs/tags/v<version>` |
| Provenance | `vegaduta-<version>.intoto.jsonl` (every `.tar.gz` asset); OCI attestations on every image; GitHub attestations (by file digest) on every chart `.tgz` | SLSA build provenance |
| SBOMs | `sbom-<image>.spdx.json`, and as signed OCI attestations | SPDX JSON (syft); an SBOM still over 16 MiB after compaction (browser images, `omnibus`) is published as an asset but not attested |

Images and charts are signed by digest, so the floating tags added at the end
are covered by the same signatures. Verification commands are in
[SECURITY.md](../../SECURITY.md#verifying-releases).

## All or nothing

- No image is tagged until **every** image built on both architectures.
- Charts are pushed only after every image's version tag exists, so a chart
  never references a missing image.
- The GitHub Release is created as a **draft**, and the floating tags
  (`latest`, `<major>.<minor>`, `postgres:16`) move only after images, charts
  and the draft all succeeded. The draft is published last.
- A failed run can leave version-tagged images and a draft release behind, but
  never moves `latest` and never publishes a release. Re-running on the same
  tag replaces the draft and completes the release.

## Pre-releases and `latest`

A tag with a suffix (`v0.2.0-rc.1`) is a pre-release:

- only the exact version tag is pushed (`ghcr.io/<owner>/core:0.2.0-rc.1`);
  `latest` and `<major>.<minor>` keep pointing at the last stable release;
- the GitHub Release is marked as a pre-release and is never GitHub's "latest
  release", so `install.sh` (which downloads `releases/latest/...` unless a
  version is given) keeps installing the last stable release;
- `helm install` without `--version` does not pick it up either.

Consequently, **the first release must be a stable one** (e.g. `v0.1.0`) for
the default `install.sh` path and `latest` image tags to work at all. Until
then, users build from source (`scripts/vegaduta up --build`) or pass an
explicit pre-release version.

## One-time setup before the first release

- **GHCR packages are private when first created.** After the first release
  run, open each package (the 17 image repositories, `omnibus` included, and every chart under
  `charts/`) in the organization's Packages tab, set its visibility to
  **Public**, and link it to the repository ("Connect repository"), or pulls
  fail with `401`/`denied` for everyone else. Later releases keep the setting.
- Optional repository variables `WEB_PUBLIC_API_BASE_URL` and
  `WEB_PUBLIC_KEYCLOAK_URL` override the build-time fallback URLs baked into
  the web images (the consoles read the real values at runtime).
- The repository must be public (or have GitHub Advanced Security) for CodeQL,
  dependency review and the Scorecard upload that the `verify` job depends on
  indirectly through `CI OK` / `CodeQL OK`.
- See [repo-setup.md](repo-setup.md) for the identity placeholders that must
  be settled first.
