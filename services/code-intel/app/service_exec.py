"""Governed execution for CodeIntelService: workspaces, policy, approvals.

Everything that writes code or runs a command in a tenant workspace goes
through here, gated by settings.allow_exec, the tenant's guardrail policy
(DENY / ASK / ALLOW), the approval queue and the audit trail. Mixed into
CodeIntelService, which supplies `settings`, `_handles` and `_heavy_slot`.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from .service_errors import (
    ApprovalNotAuthorizedError,
    ApprovalRequiredError,
    PolicyDeniedError,
)
from .tenancy import TenantContext, repo_root

if TYPE_CHECKING:
    from .settings import Settings


class GovernedExecutionMixin:
    settings: Settings

    # execution workspaces (rework loop) - gated by settings.allow_exec

    def _exec_guard(self):
        from .executor import ExecDisabledError
        if not self.settings.allow_exec:
            raise ExecDisabledError(
                "code execution is disabled on this service "
                "(set CODE_INTEL_ALLOW_EXEC=true)")

    def _workspaces_dir(self, ctx: TenantContext, repo_id: str) -> str:
        import os
        return os.path.join(
            repo_root(self.settings.data_dir, ctx, repo_id), "workspaces")

    def workspace_create(self, ctx: TenantContext, repo_id: str) -> dict:
        from . import executor
        self._exec_guard()
        with self._heavy_slot(ctx):  # refresh may fetch/clone
            h = self._handles(ctx, repo_id, refresh_checkout=True)
        meta = executor.create_workspace(
            h.cfg.root, self._workspaces_dir(ctx, repo_id))
        return {"workspace_id": meta["workspace_id"],
                "git": meta["git"], "iteration": 0}

    # -- policy + audit helpers ----------------------------------------- #

    def _tenant_dir(self, ctx: TenantContext) -> str:
        from .tenancy import tenant_root
        return tenant_root(self.settings.data_dir, ctx)

    def get_policy(self, ctx: TenantContext):
        from . import policy_store
        return policy_store.load_policy(self._tenant_dir(ctx), self.settings)

    def set_policy(self, ctx: TenantContext, policy_dict: dict) -> dict:
        from . import audit, policy_store
        from .policy import Policy
        p = policy_store.save_policy(self._tenant_dir(ctx),
                                     Policy.from_dict(policy_dict))
        audit.record(self._tenant_dir(ctx), {
            "kind": "policy_update", "actor": ctx.subject,
            "policy": p.to_dict()})
        return p.to_dict()

    def audit_tail(self, ctx: TenantContext, limit: int = 100) -> list[dict]:
        from . import audit
        return audit.tail(self._tenant_dir(ctx), limit)

    def list_pending_approvals(self, ctx: TenantContext) -> list[dict]:
        from . import audit
        return audit.list_pending(self._tenant_dir(ctx))

    # Action kinds whose PENDING approval gates a code write or command
    # execution - i.e. the same two action types core marks `adminOnly:
    # true` for on its side (codeintel.workspace.apply / .run - see
    # ApplyCodeWorkspaceApprovalExecutor / RunCodeWorkspaceApprovalExecutor
    # payloads). Kept as a set in case a future ASK-gated action kind is
    # added that should NOT require admin (there is none today).
    _ADMIN_GATED_KINDS = frozenset({"apply", "run"})
    _ADMIN_ROLES = frozenset({
        "tenant-admin", "ROLE_tenant-admin",
        "platform-admin", "ROLE_platform-admin"})

    def _is_admin(self, ctx: TenantContext) -> bool:
        """Mirrors core's ApprovalController#enforceApproverAuthorization
        (requires ROLE_tenant-admin for an `adminOnly` approval). auth_mode
        =none has no real per-user identity (see auth.py's docstring - it's
        local-dev only), so it is treated as trusted, same as every other
        'none' shortcut in this service. An internal-token caller is core
        itself, which has already run that same admin gate before forwarding
        the request (see TenantContext.via_internal_token), so it is trusted
        here too - otherwise a platform-approved apply/run would dead-end on
        code-intel's own second, independent approval it can never satisfy."""
        if self.settings.auth_mode.lower() == "none":
            return True
        if ctx.via_internal_token:
            return True
        return bool(ctx.roles & self._ADMIN_ROLES)

    def resolve_approval(self, ctx: TenantContext, approval_id: str,
                         approved: bool) -> dict:
        from . import audit
        tdir = self._tenant_dir(ctx)
        pending = audit.get(tdir, approval_id)
        if pending is None:
            raise KeyError(approval_id)
        # Gate WHO may resolve an apply/run approval the same way core
        # gates WHO may approve/reject the equivalent adminOnly request on
        # its own Approvals screen - see ApprovalNotAuthorizedError's
        # docstring for why this matters: this endpoint is reachable
        # directly by any authenticated tenant member (the web module's
        # /code-intel page), completely outside core/approval.
        if (pending["request"].get("kind") in self._ADMIN_GATED_KINDS
                and not self._is_admin(ctx)):
            raise ApprovalNotAuthorizedError(approval_id)
        entry = audit.resolve(tdir, approval_id, approved, ctx.subject)
        if entry is None:
            raise KeyError(approval_id)
        audit.record(tdir, {
            "kind": "approval_resolved", "actor": ctx.subject,
            "approval_id": approval_id,
            "decision": "approved" if approved else "denied",
            "request_kind": entry["request"].get("kind")})
        return entry

    def workspace_apply(self, ctx: TenantContext, repo_id: str,
                        ws_id: str, files: list[dict],
                        approval_id: str | None = None) -> dict:
        from . import audit, executor
        from .policy import Decision, evaluate_apply
        self._exec_guard()
        self._handles(ctx, repo_id, refresh_checkout=False)  # 404 check
        tdir = self._tenant_dir(ctx)
        policy = self.get_policy(ctx)
        verdict = evaluate_apply(policy, files)

        approval = self._consume_approval(ctx, approval_id, "apply", verdict)
        if verdict.decision == Decision.DENY:
            audit.record(tdir, {"kind": "apply_denied", "repo_id": repo_id,
                                "workspace_id": ws_id, "actor": ctx.subject,
                                "reason": verdict.reason, "rule": verdict.rule,
                                "canary": verdict.canary})
            raise PolicyDeniedError(verdict.reason)
        if verdict.decision == Decision.ASK and not approval:
            if ctx.via_internal_token:
                # core already ran the platform's admin-gated approval before
                # this internal-token call reached us; don't dead-end on a
                # SECOND, independent human sign-off code-intel could only ask
                # for via its own disconnected /approvals flow. DENY (above)
                # still fires - this only short-circuits the ASK gate for the
                # trusted platform caller. See TenantContext.via_internal_token
                # and the code-intel-approval-reconciliation findings.
                audit.record(tdir, {"kind": "apply_platform_gated",
                                    "repo_id": repo_id, "workspace_id": ws_id,
                                    "actor": ctx.subject,
                                    "reason": verdict.reason,
                                    "canary": verdict.canary})
            else:
                pending = audit.create_pending(tdir, {
                    "kind": "apply", "repo_id": repo_id, "workspace_id": ws_id,
                    "files": [{"path": f.get("path"),
                               "bytes": len(f.get("content", "") or "")}
                              for f in files],
                    "policy_reason": verdict.reason})
                audit.record(tdir, {"kind": "apply_escalated", "repo_id": repo_id,
                                    "workspace_id": ws_id, "actor": ctx.subject,
                                    "approval_id": pending["approval_id"],
                                    "reason": verdict.reason,
                                    "canary": verdict.canary})
                raise ApprovalRequiredError(pending["approval_id"], verdict.reason)

        result = executor.apply_files(
            self._workspaces_dir(ctx, repo_id), ws_id, files)
        audit.record(tdir, {"kind": "apply_executed", "repo_id": repo_id,
                            "workspace_id": ws_id, "actor": ctx.subject,
                            "written": result.get("written"),
                            "deleted": result.get("deleted"),
                            "rule": verdict.rule,
                            "approval_id": approval_id,
                            "canary": verdict.canary})
        return {**result, "policy": {"decision": verdict.decision,
                                     "rule": verdict.rule}}

    def workspace_run(self, ctx: TenantContext, repo_id: str, ws_id: str,
                      command: str, timeout: int | None = None,
                      attempts: int = 1,
                      approval_id: str | None = None) -> dict:
        from . import audit, executor
        from .policy import Decision, clamp_timeout, evaluate_command
        self._exec_guard()
        self._handles(ctx, repo_id, refresh_checkout=False)
        tdir = self._tenant_dir(ctx)
        policy = self.get_policy(ctx)
        verdict = evaluate_command(policy, command)

        approval = self._consume_approval(ctx, approval_id, "run", verdict)
        if verdict.decision == Decision.DENY:
            audit.record(tdir, {"kind": "run_denied", "repo_id": repo_id,
                                "workspace_id": ws_id, "actor": ctx.subject,
                                "command": command, "reason": verdict.reason,
                                "rule": verdict.rule, "canary": verdict.canary})
            raise PolicyDeniedError(verdict.reason)
        if verdict.decision == Decision.ASK and not approval:
            if ctx.via_internal_token:
                # See workspace_apply's matching branch: the trusted platform
                # caller already carries core's own admin-gated approval, so
                # code-intel's ASK gate must not dead-end it. DENY still fires.
                audit.record(tdir, {"kind": "run_platform_gated",
                                    "repo_id": repo_id, "workspace_id": ws_id,
                                    "actor": ctx.subject, "command": command,
                                    "reason": verdict.reason,
                                    "canary": verdict.canary})
            else:
                pending = audit.create_pending(tdir, {
                    "kind": "run", "repo_id": repo_id, "workspace_id": ws_id,
                    "command": command, "policy_reason": verdict.reason})
                audit.record(tdir, {"kind": "run_escalated", "repo_id": repo_id,
                                    "workspace_id": ws_id, "actor": ctx.subject,
                                    "command": command,
                                    "approval_id": pending["approval_id"],
                                    "reason": verdict.reason,
                                    "canary": verdict.canary})
                raise ApprovalRequiredError(pending["approval_id"], verdict.reason)

        meta = executor.load_workspace(
            self._workspaces_dir(ctx, repo_id), ws_id)
        result = executor.run_command(
            meta["path"], command,
            timeout=clamp_timeout(policy, timeout,
                                  self.settings.exec_timeout_seconds),
            attempts=attempts, ws_id=ws_id)
        audit.record(tdir, {"kind": "run_executed", "repo_id": repo_id,
                            "workspace_id": ws_id, "actor": ctx.subject,
                            "command": command, "success": result["success"],
                            "exit_code": result.get("final", {}).get("exit_code"),
                            "rule": verdict.rule, "approval_id": approval_id,
                            "canary": verdict.canary})
        return {**result, "policy": {"decision": verdict.decision,
                                     "rule": verdict.rule,
                                     "canary": verdict.canary}}

    def _consume_approval(self, ctx, approval_id, kind, verdict) -> bool:
        """True if a valid, approved token unlocks this ASK action."""
        from . import audit
        if not approval_id:
            return False
        entry = audit.get(self._tenant_dir(ctx), approval_id)
        if (entry and entry["status"] == "approved"
                and entry["request"].get("kind") == kind):
            return True
        return False

    def workspace_diff(self, ctx: TenantContext, repo_id: str,
                       ws_id: str) -> dict:
        from . import executor
        self._exec_guard()
        self._handles(ctx, repo_id, refresh_checkout=False)
        return {"workspace_id": ws_id,
                "diff": executor.diff_workspace(
                    self._workspaces_dir(ctx, repo_id), ws_id)}

    def workspace_delete(self, ctx: TenantContext, repo_id: str,
                         ws_id: str) -> dict:
        from . import executor
        self._exec_guard()
        self._handles(ctx, repo_id, refresh_checkout=False)
        executor.delete_workspace(self._workspaces_dir(ctx, repo_id), ws_id)
        return {"deleted": ws_id}

    # ------------------------------------------------------------------ #
    # long-running processes (live-preview dev servers, e.g. `npm run dev`
    # for the app-builder feature) - a long-running process is at least as
    # privileged as a one-shot run_code, so this reuses the EXACT same
    # policy.evaluate_command gate / approval / audit path as
    # workspace_run, just with a "process_*" audit event kind so the two
    # are distinguishable in the trail. See executor.start_process for why
    # run_mode="local" is refused outright.

    def process_start(self, ctx: TenantContext, repo_id: str, ws_id: str,
                      command: str, run_mode: str | None = None,
                      image: str | None = None, port: int | None = None,
                      env_overrides: dict | None = None,
                      approval_id: str | None = None) -> dict:
        from . import audit, executor
        from .policy import Decision, evaluate_command
        self._exec_guard()
        self._handles(ctx, repo_id, refresh_checkout=False)  # 404 check
        tdir = self._tenant_dir(ctx)
        policy = self.get_policy(ctx)
        verdict = evaluate_command(policy, command)

        approval = self._consume_approval(ctx, approval_id, "run", verdict)
        if verdict.decision == Decision.DENY:
            audit.record(tdir, {"kind": "process_start_denied", "repo_id": repo_id,
                                "workspace_id": ws_id, "actor": ctx.subject,
                                "command": command, "reason": verdict.reason,
                                "rule": verdict.rule, "canary": verdict.canary})
            raise PolicyDeniedError(verdict.reason)
        if verdict.decision == Decision.ASK and not approval:
            if ctx.via_internal_token:
                # See workspace_run's matching branch: the trusted platform
                # caller already carries core's own admin-gated approval.
                audit.record(tdir, {"kind": "process_start_platform_gated",
                                    "repo_id": repo_id, "workspace_id": ws_id,
                                    "actor": ctx.subject, "command": command,
                                    "reason": verdict.reason,
                                    "canary": verdict.canary})
            else:
                pending = audit.create_pending(tdir, {
                    "kind": "run", "repo_id": repo_id, "workspace_id": ws_id,
                    "command": command, "policy_reason": verdict.reason,
                    "process": True})
                audit.record(tdir, {"kind": "process_start_escalated",
                                    "repo_id": repo_id, "workspace_id": ws_id,
                                    "actor": ctx.subject, "command": command,
                                    "approval_id": pending["approval_id"],
                                    "reason": verdict.reason,
                                    "canary": verdict.canary})
                raise ApprovalRequiredError(pending["approval_id"], verdict.reason)

        meta = executor.load_workspace(
            self._workspaces_dir(ctx, repo_id), ws_id)
        result = executor.start_process(
            meta["path"], command, run_mode=run_mode, image=image, port=port,
            env_overrides=env_overrides, ws_id=ws_id)
        audit.record(tdir, {"kind": "process_started", "repo_id": repo_id,
                            "workspace_id": ws_id, "actor": ctx.subject,
                            "command": command, "process_id": result["process_id"],
                            "run_mode": result["run_mode"], "rule": verdict.rule,
                            "approval_id": approval_id, "canary": verdict.canary})
        return {**result, "policy": {"decision": verdict.decision,
                                     "rule": verdict.rule}}

    def process_stop(self, ctx: TenantContext, repo_id: str, ws_id: str,
                     process_id: str) -> dict:
        from . import audit, executor
        self._exec_guard()
        self._handles(ctx, repo_id, refresh_checkout=False)  # 404 check
        executor.stop_process(process_id)
        audit.record(self._tenant_dir(ctx), {
            "kind": "process_stopped", "repo_id": repo_id,
            "workspace_id": ws_id, "actor": ctx.subject,
            "process_id": process_id})
        return {"stopped": process_id}

    def process_status(self, ctx: TenantContext, repo_id: str, ws_id: str,
                       process_id: str) -> dict:
        from . import executor
        self._exec_guard()
        self._handles(ctx, repo_id, refresh_checkout=False)  # 404 check
        return executor.process_status(process_id)

