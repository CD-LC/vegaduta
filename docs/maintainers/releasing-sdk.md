# Releasing the Java Agent SDK

The Java Agent SDK (`sdk/java`, group `io.vegaduta`) is released on its own cadence, by
`.github/workflows/sdk-release.yml`, for a tag `sdk-vX.Y.Z`. Like every workflow in this
repository it is run by hand from the Actions tab (automatic triggers are off, see
[Releasing](releasing.md)). It does not share a version with the platform (`v*.*.*` tags).

## One-time setup (before the first publish)

| Step | Where | Notes |
|---|---|---|
| Verify the namespace | Central Portal → Namespaces | `io.vegaduta` needs a DNS TXT record on `vegaduta.io` (or use `io.github.<owner>`, which needs no domain but is unwieldy in a POM). Decide before any coordinates appear in documentation ([ADR-0013](../architecture/ADR-0013-embeddable-agent-sdk.md) §7.1) |
| Create a user token | Central Portal → Account | Becomes `CENTRAL_USERNAME` and `CENTRAL_PASSWORD` |
| Create a GPG key | `gpg --full-generate-key`, publish the public key to a keyserver | Becomes `SDK_GPG_PRIVATE_KEY` (ASCII-armored) and `SDK_GPG_PASSPHRASE` |
| Add secrets | Settings → Environments → `maven-central` → secrets | `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`, `SDK_GPG_PRIVATE_KEY`, `SDK_GPG_PASSPHRASE`. Require a reviewer on the environment |
| Turn publishing on | Settings → Variables (repository) | `SDK_PUBLISH_CENTRAL=true`. Without it the workflow still builds and creates the GitHub Release but skips Central |
| Arm the API check | Settings → Variables (repository) | After the first release set `SDK_JAPICMP_BASELINE` to its version; later releases then fail on a binary or source incompatible change of `api` or `core` |

## Releasing

1. Set the release version and commit it:

    ```bash
    mvn -f sdk/java/pom.xml versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false
    # also bump <project.build.outputTimestamp> in sdk/java/pom.xml to the release date
    ```

2. Update `CHANGELOG.md` and `sdk/java/README.md` (the dependency snippets show the version).
3. Tag and push: `git tag -s sdk-v0.1.0 -m "Java Agent SDK 0.1.0" && git push origin sdk-v0.1.0`.
4. Actions tab → **SDK release** → **Run workflow**, with **Use workflow from** set to the tag
   `sdk-v0.1.0`. The workflow verifies that the tag equals the pom version and that nothing is a `-SNAPSHOT`,
   runs the whole build on JDK 17, attests SLSA provenance for every jar, attaches jars, sources,
   javadoc, SBOMs and `SHA256SUMS` to a GitHub Release (a pre-release for `-rc` versions), and,
   if enabled, uploads signed artifacts to the Central Portal.
5. In the Central Portal, check the validated deployment and press **Publish** (`autoPublish` is
   deliberately off).
6. Open the next development version: `mvn versions:set -DnewVersion=0.2.0-SNAPSHOT`.

Re-running for an existing tag: run it again on the same tag. A branch is refused.

## Verifying a release

```bash
sha256sum -c SHA256SUMS --ignore-missing
gh attestation verify vegaduta-agent-core-0.1.0.jar --repo <owner>/<repo>
```

Maven Central artifacts additionally carry GPG signatures and, once Central supports it,
Sigstore bundles.
