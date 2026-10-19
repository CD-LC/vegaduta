"""SDLC agent tests: the full resolve loop with a scripted planner (no LLM,
no network), plus guardrail interplay.

Run: python3 tests/test_agent.py
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.agent import PlanStep, ScriptedPlanner, SdlcAgent
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
    d = os.path.join(base, "svc")
    os.makedirs(os.path.join(d, "src"))
    # buggy add() + a test that fails until it's fixed
    with open(os.path.join(d, "src", "calc.py"), "w") as f:
        f.write("def add(a, b):\n    return a - b  # BUG\n")
    with open(os.path.join(d, "test_calc.py"), "w") as f:
        f.write("import sys; sys.path.insert(0, 'src')\n"
                "from calc import add\n"
                "assert add(2, 3) == 5, f'expected 5 got {add(2, 3)}'\n"
                "print('ALL TESTS PASSED')\n")
    for cmd in (["git", "init", "-q", d],
                ["git", "-C", d, "config", "user.email", "t@t"],
                ["git", "-C", d, "config", "user.name", "t"],
                ["git", "-C", d, "add", "-A"],
                ["git", "-C", d, "commit", "-qm", "init"]):
        subprocess.run(cmd, check=True)
    return d


def main():
    tmp = tempfile.mkdtemp(prefix="ci-agent-")
    try:
        settings = Settings(data_dir=os.path.join(tmp, "data"),
                            auth_mode="none", default_embedder="hash",
                            allow_exec=True, exec_timeout_seconds=60)
        svc = CodeIntelService(settings)
        ctx = TenantContext(tenant_id="t1", subject="alice")
        repo = make_repo(tenant_root(settings.data_dir, ctx))
        svc.register(ctx, RepoEntry(repo_id="svc", source_type="local", path=repo))
        svc.index(ctx, "svc")   # so knowledge_query has something to ground on

        print("== agent resolves a bug end-to-end (autopilot) ==")
        # autopilot so the agent may apply+run in the isolated workspace
        svc.set_policy(ctx, {**svc.get_policy(ctx).to_dict(), "mode": "autopilot"})
        agent = SdlcAgent(svc)
        # scripted planner: iteration 1 writes the correct fix
        planner = ScriptedPlanner([
            PlanStep(summary="Fix add() to add instead of subtract",
                     files=[{"path": "src/calc.py",
                             "content": "def add(a, b):\n    return a + b\n"}],
                     done=True),
        ])
        res = agent.resolve(ctx, "svc", "add() returns the wrong result",
                            "python3 test_calc.py", max_iterations=3,
                            planner=planner)
        check("agent reports resolved", res.resolved and res.status == "resolved")
        check("one iteration used", res.iterations == 1)
        check("diff contains the fix",
              "-    return a - b" in res.diff and "+    return a + b" in res.diff)
        check("primary checkout untouched",
              "a - b" in open(os.path.join(repo, "src", "calc.py")).read())
        check("trace records success", res.trace and res.trace[-1]["success"])

        print("== agent iterates: wrong fix then right fix ==")
        planner2 = ScriptedPlanner([
            PlanStep("first wrong attempt",
                     [{"path": "src/calc.py", "content": "def add(a, b):\n    return a * b\n"}]),
            PlanStep("corrected",
                     [{"path": "src/calc.py", "content": "def add(a, b):\n    return a + b\n"}], True),  # noqa: E501
        ])
        res2 = agent.resolve(ctx, "svc", "fix add", "python3 test_calc.py",
                             max_iterations=3, planner=planner2)
        check("agent recovers on 2nd iteration", res2.resolved and res2.iterations == 2)
        check("trace shows fail then pass",
              res2.trace[0]["success"] is False and res2.trace[1]["success"] is True)

        print("== guardrails still bind the agent ==")
        # test_gated: apply requires approval -> agent blocks, doesn't proceed
        svc.set_policy(ctx, {**svc.get_policy(ctx).to_dict(), "mode": "test_gated"})
        res3 = agent.resolve(ctx, "svc", "fix add", "python3 test_calc.py",
                             max_iterations=2,
                             planner=ScriptedPlanner([
                                 PlanStep("attempt",
                                          [{"path": "src/calc.py",
                                            "content": "def add(a, b):\n    return a + b\n"}])]))
        check("agent blocked by approval policy", res3.status == "blocked"
              and res3.approval_id and not res3.resolved)

        # a protected-path edit is hard-denied even under autopilot
        svc.set_policy(ctx, {**svc.get_policy(ctx).to_dict(), "mode": "autopilot"})
        res4 = agent.resolve(ctx, "svc", "sneak a config change",
                             "python3 test_calc.py", max_iterations=1,
                             planner=ScriptedPlanner([
                                 PlanStep("touch protected",
                                          [{"path": ".git/hooks/x", "content": "y"}])]))
        check("protected-path edit denied", res4.status == "denied" and not res4.resolved)

        print("== exhaustion is reported honestly ==")
        res5 = agent.resolve(ctx, "svc", "unfixable", "python3 test_calc.py",
                             max_iterations=2,
                             planner=ScriptedPlanner([
                                 PlanStep("bad", [{"path": "src/calc.py",
                                                   "content": "def add(a, b):\n    return a - b\n"}])]))  # noqa: E501
        check("unresolved -> status exhausted", res5.status == "exhausted"
              and not res5.resolved and res5.iterations == 2)

        # audit captured the agent lifecycle
        kinds = [e["kind"] for e in svc.audit_tail(ctx, 200)]
        check("audit has agent_started/finished",
              "agent_started" in kinds and "agent_finished" in kinds)

        print(f"\n{PASS} passed, {FAIL} failed")
        return 1 if FAIL else 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
