#!/bin/sh
# package.sh - build the downloadable Setup artifacts in dist/.
#
#   ./package.sh darwin  [--payload vegaduta-x.y.z.tar.gz] [--version X.Y.Z]
#                        [--web-port N] [--core-port N]
#   ./package.sh windows [--version X.Y.Z]
#   ./package.sh linux   [--version X.Y.Z]
#
# darwin produces a universal "VegaDuta Setup.app" inside a .dmg (needs macOS:
# lipo, codesign, hdiutil). --payload embeds a release tarball so the app
# installs offline with no published release. --web-port/--core-port stamp the
# launcher's WEB_HOST_PORT/CORE_HOST_PORT for machines whose defaults are taken.
# windows produces vegaduta-setup-windows-amd64.zip (the exe is the whole
# installer; the zip only adds a first-run README). linux produces per-arch
# tar.gz. All artifacts are unsigned (the app is only ad-hoc signed, not
# notarized): Gatekeeper and SmartScreen warn once; every dmg/zip carries a
# README that says what to click.
set -eu

cd "$(dirname "$0")"
OUT=dist
VER=dev
PAYLOAD=
WEB_PORT=
CORE_PORT=

die() { echo "package.sh: $*" >&2; exit 1; }

OS=
while [ $# -gt 0 ]; do
  case "$1" in
    --payload)   PAYLOAD="$2"; shift 2 ;;
    --version)   VER="$2"; shift 2 ;;
    --web-port)  WEB_PORT="$2"; shift 2 ;;
    --core-port) CORE_PORT="$2"; shift 2 ;;
    --out)       OUT="$2"; shift 2 ;;
    -*)          die "unknown flag $1" ;;
    *)           [ -z "$OS" ] || die "unexpected argument $1"; OS="$1"; shift ;;
  esac
done
[ -n "$OS" ] || die "usage: package.sh darwin|windows|linux [flags]"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)" # absolute: subshells cd elsewhere

LDFLAGS="-s -w -X main.buildVersion=$VER"
build() { # build <goos> <goarch> <out>
  echo "  building $1/$2 -> $3"
  CGO_ENABLED=0 GOOS="$1" GOARCH="$2" go build -trimpath -ldflags "$LDFLAGS" -o "$3" .
}

# First-run READMEs. Unsigned builds warn once; telling the user what to click
# is the difference between a scary dialog and a working install.
readme_darwin() {
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
readme_windows() {
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

case "$OS" in
darwin)
  [ "$(uname -s)" = "Darwin" ] || die "the .app/.dmg build needs macOS (lipo, codesign, hdiutil)"
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

  # Ad-hoc signature so the bundle is internally consistent. It is NOT a
  # Developer ID signature and nothing is notarized (that needs an Apple
  # Developer ID certificate), so Gatekeeper blocks a quarantined copy until
  # the person allows it in Privacy & Security - the README covers it.
  codesign --force --deep --sign - "$APP" 2>/dev/null || true

  STAGE="$TMP/dmg"
  mkdir -p "$STAGE"
  mv "$APP" "$STAGE/"
  readme_darwin "$STAGE/READ ME FIRST.txt"
  ln -s /Applications "$STAGE/Applications" 2>/dev/null || true
  DMG="$OUT/VegaDuta-Setup${VER:+-$VER}.dmg"
  rm -f "$DMG"
  hdiutil create -volname "VegaDūta Setup" -srcfolder "$STAGE" -ov -format UDZO "$DMG" >/dev/null
  echo "wrote $DMG ($(sha "$DMG"))"
  ;;

windows)
  TMP="$(mktemp -d)"
  trap 'rm -rf "$TMP"' EXIT
  build windows amd64 "$TMP/vegaduta-setup.exe"
  readme_windows "$TMP/READ-ME-FIRST.txt"
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
