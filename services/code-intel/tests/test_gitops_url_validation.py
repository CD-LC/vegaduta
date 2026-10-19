"""Security tests for the 2026-09-18 audit findings on the clone source.

- git_url is tenant input: only https, never a private/internal address, never
  something git would parse as an option; git_ref must look like a ref.
- An operator credential named through auth_env only travels to URL prefixes
  the operator approved (CODE_INTEL_AUTH_ENV_URL_PREFIXES), fail-closed.

Every rejection must be a GitOpsValidationError raised BEFORE any git process.

Run: python3 tests/test_gitops_url_validation.py   (from the code-intel directory)
"""

from __future__ import annotations

import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app import gitops
from app.gitops import GitOpsValidationError
from app.registry import RepoEntry

PASS = 0
FAIL = 0


def check(name: str, cond: bool, extra: str = ""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ok    {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name} {extra}")


def rejected(tmp: str, **kw) -> bool:
    calls: list = []
    real_run = gitops.subprocess.run
    gitops.subprocess.run = lambda *a, **k: calls.append(a) or real_run(*a, **k)
    try:
        gitops.ensure_checkout(
            RepoEntry(repo_id="r", source_type="git", **kw), tmp, timeout=5)
        return False
    except GitOpsValidationError:
        return len(calls) == 0
    except gitops.GitOpsError:
        return False
    finally:
        gitops.subprocess.run = real_run


def main():
    for k in ("CODE_INTEL_GIT_ALLOWED_SCHEMES", "CODE_INTEL_AUTH_ENV_URL_PREFIXES"):
        os.environ.pop(k, None)
    with tempfile.TemporaryDirectory() as tmp:
        print("== git_url validation (defaults: https only) ==")
        for label, url in [
            ("file:// (cross-tenant disk read)", "file:///data/tenants/other/repos/x/checkout"),
            ("plain path", "/data/tenants/other"),
            ("ssh://", "ssh://git@github.com/o/r.git"),
            ("scp-style", "git@github.com:o/r.git"),
            ("ext:: transport", "ext::sh -c id"),
            ("http://", "http://github.com/o/r.git"),
            ("option injection", "--upload-pack=touch /tmp/pwned"),
            ("cloud metadata", "https://169.254.169.254/latest/"),
            ("loopback", "https://127.0.0.1:8080/r.git"),
            ("localhost name", "https://localhost/r.git"),
            ("rfc1918", "https://10.0.0.5/r.git"),
            ("ipv6 loopback", "https://[::1]/r.git"),
            ("v4-mapped loopback", "https://[::ffff:127.0.0.1]/r.git"),
            ("decimal-encoded loopback", "https://2130706433/r.git"),
            ("embedded credentials", "https://user:pw@github.com/o/r.git"),
            ("newline smuggling", "https://github.com/o/r.git\n--upload-pack=x"),
        ]:
            check(f"rejected: {label}", rejected(tmp, git_url=url))

        print("== git_ref validation ==")
        for ref in ["--upload-pack=x", "-b", "a b", "x;id", "a\nb"]:
            check(f"rejected ref {ref!r}",
                  rejected(tmp, git_url="https://github.com/o/r.git", git_ref=ref))
        try:
            gitops.validate_git_source("https://github.com/o/r.git", "release/1.2_x-y")
            check("ordinary https URL + ref accepted", True)
        except GitOpsValidationError as e:
            # No DNS in this sandbox is not a validation bug.
            check("ordinary https URL + ref accepted", "does not resolve" in str(e), str(e))

        print("== auth_env only travels to operator-approved prefixes ==")
        os.environ["CODE_INTEL_GIT_TOKEN"] = "operator-git-token-FAKE"
        try:
            check("no prefixes configured -> auth_env refused (fail closed)",
                  rejected(tmp, git_url="https://github.com/attacker/r.git",
                           auth_env="CODE_INTEL_GIT_TOKEN"))
            os.environ["CODE_INTEL_AUTH_ENV_URL_PREFIXES"] = "https://github.com/vegaduta/"
            check("attacker-controlled remote refused",
                  rejected(tmp, git_url="https://github.com/attacker/r.git",
                           auth_env="CODE_INTEL_GIT_TOKEN"))
            check("look-alike host refused",
                  rejected(tmp, git_url="https://github.com.evil.example/vegaduta/r.git",
                           auth_env="CODE_INTEL_GIT_TOKEN"))
        finally:
            del os.environ["CODE_INTEL_GIT_TOKEN"]
            os.environ.pop("CODE_INTEL_AUTH_ENV_URL_PREFIXES", None)

    print(f"\n{PASS} passed, {FAIL} failed")
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
