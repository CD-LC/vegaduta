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

The `setup-exe-windows` job signs through **DigiCert KeyLocker** (the Software Trust Manager part of
DigiCert ONE): the private key stays in DigiCert's HSM and the job only sends signing requests, which
is what the CA rules for code signing now require. It is not Azure Artifact Signing, which Microsoft
does not offer to organizations in India (its public-trust list is the US, Canada, the EU, the UK,
Australia, New Zealand, Japan, South Korea, Singapore, Switzerland, Norway and Israel; checked
2026-11-01 against Microsoft's quickstart).

1. Buy a **KeyLocker code-signing certificate** for your organization (OV or EV; DigiCert validates the
   organization, which takes days) and note the **keypair alias** DigiCert ONE gives it.
2. In DigiCert ONE create a **service user** for CI, generate its **API key**, and generate its **client
   authentication certificate** (a `.p12` file with a password). Treat both like passwords.
3. Note your account's **host** (shown in DigiCert ONE; for the US region it is
   `https://clientauth.one.digicert.com`).
4. Add the secrets and variables below.

The job uses DigiCert's official action in *simple signing* mode (it runs `smctl` directly, with no
`signtool` or key-storage-provider setup). Another Authenticode signer can replace the single "Sign the
exe" step; the release job only needs a signed `vegaduta-setup.exe` and a `signed.txt` that says `true`.

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
| `SM_API_KEY` | secret | the service user's DigiCert ONE API key |
| `SM_CLIENT_CERT_FILE_B64` | secret | base64 of the service user's client authentication certificate (`.p12`) |
| `SM_CLIENT_CERT_PASSWORD` | secret | that certificate's password |
| `SM_HOST` | variable | your DigiCert ONE host, for example `https://clientauth.one.digicert.com` |
| `SM_KEYPAIR_ALIAS` | variable | the alias of the KeyLocker keypair to sign with |

What each combination does:

| Set | Result |
|---|---|
| nothing | unchanged: ad-hoc signed `.dmg`, unsigned `.exe`, READMEs with the "Open Anyway / Run anyway" steps |
| the three `APPLE_*` signing secrets only | app and dmg are Developer ID signed but **not** notarized, so Gatekeeper still warns |
| signing + `APPLE_NOTARY_*` | signed, notarized, stapled `.dmg`; the README drops the Gatekeeper steps |
| + the Installer secrets | also a signed, notarized, stapled `.pkg` |
| all five `SM_*` values | signed, timestamped `.exe`; the README drops the SmartScreen steps |

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
