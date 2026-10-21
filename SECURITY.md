# Security policy

## Reporting a vulnerability

**Please do not report security vulnerabilities through public issues,
discussions or pull requests.**

**Primary channel: GitHub private vulnerability reporting.** Open
<https://github.com/CD-LC/vegaduta/security/advisories/new> (or the
repository's **Security** tab, then **Report a vulnerability**). The report is
visible only to you and the maintainers, and we can collaborate on the fix and
the advisory in the same place.

If you cannot use GitHub, email **punith.nagaraju1988@gmail.com** with the subject
line `SECURITY`.
<!-- TODO(maintainers): punith.nagaraju1988@gmail.com is a placeholder. Replace it
with a monitored address before launch (scripts/dev/set-repo-identity.sh
--security-email), and enable private vulnerability reporting in the repository
settings (Settings -> Code security). -->

Please include what you found, the affected component and version or commit,
steps to reproduce, and the impact you expect. A proof of concept helps.

## What to expect

| Step | Target |
|---|---|
| Acknowledge the report | within 3 working days |
| Initial assessment and severity | within 10 working days |
| Fix for a critical or high issue | as fast as possible, normally within 30 days |
| Public advisory | when a fixed release is available, on a date agreed with you |

We credit reporters in the advisory unless you ask us not to. We will not take
legal action against good-faith research that follows this policy, avoids
privacy violations and service disruption, and does not access other people's
data beyond what is needed to demonstrate the issue.

## Security documentation

- [Threat model and known exposures](docs/platform-documentation/security/threat-model.html)
- [Security architecture](docs/platform-documentation/security/security-architecture.html) (authentication, tenant isolation, route rules, conversation ownership, sandboxing)
- [Authorization model](docs/security/authorization.md)
- [OWASP mapping](docs/security/owasp-mapping.md) (Agentic, LLM and MCP Top 10: covered, partly covered and open items) and [A2A conformance](docs/architecture/a2a-conformance.md)
- [Hardening settings](docs/engineering/SECURITY-HARDENING.md) and [key rotation](docs/operations/key-rotation.md)

## Supported versions

| Version | Security fixes |
|---|---|
| latest minor release (`x.y.*`) | yes |
| previous minor release | critical fixes for 3 months after the next minor ships |
| older releases | no |
| `main` | yes (fixed in place) |

Before 1.0, only the latest release and `main` receive fixes.

## Scope

In scope: everything in this repository, including the `core` API, the `web`
consoles, the services under `services/`, the Compose files,
the Helm charts, and the release pipeline (image signing, SBOM, provenance).

Of particular interest:

- cross-tenant data access (schema-per-tenant isolation, tenant context)
- authentication or token-issuer bypass, privilege escalation between roles
- escape from the tool sandbox or the MCP stdio runner
- exposure of stored provider keys or channel secrets (`APP_ENCRYPTION_KEY` handling)
- server-side request forgery through MCP, OpenAPI tools or workflow HTTP nodes
- prompt-injection paths that bypass the approval gate for outbound actions

Out of scope: findings that need a compromised host or administrator
credentials, volumetric denial of service, missing hardening headers without a
demonstrated impact, and vulnerabilities in third-party services the platform
connects to (report those upstream).

## Verifying releases

Every release artifact is signed keylessly (Sigstore) by the release workflow
running on the release tag, so the signing identity is always
`https://github.com/CD-LC/vegaduta/.github/workflows/release.yml@refs/tags/v<version>`.

Container images (cosign signature, SPDX SBOM and SLSA provenance
attestations) and Helm charts (cosign signature; SLSA provenance on the
packaged `.tgz`):

```sh
cosign verify ghcr.io/cd-lc/core:<version>   --certificate-identity-regexp '^https://github\.com/CD-LC/vegaduta/\.github/workflows/release\.yml@refs/tags/v'   --certificate-oidc-issuer https://token.actions.githubusercontent.com
gh attestation verify oci://ghcr.io/cd-lc/core:<version> --repo CD-LC/vegaduta
cosign verify ghcr.io/cd-lc/charts/vegaduta:<version>   --certificate-identity-regexp '^https://github\.com/CD-LC/vegaduta/\.github/workflows/release\.yml@refs/tags/v'   --certificate-oidc-issuer https://token.actions.githubusercontent.com
gh attestation verify vegaduta-<version>.tgz --repo CD-LC/vegaduta
```

Release downloads (`vegaduta-<version>.tar.gz`, the Setup app `.dmg`/`.zip`/`.tar.gz`
files, charts, SBOMs): check
`SHA256SUMS` against its signature, then the files against `SHA256SUMS`:

```sh
cosign verify-blob SHA256SUMS --bundle SHA256SUMS.sigstore.json   --certificate-identity-regexp '^https://github\.com/CD-LC/vegaduta/\.github/workflows/release\.yml@refs/tags/v'   --certificate-oidc-issuer https://token.actions.githubusercontent.com
sha256sum -c SHA256SUMS --ignore-missing
gh attestation verify vegaduta-<version>.tar.gz --repo CD-LC/vegaduta
```

A signature whose identity is not a `refs/tags/v...` run of `release.yml` in
this repository is not a release signature.
