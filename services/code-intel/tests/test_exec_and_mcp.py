"""Tests for the execution/rework loop and the MCP stdio bridge.

Run: python3 tests/test_exec_and_mcp.py   (from the code-intel directory)
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.executor import ExecDisabledError, WorkspaceError
from app.registry import RepoEntry
from app.service import CodeIntelService
from app.settings import Settings
from app.tenancy import TenantContext, tenant_root

PASS = 0
FAIL = 0


def check(name, cond, extra=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ok    {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name} {extra}")


def make_repo(base):
    d = os.path.join(base, "src-exec")
    os.makedirs(os.path.join(d, "src"))
    # A buggy module + a test that exposes the bug: the classic
    # run -> fail -> fix -> rerun scenario.
    with open(os.path.join(d, "src", "calc.py"), "w") as f:
        f.write("def add(a, b):\n    return a - b  # BUG\n")
    with open(os.path.join(d, "test_calc.py"), "w") as f:
        f.write("import sys; sys.path.insert(0, 'src')\n"
                "from calc import add\n"
                "assert add(2, 3) == 5, f'expected 5 got {add(2, 3)}'\n"
                "print('ALL TESTS PASSED')\n")
    subprocess.run(["git", "init", "-q", d], check=True)
    subprocess.run(["git", "-C", d, "config", "user.email", "t@t"], check=True)
    subprocess.run(["git", "-C", d, "config", "user.name", "t"], check=True)
    subprocess.run(["git", "-C", d, "add", "-A"], check=True)
    subprocess.run(["git", "-C", d, "commit", "-qm", "init"], check=True)
    return d


def test_rework_loop(tmp):
    print("== execution + rework loop ==")
    settings = Settings(data_dir=os.path.join(tmp, "data"), auth_mode="none",
                        default_embedder="hash", allow_exec=True)
    svc = CodeIntelService(settings)
    ctx = TenantContext(tenant_id="tenant_x")
    repo = make_repo(tenant_root(settings.data_dir, ctx))
    svc.register(ctx, RepoEntry(repo_id="calc", source_type="local", path=repo))
    # This suite exercises the exec/rework MECHANICS, not the guardrails
    # (those have their own suite). Run tenant_x under autopilot so arbitrary
    # test commands and edits proceed without approval; the policy is
    # persisted to the tenant dir, so the MCP subprocess below inherits it.
    svc.set_policy(ctx, {**svc.get_policy(ctx).to_dict(), "mode": "autopilot"})

    ws = svc.workspace_create(ctx, "calc")
    check("workspace created (git worktree)", ws["git"] is True)

    r1 = svc.workspace_run(ctx, "calc", ws["workspace_id"],
                           "python3 test_calc.py", timeout=60)
    check("iteration 1: run fails on the bug", r1["success"] is False)
    check("failure carries rework_hint", "apply_files" in r1.get("rework_hint", ""))
    check("failure output is structured",
          "expected 5" in r1["final"]["stderr"] + r1["final"]["stdout"])

    # THE REWORK STEP (in production the agent LLM decides this content
    # after reading final.stderr; here we play the agent):
    fixed = "def add(a, b):\n    return a + b\n"
    a = svc.workspace_apply(ctx, "calc", ws["workspace_id"],
                            [{"path": "src/calc.py", "content": fixed}])
    check("apply_files writes the fix", a["written"] == ["src/calc.py"]
          and a["iteration"] == 1)

    r2 = svc.workspace_run(ctx, "calc", ws["workspace_id"],
                           "python3 test_calc.py", timeout=60)
    check("iteration 2: rerun succeeds after fix", r2["success"] is True)
    check("success output visible", "ALL TESTS PASSED" in r2["final"]["stdout"])

    d = svc.workspace_diff(ctx, "calc", ws["workspace_id"])
    check("workspace_diff yields the final patch",
          "-    return a - b" in d["diff"] and "+    return a + b" in d["diff"])

    with open(os.path.join(repo, "src", "calc.py")) as f:
        check("primary checkout NEVER modified", "a - b" in f.read())

    # attempts (flaky absorption): command succeeds on 2nd attempt
    flaky = ("test -f flag && echo RECOVERED || (touch flag; echo transient; exit 1)")
    r3 = svc.workspace_run(ctx, "calc", ws["workspace_id"], flaky,
                           timeout=30, attempts=3)
    check("deterministic retries absorb flakiness",
          r3["success"] and r3["attempts_used"] == 2 and r3["flaky"])

    # guardrails
    try:
        svc.workspace_apply(ctx, "calc", ws["workspace_id"],
                            [{"path": "../escape.txt", "content": "x"}])
        check("path escape blocked", False)
    except WorkspaceError:
        check("path escape blocked", True)
    svc2 = CodeIntelService(Settings(data_dir=os.path.join(tmp, "data"),
                                     auth_mode="none",
                                     default_embedder="hash",
                                     allow_exec=False))
    try:
        svc2.workspace_create(ctx, "calc")
        check("exec disabled -> fails closed", False)
    except ExecDisabledError:
        check("exec disabled -> fails closed", True)

    svc.workspace_delete(ctx, "calc", ws["workspace_id"])
    check("workspace cleanup", True)
    return repo


def test_mcp_bridge(tmp, repo):
    print("== MCP stdio bridge (subprocess, direct backend) ==")
    env = dict(os.environ)
    env.update({"CODE_INTEL_DIRECT": "1",
                "CODE_INTEL_DATA_DIR": os.path.join(tmp, "data"),
                "CODE_INTEL_AUTH_MODE": "none",
                "CODE_INTEL_EMBEDDER": "hash",
                "CODE_INTEL_ALLOW_EXEC": "true",
                "CODE_INTEL_TENANT": "tenant_x",
                "CODE_INTEL_REPO": "calc"})
    proc = subprocess.Popen(
        [sys.executable, "-m", "app.mcp_bridge"],
        cwd=os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)

    def rpc(msg):
        proc.stdin.write(json.dumps(msg) + "\n")
        proc.stdin.flush()
        return json.loads(proc.stdout.readline())

    init = rpc({"jsonrpc": "2.0", "id": 1, "method": "initialize",
                "params": {"protocolVersion": "2024-11-05",
                           "capabilities": {}, "clientInfo": {"name": "t"}}})
    check("initialize handshake", init["result"]["serverInfo"]["name"] == "code-intel")
    proc.stdin.write(json.dumps({"jsonrpc": "2.0",
                                 "method": "notifications/initialized"}) + "\n")
    proc.stdin.flush()

    tools = rpc({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
    names = {t["name"] for t in tools["result"]["tools"]}
    check("tools/list exposes the full loop",
          {"search_code", "code_impact", "create_workspace", "apply_files",
           "run_code", "workspace_diff"} <= names)

    ws = rpc({"jsonrpc": "2.0", "id": 3, "method": "tools/call",
              "params": {"name": "create_workspace", "arguments": {}}})
    ws_data = json.loads(ws["result"]["content"][0]["text"])
    check("tools/call create_workspace", "workspace_id" in ws_data)

    run = rpc({"jsonrpc": "2.0", "id": 4, "method": "tools/call",
               "params": {"name": "run_code",
                          "arguments": {"workspace_id": ws_data["workspace_id"],
                                        "command": "python3 test_calc.py"}}})
    run_data = json.loads(run["result"]["content"][0]["text"])
    check("run_code reports failure with rework hint",
          run_data["success"] is False and "apply_files" in run_data["rework_hint"])

    fix = rpc({"jsonrpc": "2.0", "id": 5, "method": "tools/call",
               "params": {"name": "apply_files",
                          "arguments": {"workspace_id": ws_data["workspace_id"],
                                        "files": [{"path": "src/calc.py",
                                                   "content": "def add(a, b):\n    return a + b\n"}]}}})  # noqa: E501
    check("apply_files over MCP", not fix["result"]["isError"])

    rerun = rpc({"jsonrpc": "2.0", "id": 6, "method": "tools/call",
                 "params": {"name": "run_code",
                            "arguments": {"workspace_id": ws_data["workspace_id"],
                                          "command": "python3 test_calc.py"}}})
    rerun_data = json.loads(rerun["result"]["content"][0]["text"])
    check("full rework loop over MCP succeeds", rerun_data["success"] is True)

    bad = rpc({"jsonrpc": "2.0", "id": 7, "method": "tools/call",
               "params": {"name": "run_code",
                          "arguments": {"workspace_id": "ws-nope",
                                        "command": "true"}}})
    check("tool errors surface as isError, not crashes",
          bad["result"]["isError"] is True)

    proc.stdin.close()
    proc.wait(timeout=10)
    check("bridge exits cleanly on stdin close", proc.returncode == 0)


def main():
    tmp = tempfile.mkdtemp(prefix="codeintel-exec-")
    try:
        repo = test_rework_loop(tmp)
        test_mcp_bridge(tmp, repo)
        print(f"\n{PASS} passed, {FAIL} failed")
        return 1 if FAIL else 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
