# Releasing the Java Agent SDK

The Java Agent SDK (`sdk/java`, group `io.github.cd-lc`) is released on its own cadence, by
`.github/workflows/sdk-release.yml`, for a tag `sdk-vX.Y.Z`. Like the
repository's other release workflows (`release.yml`, `pypi-release.yml`) it is run by hand
from the Actions tab: it has no tag-push trigger (see [Releasing](releasing.md)). It does not share a version with the platform (`v*.*.*` tags).

## One-time setup (before the first publish)

| Step | Where | Notes |
|---|---|---|
| Verify the namespace | Central Portal → Namespaces | `io.github.cd-lc` is verified via a throwaway public repo in the `CD-LC` org (the portal hands you the repo name, usually a random key, and checks it exists). `io.vegaduta`/`ai.vegaduta` would need a DNS TXT on a domain we own. Decide before any coordinates appear in documentation ([ADR-0013](../architecture/ADR-0013-embeddable-agent-sdk.md) §7.1) |
| Create a user token | Central Portal → Account | Becomes `CENTRAL_USERNAME` and `CENTRAL_PASSWORD` |
| Create a GPG key | `gpg --full-generate-key`, publish the public key to a keyserver | Becomes `SDK_GPG_PRIVATE_KEY` (ASCII-armored) and `SDK_GPG_PASSPHRASE` |
| Add secrets | Settings → Environments → `maven-central` → secrets | `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`, `SDK_GPG_PRIVATE_KEY`, `SDK_GPG_PASSPHRASE`. Require a reviewer on the environment |
| Turn publishing on | Settings → Variables (repository) | `SDK_PUBLISH_CENTRAL=true`. Without it the workflow still builds and creates the GitHub Release but skips Central |
| Arm the API check | Settings → Variables (repository) | After the first release set `SDK_JAPICMP_BASELINE` to its version; later releases then fail on a binary or source incompatible change of `api` or `core` |

## Releasing

1. Set the release version and commit it:

    ```bash
    mvn -f sdk/java/pom.xml versions:set -DnewVersion=0.1.1 -DgenerateBackupPoms=false
    # also bump <project.build.outputTimestamp> in sdk/java/pom.xml to the release date
    ```

2. Update `CHANGELOG.md` and `sdk/java/README.md` (the dependency snippets show the version).
3. Tag and push: `git tag -s sdk-v0.1.1 -m "Java Agent SDK 0.1.1" && git push origin sdk-v0.1.1`.
4. Actions tab → **SDK release** → **Run workflow**, with **Use workflow from** set to the tag
   `sdk-v0.1.1`. The workflow verifies that the tag equals the pom version and that nothing is a `-SNAPSHOT`,
   runs the whole build on JDK 17, attests SLSA provenance for every jar, attaches jars, sources,
   javadoc, SBOMs, the runnable scanner CLI (`vegaduta-agent-scanner-<version>-cli.jar`, a shaded jar with the SDK's API and core, SLF4J's API and RE2/J inside) and `SHA256SUMS` to a GitHub Release (a pre-release for `-rc` versions), and,
   if enabled, uploads signed artifacts to the Central Portal.
5. In the Central Portal, check the validated deployment and press **Publish** (`autoPublish` is
   deliberately off) - **or** tick the workflow's `publish-deployment` input when dispatching: it
   calls the same Portal publish endpoint as the button, with the `CENTRAL_*` secrets, and waits for
   `PUBLISHED`. Useful when nobody is sure which Sonatype login owns the token.
6. Open the next development version: `mvn versions:set -DnewVersion=0.2.0-SNAPSHOT`.

Re-running for an existing tag: run it again on the same tag. A branch is refused.

## Dry-run the bundle before tagging

The Central staging layout can be verified without credentials; the upload is the only step that
needs them:

```bash
cat > /tmp/fake-central.xml <<'EOF'
<settings><servers><server><id>central</id><username>x</username><password>x</password></server></servers></settings>
EOF
mvn -f sdk/java/pom.xml -s /tmp/fake-central.xml -Prelease -Dgpg.skip -DskipTests deploy
# upload 401s on the fake token, then inspect:
unzip -l sdk/java/target/central-publishing/central-bundle.zip
# every io/github/cd-lc/<artifact>/<version>/ dir must contain a *.pom - that is exactly
# what the failed sdk-v0.1.0 upload lacked.
```

## Incident note: why the first publish is 0.1.1

`sdk-v0.1.0` (2026-10-22) built and released to GitHub but Central rejected the bundle:
`Deployment ... failed: Bundle has content that does NOT have a .pom file` for every artifact
directory. The run's staging log shows each `.flattened-pom.xml` installed as the correctly named
`<artifactId>-0.1.0.pom`, so the defect is in 0.7.0's bundle assembly/validation path, not in our
staging. On 0.12.0 the produced `central-bundle.zip` carries a `*.pom` in every
`io/github/cd-lc/<artifact>/<version>/` directory (verified with the dry-run above). The `sdk-v0.1.0`
GitHub Release had already shipped its jars, so the 0.1.0 coordinate is retired and publishing
continues at **0.1.1** on tag `sdk-v0.1.1`.

## Verifying a release

```bash
sha256sum -c SHA256SUMS --ignore-missing
gh attestation verify vegaduta-agent-core-0.1.0.jar --repo <owner>/<repo>
```

Maven Central artifacts additionally carry GPG signatures and, once Central supports it,
Sigstore bundles.
