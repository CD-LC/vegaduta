"""Errors CodeIntelService raises; main.py maps each one to an HTTP status."""

from __future__ import annotations


class RepoBusyError(RuntimeError):
    pass


class HeavyJobBusyError(RuntimeError):
    """Process-wide or per-tenant heavy-job cap reached. HTTP 429."""

    def __init__(self, message: str, retry_after: int = 30):
        super().__init__(message)
        self.retry_after = retry_after


class RepoNotFoundError(KeyError):
    pass


class PolicyDeniedError(PermissionError):
    """A guardrail hard-denied the action (403)."""


class ApprovalRequiredError(PermissionError):
    """An ASK-gated action needs human approval first (202-style, 428)."""

    def __init__(self, approval_id: str, reason: str):
        super().__init__(reason)
        self.approval_id = approval_id
        self.reason = reason


class ApprovalNotAuthorizedError(PermissionError):
    """Caller lacks the admin role required to resolve an apply/run
    approval (403).

    core/approval marks the SAME two action types (codeintel.workspace.apply
    / .run) `adminOnly: true`, so `ApprovalController#enforceApproverAuthorization`
    requires ROLE_tenant-admin before anyone may approve/reject them there
    (see ApplyCodeChangesToolCallback's javadoc). This service has its own,
    independent /approvals endpoints - reachable directly by any
    authenticated tenant member from the web module's /code-intel page
    (that page talks to this service with the user's own Keycloak token,
    not proxied through core - see code-intel-approval-reconciliation/
    docs/code-intel-approval-reconciliation/RECONCILIATION-FINDINGS-2026-07-20.md).
    Without this check, a
    non-admin tenant member could self-approve their own escalated
    apply/run here and it would never touch core/approval or the
    platform's real Approvals screen at all - see resolve_approval."""

    def __init__(self, approval_id: str):
        super().__init__(f"resolving approval {approval_id} requires an admin role")
        self.approval_id = approval_id

