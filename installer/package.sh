#!/bin/sh
# package.sh - build the downloadable Setup artifacts in dist/.
#
#   ./package.sh darwin  [--payload vegaduta-x.y.z.tar.gz] [--version X.Y.Z]
#                        [--web-port N] [--core-port N]
#   ./package.sh windows [--version X.Y.Z] [--exe vegaduta-setup.exe [--signed]]
#   ./package.sh windows-exe [--version X.Y.Z]
#   ./package.sh linux   [--version X.Y.Z]
#
# darwin produces a universal "VegaDuta Setup.app" inside a .dmg (needs macOS:
# lipo, codesign, hdiutil). --payload embeds a release tarball so the app
# installs offline with no published release. --web-port/--core-port stamp the
# launcher's WEB_HOST_PORT/CORE_HOST_PORT for machines whose defaults are taken.
# windows produces vegaduta-setup-windows-amd64.zip (the exe is the whole
# installer; the zip only adds a first-run README). --exe packs a prebuilt exe
# (for example one signed by the release workflow) instead of building it;
# --signed says that exe is Authenticode-signed so the README drops the
# SmartScreen instructions. windows-exe builds just the exe, ready to be signed.
# linux produces per-arch tar.gz.
#
# Signing is opt-in through the environment, so a build without credentials is
# unchanged (ad-hoc signed app, unsigned exe; Gatekeeper and SmartScreen warn
# once and every dmg/zip carries a README that says what to click):
#   MAC_SIGN_IDENTITY       "Developer ID Application: ..." - signs the app (hardened
#                           runtime, secure timestamp) and the dmg
#   MAC_NOTARY_KEY_PATH, MAC_NOTARY_KEY_ID, MAC_NOTARY_ISSUER
#                           App Store Connect API key (.p8, key id, issuer id) -
#                           notarizes and staples the app and the dmg
#   MAC_INSTALLER_IDENTITY  "Developer ID Installer: ..." - also builds a signed,
#                           notarized, stapled .pkg that installs the app
#   MAC_KEYCHAIN            keychain that holds the identities (default: search list)
# docs/maintainers/release-signing.md lists the secrets and the checks.
set -eu

cd "$(dirname "$0")"
OUT=dist
VER=dev
PAYLOAD=
WEB_PORT=
CORE_PORT=
EXE=
EXE_SIGNED=

die() { echo "package.sh: $*" >&2; exit 1; }

OS=
while [ $# -gt 0 ]; do
  case "$1" in
    --exe)       EXE="$2"; shift 2 ;;
    --signed)    EXE_SIGNED=1; shift ;;
    --payload)   PAYLOAD="$2"; shift 2 ;;
    --version)   VER="$2"; shift 2 ;;
    --web-port)  WEB_PORT="$2"; shift 2 ;;
    --core-port) CORE_PORT="$2"; shift 2 ;;
    --out)       OUT="$2"; shift 2 ;;
    -*)          die "unknown flag $1" ;;
    *)           [ -z "$OS" ] || die "unexpected argument $1"; OS="$1"; shift ;;
  esac
done
[ -n "$OS" ] || die "usage: package.sh darwin|windows|windows-exe|linux [flags]"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)" # absolute: subshells cd elsewhere

LDFLAGS="-s -w -X main.buildVersion=$VER"
build() { # build <goos> <goarch> <out>
  echo "  building $1/$2 -> $3"
  CGO_ENABLED=0 GOOS="$1" GOARCH="$2" go build -trimpath -ldflags "$LDFLAGS" -o "$3" .
}

# First-run READMEs. Unsigned builds warn once; telling the user what to click
# is the difference between a scary dialog and a working install.
readme_darwin() { # readme_darwin <file> [notarized]
  if [ -n "${2:-}" ]; then
    cat > "$1" <<'EOF'
VegaDūta Setup
==============

1. Drag "VegaDuta Setup" to Applications (or anywhere) and double-click it.
   Running it straight from this window works too. This build is signed and
   notarized by Apple, so macOS opens it without a security warning.
2. A setup page opens in your browser. Keep it (and the app) open until it
   says done, then follow the "finish setup" link to choose your password.

What it does: checks your computer, offers to install/start Docker, downloads
(or uses the bundled) verified release, starts VegaDūta in Docker and opens
the console.

Log, if something goes wrong: ~/Library/Logs/vegaduta-setup.log
EOF
    return
  fi
  cat > "$1" <<'EOF'
VegaDūta Setup
==============

1. Drag "VegaDuta Setup" to Applications (or anywhere) and double-click it.
   Running it straight from this window works too.
2. macOS will refuse the first time ("Apple could not verify ... is free of
   malware"): this build is not notarized by Apple yet. To allow it, once:
   - macOS 15 (Sequoia) and later: click "Done", open System Settings >
     Privacy & Security, scroll to the Security section, click "Open Anyway"
     next to "VegaDuta Setup was blocked", then enter your password and
     choose "Open Anyway" again. (Right-click > Open no longer works there.)
   - macOS 14 and earlier: right-click the app, choose "Open", then "Open".
   - Or, in Terminal: xattr -dr com.apple.quarantine "/Applications/VegaDuta Setup.app"
     (use the path you copied the app to), then open it normally.
3. A setup page opens in your browser. Keep it (and the app) open until it
   says done, then follow the "finish setup" link to choose your password.

What it does: checks your computer, offers to install/start Docker, downloads
(or uses the bundled) verified release, starts VegaDūta in Docker and opens
the console.

Log, if something goes wrong: ~/Library/Logs/vegaduta-setup.log
EOF
}
readme_windows() { # readme_windows <file> [signed]
  if [ -n "${2:-}" ]; then
    cat > "$1" <<'EOF'
VegaDūta Setup
==============

1. Double-click vegaduta-setup.exe. It is digitally signed; right-click >
   Properties > Digital Signatures shows the publisher.
2. A setup page opens in your browser. Keep it (and the console window) open
   until it says done, then follow the "finish setup" link to choose your
   password.

What it does: checks your computer, offers to install/start Docker Desktop,
downloads the verified release, starts VegaDūta in Docker and opens the
console.
EOF
    return
  fi
  cat > "$1" <<'EOF'
VegaDūta Setup
==============

1. Double-click vegaduta-setup.exe.
2. Windows SmartScreen may say the app is unrecognized (this build is not
   signed yet). Click "More info", then "Run anyway". You only do this once.
3. A setup page opens in your browser. Keep it (and the console window) open
   until it says done, then follow the "finish setup" link to choose your
   password.

What it does: checks your computer, offers to install/start Docker Desktop,
downloads the verified release, starts VegaDūta in Docker and opens the
console.
EOF
}
readme_linux() {
  cat > "$1" <<'EOF'
VegaDūta Setup
==============

Run ./vegaduta-setup in a terminal. A setup page opens in your browser; keep
both open until it says done. It checks the machine, prints the Docker install
command if needed, downloads the verified release and starts the stack.
EOF
}
# sha256sum on Linux runners, shasum on macOS - same "<hash>  <name>" output.
sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$@"; else shasum -a 256 "$@"; fi
}
sha() { sha256 "$1" | awk '{print $1}'; }

# macOS signing is opt-in (see the header). With no MAC_* variables the app is
# only ad-hoc signed, exactly as before.
mac_signing() { [ -n "${MAC_SIGN_IDENTITY:-}" ]; }
mac_notarizing() {
  [ -n "${MAC_NOTARY_KEY_PATH:-}" ] && [ -n "${MAC_NOTARY_KEY_ID:-}" ] && [ -n "${MAC_NOTARY_ISSUER:-}" ]
}
# Developer ID signature with the hardened runtime and a secure timestamp, both
# of which notarization requires. A failure stops the build: a half-signed
# release must never ship.
sign_mac() { # sign_mac <bundle-or-executable>
  codesign --force --timestamp --options runtime ${MAC_KEYCHAIN:+--keychain "$MAC_KEYCHAIN"} \
    --sign "$MAC_SIGN_IDENTITY" "$1"
}
# notarize <file>: submit to Apple and wait; anything but "Accepted" fails the
# build and prints Apple's log. notarytool's exit code alone is not trusted.
notarize() {
  nout="$TMP/notary-$(basename "$1").json"
  xcrun notarytool submit "$1" --key "$MAC_NOTARY_KEY_PATH" --key-id "$MAC_NOTARY_KEY_ID" \
    --issuer "$MAC_NOTARY_ISSUER" --wait --timeout 45m --output-format json >"$nout" || true
  nstatus="$(plutil -extract status raw -o - "$nout" 2>/dev/null || true)"
  nid="$(plutil -extract id raw -o - "$nout" 2>/dev/null || true)"
  if [ "$nstatus" != "Accepted" ]; then
    cat "$nout" >&2 || true
    if [ -n "$nid" ]; then
      xcrun notarytool log "$nid" --key "$MAC_NOTARY_KEY_PATH" --key-id "$MAC_NOTARY_KEY_ID" \
        --issuer "$MAC_NOTARY_ISSUER" >&2 || true
    fi
    die "notarization of $(basename "$1") ended '${nstatus:-no status}'"
  fi
  echo "  notarized $(basename "$1") ($nid)"
}
staple() { xcrun stapler staple "$1" && xcrun stapler validate "$1"; }

case "$OS" in
darwin)
  [ "$(uname -s)" = "Darwin" ] || die "the .app/.dmg build needs macOS (lipo, codesign, hdiutil)"
  # Refuse half-configured signing: a partial setup would quietly ship a build
  # that still warns.
  if ! mac_signing; then
    [ -z "${MAC_NOTARY_KEY_PATH:-}${MAC_NOTARY_KEY_ID:-}${MAC_NOTARY_ISSUER:-}${MAC_INSTALLER_IDENTITY:-}" ] ||
      die "MAC_NOTARY_* / MAC_INSTALLER_IDENTITY are set but MAC_SIGN_IDENTITY is not"
  fi
  if [ -n "${MAC_NOTARY_KEY_PATH:-}${MAC_NOTARY_KEY_ID:-}${MAC_NOTARY_ISSUER:-}" ] && ! mac_notarizing; then
    die "set all of MAC_NOTARY_KEY_PATH, MAC_NOTARY_KEY_ID and MAC_NOTARY_ISSUER, or none"
  fi
  if [ -n "${MAC_INSTALLER_IDENTITY:-}" ] && ! mac_notarizing; then
    die "MAC_INSTALLER_IDENTITY needs the MAC_NOTARY_* variables: an un-notarized .pkg still warns"
  fi
  TMP="$(mktemp -d)"
  trap 'rm -rf "$TMP"' EXIT

  build darwin amd64 "$TMP/setup-amd64"
  build darwin arm64 "$TMP/setup-arm64"
  lipo -create "$TMP/setup-amd64" "$TMP/setup-arm64" -output "$TMP/vegaduta-setup-universal"

  APP="$TMP/VegaDuta Setup.app"
  mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
  cp "$TMP/vegaduta-setup-universal" "$APP/Contents/MacOS/vegaduta-setup-bin"

  args='--dir "$HOME/VegaDuta" --tier personal'
  if [ -n "$PAYLOAD" ]; then
    [ -f "$PAYLOAD" ] || die "--payload $PAYLOAD not found"
    pname="$(basename "$PAYLOAD")"
    cp "$PAYLOAD" "$APP/Contents/Resources/$pname"
    (cd "$APP/Contents/Resources" && sha256 "$pname" > SHA256SUMS)
    args="--release-url \"file://\$HERE/../Resources/$pname\" $args"
    echo "  embedding payload $pname (offline install)"
  fi
  envs=
  [ -n "$WEB_PORT" ]  && envs="$envs WEB_HOST_PORT=$WEB_PORT"
  [ -n "$CORE_PORT" ] && envs="$envs CORE_HOST_PORT=$CORE_PORT"

  cat > "$APP/Contents/MacOS/launcher" <<EOF
#!/bin/bash
HERE="\$(cd "\$(dirname "\$0")" && pwd)"
# Finder launches apps with a minimal PATH; docker/brew live elsewhere.
export PATH="/usr/local/bin:/opt/homebrew/bin:/opt/homebrew/sbin:\$PATH"
${envs:+export$envs}
LOG="\$HOME/Library/Logs/vegaduta-setup.log"
mkdir -p "\$HOME/Library/Logs"
exec "\$HERE/vegaduta-setup-bin" $args >>"\$LOG" 2>&1
EOF
  chmod +x "$APP/Contents/MacOS/launcher"

  cat > "$APP/Contents/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleExecutable</key><string>launcher</string>
  <key>CFBundleName</key><string>VegaDūta Setup</string>
  <key>CFBundleIdentifier</key><string>dev.vegaduta.setup</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleVersion</key><string>$VER</string>
  <key>CFBundleShortVersionString</key><string>$VER</string>
</dict></plist>
EOF

  NOTARIZED=
  if mac_signing; then
    # Developer ID: sign the Go binary first, then the bundle (no --deep), then
    # notarize and staple the app so it also passes Gatekeeper offline once it is
    # dragged out of the dmg.
    sign_mac "$APP/Contents/MacOS/vegaduta-setup-bin"
    sign_mac "$APP"
    codesign --verify --strict --verbose=2 "$APP"
    if mac_notarizing; then
      ditto -c -k --keepParent "$APP" "$TMP/notarize-app.zip"
      notarize "$TMP/notarize-app.zip"
      staple "$APP"
      NOTARIZED=1
    fi
  else
    # Ad-hoc signature so the bundle is internally consistent. It is NOT a
    # Developer ID signature and nothing is notarized (that needs an Apple
    # Developer ID certificate), so Gatekeeper blocks a quarantined copy until
    # the person allows it in Privacy & Security - the README covers it.
    codesign --force --deep --sign - "$APP" 2>/dev/null || true
  fi

  STAGE="$TMP/dmg"
  mkdir -p "$STAGE"
  mv "$APP" "$STAGE/"
  readme_darwin "$STAGE/READ ME FIRST.txt" "$NOTARIZED"
  ln -s /Applications "$STAGE/Applications" 2>/dev/null || true
  DMG="$OUT/VegaDuta-Setup${VER:+-$VER}.dmg"
  rm -f "$DMG"
  hdiutil create -volname "VegaDūta Setup" -srcfolder "$STAGE" -ov -format UDZO "$DMG" >/dev/null
  if mac_signing; then
    codesign --force --timestamp ${MAC_KEYCHAIN:+--keychain "$MAC_KEYCHAIN"} --sign "$MAC_SIGN_IDENTITY" "$DMG"
    if mac_notarizing; then
      notarize "$DMG"
      staple "$DMG"
    fi
  fi
  echo "wrote $DMG ($(sha "$DMG"))"

  # A signed .pkg that installs the (already stapled) app into /Applications,
  # for managed fleets and people who prefer an installer to a drag. It is only
  # built when a Developer ID Installer identity is configured.
  if [ -n "${MAC_INSTALLER_IDENTITY:-}" ]; then
    PKGROOT="$TMP/pkgroot"
    mkdir -p "$PKGROOT"
    ditto "$STAGE/VegaDuta Setup.app" "$PKGROOT/VegaDuta Setup.app"
    # Not relocatable: a copy of the app elsewhere must not be silently replaced.
    pkgbuild --analyze --root "$PKGROOT" "$TMP/component.plist" >/dev/null
    # (--analyze omits the key on current macOS, so Add it when Set finds nothing.)
    /usr/libexec/PlistBuddy -c "Set :0:BundleIsRelocatable false" "$TMP/component.plist" 2>/dev/null ||
      /usr/libexec/PlistBuddy -c "Add :0:BundleIsRelocatable bool false" "$TMP/component.plist"
    pkgbuild --root "$PKGROOT" --component-plist "$TMP/component.plist" \
      --install-location /Applications --identifier dev.vegaduta.setup.pkg \
      --version "$VER" "$TMP/setup-component.pkg" >/dev/null
    PKG="$OUT/VegaDuta-Setup${VER:+-$VER}.pkg"
    rm -f "$PKG"
    productbuild --package "$TMP/setup-component.pkg" --sign "$MAC_INSTALLER_IDENTITY" \
      ${MAC_KEYCHAIN:+--keychain "$MAC_KEYCHAIN"} --timestamp "$PKG" >/dev/null
    notarize "$PKG"
    staple "$PKG"
    echo "wrote $PKG ($(sha "$PKG"))"
  fi
  ;;

windows-exe)
  # Just the exe, so a signing step can sign it before it is zipped.
  build windows amd64 "$OUT/vegaduta-setup.exe"
  echo "wrote $OUT/vegaduta-setup.exe ($(sha "$OUT/vegaduta-setup.exe"))"
  ;;

windows)
  [ -z "$EXE_SIGNED" ] || [ -n "$EXE" ] || die "--signed needs --exe: only a prebuilt exe can already be signed"
  TMP="$(mktemp -d)"
  trap 'rm -rf "$TMP"' EXIT
  if [ -n "$EXE" ]; then
    [ -f "$EXE" ] || die "--exe $EXE not found"
    cp "$EXE" "$TMP/vegaduta-setup.exe"
  else
    build windows amd64 "$TMP/vegaduta-setup.exe"
  fi
  readme_windows "$TMP/READ-ME-FIRST.txt" "$EXE_SIGNED"
  ZIP="$OUT/vegaduta-setup-windows-amd64.zip"
  rm -f "$ZIP"
  (cd "$TMP" && zip -q -j "$ZIP" vegaduta-setup.exe READ-ME-FIRST.txt)
  echo "wrote $ZIP ($(sha "$ZIP"))"
  ;;

linux)
  for arch in amd64 arm64; do
    TMP="$(mktemp -d)"
    trap 'rm -rf "$TMP"' EXIT
    name="vegaduta-setup-linux-$arch"
    build linux "$arch" "$TMP/$name"
    readme_linux "$TMP/README.txt"
    (cd "$TMP" && tar -czf "$OUT/$name.tar.gz" "$name" README.txt)
    rm -rf "$TMP"
    echo "wrote $OUT/$name.tar.gz ($(sha "$OUT/$name.tar.gz"))"
  done
  ;;

*) die "unknown target $OS (darwin|windows|linux)" ;;
esac
