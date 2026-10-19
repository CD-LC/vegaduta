"""SDLC resolve/review agent — the payoff of the whole stack.

Given a goal (an issue, a bug report, a change request) and a repo, it runs
the SWE-agent / Reflexion loop wired to the tools and guardrails already
built:

  1. gather context with the READ-ONLY tools (grounded code search + the
     code graph),
  2. open an ISOLATED workspace (a git worktree; never the real checkout),
  3. loop: an LLM planner proposes full-file edits → apply to the workspace
     → run the test/build command → read the structured failure → propose a
     fix → rerun, until tests pass or a max-iteration budget is hit,
  4. return the proposed unified DIFF + a per-iteration trace + the context
     citations — a change for a human to review and merge. The agent never
     merges.

Guardrails are inherited for free: every apply/run goes through
CodeIntelService, so the deny-list, protected paths, blast-radius caps,
injection canary, ASK-approval gate, and audit trail all apply exactly as
they do to a human using the Execute tab. If the tenant's policy requires
approval (test_gated), the agent stops and surfaces the pending approval
rather than proceeding — autonomy is a per-tenant decision, not the agent's.

The LLM is pluggable via the Planner interface, so the orchestration is
fully testable without a key (ScriptedPlanner) and swappable
(AnthropicPlanner, default).

TWO separate gates stand in front of this loop, and they are deliberately
reported as two separate failures (see agent_capabilities / the messages
below), because they have different owners and different fixes:

  * no planner configured - ANTHROPIC_API_KEY is unset and/or the `anthropic`
    package is absent. Deployment/config problem => 503.
  * execution disabled    - CODE_INTEL_ALLOW_EXEC=false on this host.
    Posture/platform problem => 403, and on Azure Container Apps it is not a
    toggle at all (no privileged containers, so no Docker sandbox).

Both are checked BEFORE any audit row, workspace, or LLM call, so the caller
gets an honest "this capability is unavailable, here is why" instead of a 500
thrown from inside iteration 1 with a workspace already on disk.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field

from . import audit, executor
from .service import (
    ApprovalRequiredError,
    CodeIntelService,
    PolicyDeniedError,
)
from .tenancy import TenantContext


class PlannerUnavailableError(RuntimeError):
    """Raised (=> HTTP 503) when the default LLM planner cannot be built at
    all: ANTHROPIC_API_KEY not configured, or the `anthropic` package not
    installed in this image. A deployment concern, NOT a bad request and not
    an LLM call that failed - and deliberately distinct from
    executor.ExecDisabledError (=> 403), so an operator reading a response or
    /health can tell "no planner configured" from "execution disabled on this
    host"."""


# Operator-facing reasons. Single source of truth, shared by the request path
# and by the /health capability report, so the two can never drift.
NO_API_KEY_REASON = (
    "ANTHROPIC_API_KEY is not configured on this service, so the SDLC agent's "
    "default LLM planner cannot be built and POST /repos/{repo_id}/agent/"
    "resolve is unavailable. This is an OPTIONAL feature: indexing, search, "
    "the code graph and /knowledge/query all work without it.")
NO_SDK_REASON = (
    "the 'anthropic' package is not installed in this image (it is an "
    "optional line in code-intel/requirements.txt), so the SDLC agent's "
    "default LLM planner is unavailable. Install it to enable POST "
    "/repos/{repo_id}/agent/resolve.")
EXEC_DISABLED_REASON = (
    "the SDLC agent loop applies edits and runs a test command, so it needs "
    "code execution, which is disabled on this host "
    "(CODE_INTEL_ALLOW_EXEC=false). On Azure Container Apps this is not a "
    "toggle to flip: there is no privileged-container support, so the "
    "Docker-in-Docker sandbox the run modes rely on cannot run there.")


# --------------------------------------------------------------------- #
# planner interface

@dataclass
class PlanStep:
    summary: str
    files: list[dict]          # [{"path","content"}] full-file writes
    done: bool = False         # planner believes the goal is met


class Planner:
    """Proposes the next set of file edits given the goal, gathered context,
    the current contents of the candidate files, and the last run result."""

    def propose(self, goal: str, context: dict, current_files: dict,
                last_result: dict | None, iteration: int) -> PlanStep:
        raise NotImplementedError


class ScriptedPlanner(Planner):
    """Deterministic planner for tests: returns pre-canned steps in order."""

    def __init__(self, steps: list[PlanStep]):
        self._steps = steps

    def propose(self, goal, context, current_files, last_result, iteration):
        idx = min(iteration - 1, len(self._steps) - 1)
        return self._steps[idx]


class AnthropicPlanner(Planner):
    """LLM planner (Anthropic). Requires the `anthropic` package and
    ANTHROPIC_API_KEY. Prompts the model with the goal, grounded context,
    the current file contents, and the last test failure, and asks for a
    strict-JSON plan of full-file edits."""

    SYSTEM = (
        "You are an autonomous software engineer. You are given a goal, "
        "context from the codebase, the current contents of candidate files, "
        "and (after the first attempt) the last test failure. Propose the "
        "minimal set of FULL file contents that will make the goal's tests "
        "pass. Respond with ONLY a JSON object: "
        '{"summary": "...", "done": false, '
        '"files": [{"path": "relative/path", "content": "FULL new file"}]}. '
        "Never include prose outside the JSON. Only edit files needed for the "
        "goal; never touch build config, secrets, or CI."
    )

    def __init__(self, model: str = "claude-sonnet-4-5", max_tokens: int = 4000):
        self.model = model
        self.max_tokens = max_tokens
        self._client = None

    def preflight(self) -> None:
        """Build the client NOW so a missing key / missing SDK surfaces before
        the caller's request does any work. Constructing anthropic.Anthropic()
        makes no network call, so this is a config check, not a liveness one:
        it proves the planner CAN be built, not that the API answers."""
        self._client_or_raise()

    def _client_or_raise(self):
        if self._client is None:
            # Defence in depth for callers that construct this class directly
            # instead of going through default_planner()'s gate; reads the
            # environment the SDK itself reads, which is the same value
            # Settings.agent_api_key_present captured at process start. Key
            # presence is checked first because the SDK's own "no api_key"
            # error names an env var but not the feature it disables.
            if not os.environ.get("ANTHROPIC_API_KEY", "").strip():
                raise PlannerUnavailableError(NO_API_KEY_REASON)
            try:
                import anthropic  # type: ignore
            except ImportError as e:
                raise PlannerUnavailableError(NO_SDK_REASON) from e
            self._client = anthropic.Anthropic()
        return self._client

    def propose(self, goal, context, current_files, last_result, iteration):
        client = self._client_or_raise()
        passages = "\n\n".join(
            f"# {p['citation']}\n{p['content'][:1500]}"
            for p in (context.get("passages") or [])[:6])
        files_blob = "\n\n".join(
            f"=== {path} ===\n{content[:6000]}"
            for path, content in current_files.items())
        failure = ""
        if last_result and not last_result.get("success"):
            fin = last_result.get("final", {})
            failure = ("Last attempt FAILED. Test output:\n"
                       + (fin.get("stdout", "") + fin.get("stderr", ""))[:4000])
        user = (
            f"GOAL:\n{goal}\n\n"
            f"CONTEXT (grounded code search):\n{passages}\n\n"
            f"CURRENT FILES:\n{files_blob or '(none identified yet)'}\n\n"
            f"{failure}\n\nRespond with the JSON plan.")
        msg = client.messages.create(
            model=self.model, max_tokens=self.max_tokens,
            system=self.SYSTEM,
            messages=[{"role": "user", "content": user}])
        text = "".join(b.text for b in msg.content if b.type == "text").strip()
        data = _extract_json(text)
        return PlanStep(
            summary=data.get("summary", ""),
            files=[f for f in data.get("files", [])
                   if isinstance(f, dict) and f.get("path")],
            done=bool(data.get("done")))


def _extract_json(text: str) -> dict:
    import json
    try:
        return json.loads(text)
    except Exception:
        pass
    start = text.find("{")
    end = text.rfind("}")
    if start >= 0 and end > start:
        try:
            return json.loads(text[start:end + 1])
        except Exception:
            pass
    return {"summary": "(unparseable planner output)", "files": [], "done": False}


# --------------------------------------------------------------------- #
# capability reporting: is the agent loop actually usable on this host?

def _planner_unavailable_reason(settings) -> str | None:
    """None when the default planner can be built; otherwise the operator
    reason. THE gate - both /health and the request path go through this one
    function, so the capability report and the actual behaviour cannot drift.
    Uses find_spec rather than importing anthropic, so /health stays
    side-effect-free."""
    import importlib.util

    if not settings.agent_api_key_present:
        return NO_API_KEY_REASON
    try:
        found = importlib.util.find_spec("anthropic") is not None
    except (ImportError, ValueError):
        found = False
    return None if found else NO_SDK_REASON


def default_planner(settings) -> Planner:
    """The planner used when the caller injects none. Raises
    PlannerUnavailableError HERE - before a workspace is created - rather than
    at the first propose() call halfway through the loop."""
    reason = _planner_unavailable_reason(settings)
    if reason:
        raise PlannerUnavailableError(reason)
    planner = AnthropicPlanner()
    planner.preflight()
    return planner


def agent_capabilities(settings) -> dict:
    """Operator-facing report of whether POST /repos/{repo_id}/agent/resolve
    can run, surfaced under /health so the two failure modes are visible
    WITHOUT a trial call that 500s. The gates are reported separately on
    purpose: `planner` is a config gap someone can close by setting a key,
    `exec` is a host posture that on Azure Container Apps cannot be flipped at
    all. Mirrors rl-trainer's `mode: llm|stub-only` on its own /health, one
    level more detailed because there are two gates here, not one."""
    planner_reason = _planner_unavailable_reason(settings)
    exec_reason = None if settings.allow_exec else EXEC_DISABLED_REASON
    return {
        "planner": {"configured": planner_reason is None,
                    "reason": planner_reason},
        "exec": {"enabled": bool(settings.allow_exec),
                 "reason": exec_reason},
        "enabled": planner_reason is None and bool(settings.allow_exec),
        "reasons": [r for r in (planner_reason, exec_reason) if r],
    }


# --------------------------------------------------------------------- #
# the agent

@dataclass
class AgentResult:
    goal: str
    resolved: bool
    status: str                       # "resolved" | "exhausted" | "blocked" | "denied"
    iterations: int
    trace: list[dict] = field(default_factory=list)
    diff: str = ""
    workspace_id: str | None = None
    context_citations: list[str] = field(default_factory=list)
    blocked_reason: str | None = None
    approval_id: str | None = None

    def to_dict(self) -> dict:
        return {
            "goal": self.goal, "resolved": self.resolved, "status": self.status,
            "iterations": self.iterations, "trace": self.trace, "diff": self.diff,
            "workspace_id": self.workspace_id,
            "context_citations": self.context_citations,
            "blocked_reason": self.blocked_reason, "approval_id": self.approval_id,
        }


class SdlcAgent:
    def __init__(self, service: CodeIntelService):
        self.service = service

    def resolve(self, ctx: TenantContext, repo_id: str, goal: str,
                test_command: str, max_iterations: int = 4,
                planner: Planner | None = None) -> AgentResult:
        # Both gates BEFORE any side effect - no audit row, no workspace, no
        # LLM call. Previously the default planner was built lazily and first
        # touched at iteration 1, so an unconfigured deployment (which is every
        # deployment today: nothing passes ANTHROPIC_API_KEY to this service)
        # got a 500 out of the middle of the loop, with a workspace already on
        # disk and agent_started already written to the audit log.
        #
        # Order matters only for which reason the caller sees first; the two
        # are distinct types and distinct HTTP statuses (403 vs 503) so an
        # operator can always tell them apart, and /health reports BOTH at once
        # via agent_capabilities.
        settings = self.service.settings
        if not settings.allow_exec:
            # Same ExecDisabledError -> 403 as every other exec route
            # (service._exec_guard, which still runs underneath us on
            # workspace_create), but carrying the agent-specific reason:
            # "set CODE_INTEL_ALLOW_EXEC=true" is not actionable advice on a
            # host where the sandbox cannot exist at all.
            raise executor.ExecDisabledError(EXEC_DISABLED_REASON)
        planner = planner or default_planner(settings)
        tdir = self.service._tenant_dir(ctx)

        audit.record(tdir, {"kind": "agent_started", "actor": ctx.subject,
                            "repo_id": repo_id, "goal": goal[:500],
                            "test_command": test_command})

        # 1) gather grounded context (read-only)
        context = self.service.knowledge_query(ctx, goal, repo_id=repo_id, top_k=6)
        candidate_paths = _dedupe([p["path"] for p in context.get("passages", [])])

        # 2) isolated workspace
        ws = self.service.workspace_create(ctx, repo_id)["workspace_id"]
        ws_path = executor.load_workspace(
            self.service._workspaces_dir(ctx, repo_id), ws)["path"]

        trace: list[dict] = []
        last_result = None
        resolved = False
        status = "exhausted"

        try:
            for i in range(1, max(1, min(max_iterations, 10)) + 1):
                current_files = _read_files(ws_path, candidate_paths)
                plan = planner.propose(goal, context, current_files, last_result, i)

                if plan.files:
                    self.service.workspace_apply(ctx, repo_id, ws, plan.files)
                    # widen candidate set with anything the planner created
                    for f in plan.files:
                        if f["path"] not in candidate_paths:
                            candidate_paths.append(f["path"])

                run = self.service.workspace_run(ctx, repo_id, ws, test_command)
                last_result = run
                fin = run.get("final", {})
                trace.append({
                    "iteration": i,
                    "summary": plan.summary,
                    "files_written": [f["path"] for f in plan.files],
                    "success": run["success"],
                    "exit_code": fin.get("exit_code"),
                    "output_tail": (fin.get("stdout", "") + fin.get("stderr", ""))[-1200:],
                })
                if run["success"]:
                    resolved = True
                    status = "resolved"
                    break

            diff = self.service.workspace_diff(ctx, repo_id, ws)["diff"]
        except ApprovalRequiredError as e:
            status = "blocked"
            diff = self.service.workspace_diff(ctx, repo_id, ws).get("diff", "")
            audit.record(tdir, {"kind": "agent_blocked", "actor": ctx.subject,
                                "repo_id": repo_id, "approval_id": e.approval_id,
                                "reason": e.reason})
            return AgentResult(goal, False, status, len(trace), trace, diff, ws,
                               context.get("citations", []),
                               blocked_reason=e.reason, approval_id=e.approval_id)
        except PolicyDeniedError as e:
            status = "denied"
            audit.record(tdir, {"kind": "agent_denied", "actor": ctx.subject,
                                "repo_id": repo_id, "reason": str(e)})
            return AgentResult(goal, False, status, len(trace), trace, "", ws,
                               context.get("citations", []), blocked_reason=str(e))

        audit.record(tdir, {"kind": "agent_finished", "actor": ctx.subject,
                            "repo_id": repo_id, "resolved": resolved,
                            "iterations": len(trace)})
        return AgentResult(goal, resolved, status, len(trace), trace, diff, ws,
                           context.get("citations", []))


# --------------------------------------------------------------------- #

def _dedupe(items: list[str]) -> list[str]:
    seen, out = set(), []
    for x in items:
        if x and x not in seen:
            seen.add(x)
            out.append(x)
    return out


def _read_files(ws_path: str, paths: list[str], limit: int = 8,
                max_bytes: int = 40_000) -> dict:
    out: dict[str, str] = {}
    for rel in paths[:limit]:
        ap = os.path.realpath(os.path.join(ws_path, rel))
        if not ap.startswith(os.path.realpath(ws_path) + os.sep):
            continue
        try:
            with open(ap, encoding="utf-8", errors="replace") as f:
                out[rel] = f.read(max_bytes)
        except OSError:
            continue
    return out
