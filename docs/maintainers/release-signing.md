# Signing the Setup app

The Setup app ships as a macOS `.dmg` (and an optional `.pkg`) and a Windows `.exe`. Unsigned,
macOS Gatekeeper and Windows SmartScreen warn on first launch. `release.yml` signs, notarizes and
staples them **when the repository holds the credentials below**, and builds exactly what it built
before when it does not. So nothing changes until the secrets are added, and nothing else has to be
edited afterwards.

| Platform | Artifact | What the release does with credentials |
|---|---|---|
| macOS | `VegaDuta-Setup-<version>.dmg` | signs the app and the dmg with a Developer ID Application certificate (hardened runtime, secure timestamp), notarizes and staples both |
| macOS | `VegaDuta-Setup-<version>.pkg` | built only when an Installer certificate is configured; signed, notarized and stapled |
| Windows | `vegaduta-setup.exe` inside `vegaduta-setup-windows-amd64.zip` | Authenticode-signed and timestamped before it is zipped |

The code lives in `installer/package.sh` (all the macOS steps, driven by `MAC_*` environment
variables) and in the `setup-pkg-darwin` and `setup-exe-windows` jobs of `.github/workflows/release.yml`.

## What you have to obtain

Neither of these can be done from the repository; both cost money and need an identity check.

### Apple

1. Enroll in the **Apple Developer Program** (individual or organization; an organization needs a
   D-U-N-S number).
2. As the account holder, create two certificates under *Certificates, IDs & Profiles*:
   **Developer ID Application** (signs the app and dmg) and **Developer ID Installer** (signs the
   `.pkg`; skip it if you do not want a `.pkg`).
3. Export each, with its private key, from Keychain Access as a `.p12`, using the same password for both.
4. In App Store Connect, *Users and Access > Integrations > Team Keys*, create an API key and download
   the `.p8` (it can be downloaded once). Note the **Key ID** and the **Issuer ID**.

### Windows

The workflow signs through **Azure Artifact Signing** (formerly Trusted Signing): Microsoft holds the
key in an HSM and the service has its own CA, which SmartScreen trusts.

**Check eligibility first.** Artifact Signing's public identity validation has been limited to certain
countries and organization types. If your organization is not eligible, use a certificate from a
code-signing CA (DigiCert, Sectigo, SSL.com and others) with **cloud or HSM signing**: CAs have had to
keep the private key in hardware since 2023, so a downloadable `.pfx` is no longer issued. Then replace
the single "Sign the exe" step in `setup-exe-windows` with your provider's action or CLI. The release
job only needs a signed `vegaduta-setup.exe` and a `signed.txt` that says `true`.

For Artifact Signing: create an Azure subscription, a signing account and a *public trust* certificate
profile (this is where identity validation happens), then an app registration whose service principal
holds the certificate-profile signer role on that profile.

## Repository secrets and variables

*Settings > Secrets and variables > Actions.* Base64 a file with `base64 -i file | pbcopy` (macOS).

| Name | Kind | Value |
|---|---|---|
| `APPLE_DEVELOPER_ID_APPLICATION_P12` | secret | base64 of the Developer ID Application `.p12` |
| `APPLE_CERT_PASSWORD` | secret | the password both `.p12` files were exported with |
| `APPLE_SIGNING_IDENTITY` | secret | the certificate's common name, for example `Developer ID Application: Example Ltd (ABCDE12345)` |
| `APPLE_NOTARY_KEY_P8` | secret | base64 of the App Store Connect API key `.p8` |
| `APPLE_NOTARY_KEY_ID` | secret | the API key's Key ID |
| `APPLE_NOTARY_ISSUER_ID` | secret | the team's Issuer ID |
| `APPLE_DEVELOPER_ID_INSTALLER_P12` | secret | optional: base64 of the Developer ID Installer `.p12` |
| `APPLE_INSTALLER_IDENTITY` | secret | optional: `Developer ID Installer: Example Ltd (ABCDE12345)` |
| `AZURE_TENANT_ID`, `AZURE_CLIENT_ID`, `AZURE_CLIENT_SECRET` | secrets | the app registration |
| `AZURE_SIGNING_ENDPOINT` | variable | the regional endpoint, for example `https://eus.codesigning.azure.net/` |
| `AZURE_SIGNING_ACCOUNT` | variable | the signing account name |
| `AZURE_SIGNING_PROFILE` | variable | the certificate profile name |

What each combination does:

| Set | Result |
|---|---|
| nothing | unchanged: ad-hoc signed `.dmg`, unsigned `.exe`, READMEs with the "Open Anyway / Run anyway" steps |
| the three `APPLE_*` signing secrets only | app and dmg are Developer ID signed but **not** notarized, so Gatekeeper still warns |
| signing + `APPLE_NOTARY_*` | signed, notarized, stapled `.dmg`; the README drops the Gatekeeper steps |
| + the Installer secrets | also a signed, notarized, stapled `.pkg` |
| all `AZURE_*` | signed, timestamped `.exe`; the README drops the SmartScreen steps |

`package.sh` refuses a half-configured macOS setup (for example a notary key without a signing
identity, or an Installer identity without notarization) rather than quietly ship a build that
still warns.

## Checking a release

The workflow already fails the release if Gatekeeper does not accept the notarized artifacts or if
the `.exe` signature does not read back as valid and timestamped. By hand, on a downloaded copy:

```sh
# macOS
spctl --assess --type open --context context:primary-signature -vv VegaDuta-Setup-<version>.dmg
xcrun stapler validate VegaDuta-Setup-<version>.dmg
pkgutil --check-signature VegaDuta-Setup-<version>.pkg
codesign -dvv --verify --strict "/Volumes/VegaDūta Setup/VegaDuta Setup.app"   # flags=0x10000(runtime)
```

```powershell
# Windows
Get-AuthenticodeSignature .\vegaduta-setup.exe | Format-List Status, SignerCertificate, TimeStamperCertificate
```

The final proof is a download through a browser (which adds the quarantine / mark-of-the-web) on a
clean Mac and a clean Windows machine: no warning on the Mac; on Windows, SmartScreen may still show
"unrecognized app" until the signing identity has built reputation, which a signature alone does not
bypass.

## Keeping it working

- **Expiry.** A Developer ID certificate lasts five years; notarized, stapled artifacts stay valid after
  it expires, but the next release needs a new certificate. Signatures made with a timestamp stay valid
  after the Windows certificate expires.
- **Rotation.** Replace the secrets; no code change.
- **Least privilege.** The secrets are available to the release workflow, which runs on tags. Putting the
  signing jobs behind a protected GitHub Environment with required reviewers would add an approval step
  before any signing happens; it is not set up here because it changes how releases are started.
- **A notarization failure fails the release.** `package.sh` prints Apple's log. Common causes: a binary
  signed without the hardened runtime, a missing secure timestamp, or an expired agreement in the Apple
  Developer account.
