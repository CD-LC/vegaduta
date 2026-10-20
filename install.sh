#!/bin/sh
# =============================================================================
# VegaDūta installer (POSIX sh) — Linux, macOS, WSL.
#
# Everything runs inside main(), called on the LAST line: a truncated
# `curl | sh` download defines a partial function and executes nothing.
# See usage() below (or ./install.sh --help) for options.
# =============================================================================

# The release this installer belongs to. The release workflow replaces the
# placeholder in the copy inside each release tarball (and in any install.sh it
# publishes); an unreplaced placeholder (install.sh fetched from the main
# branch) resolves the latest published release and prints the version picked.
VEGADUTA_INSTALLER_VERSION="__VEGADUTA_VERSION__"

usage() {
  cat <<'EOF'
VegaDūta installer (Linux, macOS, WSL)

  curl -fsSL https://raw.githubusercontent.com/CD-LC/vegaduta/main/install.sh | sh
  curl -fsSL .../install.sh | sh -s -- --full --domain example.com
  ./install.sh [--lite|--full|personal] [--profile NAME]... [--domain D] [--build] [--no-start] [--version X.Y.Z]

Inside a checkout it uses that checkout. Otherwise it downloads the release
tarball into $VEGADUTA_HOME (default ./vegaduta), verifies it against the
release's SHA256SUMS (and, when cosign is installed, the keyless Sigstore
signature of SHA256SUMS), then runs scripts/vegaduta:
generate secrets -> doctor -> up (health-gated) -> URLs.

`personal` (one person, one laptop) starts with private web search and the MCP
connector runner on, so a fresh install can search the web and use fetch and
time with nothing to configure. Add --no-search and/or --no-tools to leave
either out; the choice is remembered (docs/personal-mode.md).

Environment:
  VEGADUTA_VERSION             release to install (default: the release this
                               installer came with, else the latest release)
  VEGADUTA_HOME                install directory when downloading (default: ./vegaduta)
  VEGADUTA_REPO                GitHub owner/repo (default: CD-LC/vegaduta)
  VEGADUTA_REQUIRE_SIGNATURE=1 fail unless cosign verifies SHA256SUMS
  VEGADUTA_RELEASE_URL         install from this tarball URL instead; needs
  VEGADUTA_RELEASE_SHA256      its expected SHA-256 (hex)

Windows (native): download the release zip and run
  powershell -ExecutionPolicy Bypass -File scripts\vegaduta.ps1 up
EOF
}

say() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mWARN:\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

# fetch URL FILE
fetch() {
  if have curl; then curl -fsSL --proto '=https' --tlsv1.2 --retry 3 "$1" -o "$2"
  elif have wget; then wget -q --https-only -O "$2" "$1"
  else die "curl or wget is required"; fi
}

# latest_version -> X.Y.Z of the newest published (non-pre) release
latest_version() {
  _url="https://github.com/$REPO/releases/latest"
  if have curl; then
    _eff="$(curl -fsSLI --proto '=https' -o /dev/null -w '%{url_effective}' "$_url")" || return 1
  else
    _eff="$(wget -q --https-only --max-redirect=5 -S --spider "$_url" 2>&1 | awk '/^ *[Ll]ocation: /{l=$2} END{print l}')" || return 1
  fi
  _tag="${_eff##*/}"
  case "$_tag" in
    v[0-9]*) printf '%s\n' "${_tag#v}" ;;
    *) return 1 ;;
  esac
}

sha256_of() {
  if have sha256sum; then sha256sum "$1" | awk '{print $1}'
  elif have shasum; then shasum -a 256 "$1" | awk '{print $1}'
  elif have openssl; then openssl dgst -sha256 "$1" | awk '{print $NF}'
  else die "sha256sum, shasum or openssl is required to verify the download"; fi
}

# verify_signature SUMS_FILE DIR -> cosign keyless check of the checksums file.
# The published asset is SHA256SUMS on current releases; early releases named
# it SHA256SUMS.txt - SUMS_NAME (set by download_release) tracks whichever was
# fetched, and the signature bundle beside it carries the same base name.
verify_signature() {
  if ! have cosign; then
    [ "${VEGADUTA_REQUIRE_SIGNATURE:-0}" = 1 ] && die "VEGADUTA_REQUIRE_SIGNATURE=1 but cosign is not installed (https://docs.sigstore.dev/cosign/system_config/installation/)"
    say "cosign not installed: checked SHA-256 only (install cosign to also verify the release signature)"
    return 0
  fi
  if ! fetch "$BASE/$SUMS_NAME.sigstore.json" "$2/SHA256SUMS.sigstore.json" 2>/dev/null \
     && ! fetch "$BASE/SHA256SUMS.sigstore.json" "$2/SHA256SUMS.sigstore.json" 2>/dev/null; then
    [ "${VEGADUTA_REQUIRE_SIGNATURE:-0}" = 1 ] && die "release v$VERSION has no SHA256SUMS.sigstore.json signature bundle"
    warn "release v$VERSION publishes no signature bundle; checked SHA-256 only"
    return 0
  fi
  cosign verify-blob "$1" \
    --bundle "$2/SHA256SUMS.sigstore.json" \
    --certificate-identity-regexp "^https://github\\.com/$REPO/\\.github/workflows/release\\.yml@refs/tags/v" \
    --certificate-oidc-issuer https://token.actions.githubusercontent.com >/dev/null 2>&1 \
    || die "cosign could NOT verify SHA256SUMS for v$VERSION - do not install this download"
  say "Signature of SHA256SUMS verified (Sigstore, release workflow of $REPO)"
}

download_release() {
  have tar || die "tar is required"
  mkdir -p "$HOME_DIR"
  _tmp="$(mktemp -d "${TMPDIR:-/tmp}/vegaduta-install.XXXXXX")" || die "mktemp failed"
  trap 'rm -rf "$_tmp"' EXIT INT TERM
  _tgz="$_tmp/vegaduta.tar.gz"
  if [ -n "${VEGADUTA_RELEASE_URL:-}" ]; then
    [ -n "${VEGADUTA_RELEASE_SHA256:-}" ] || die "VEGADUTA_RELEASE_URL needs VEGADUTA_RELEASE_SHA256 (the tarball's expected SHA-256)"
    say "Downloading $VEGADUTA_RELEASE_URL"
    fetch "$VEGADUTA_RELEASE_URL" "$_tgz" || die "download failed: $VEGADUTA_RELEASE_URL"
    _want="$VEGADUTA_RELEASE_SHA256"
  else
    BASE="https://github.com/$REPO/releases/download/v$VERSION"
    _name="vegaduta-$VERSION.tar.gz"
    say "Downloading VegaDūta v$VERSION ($BASE/$_name)"
    fetch "$BASE/$_name" "$_tgz" || die "download failed: $BASE/$_name"
    # Published as SHA256SUMS on current releases, SHA256SUMS.txt on the first
    # ones - accept either; keep the name for the signature bundle beside it.
    if fetch "$BASE/SHA256SUMS" "$_tmp/SHA256SUMS" 2>/dev/null; then
      SUMS_NAME=SHA256SUMS
    elif fetch "$BASE/SHA256SUMS.txt" "$_tmp/SHA256SUMS" 2>/dev/null; then
      SUMS_NAME=SHA256SUMS.txt
    else
      die "download failed: $BASE/SHA256SUMS (refusing to install an unverified tarball)"
    fi
    verify_signature "$_tmp/SHA256SUMS" "$_tmp"
    # Lines are "<sha256>  <name>" or "<sha256>  ./<name>" (sha256sum format).
    _want="$(awk -v n="$_name" '$2==n || $2=="./"n || $2=="*"n {print $1; exit}' "$_tmp/SHA256SUMS")"
    [ -n "$_want" ] || die "SHA256SUMS has no entry for $_name"
  fi
  _got="$(sha256_of "$_tgz")"
  [ "$_got" = "$_want" ] || die "checksum mismatch for the release tarball (expected $_want, got $_got) - not installing"
  say "SHA-256 verified"
  # Release tarballs contain a single top-level directory.
  tar -xzf "$_tgz" -C "$HOME_DIR" --strip-components=1 --no-same-owner || die "could not unpack the release tarball"
  # main() ends with `exec`, which skips EXIT traps: clean up now.
  rm -rf "$_tmp"; trap - EXIT INT TERM
  ROOT="$(cd "$HOME_DIR" && pwd)"
  # Pin the image tag to the release just installed (older tarballs shipped
  # an unstamped .env.example).
  _ex="$ROOT/deploy/compose/.env.example"
  if [ -f "$_ex" ] && grep -q '^VEGADUTA_VERSION=$' "$_ex"; then
    awk -v v="$VERSION" '/^VEGADUTA_VERSION=$/{print "VEGADUTA_VERSION=" v; next} {print}' "$_ex" > "$_ex.tmp" && mv "$_ex.tmp" "$_ex"
  fi
  say "Unpacked v$VERSION into $ROOT"
}

main() {
  set -eu
  # pipefail where the shell has it (bash, zsh, dash >= 0.5.12, busybox ash).
  # shellcheck disable=SC3040
  if (set -o pipefail) 2>/dev/null; then set -o pipefail; fi
  # No `umask 077` here: the unpacked configs (realm JSON, Caddyfile,
  # Prometheus rules...) are bind-mounted into containers that run as other
  # uids and must stay world-readable. scripts/vegaduta sets umask 077 itself
  # for .env and backups; the download temp dir is mktemp -d (0700).

  REPO="${VEGADUTA_REPO:-CD-LC/vegaduta}"
  VERSION="${VEGADUTA_VERSION:-}"
  HOME_DIR="${VEGADUTA_HOME:-./vegaduta}"
  START=1

  # Split our own flags from the ones passed through to `vegaduta up`.
  PASS=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --no-start) START=0 ;;
      --version) shift; VERSION="${1:?--version needs a value}" ;;
      --version=*) VERSION="${1#*=}" ;;
      -h|--help) usage; exit 0 ;;
      *) PASS="$PASS $1" ;;
    esac
    shift
  done
  VERSION="${VERSION#v}"

  say "Checking prerequisites"
  have docker || die "Docker is required: https://docs.docker.com/engine/install/ (or Docker Desktop)"
  docker compose version >/dev/null 2>&1 || die "Docker Compose v2 plugin is required ('docker compose')"
  docker info >/dev/null 2>&1 || die "Docker daemon is not running (or this user may not access it)"
  have bash || die "bash is required to run scripts/vegaduta"

  # 1. Locate or fetch the distribution --------------------------------------
  SELF_DIR="$(dirname "$0")"
  if [ -f "scripts/vegaduta" ] && [ -d "deploy/compose" ]; then
    ROOT="$(pwd)"
    say "Using this checkout: $ROOT"
  elif [ -f "$SELF_DIR/scripts/vegaduta" ] && [ -d "$SELF_DIR/deploy/compose" ]; then
    ROOT="$(cd "$SELF_DIR" && pwd)"
    say "Using checkout: $ROOT"
  else
    if [ -z "${VEGADUTA_RELEASE_URL:-}" ]; then
      if [ -z "$VERSION" ]; then
        case "$VEGADUTA_INSTALLER_VERSION" in
          __*) VERSION="$(latest_version)" || die "could not resolve the latest release of $REPO; pass --version X.Y.Z" ;;
          *) VERSION="$VEGADUTA_INSTALLER_VERSION" ;;
        esac
      fi
      case "$VERSION" in
        [0-9]*.[0-9]*.[0-9]*) ;;
        *) die "invalid version '$VERSION' (expected X.Y.Z)" ;;
      esac
      say "Release: v$VERSION"
    else
      VERSION="${VERSION:-custom}"
    fi
    download_release
  fi

  cd "$ROOT"
  chmod +x scripts/vegaduta 2>/dev/null || true

  # 2. Secrets (never overwrites existing values) ----------------------------
  DOMAIN=""
  # shellcheck disable=SC2086  # PASS holds simple space-separated flags
  set -- $PASS
  while [ $# -gt 0 ]; do
    case "$1" in
      --domain) DOMAIN="${2:-}"; [ $# -gt 1 ] && shift ;;
      --domain=*) DOMAIN="${1#*=}" ;;
    esac
    shift
  done
  if [ -n "$DOMAIN" ]; then bash scripts/vegaduta secrets --domain "$DOMAIN"
  else bash scripts/vegaduta secrets; fi

  if [ "$START" = 0 ]; then
    case " $PASS " in
      *" personal "*) say "Installed. Start with: cd $ROOT && scripts/vegaduta personal" ;;
      *) say "Installed. Start with: cd $ROOT && scripts/vegaduta up --lite   (or --full)" ;;
    esac
    exit 0
  fi

  # 3. Start (lite by default; full when a domain is given) ------------------
  # `personal` is its own subcommand (one person, one laptop), not an `up` flag.
  case " $PASS " in
    *" personal "*)
      # POSIX sh (dash on Debian/Ubuntu) has no ${var/pat/rep}: drop the word with sed.
      PASS="$(printf ' %s ' "$PASS" | sed 's/ personal / /')"
      say "Starting: scripts/vegaduta personal$PASS"
      # shellcheck disable=SC2086
      exec bash scripts/vegaduta personal $PASS ;;
  esac
  case " $PASS " in
    *" --lite "*|*" --full "*) ;;
    *) if [ -n "$DOMAIN" ]; then PASS="--full$PASS"; else PASS="--lite$PASS"; fi ;;
  esac
  say "Starting: scripts/vegaduta up $PASS"
  # shellcheck disable=SC2086
  exec bash scripts/vegaduta up $PASS
}

main "$@"
