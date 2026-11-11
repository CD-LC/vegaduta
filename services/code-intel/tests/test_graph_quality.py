"""Framework-free tests for call extraction and edge resolution quality.

Pins the regressions behind "code-intel does not give proper results":
  * Java `method_invocation` was matched by the generic "method" definition
    fragment, so every call became a bogus SYMBOL and no `calls` edge existed;
  * calls were resolved by bare name, so `map.get(k)` or `Pattern.compile(x)`
    pointed at whichever in-repo method shared the name.

Run: python3 -m tests.test_graph_quality   (from the code-intel directory)
"""

from __future__ import annotations

import os
import shutil
import sqlite3
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from codebase_rag import graph
from codebase_rag.config import Config
from codebase_rag.graph_extract import extract_file, is_call_type, is_definition_type

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


JAVA = """package p;
import java.util.List;
public class A extends B implements C {
  private final D d;
  public void f(List<String> xs) { foo(); this.bar(1); new E(); d.baz(xs.size()); Util.run(); }
  private int foo() { return 1; }
  void bar(int i) { }
}
"""


def calls(fx):
    return {
        (e.src_qualified, e.target_name, e.qualifier) for e in fx.edges if e.edge_type == "calls"
    }


def test_type_classification() -> None:
    check("method_invocation is a call", is_call_type("method_invocation"))
    check("object_creation is a call", is_call_type("object_creation_expression"))
    check("method_invocation is NOT a definition", not is_definition_type("method_invocation"))
    check("method_declaration is a definition", is_definition_type("method_declaration"))
    check("method_reference is NOT a definition", not is_definition_type("method_reference"))
    check("ruby method is a definition", is_definition_type("method"))
    check("module_import is NOT a definition", not is_definition_type("module_import"))


def test_java_extraction() -> None:
    fx = extract_file("A.java", JAVA, "java")
    names = {s.qualified_name for s in fx.symbols}
    check("java: only real symbols (class + 3 methods)", names == {"A", "A.f", "A.foo", "A.bar"})
    c = calls(fx)
    check("java: bare call captured", ("A.f", "foo", None) in c)
    check("java: this.bar keeps receiver", ("A.f", "bar", "this") in c)
    check("java: field receiver captured", ("A.f", "baz", "d") in c)
    check("java: static receiver captured", ("A.f", "run", "Util") in c)
    check("java: constructor call is a call to the class", ("A.f", "E", None) in c)


def test_other_languages() -> None:
    cases = {
        "a.cs": ("csharp", "class A { void F() { Helper.Run(); } }", "Run"),
        "a.kt": ("kotlin", "class A { fun f() { helper() } }", "helper"),
        "a.php": ("php", "<?php class A { function f() { $this->go(); } }", "go"),
        "a.rb": ("ruby", "class A\n  def f\n    helper(1)\n  end\nend\n", "helper"),
        "a.ts": ("typescript", "class A { f() { svc.load(1); } }", "load"),
        "a.go": ("go", "package p\nfunc F() { helper() }\n", "helper"),
        "a.rs": ("rust", "fn f() { helper(); }", "helper"),
        "a.py": ("python", "def f():\n    helper()\n", "helper"),
    }
    for path, (lang, src, want) in cases.items():
        fx = extract_file(path, src, lang)
        got = {n for _, n, _ in calls(fx)}
        check(f"{lang}: call to {want} extracted", want in got)


def build(files: dict[str, str]):
    root = tempfile.mkdtemp(prefix="ci-quality-")
    for rel, text in files.items():
        full = os.path.join(root, rel)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8") as fh:
            fh.write(text)
    cfg = Config(root=root, embedder="hash")
    graph.build_graph(cfg, quiet=True)
    return root, cfg


def edges(cfg, target):
    c = sqlite3.connect(cfg.db_path)
    try:
        return c.execute(
            "SELECT src_symbol, confidence, target_path FROM graph_edges "
            "WHERE edge_type='calls' AND target_name=?",
            (target,),
        ).fetchall()
    finally:
        c.close()


def test_resolution() -> None:
    root, cfg = build(
        {
            "svc/Registry.java": (
                "package svc;\npublic class Registry {\n"
                "  public Object get(String k) { return null; }\n"
                "  public void reset() { this.clear(); }\n"
                "  void clear() { }\n}\n"
            ),
            "svc/Caller.java": (
                "package svc;\nimport java.util.Map;\nimport java.util.regex.Pattern;\n"
                "public class Caller {\n"
                "  Registry registry;\n"
                "  void a(Map<String,String> map) {\n"
                '    map.get("x");\n'
                '    registry.get("x");\n'
                '    Pattern.compile("y");\n'
                "    Registry.reset();\n"
                "  }\n}\n"
            ),
            "svc/Util.java": (
                "package svc;\npublic class Util { static void compile(String s) { } }\n"
            ),
        }
    )
    try:
        gets = edges(cfg, "get")
        by_conf = sorted(c for _, c, _ in gets)
        check("map.get on a library variable is external", "external" in by_conf)
        check(
            "registry.get resolves to Registry",
            any(tp and tp.endswith("Registry.java") for _, _, tp in gets),
        )
        comp = edges(cfg, "compile")
        check(
            "Pattern.compile (external type) is not Util.compile",
            all(c == "external" for _, c, _ in comp),
        )
        clear = edges(cfg, "clear")
        check(
            "this.clear resolves within the class",
            any(c == "local" and tp.endswith("Registry.java") for _, c, tp in clear),
        )
        stats = graph.graph_stats(cfg)
        check("java call edges exist", stats["edges"].get("calls", 0) > 0)
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_incremental_relink() -> None:
    root, cfg = build({"a.py": "def caller():\n    helper()\n"})
    try:
        check("before: helper is external", [c for _, c, _ in edges(cfg, "helper")] == ["external"])
        with open(os.path.join(root, "b.py"), "w", encoding="utf-8") as fh:
            fh.write("def helper():\n    return 1\n")
        st = graph.build_graph(cfg, quiet=True)
        check("adding b.py relinks the unchanged caller", st.get("edges_relinked", 0) >= 1)
        rows = edges(cfg, "helper")
        check(
            "after: caller in unchanged a.py now resolves to b.py",
            any(c == "global" and tp == "b.py" for _, c, tp in rows),
        )
        os.remove(os.path.join(root, "b.py"))
        graph.build_graph(cfg, quiet=True)
        check(
            "deleting b.py drops the stale target",
            [c for _, c, _ in edges(cfg, "helper")] == ["external"],
        )
    finally:
        shutil.rmtree(root, ignore_errors=True)


def main() -> int:
    test_type_classification()
    test_java_extraction()
    test_other_languages()
    test_resolution()
    test_incremental_relink()
    print(f"\n{PASS} passed, {FAIL} failed")
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
