"""A monorepo `subdir` is interpolated into `cd <subdir> && ...` shell commands
(and into the generated GitHub Actions workflow), so it must be plain path
characters only.

The old check was ``_SUBDIR_RX.match(sub)`` with a ``$``-anchored pattern, and
``$`` also matches before ONE trailing newline: ``safe_repo_path('a/b\\n/')``
keeps the newline inside ``a/b\\n`` (it only strips whitespace at the ends of the
whole string), the pattern accepted it, and the manifest became
``cd a/b\\n && npm install``: a newline starts a second command. These tests
drive the real import route and the pure helpers with such values.

Run: python -m tests.test_subdir_validation   (from the app-builder directory)
"""

from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app import repo_import  # noqa: E402
from app.github_client import GitHubError  # noqa: E402
from tests.test_repo_sync import HDRS, REACT_PKG, Scenario  # noqa: E402

PASS = 0
FAIL = 0

# Each of these must be refused: a control character (newline, CR, NUL, DEL,
# vertical tab), a shell metacharacter, whitespace, or a leading "-" (`cd -L`).
BAD = [
    "apps/web\n/",
    "apps/web\r\n/",
    "apps\n/web",
    "apps/web\n/\n",
    "apps/web\x00/",
    "apps/web\x7f/",
    "apps/web\x0b/",
    "apps/web\x0c/x",
    "apps/web;id",
    "apps/web && id",
    "apps/$(id)",
    "apps/`id`",
    "apps/web|x",
    "-L",
    "-P/apps",
    "apps web",
]


def check(name: str, cond: bool, extra: str = ""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ok    {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name} {extra}")


def refused(sub: str) -> bool:
    try:
        repo_import.validate_subdir(sub)
    except GitHubError as e:
        return e.status == 400
    return False


def test_pure_helpers():
    print("== validate_subdir / scope_manifest (pure) ==")
    for sub in BAD:
        check(f"validate_subdir refuses {sub!r}", refused(sub))
    accepted = {
        "apps/web": "apps/web",
        "a": "a",
        "a.b/c_d-e": "a.b/c_d-e",
        "apps/web/": "apps/web",
        # whitespace at the very ends is trimmed by safe_repo_path, so no newline survives
        "apps/web\n": "apps/web",
        "./apps/./web": "apps/web",
        "a/-b": "a/-b",
    }
    for sub, want in accepted.items():
        try:
            got = repo_import.validate_subdir(sub)
        except GitHubError as e:
            got = f"refused: {e}"
        check(f"validate_subdir accepts {sub!r}", got == want, extra=repr(got))

    manifest = {"install": "npm install", "run": "npm run dev", "build": "", "test": "npm test"}
    scoped = repo_import.scope_manifest(manifest, "apps/web")
    check(
        "a plain subdir prefixes the commands, unquoted",
        scoped["install"] == "cd apps/web && npm install"
        and scoped["run"] == "cd apps/web && npm run dev"
        and scoped["build"] == "",
        extra=str(scoped),
    )
    for sub in BAD:
        try:
            out = repo_import.scope_manifest(dict(manifest), sub)
        except GitHubError:
            out = None
        check(f"scope_manifest never builds a shell string from {sub!r}", out is None, str(out))

    print("== capacitor/astro config names are full matches ==")
    files = {"package.json": REACT_PKG}

    def reader(rel):
        return files.get(rel)

    got = repo_import.detect_stack({"package.json", "capacitor.config.ts"}, reader)
    check("capacitor.config.ts is detected", got["stack"] == "capacitor-app", str(got))
    got = repo_import.detect_stack({"package.json", "capacitor.config.ts\n"}, reader)
    check("'capacitor.config.ts\\n' is not a capacitor config", got["stack"] != "capacitor-app")


def test_import_route():
    print("== /projects/import with a newline-bearing subdir (real route, fake GitHub) ==")
    for sub in ("apps/web\n/", "apps/web\r\n/", "-L"):
        # The files really exist under that (hostile) name in the repo, so an
        # unfixed import goes all the way through and records the command.
        seed = {"README.md": "# mono\n", f"{sub.rstrip('/')}/package.json": REACT_PKG}
        sc = Scenario(seed)
        sc.connect()
        refs_before = dict(sc.fake.refs)
        r = sc.imp(project_id="p1", subdir=sub)
        check(
            f"subdir {sub!r} is refused with 400", r.status_code == 400, f"{r.status_code} {r.text}"
        )
        check("no branch was created", sc.fake.refs == refs_before)
        check("no project was registered", sc.client.get("/projects", headers=HDRS).json() == [])
        check("code-intel was never asked to register a repo", sc.registrations == [])

    sc = Scenario({"README.md": "x", "apps/web/package.json": REACT_PKG})
    sc.connect()
    r = sc.imp(project_id="ok", subdir="apps/web")
    man = (r.json().get("github") or {}).get("manifest", {}) if r.status_code == 201 else {}
    check("a clean subdir still imports", r.status_code == 201, f"{r.status_code} {r.text}")
    check(
        "and the command is exactly cd + install",
        man.get("install") == "cd apps/web && npm install",
        str(man),
    )
    check(
        "no command contains a control character",
        all(
            "\n" not in str(man.get(k, "")) and "\r" not in str(man.get(k, ""))
            for k in ("install", "run", "build", "test")
        ),
        str(man),
    )


def main() -> int:
    test_pure_helpers()
    test_import_route()
    print(f"\n{PASS} passed, {FAIL} failed")
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
