"""Clone/fetch manager for git-sourced repos.

- Persistent clone per (tenant, repo) under the tenant's subtree: first
  call clones (partial clone, --filter=blob:none), later calls fetch+reset.
- A token can come from two places on the registry entry:
  - `entry.auth_token`: a direct, per-call credential (e.g. a tenant's own
    BYO PAT) supplied by the caller. Takes precedence when present.
  - `entry.auth_env`: process env, resolved via the NAME held in
    `auth_env`. Used only when `auth_token` is not set.
  Whichever wins, the token is injected per invocation with an ephemeral
  credential helper - never written to disk, never stored in the clone's
  config, never logged, never present in argv.
"""

from __future__ import annotations

import hashlib
import ipaddress
import os
import re
import shutil
import socket
import subprocess
import tempfile
from collections.abc import Callable
from dataclasses import dataclass
from urllib.parse import urlsplit

from .registry import RepoEntry, allowed_auth_env


class GitOpsError(RuntimeError):
    pass


class GitOpsValidationError(GitOpsError):
    """Caller-supplied input was rejected BEFORE any git/network/filesystem
    work (un-allowlisted auth_env name, local path escaping the tenant's
    subtree, missing git_url, ...). Mapped to HTTP 400 in main.py, unlike
    plain GitOpsError (a git operation that actually failed -> 500)."""


class RepoTooLargeError(GitOpsError):
    """A clone exceeded the per-repo size/file-count limit, or the tenant's
    disk quota. Mapped to HTTP 413 in main.py."""


def _env_int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, "") or default)
    except ValueError:
        return default


def _limits() -> tuple[int, int, int]:
    """(max repo bytes, max repo files, tenant quota bytes); 0 disables.

    The per-repo size/file limits are OFF unless
    CODE_INTEL_ENFORCE_REPO_LIMITS=true: repos already registered may be
    larger than the limits, and enforcing on their first upstream commit
    would freeze them (every fetch rejected). Operators opt in after
    checking existing repos fit (or raising CODE_INTEL_MAX_REPO_MB/FILES).
    """
    mb = 1024 * 1024
    enforce = os.environ.get("CODE_INTEL_ENFORCE_REPO_LIMITS", "").strip().lower() == "true"
    return (
        (_env_int("CODE_INTEL_MAX_REPO_MB", 500) * mb) if enforce else 0,
        _env_int("CODE_INTEL_MAX_REPO_FILES", 50000) if enforce else 0,
        _env_int("CODE_INTEL_TENANT_QUOTA_MB", 0) * mb,
    )


def _tree_usage(
    root: str, max_bytes: int = 0, max_files: int = 0, skip_git: bool = False
) -> tuple[int, int, bool]:
    """Walk `root` -> (bytes, files, exceeded). Stops early once a non-zero
    cap is passed so a hostile repo cannot make the check itself expensive.
    Symlinks are not followed."""
    total = files = 0
    stack = [root]
    while stack:
        try:
            with os.scandir(stack.pop()) as it:
                for e in it:
                    try:
                        if e.is_symlink():
                            continue
                        if e.is_dir(follow_symlinks=False):
                            if skip_git and e.name == ".git":
                                continue
                            stack.append(e.path)
                            continue
                        files += 1
                        total += e.stat(follow_symlinks=False).st_size
                    except OSError:
                        continue
                    if (max_bytes and total > max_bytes) or (max_files and files > max_files):
                        return total, files, True
        except OSError:
            continue
    return total, files, False


def _check_repo_size(checkout: str) -> None:
    max_bytes, max_files, _ = _limits()
    if not (max_bytes or max_files):
        return
    total, files, exceeded = _tree_usage(checkout, max_bytes, max_files, skip_git=True)
    if exceeded:
        raise RepoTooLargeError(
            f"repository exceeds the size limit "
            f"({max_bytes // (1024 * 1024)} MB / {max_files} files)"
        )


def _check_tenant_quota(tenant_dir: str) -> None:
    _, _, quota = _limits()
    if not quota or not os.path.isdir(tenant_dir):
        return
    _, _, exceeded = _tree_usage(tenant_dir, max_bytes=quota)
    if exceeded:
        raise RepoTooLargeError(
            f"tenant disk quota exceeded ({quota // (1024 * 1024)} MB); "
            "delete unused repos before adding more"
        )


def _within_data_root(path: str, data_root: str) -> bool:
    """True if `path` resolves to `data_root` itself or a descendant of it.

    Both sides are passed through os.path.realpath first, so symlinks that
    would otherwise point a caller-supplied local path outside the service's
    data root are resolved (and thus caught) rather than followed.
    """
    root = os.path.realpath(data_root)
    resolved = os.path.realpath(path)
    return resolved == root or resolved.startswith(root + os.sep)


_FS_CHMOD_SUPPORT: dict[str, bool] = {}


def _fs_supports_chmod(path: str) -> bool:
    """Probe whether `path`'s filesystem honors chmod(2). Result is cached
    per realpath - mounts don't change mid-process.

    Azure Files (SMB/CIFS) mounts - which back CODE_INTEL_DATA_DIR on the
    Azure Container Apps deployment - refuse chmod outright. Git's lockfile
    commit path chmods whenever it rewrites an existing config/ref/index
    file, so a `.git` directory on such a mount makes EVERY mutating git op
    die with "chmod on .../config.lock failed: Operation not permitted"
    (the 2026-09-27 prod breakage). On such filesystems git metadata must
    live elsewhere - see _gitdir_for.
    """
    key = os.path.realpath(path)
    cached = _FS_CHMOD_SUPPORT.get(key)
    if cached is not None:
        return cached
    os.makedirs(key, exist_ok=True)
    fd, probe = tempfile.mkstemp(dir=key, prefix=".ci-chmod-probe-")
    try:
        os.close(fd)
        try:
            os.chmod(probe, 0o600)
            ok = True
        except OSError:
            ok = False
    finally:
        try:
            os.unlink(probe)
        except OSError:
            pass
    _FS_CHMOD_SUPPORT[key] = ok
    return ok


def _gitdir_for(repo_dir: str) -> str:
    """Deterministic container-local git dir for a checkout whose worktree
    sits on a chmod-less filesystem (see _fs_supports_chmod). Clone uses
    `git clone --separate-git-dir`: the share keeps only the worktree plus a
    `.git` gitlink file, while every lockfile/config/ref/index write lands
    on local disk where chmod works.

    The location is deliberately NOT persistent - a replica restart wipes it
    while the share keeps the worktree and its gitlink. ensure_checkout
    detects the dead gitlink and re-clones on demand. CODE_INTEL_GITDIR_ROOT
    relocates the root (tests, operators); default is the system temp dir.
    """
    root = os.environ.get("CODE_INTEL_GITDIR_ROOT") or os.path.join(
        tempfile.gettempdir(), "code-intel-gitdirs"
    )
    digest = hashlib.sha256(os.path.realpath(repo_dir).encode("utf-8")).hexdigest()[:16]
    base = os.path.basename(os.path.normpath(repo_dir)) or "repo"
    return os.path.join(root, f"{base}-{digest}")


def _rmtree_retry_readonly(func: Callable[[str], object], path: str, _exc: object) -> None:
    """rmtree onerror hook: git pack files are read-only, which some
    filesystems (Windows, some NFS) refuse to unlink - clear the bit and
    retry. POSIX unlinks never consult the file's own mode, so on the
    Linux container this path is simply never reached."""
    os.chmod(path, 0o700)
    func(path)


def discard_gitdir(repo_dir: str) -> None:
    """Drop the container-local git dir a chmod-less-fs checkout used, if
    any. Called on repo/tenant deletion so stale clones don't accumulate in
    CODE_INTEL_GITDIR_ROOT on a long-lived replica. No-op for in-checkout
    clones (their metadata dies with the rmtree'd worktree)."""
    gitdir = _gitdir_for(repo_dir)
    # not ignore_errors+onerror together: ignore_errors replaces onerror on
    # py3.12+, so the read-only-retry would never run. Best-effort cleanup
    # regardless - a leftover gitdir must not fail the delete request.
    try:
        if os.path.isdir(gitdir):
            shutil.rmtree(gitdir, onerror=_rmtree_retry_readonly)
    except OSError:
        pass


def _existing_git_dir(checkout: str) -> str | None:
    """The checkout's live git dir, or None if it has no usable metadata.

    `.git` may be a directory (plain clone on a chmod-capable fs) or a
    gitlink FILE pointing at a --separate-git-dir target. A gitlink whose
    target is gone - e.g. the replica holding it restarted and wiped the
    container-local dir - counts as missing so the caller re-clones instead
    of fetching into nothing.
    """
    meta = os.path.join(checkout, ".git")
    if os.path.isdir(meta):
        return meta
    if not os.path.isfile(meta):
        return None
    try:
        with open(meta, encoding="utf-8") as f:
            target = f.read().strip()
    except OSError:
        return None
    if not target.startswith("gitdir:"):
        return None
    gitdir = target[len("gitdir:") :].strip()
    if not os.path.isabs(gitdir):
        gitdir = os.path.join(checkout, gitdir)
    return gitdir if os.path.isdir(gitdir) else None


_GIT_REF_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._/\-]{0,199}$")


def _allowed_schemes() -> frozenset[str]:
    """URL schemes a caller-supplied git_url may use. https only by default:
    file:// reads this container's own disk (another tenant's clones, mounted
    volumes), ssh/git/ext reach things no tenant should name. An operator (or
    the test-suite, which clones fixture repos over file://) widens this with
    CODE_INTEL_GIT_ALLOWED_SCHEMES."""
    raw = os.environ.get("CODE_INTEL_GIT_ALLOWED_SCHEMES", "https")
    return frozenset(x.strip().lower() for x in raw.split(",") if x.strip())


def _auth_env_url_prefixes() -> tuple[str, ...]:
    raw = os.environ.get("CODE_INTEL_AUTH_ENV_URL_PREFIXES", "")
    return tuple(x.strip() for x in raw.split(",") if x.strip())


def _is_public_address(ip: str) -> bool:
    addr: ipaddress.IPv4Address | ipaddress.IPv6Address = ipaddress.ip_address(ip)
    if isinstance(addr, ipaddress.IPv6Address) and addr.ipv4_mapped:
        addr = addr.ipv4_mapped
    return addr.is_global and not addr.is_multicast


_DEFAULT_PORTS = {"https": 443, "http": 80, "ssh": 22, "git": 9418}
# Schemes whose transport is libcurl, i.e. where http.curloptResolve pins
# the connection. ssh:// and git:// open their own sockets and re-resolve.
_CURL_SCHEMES = frozenset({"http", "https"})


@dataclass(frozen=True)
class PinnedHost:
    """The addresses validate_git_source vetted for a git_url's host.

    SVC-006: validating the name and then letting git resolve it AGAIN leaves
    a DNS-rebinding window (public record at check time, 169.254.169.254 a
    second later at connect time). For http(s) the vetted addresses are
    handed to git as `-c http.curloptResolve=<host>:<port>:<ip>[,<ip>...]`
    (CURLOPT_RESOLVE; git >= 2.37), so libcurl connects to exactly those
    addresses while Host header and TLS SNI/cert verification still use the
    real hostname."""

    scheme: str
    host: str
    port: int
    addresses: tuple[str, ...]

    def curl_resolve(self) -> str:
        addrs = ",".join(f"[{a}]" if ":" in a else a for a in self.addresses)
        return f"{self.host}:{self.port}:{addrs}"

    def git_config_args(self) -> list[str]:
        """`-c` args that pin this host for one git invocation (and, via
        GIT_CONFIG_PARAMETERS, every git child it spawns - e.g. the lazy
        blob fetches of a --filter=blob:none clone). Empty for non-curl
        schemes: ssh/git re-resolve on their own; that residual is covered
        only by https-only default schemes plus network egress policy."""
        if self.scheme not in _CURL_SCHEMES:
            return []
        return ["-c", f"http.curloptResolve={self.curl_resolve()}"]


def validate_git_source(git_url: str, git_ref: str | None) -> PinnedHost | None:
    """Reject a caller-supplied clone source BEFORE any git process runs.

    git_url is tenant input. Unchecked it is three holes at once (security
    audit 2026-09-18): `file:///data/tenants/<other>/...` reads another
    tenant's clone straight past the local-path confinement below; a URL at an
    internal host (cloud metadata, core, the VM) turns the clone into an SSRF
    probe; and a value starting with `-` is parsed by git as an option.

    Returns the vetted addresses as a PinnedHost so the caller can pin git's
    connection to them (no second resolution - see PinnedHost), or None when
    there is nothing to pin (file:// or an IP-literal host, which git never
    resolves).
    """
    if git_url.startswith("-") or any(c in git_url for c in "\r\n\0"):
        raise GitOpsValidationError("git_url is not a valid URL")
    if git_ref is not None and not _GIT_REF_RE.fullmatch(git_ref):
        raise GitOpsValidationError("git_ref is not a valid branch or tag name")
    parts = urlsplit(git_url)
    scheme = parts.scheme.lower()
    if scheme not in _allowed_schemes():
        raise GitOpsValidationError(
            f"git_url scheme '{scheme or '(none)'}' is not allowed; use https"
        )
    if scheme == "file":
        return None
    if parts.username or parts.password:
        raise GitOpsValidationError("git_url must not embed credentials; use auth_token")
    host = parts.hostname
    if not host:
        raise GitOpsValidationError("git_url has no host")
    try:
        port = parts.port or _DEFAULT_PORTS.get(scheme, 443)
    except ValueError:  # non-numeric / out-of-range port
        raise GitOpsValidationError("git_url has an invalid port")  # noqa: B904
    try:
        infos = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    except (socket.gaierror, UnicodeError, ValueError):
        raise GitOpsValidationError(f"git_url host does not resolve: {host}")  # noqa: B904
    addresses = [str(i[4][0]) for i in infos]
    # EVERY address must be public: a name with one public and one private
    # record would otherwise pass here and connect to the private one.
    if not addresses or not all(_is_public_address(a) for a in addresses):
        raise GitOpsValidationError("git_url resolves to a private or internal address")
    try:
        ipaddress.ip_address(host)
        return None  # IP literal: git connects to it as-is, nothing to rebind
    except ValueError:
        pass
    return PinnedHost(
        scheme=scheme, host=host.lower(), port=port, addresses=tuple(dict.fromkeys(addresses))
    )


def _run(
    args: list[str], cwd: str | None, timeout: int, token: str | None, pin: PinnedHost | None = None
) -> str:
    env = dict(os.environ)
    # Never let git prompt, and restrict transports at the git layer too, so a
    # redirect or a submodule cannot move the fetch onto ext::/ssh/file even
    # though the URL itself passed validate_git_source.
    env["GIT_TERMINAL_PROMPT"] = "0"
    # core.symlinks=false (SEC-016): a committed symlink is checked out as a
    # plain file holding the link text, so nothing downstream (indexer,
    # workspaces) can be steered at another tenant's files. Persisted into
    # new clones too (see ensure_checkout) for git commands run elsewhere.
    cmd = [
        "git",
        "-c",
        "protocol.allow=never",
        "-c",
        "http.followRedirects=false",
        "-c",
        "core.symlinks=false",
    ]
    if cwd:
        # Mounts like Azure Files report every file as root-owned while the
        # container runs as `codeintel` - git then refuses to touch the
        # checkout ("detected dubious ownership"). Command-line -c IS
        # protected config scope, so whitelisting exactly the path we are
        # operating on works; the repo's own config files can never widen it.
        cmd += ["-c", f"safe.directory={os.path.realpath(cwd)}"]
    for scheme in sorted(_allowed_schemes()):
        cmd += ["-c", f"protocol.{scheme}.allow=always"]
    if pin is not None:
        # Connect to exactly the addresses validate_git_source vetted - no
        # second DNS lookup (SVC-006 DNS-rebinding). Redirects stay off
        # above, so the pinned host is the only host this run can reach.
        cmd += pin.git_config_args()
    if token:
        # Ephemeral credential helper: reads the token from env at call
        # time. Nothing lands in .git/config or argv (argv shows the
        # helper script, not the secret).
        env["CODE_INTEL_GIT_TOKEN"] = token
        helper = '!f() { echo username=x-access-token; echo "password=$CODE_INTEL_GIT_TOKEN"; }; f'
        cmd += ["-c", f"credential.helper={helper}"]
    cmd += args
    result = subprocess.run(cmd, cwd=cwd, env=env, capture_output=True, text=True, timeout=timeout)
    if result.returncode != 0:
        # stderr may echo the URL; tokens never appear (helper-based auth).
        raise GitOpsError(f"git {' '.join(args[:2])} failed: {result.stderr.strip()[:500]}")
    return result.stdout


def _rev(checkout: str, rev: str, timeout: int) -> str | None:
    try:
        return (
            _run(
                ["rev-parse", "--verify", "-q", rev + "^{commit}"],
                cwd=checkout,
                timeout=timeout,
                token=None,
            ).strip()
            or None
        )
    except GitOpsError:
        return None


# Commits (per checkout path) already rejected as too large. Remembered so a
# stuck repo does not fetch+reset+rollback again on every call; in memory
# only, so a restart re-evaluates once (a new upstream commit has a new sha).
_REJECTED: dict[str, str] = {}


def _too_large() -> RepoTooLargeError:
    max_bytes, max_files, _ = _limits()
    return RepoTooLargeError(
        f"repository exceeds the size limit ({max_bytes // (1024 * 1024)} MB / {max_files} files)"
    )


def _update_checkout(
    checkout: str, target: str, timeout: int, pin: PinnedHost | None = None
) -> None:
    """Move the existing checkout to `target` (already fetched).

    The size limits are only evaluated when the fetch actually moved the tree
    (so plain search/status calls pay two rev-parse calls, no tree walk).
    The file-count limit is checked BEFORE `reset --hard` from the object
    database (no blob download); the byte limit needs the files, so it runs
    after the reset and, on a breach, the checkout is reset back to the
    previous commit so an oversized tree never stays in the shared checkout.
    """
    old = _rev(checkout, "HEAD", timeout)
    new = _rev(checkout, target, timeout)
    if old is not None and old == new:
        return  # nothing changed -> nothing to enforce
    max_bytes, max_files, _ = _limits()
    if not (max_bytes or max_files):
        _run(["reset", "--hard", target], cwd=checkout, timeout=timeout, token=None, pin=pin)
        return
    key = os.path.realpath(checkout)
    if new and _REJECTED.get(key) == new:
        raise _too_large()  # known-oversized commit: leave the tree alone
    if max_files and new:
        n = _run(
            ["ls-tree", "-r", "--name-only", new], cwd=checkout, timeout=timeout, token=None
        ).count("\n")
        if n > max_files:
            _REJECTED[key] = new
            raise _too_large()
    _run(["reset", "--hard", target], cwd=checkout, timeout=timeout, token=None, pin=pin)
    try:
        _check_repo_size(checkout)
    except RepoTooLargeError:
        if new:
            _REJECTED[key] = new
        if old is not None:
            try:
                _run(["reset", "--hard", old], cwd=checkout, timeout=timeout, token=None, pin=pin)
            except GitOpsError:
                pass
        raise


def ensure_checkout(
    entry: RepoEntry, repo_dir: str, timeout: int, data_root: str | None = None
) -> str:
    """Return the working-tree path for this repo, cloning/updating as
    needed. Local-source repos are returned as-is (must exist AND be confined
    to `data_root` when it is supplied)."""
    if entry.source_type == "local":
        if not entry.path or not os.path.isdir(entry.path):
            raise GitOpsValidationError(f"local path does not exist: {entry.path}")
        # Confine caller-supplied local paths to the confinement root the
        # caller passes (service.py passes the REQUESTING TENANT'S own
        # subtree, not the shared data root) so a tenant cannot register
        # path=/proc/self, /etc, /app or another tenant's clones/indexes and
        # have their contents indexed/searched. Operator-provisioned paths
        # must live under the tenant's own subtree to resolve.
        if data_root is not None and not _within_data_root(entry.path, data_root):
            raise GitOpsValidationError(
                f"local path escapes the tenant's data subtree: {entry.path}"
            )
        return entry.path

    if not entry.git_url:
        raise GitOpsValidationError("git source requires git_url")
    pin = validate_git_source(entry.git_url, entry.git_ref)
    # Precedence: a direct per-call auth_token (e.g. tenant BYO PAT) always
    # wins over auth_env when both are somehow set - it's the more specific,
    # more freshly-supplied credential. auth_env is only resolved as a
    # fallback when no direct token was provided.
    token: str | None
    if entry.auth_token:
        token = entry.auth_token
    elif entry.auth_env:
        # auth_env is a caller-supplied env-var NAME. Reject any name that is
        # not on the operator allowlist BEFORE reading the environment, so a
        # caller can never resolve an unrelated process secret (e.g.
        # CODE_INTEL_INTERNAL_TOKEN) and have it injected as the git password
        # toward their own git_url.
        if entry.auth_env not in allowed_auth_env():
            raise GitOpsValidationError(
                f"auth_env '{entry.auth_env}' is not an allowed credential env var name"
            )
        # The name allowlist alone is not enough: every tenant can name the
        # built-in CODE_INTEL_GIT_TOKEN next to a git_url THEY control, and the
        # credential helper would hand the operator's token to that remote.
        # An operator credential therefore only ever travels to URL prefixes
        # the operator listed (fail closed: none listed = auth_env unusable).
        if not entry.git_url.startswith(_auth_env_url_prefixes() or ("\0",)):
            raise GitOpsValidationError(
                "auth_env credentials are only sent to operator-approved "
                "repositories (CODE_INTEL_AUTH_ENV_URL_PREFIXES); supply "
                "auth_token for your own repository"
            )
        token = os.environ.get(entry.auth_env)
        if not token:
            raise GitOpsValidationError(
                f"auth_env '{entry.auth_env}' is not set in the service environment"
            )
    else:
        token = None

    checkout = os.path.join(repo_dir, "checkout")
    if _existing_git_dir(checkout) is not None:
        # Fetch from the URL that was just validated and pinned, not whatever
        # an older registration left in remote.origin.url - otherwise the pin
        # (keyed on the validated host) would not apply to the connection.
        _run(
            ["remote", "set-url", "origin", entry.git_url],
            cwd=checkout,
            timeout=timeout,
            token=None,
        )
        _run(["fetch", "--prune", "origin"], cwd=checkout, timeout=timeout, token=token, pin=pin)
        ref = entry.git_ref or "HEAD"
        target = f"origin/{ref}" if entry.git_ref else "origin/HEAD"
        # reset --hard checks out the new tree; in a --filter=blob:none clone
        # that lazily fetches missing blobs from the remote, so it is a
        # network operation too and gets the same pin.
        _update_checkout(checkout, target, timeout, pin)
        _persist_pin(checkout, pin, timeout)
        return checkout

    # No usable git metadata: fresh repo, an interrupted clone's leftovers,
    # or a separate-git-dir checkout whose container-local gitdir died with
    # its replica. Whatever is there cannot be fetched into - wipe and clone.
    if os.path.isdir(checkout):
        shutil.rmtree(checkout, onerror=_rmtree_retry_readonly)
    # Best-effort tenant disk quota before spending bandwidth on a clone
    # (data_root = the tenant's subtree when called from service.py).
    if data_root is not None:
        _check_tenant_quota(data_root)
    args = ["clone", "--filter=blob:none", "--config", "core.symlinks=false"]
    if not _fs_supports_chmod(repo_dir):
        gitdir = _gitdir_for(repo_dir)
        os.makedirs(os.path.dirname(gitdir), exist_ok=True)
        args += ["--separate-git-dir", gitdir]
    if entry.git_ref:
        args += ["--branch", entry.git_ref]
    args += ["--", entry.git_url, checkout]
    _run(args, cwd=None, timeout=timeout, token=token, pin=pin)
    try:
        _check_repo_size(checkout)
    except RepoTooLargeError:
        # Do not leave an oversized partial clone eating the tenant's disk.
        try:
            shutil.rmtree(checkout, onerror=_rmtree_retry_readonly)
        except OSError:
            pass
        discard_gitdir(repo_dir)
        raise
    _persist_pin(checkout, pin, timeout)
    return checkout


def _persist_pin(checkout: str, pin: PinnedHost | None, timeout: int) -> None:
    """Record the vetted addresses in the checkout's own .git/config.

    A --filter=blob:none clone keeps fetching blobs lazily on LATER local
    git commands this module doesn't run (executor's `worktree add`, the
    indexer's diff/show in codebase_rag/gitstate.py). Those inherit repo
    config, so they connect to the last-validated addresses instead of
    re-resolving the name. Refreshed on every validated clone/fetch; only
    this service writes .git/config (a repo's tracked content can't).
    """
    if pin is None or not pin.git_config_args():
        return
    _run(
        ["config", "--local", "--replace-all", "http.curloptResolve", pin.curl_resolve()],
        cwd=checkout,
        timeout=timeout,
        token=None,
    )
