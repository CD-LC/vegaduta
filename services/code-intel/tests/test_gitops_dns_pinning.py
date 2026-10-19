"""SVC-006 (gitops half): the clone/fetch connects to exactly the addresses
validate_git_source vetted - no second DNS resolution (DNS-rebinding window).

The resolver is mocked (socket.getaddrinfo patched inside app.gitops) and
git itself is replaced by a recorder, so these checks need no network and
no real remote. One extra check drives the REAL git binary against a local
TCP listener to prove git actually honours http.curloptResolve (i.e. that
the pin is not just a string we put in argv); it is skipped when git is
missing or older than 2.37.

Run: python -m tests.test_gitops_dns_pinning   (from the code-intel directory)
"""

from __future__ import annotations

import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app import gitops
from app.gitops import GitOpsValidationError, PinnedHost
from app.registry import RepoEntry

PASS = 0
FAIL = 0
SKIP = 0

PUBLIC_V4 = "140.82.112.3"
PUBLIC_V4_B = "140.82.112.4"
PUBLIC_V6 = "2606:50c0:8000::153"


def check(name: str, cond: bool, extra: str = "") -> None:
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ok    {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name} {extra}")


def skip(name: str, why: str) -> None:
    global SKIP
    SKIP += 1
    print(f"  skip  {name} ({why})")


def _resolver(*addrs: str):
    """A getaddrinfo stand-in returning `addrs` for any name."""
    def fake(host, port, *a, **k):
        out = []
        for ip in addrs:
            fam = socket.AF_INET6 if ":" in ip else socket.AF_INET
            sa = (ip, port, 0, 0) if fam == socket.AF_INET6 else (ip, port)
            out.append((fam, socket.SOCK_STREAM, 6, "", sa))
        return out
    return fake


class _GitRecorder:
    """Replaces gitops.subprocess.run: records argv, always succeeds."""

    def __init__(self) -> None:
        self.calls: list[list[str]] = []

    def __call__(self, cmd, *a, **k):
        self.calls.append(list(cmd))
        return subprocess.CompletedProcess(cmd, 0, stdout="", stderr="")

    def by_verb(self, verb: str) -> list[list[str]]:
        out = []
        for c in self.calls:
            # first positional after all `-c k=v` pairs
            i = 1
            while i < len(c) and c[i] == "-c":
                i += 2
            if i < len(c) and c[i] == verb:
                out.append(c)
        return out


def _resolve_opts(cmd: list[str]) -> list[str]:
    return [cmd[i + 1] for i in range(len(cmd) - 1)
            if cmd[i] == "-c" and cmd[i + 1].lower().startswith("http.curloptresolve=")]


def test_validation_returns_pin() -> None:
    print("== validate_git_source returns the vetted addresses ==")
    with mock.patch.object(gitops.socket, "getaddrinfo",
                           _resolver(PUBLIC_V4, PUBLIC_V4_B, PUBLIC_V4)):
        pin = gitops.validate_git_source("https://GitHub.com/o/r.git", None)
    check("pin returned for an https host", isinstance(pin, PinnedHost))
    assert pin is not None
    check("pin host lower-cased, default port 443",
          pin.host == "github.com" and pin.port == 443)
    check("duplicate records collapsed, order kept",
          pin.addresses == (PUBLIC_V4, PUBLIC_V4_B))
    check("curl resolve string",
          pin.curl_resolve() == f"github.com:443:{PUBLIC_V4},{PUBLIC_V4_B}")

    with mock.patch.object(gitops.socket, "getaddrinfo", _resolver(PUBLIC_V6)):
        pin6 = gitops.validate_git_source("https://example.org:8443/r.git", None)
    check("IPv6 address bracketed, explicit port kept",
          pin6 is not None
          and pin6.curl_resolve() == f"example.org:8443:[{PUBLIC_V6}]")

    with mock.patch.dict(os.environ, {"CODE_INTEL_GIT_ALLOWED_SCHEMES": "https,http,ssh"}):
        with mock.patch.object(gitops.socket, "getaddrinfo", _resolver(PUBLIC_V4)):
            pin_http = gitops.validate_git_source("http://example.org/r.git", None)
            pin_ssh = gitops.validate_git_source("ssh://example.org/r.git", None)
    check("http:// default port is 80 (was wrongly resolved as 443)",
          pin_http is not None and pin_http.port == 80)
    check("ssh:// gets no curl pin (documented residual: egress policy)",
          pin_ssh is not None and pin_ssh.git_config_args() == [])

    with mock.patch.object(gitops.socket, "getaddrinfo", _resolver(PUBLIC_V4)):
        check("IP-literal host -> nothing to pin",
              gitops.validate_git_source(f"https://{PUBLIC_V4}/r.git", None) is None)


def test_private_and_mixed_rejected() -> None:
    print("== private / mixed resolutions are rejected before any git process ==")
    with tempfile.TemporaryDirectory() as tmp:
        for label, addrs in [
            ("rfc1918 only", ("10.0.0.5",)),
            ("cloud metadata", ("169.254.169.254",)),
            ("loopback v6", ("::1",)),
            ("mixed public + private", (PUBLIC_V4, "192.168.1.10")),
            ("mixed private + public", ("127.0.0.1", PUBLIC_V4)),
            ("v4-mapped private", ("::ffff:10.1.2.3",)),
        ]:
            rec = _GitRecorder()
            with mock.patch.object(gitops.socket, "getaddrinfo", _resolver(*addrs)), \
                 mock.patch.object(gitops.subprocess, "run", rec):
                try:
                    gitops.ensure_checkout(
                        RepoEntry(repo_id="r", source_type="git",
                                  git_url="https://rebind.example/o/r.git"),
                        tmp, timeout=5)
                    ok = False
                except GitOpsValidationError:
                    ok = True
            check(f"rejected: {label}", ok and not rec.calls,
                  f"calls={rec.calls}")


def test_clone_is_pinned() -> None:
    print("== clone runs with the pinned resolve and records it in repo config ==")
    tmp = tempfile.mkdtemp(prefix="ci-dnspin-clone-")
    try:
        rec = _GitRecorder()
        with mock.patch.object(gitops.socket, "getaddrinfo", _resolver(PUBLIC_V4)), \
             mock.patch.object(gitops.subprocess, "run", rec):
            gitops.ensure_checkout(
                RepoEntry(repo_id="r", source_type="git",
                          git_url="https://rebind.example/o/r.git"),
                tmp, timeout=5)
        clones = rec.by_verb("clone")
        check("exactly one clone", len(clones) == 1, str(rec.calls))
        want = f"http.curloptResolve=rebind.example:443:{PUBLIC_V4}"
        check("clone carries -c http.curloptResolve=<host>:443:<validated ip>",
              bool(clones) and _resolve_opts(clones[0]) == [want],
              str(clones and _resolve_opts(clones[0])))
        check("clone still has http.followRedirects=false",
              bool(clones) and "http.followRedirects=false" in clones[0])
        check("pin precedes the clone verb (it is config, not an argument)",
              bool(clones) and clones[0].index(want) < clones[0].index("clone"))
        cfg = rec.by_verb("config")
        check("pin persisted to the checkout's .git/config for lazy blob fetches",
              len(cfg) == 1
              and cfg[0][-3:] == ["--replace-all", "http.curloptResolve",
                                  f"rebind.example:443:{PUBLIC_V4}"], str(cfg))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def test_fetch_is_pinned() -> None:
    print("== fetch + reset on an existing checkout are pinned too ==")
    tmp = tempfile.mkdtemp(prefix="ci-dnspin-fetch-")
    try:
        os.makedirs(os.path.join(tmp, "checkout", ".git"))
        rec = _GitRecorder()
        with mock.patch.object(gitops.socket, "getaddrinfo", _resolver(PUBLIC_V4)), \
             mock.patch.object(gitops.subprocess, "run", rec):
            gitops.ensure_checkout(
                RepoEntry(repo_id="r", source_type="git",
                          git_url="https://rebind.example/o/r.git"),
                tmp, timeout=5)
        want = f"http.curloptResolve=rebind.example:443:{PUBLIC_V4}"
        remotes = rec.by_verb("remote")
        check("origin URL re-pointed at the validated URL before fetching",
              bool(remotes)
              and remotes[0][-4:] == ["remote", "set-url", "origin",
                                      "https://rebind.example/o/r.git"], str(remotes))
        fetches = rec.by_verb("fetch")
        check("fetch pinned", len(fetches) == 1 and _resolve_opts(fetches[0]) == [want])
        resets = rec.by_verb("reset")
        check("reset --hard pinned (lazy blob fetch in a partial clone)",
              len(resets) == 1 and _resolve_opts(resets[0]) == [want])
        order = [v for c in rec.calls for v in ("remote", "fetch", "reset", "config")
                 if v in c]
        check("order: set-url -> fetch -> reset -> persist",
              order == ["remote", "fetch", "reset", "config"], str(order))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def _git_version() -> tuple[int, int] | None:
    try:
        out = subprocess.run(["git", "--version"], capture_output=True,
                             text=True, timeout=10).stdout
    except (OSError, subprocess.SubprocessError):
        return None
    m = re.search(r"(\d+)\.(\d+)", out)
    return (int(m.group(1)), int(m.group(2))) if m else None


def test_real_git_honours_pin() -> None:
    print("== real git connects to the pinned address, not DNS ==")
    ver = _git_version()
    if ver is None or ver < (2, 37):
        skip("real git honours http.curloptResolve", f"git {ver} < 2.37 or missing")
        return
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.bind(("127.0.0.1", 0))
    srv.listen(1)
    srv.settimeout(20)
    port = srv.getsockname()[1]
    got: dict[str, bytes] = {}

    def accept() -> None:
        try:
            conn, _ = srv.accept()
            conn.settimeout(5)
            got["hello"] = conn.recv(4096)
            conn.close()
        except OSError:
            pass

    t = threading.Thread(target=accept, daemon=True)
    t.start()
    # `.invalid` is guaranteed never to resolve (RFC 2606): the ONLY way git
    # can reach the listener is through the pin.
    host = "pinned-target.invalid"
    pin = PinnedHost(scheme="https", host=host, port=port, addresses=("127.0.0.1",))
    try:
        # Hermetic: ignore the machine's system gitconfig (some Windows Git
        # installs pin an http.sslBackend the build doesn't ship, which kills
        # every https operation before it ever connects).
        with mock.patch.dict(os.environ, {"GIT_CONFIG_NOSYSTEM": "1"}):
            gitops._run(["ls-remote", "--", f"https://{host}:{port}/o/r.git"],
                        cwd=None, timeout=20, token=None, pin=pin)
    except gitops.GitOpsError:
        pass  # the listener speaks no TLS; failing afterwards is expected
    t.join(timeout=20)
    srv.close()
    hello = got.get("hello", b"")
    check("git connected to the pinned 127.0.0.1 listener", bool(hello))
    check("TLS SNI still carries the real hostname", host.encode() in hello)


def main() -> int:
    test_validation_returns_pin()
    test_private_and_mixed_rejected()
    test_clone_is_pinned()
    test_fetch_is_pinned()
    test_real_git_honours_pin()
    print(f"\n{PASS} passed, {FAIL} failed, {SKIP} skipped")
    return 1 if FAIL else 0


if __name__ == "__main__":
    raise SystemExit(main())
