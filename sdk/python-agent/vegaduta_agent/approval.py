"""Human approval: the gate between a model's intent and a real effect.

Mirrors ``io.vegaduta.agent.api.approval``. A call that requires approval is
recorded as an ``ApprovalRequest`` with a resumption — the callable that runs
the tool — instead of executing. Whoever approves (your UI, a chat command, a
ticket) calls ``approve(id, decided_by)``; the resumption then runs, its result
is journaled and stored on the request.
"""

from __future__ import annotations

import threading
import uuid
from collections.abc import Callable, Iterable
from dataclasses import dataclass, replace
from datetime import datetime, timedelta, timezone
from enum import Enum
from typing import Protocol

from .context import RunContext
from .errors import (
    ApprovalAlreadyDecidedException,
    ApprovalNotAuthorizedException,
    ApprovalNotFoundException,
)
from .tool_call import ToolCall


class ApprovalStatus(Enum):
    PENDING = "PENDING"
    APPROVED = "APPROVED"  # a human approved it; the action may run (or is running)
    REJECTED = "REJECTED"
    EXPIRED = "EXPIRED"
    EXECUTED = "EXECUTED"  # approved and the action ran successfully
    FAILED = "FAILED"  # approved but the action failed

    @property
    def decided(self) -> bool:
        return self is not ApprovalStatus.PENDING


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


@dataclass(frozen=True)
class ApprovalRequest:
    id: uuid.UUID
    action_type: str
    summary: str
    payload_json: str
    context: RunContext
    requested_by: str
    status: ApprovalStatus
    created_at: datetime
    expires_at: datetime
    decided_at: datetime | None = None
    decided_by: str | None = None
    decision_reason: str | None = None
    result_json: str | None = None

    @classmethod
    def pending(
        cls,
        action_type: str,
        summary: str,
        payload_json: str,
        context: RunContext,
        ttl: timedelta,
        requested_by: str = "agent",
    ) -> ApprovalRequest:
        now = _utcnow()
        return cls(
            id=uuid.uuid4(),
            action_type=action_type,
            summary=summary,
            payload_json=payload_json,
            context=context,
            requested_by=requested_by,
            status=ApprovalStatus.PENDING,
            created_at=now,
            expires_at=now + ttl,
        )


class ApprovalDecisionAuthorizer(Protocol):
    """Optional: who may decide a given request. Raise or return False to
    refuse; the default authorizer allows anyone who can reach the gate."""

    def may_decide(self, request: ApprovalRequest, decided_by: str) -> bool: ...


class ApprovalStore(Protocol):
    def save(self, request: ApprovalRequest, resumption: Callable[[], str] | None) -> None: ...

    def get(self, request_id: uuid.UUID) -> ApprovalRequest | None: ...

    def update(self, request: ApprovalRequest) -> None: ...

    def resumption_of(self, request_id: uuid.UUID) -> Callable[[], str] | None: ...

    def pending(self, tenant_id: str | None, limit: int) -> list[ApprovalRequest]: ...


class InMemoryApprovalStore:
    """Process-local store. Resumptions live only in memory, so an approval
    that outlives the process is recorded but cannot execute — persist through
    your own ``ApprovalStore`` (or re-issue the call) if that matters."""

    def __init__(self) -> None:
        self._requests: dict[uuid.UUID, ApprovalRequest] = {}
        self._resumptions: dict[uuid.UUID, Callable[[], str] | None] = {}
        self._lock = threading.Lock()

    def save(self, request: ApprovalRequest, resumption: Callable[[], str] | None) -> None:
        with self._lock:
            self._requests[request.id] = request
            self._resumptions[request.id] = resumption

    def get(self, request_id: uuid.UUID) -> ApprovalRequest | None:
        return self._requests.get(request_id)

    def update(self, request: ApprovalRequest) -> None:
        with self._lock:
            self._requests[request.id] = request

    def resumption_of(self, request_id: uuid.UUID) -> Callable[[], str] | None:
        return self._resumptions.get(request_id)

    def pending(self, tenant_id: str | None, limit: int) -> list[ApprovalRequest]:
        with self._lock:
            rows = [
                r
                for r in self._requests.values()
                if r.status is ApprovalStatus.PENDING
                and (tenant_id is None or r.context.tenant_id == tenant_id)
            ]
        rows.sort(key=lambda r: r.created_at)
        return rows[:limit]


class ApprovalGate:
    """The front door for human decisions. Thread-safe."""

    def __init__(
        self,
        store: ApprovalStore | None = None,
        authorizer: ApprovalDecisionAuthorizer | None = None,
        default_ttl: timedelta = timedelta(hours=24),
        on_decision: Callable[[ApprovalRequest], None] | None = None,
    ) -> None:
        self._store = store or InMemoryApprovalStore()
        self._authorizer = authorizer
        self._default_ttl = default_ttl
        self._on_decision = on_decision
        self._lock = threading.Lock()

    # -- recording ----------------------------------------------------

    def request_for_tool(
        self,
        call: ToolCall,
        context: RunContext,
        summary: str,
        resumption: Callable[[], str] | None = None,
        ttl: timedelta | None = None,
    ) -> ApprovalRequest:
        """Records a tool call that must wait for a human. ``resumption`` is
        what ``approve`` runs; omit it only if the action is executed by an
        external workflow, not this process."""
        request = ApprovalRequest.pending(
            action_type=f"tool:{call.tool_name}",
            summary=summary,
            payload_json=call.arguments_json,
            context=context,
            ttl=ttl or self._default_ttl,
            requested_by=context.principal or "agent",
        )
        self._store.save(request, resumption)
        return request

    def request(
        self,
        action_type: str,
        summary: str,
        payload_json: str,
        context: RunContext,
        resumption: Callable[[], str] | None = None,
        ttl: timedelta | None = None,
    ) -> ApprovalRequest:
        """Records any action (not only a tool call) that must wait for a human."""
        request = ApprovalRequest.pending(
            action_type=action_type,
            summary=summary,
            payload_json=payload_json,
            context=context,
            ttl=ttl or self._default_ttl,
            requested_by=context.principal or "agent",
        )
        self._store.save(request, resumption)
        return request

    # -- deciding -----------------------------------------------------

    def _must_get(self, request_id: uuid.UUID | str) -> ApprovalRequest:
        rid = uuid.UUID(str(request_id)) if not isinstance(request_id, uuid.UUID) else request_id
        request = self._store.get(rid)
        if request is None:
            raise ApprovalNotFoundException(f"no approval request {rid}")
        if request.status is ApprovalStatus.PENDING and _utcnow() > request.expires_at:
            request = replace(request, status=ApprovalStatus.EXPIRED, decided_at=_utcnow())
            self._store.update(request)
        return request

    def _check_decidable(self, request: ApprovalRequest, decided_by: str) -> None:
        if request.status.decided:
            raise ApprovalAlreadyDecidedException(f"approval {request.id} is already {request.status.value}")
        if self._authorizer is not None and not self._authorizer.may_decide(request, decided_by):
            raise ApprovalNotAuthorizedException(f"{decided_by} may not decide approval {request.id}")

    def approve(self, request_id: uuid.UUID | str, decided_by: str) -> ApprovalRequest:
        """Approves and — when a resumption was stored — executes the action,
        recording the result (or the failure) on the request."""
        with self._lock:
            request = self._must_get(request_id)
            self._check_decidable(request, decided_by)
            request = replace(
                request, status=ApprovalStatus.APPROVED, decided_at=_utcnow(), decided_by=decided_by
            )
            self._store.update(request)
            resumption = self._store.resumption_of(request.id)
        self._notify(request)
        if resumption is not None:
            try:
                result = resumption()
            except Exception as exc:
                with self._lock:
                    request = replace(request, status=ApprovalStatus.FAILED, result_json=str(exc))
                    self._store.update(request)
            else:
                with self._lock:
                    request = replace(request, status=ApprovalStatus.EXECUTED, result_json=result)
                    self._store.update(request)
        return request

    def reject(
        self, request_id: uuid.UUID | str, decided_by: str, reason: str | None = None
    ) -> ApprovalRequest:
        with self._lock:
            request = self._must_get(request_id)
            self._check_decidable(request, decided_by)
            request = replace(
                request,
                status=ApprovalStatus.REJECTED,
                decided_at=_utcnow(),
                decided_by=decided_by,
                decision_reason=reason,
            )
            self._store.update(request)
        self._notify(request)
        return request

    # -- queries --------------------------------------------------------

    def find(self, request_id: uuid.UUID | str) -> ApprovalRequest | None:
        rid = uuid.UUID(str(request_id)) if not isinstance(request_id, uuid.UUID) else request_id
        return self._store.get(rid)

    def pending(self, tenant_id: str | None = None, limit: int = 50) -> list[ApprovalRequest]:
        return self._store.pending(tenant_id, limit)

    def _notify(self, request: ApprovalRequest) -> None:
        if self._on_decision is not None:
            self._on_decision(request)


class FourEyesAuthorizer:
    """The requester may never decide their own request — the two-person rule
    a payments or release workflow typically needs. Plug in as
    ``ApprovalGate(authorizer=FourEyesAuthorizer())``; combine with your own
    authorizer for role checks."""

    def may_decide(self, request: ApprovalRequest, decided_by: str) -> bool:
        return decided_by != request.requested_by


class _StaticAuthorizer:
    """A fixed allow-list of decider ids."""

    def __init__(self, deciders: Iterable[str]) -> None:
        self._deciders = frozenset(deciders)

    def may_decide(self, request: ApprovalRequest, decided_by: str) -> bool:
        return decided_by in self._deciders


def allowed_deciders(*decider_ids: str) -> ApprovalDecisionAuthorizer:
    """An authorizer admitting exactly the given decider ids."""
    return _StaticAuthorizer(decider_ids)


# -- signed decision receipts ------------------------------------------------

def _receipt_fields(request: ApprovalRequest) -> dict[str, str]:
    return {
        "approvalId": str(request.id),
        "actionType": request.action_type,
        "decision": request.status.value,
        "decidedBy": request.decided_by or "",
        "decidedAt": request.decided_at.isoformat() if request.decided_at else "",
        "runId": request.context.run_id,
    }


def _receipt_canonical(fields: dict[str, str]) -> str:
    import json

    return json.dumps(fields, sort_keys=True, separators=(",", ":"))


def sign_receipt(request: ApprovalRequest, signer) -> dict[str, str]:
    """A signed decision receipt: who decided what, on which request, when —
    the artifact an audit or a dispute reads back. ``signer`` is anything with
    ``sign(bytes) -> bytes`` (``evidence.HmacSha256``, or an Ed25519 signer
    from your crypto library)."""
    import base64

    fields = _receipt_fields(request)
    digest = _receipt_canonical(fields).encode("utf-8")
    receipt = dict(fields)
    receipt["signature"] = base64.b64encode(signer.sign(digest)).decode("ascii")
    return receipt


def verify_receipt(receipt: dict[str, str], verifier) -> bool:
    """Re-checks a receipt's signature. ``verifier`` is anything with
    ``verify(data: bytes, signature: bytes) -> bool``."""
    import base64

    sig = receipt.get("signature")
    if not sig:
        return False
    fields = {k: v for k, v in receipt.items() if k != "signature"}
    try:
        raw = base64.b64decode(sig, validate=True)
    except Exception:
        return False
    return verifier.verify(_receipt_canonical(fields).encode("utf-8"), raw)
