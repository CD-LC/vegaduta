"""SVC-005: context scripts never hand a repository filename to a shell.

The command template is operator config; `{file}` is a repo-relative path,
i.e. attacker-controlled for any tenant repo. These checks run REAL
subprocesses (the current Python interpreter as the "script") so they prove
the filename arrives as exactly one argv element and no shell ever parses
it - on Windows and Linux alike.

Run: python -m tests.test_context_scripts   (from the code-intel directory)
"""

from __future__ import annotations

import os
import shlex
import shutil
import subprocess
import sys
import tempfile
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from codebase_rag import contextualizer
from codebase_rag.config import Config, ContextScript
from codebase_rag.contextualizer import build_script_argv, run_context_scripts

PASS = 0
FAIL = 0
SKIP = 0

MALICIOUS = "a;touch pwned`$(id)`.py"
# Prints each argv element on its own line, bracketed, so the test can see
# exactly how many arguments arrived and what each one was.
ECHO_ARGS = (shlex.quote(sys.executable)
             + " -c 'import sys; print(\"|\".join(\"[\"+a+\"]\" for a in sys.argv[1:]))'")


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


def _run(root: str, template: str, rel_path: str) -> str:
    cfg = Config(root=root, context_scripts=[
        ContextScript(name="t", command=template, timeout=30)])
    return run_context_scripts(cfg, rel_path)


def test_argv_building() -> None:
    print("== build_script_argv: tokenize first, substitute per token ==")
    check("normal substitution",
          build_script_argv("ctags --output-format=json -f - {file}", "src/a.py")
          == ["ctags", "--output-format=json", "-f", "-", "src/a.py"])
    check("malicious name stays ONE argv element, verbatim",
          build_script_argv("echo {file}", MALICIOUS) == ["echo", MALICIOUS])
    check("spaces in a filename do not split it",
          build_script_argv("wc -l {file}", "my file.py") == ["wc", "-l", "my file.py"])
    check("{file} inside a token is substituted in place",
          build_script_argv("tool --path={file}", "x y.py") == ["tool", "--path=x y.py"])
    check("quoted template groups are preserved",
          build_script_argv("git log -1 '--format=%s %an' -- {file}", "a.py")
          == ["git", "log", "-1", "--format=%s %an", "--", "a.py"])
    check("leading-dash filename cannot become an option",
          build_script_argv("cat {file}", "--output=/etc/x") == ["cat", "./--output=/etc/x"])


def test_real_subprocess_no_injection() -> None:
    print("== real subprocess: malicious filename is inert ==")
    root = tempfile.mkdtemp(prefix="ci-ctxscript-")
    try:
        out = _run(root, ECHO_ARGS + " {file}", MALICIOUS)
        check("script received the filename as exactly one literal argument",
              out == f"[t] [{MALICIOUS}]", repr(out))
        check("no `pwned` file created (the `;touch` never ran)",
              not any(n.startswith("pwned") for n in os.listdir(root)),
              str(os.listdir(root)))

        out2 = _run(root, ECHO_ARGS + " --file={file} extra", "src/ok file.py")
        check("normal substitution still works end-to-end",
              out2 == "[t] [--file=src/ok file.py]|[extra]", repr(out2))

        out3 = _run(root, ECHO_ARGS + " 'unbalanced {file}", "a.py")
        check("unbalanced template quote -> reported, no crash, nothing run",
              out3.startswith("[t] (invalid command template"), repr(out3))
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_never_shell_true() -> None:
    print("== subprocess.run is called with an argv list and shell=False ==")
    seen: list[tuple[object, dict]] = []

    def fake_run(cmd, *a, **k):
        seen.append((cmd, k))
        return subprocess.CompletedProcess(cmd, 0, stdout="x", stderr="")

    with mock.patch.object(contextualizer.subprocess, "run", fake_run):
        _run(tempfile.gettempdir(), "echo {file}", MALICIOUS)
    check("argv list, shell=False",
          len(seen) == 1 and seen[0][0] == ["echo", MALICIOUS]
          and seen[0][1].get("shell") is False, str(seen))


def test_posix_shell_wrapper() -> None:
    print("== explicit `sh -c '...\"$1\"' _ {file}` wrapper keeps the file inert ==")
    sh = shutil.which("sh")
    if os.name == "nt" or not sh:
        skip("sh -c wrapper", "no POSIX sh on this platform")
        return
    root = tempfile.mkdtemp(prefix="ci-ctxscript-sh-")
    try:
        out = _run(root, "sh -c 'printf \"%s\" \"$1\"' _ {file}", MALICIOUS)
        check("positional $1 carries the literal name", out == f"[t] {MALICIOUS}", repr(out))
        check("no `pwned` file created",
              not any(n.startswith("pwned") for n in os.listdir(root)))
    finally:
        shutil.rmtree(root, ignore_errors=True)


def main() -> int:
    test_argv_building()
    test_real_subprocess_no_injection()
    test_never_shell_true()
    test_posix_shell_wrapper()
    print(f"\n{PASS} passed, {FAIL} failed, {SKIP} skipped")
    return 1 if FAIL else 0


if __name__ == "__main__":
    raise SystemExit(main())
