"""Framework-free tests for codebase_rag/analysis.py and the extraction
additions behind it (signature/doc/is_test on symbols, `references` edges,
api_routes rows): builds a graph over a fixture repo and asserts every
analysis query's mechanics - not retrieval quality, which is downstream of
the deterministic extraction these tests pin down.

Run: python3 -m tests.test_analysis   (from the code-intel directory)
"""

from __future__ import annotations

import os
import shutil
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from codebase_rag import analysis, graph
from codebase_rag.config import Config

PASS = 0
FAIL = 0


def check(name: str, cond) -> None:
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  PASS {name}")
    else:
        FAIL += 1
        print(f"  FAIL {name}")


FIXTURE = {
    "src/db.py": 'import os\n\n\ndef connect():\n    """Open the DB."""\n'
    "    return os.environ\n\n\ndef close(conn):\n    pass\n",
    "src/auth.py": (
        '"""Auth service."""\n'
        "from src.db import connect\n\n\n"
        "class AuthService:\n"
        '    """Handles login and tokens."""\n\n'
        "    def login(self, user, password):\n"
        '        """Authenticate and return a session token."""\n'
        "        conn = connect()\n"
        "        return self.issue_token(user, conn)\n\n"
        "    def issue_token(self, user, conn):\n"
        '        return verify(user) and "tok"\n\n\n'
        'def verify(user):\n    """Docstring for verify."""\n'
        "    return bool(user)\n\n\n"
        "def unused_helper():\n    return 42\n"
    ),
    "src/server.py": (
        "from src.auth import AuthService\n\n"
        "app = None\n\n\n"
        '@app.get("/users/{id}")\n'
        "def get_user():\n    pass\n\n\n"
        'def main():\n    AuthService().login("a", "b")\n'
    ),
    "src/typed.py": (
        "from src.auth import AuthService\n\n\n"
        "def typed_handler(svc: AuthService) -> AuthService:\n"
        "    cfg = AuthService.instance.config\n"
        "    return svc\n"
    ),
    "tests/test_auth.py": (
        "from src.auth import AuthService\n\n\n"
        "def test_login():\n    svc = AuthService()\n"
        '    assert svc.login("u", "p")\n'
    ),
}


def main() -> int:
    root = tempfile.mkdtemp(prefix="ci-analysis-test-")
    try:
        for rel, content in FIXTURE.items():
            p = os.path.join(root, rel)
            os.makedirs(os.path.dirname(p), exist_ok=True)
            with open(p, "w") as f:
                f.write(content)
        cfg = Config(root=root, embedder="hash")
        stats = graph.build_graph(cfg, quiet=True)
        check("build extracted files", stats["files"] == 5)
        check("references edges extracted", stats["edges"].get("references", 0) > 0)

        m = analysis.repo_map(cfg, budget=4000)
        check("repo_map returns files", len(m["files"]) > 0)
        check("repo_map respects budget", m["used_chars"] <= 4000)
        check(
            "repo_map has signatures", any(s["signature"] for f in m["files"] for s in f["symbols"])
        )

        found = analysis.find_symbols(cfg, query="login")
        qnames = {s["qualified_name"] for s in found}
        check("find_symbols finds login", "AuthService.login" in qnames)
        check("find_symbols finds test symbol", "test_login" in qnames)
        prod = analysis.find_symbols(cfg, query="login", include_tests=False)
        check("find_symbols excludes tests", all(not s["is_test"] for s in prod))

        d = analysis.symbol_detail(cfg, "AuthService.login")
        check("symbol_detail signature", d["signature"].startswith("def login"))
        check("symbol_detail body", "issue_token" in d.get("body", ""))
        check("symbol_detail is_test", d["is_test"] is False)

        refs = analysis.references_of(cfg, "AuthService")
        check("references has calls", len(refs["by_type"].get("calls", [])) >= 2)
        check("references has ref edges", len(refs["by_type"].get("references", [])) >= 1)

        impl = analysis.implementations_of(cfg, "AuthService")
        check("implementations no false positives", impl["total"] == 0)

        path = analysis.call_path(cfg, "main", "verify")
        check("call_path found", path.get("found") is True)
        check(
            "call_path chain",
            path.get("path", [])[-1] == "verify" or path.get("path", [])[-1].endswith("verify"),
        )

        dc = analysis.dead_code(cfg)
        dead_names = {c["symbol"] for c in dc["candidates"]}
        check("dead_code finds unused_helper", "unused_helper" in dead_names)
        check("dead_code skips main/entrypoint", "main" not in dead_names)
        check("dead_code skips verified", "verify" not in dead_names)

        t = analysis.tests_for(cfg, "AuthService.login")
        check("tests_for covered", t["covered"] is True)
        check("tests_for file", "tests/test_auth.py" in t["test_files"])
        t2 = analysis.tests_for(cfg, "unused_helper")
        check("tests_for uncovered", t2["covered"] is False)

        api = analysis.api_surface(cfg)
        routes = {(r["method"], r["route"]) for r in api["routes"]}
        check("api_surface GET /users/{id}", ("GET", "/users/{id}") in routes)

        cycles = analysis.import_cycles(cfg)
        check("import_cycles none in acyclic fixture", cycles["count"] == 0)

        outline = analysis.file_outline(cfg, "src/auth.py")
        check(
            "outline has class + methods",
            {"AuthService", "AuthService.login"} <= {s["qualified_name"] for s in outline},
        )

        # doc extraction: the class docstring must land on the symbol row
        doc_rows = analysis.find_symbols(cfg, query="AuthService")
        auth_cls = [s for s in doc_rows if s["name"] == "AuthService"]
        check("doc on class symbol", auth_cls and "login" in (auth_cls[0]["doc"] or ""))

        hot = analysis.hotspots(cfg)
        check("hotspots honest on non-git", hot["hotspots"] == [])

        print(f"\n{PASS} passed, {FAIL} failed")
        return 1 if FAIL else 0
    finally:
        shutil.rmtree(root, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
