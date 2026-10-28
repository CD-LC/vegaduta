"""Human approval: the gate between a model's intent and a real effect.

Mirrors ``io.vegaduta.agent.api.approval``. A call that requires approval is
recorded as an ``ApprovalRequest`` with a resumption — the callable that runs
the tool — instead of executing. Whoever approves (your UI, a chat command, a
ticket) calls ``approve(id, decided_by)``; the resumption then runs, its result
is journaled and stored on the request.
"""

from __future__ import annotations

import inspect
import re
import threading
import uuid
import warnings
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
    """Who may decide a given request. Raise or return False to refuse. The
    gate's default is ``FourEyesAuthorizer`` (the requester may not decide
    their own request); pass ``allow_any`` to opt out explicitly."""

    def may_decide(self, request: ApprovalRequest, decided_by: str) -> bool: ...


class ApprovalStore(Protocol):
    """Persistence for approval requests.

    Compare-and-set contract. Every decision (approve, reject, expire, record
    the result) is written with ``update(request, expected_status)``, and the
    store MUST apply it atomically only when the stored status still equals
    ``expected_status``, returning ``False`` otherwise (for SQL:
    ``UPDATE ... WHERE id=? AND status=?`` and check the affected row count).
    That is what stops two gate instances or two processes sharing one store
    from both approving the same request and running its resumption twice; the
    gate's in-process lock cannot. ``expected_status=None`` is an unconditional
    write for callers that do not need the guard. A store whose ``update`` does
    not accept ``expected_status`` still works, but ``ApprovalGate`` warns that
    multi-process safety is lost and falls back to unconditional writes.
    ``InMemoryApprovalStore`` and ``SqliteApprovalStore`` are the references."""

    def save(self, request: ApprovalRequest, resumption: Callable[[], str] | None) -> None: ...

    def get(self, request_id: uuid.UUID) -> ApprovalRequest | None: ...

    def update(self, request: ApprovalRequest, expected_status: ApprovalStatus | None = None) -> bool:
        """Persist ``request``; with ``expected_status`` only if the stored
        status equals it. Returns whether the write was applied."""
        ...

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

    def update(self, request: ApprovalRequest, expected_status: ApprovalStatus | None = None) -> bool:
        """Stores ``request``. With ``expected_status`` this is a
        compare-and-set: the write happens only if the stored status still
        equals it, and ``False`` is returned otherwise. Without it the write
        is unconditional (always ``True``)."""
        with self._lock:
            if expected_status is not None:
                current = self._requests.get(request.id)
                if current is None or current.status is not expected_status:
                    return False
            self._requests[request.id] = request
            return True

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


class _AllowAny:
    """Permits any decider, including the requester. The explicit opt-out of
    the two-person rule: ``ApprovalGate(authorizer=allow_any)``."""

    def may_decide(self, request: ApprovalRequest, decided_by: str) -> bool:
        return True


allow_any: ApprovalDecisionAuthorizer = _AllowAny()

_MAX_FAILURE_MESSAGE = 200
_SECRET_RE = re.compile(
    r"(?i)(bearer\s+[A-Za-z0-9._~+/=-]+"
    r"|(?:api[_-]?key|token|secret|password|passwd|authorization)\s*[=:]\s*(?:(?:bearer|basic)\s+)?\S+"
    r"|\b[A-Za-z0-9_-]{32,}\b)"
)


def _supports_cas(store: object) -> bool:
    try:
        params = inspect.signature(store.update).parameters  # type: ignore[attr-defined]
    except (TypeError, ValueError, AttributeError):
        return False
    return "expected_status" in params or any(
        p.kind is inspect.Parameter.VAR_KEYWORD for p in params.values()
    )


def _failure_result(exc: BaseException) -> str:
    """Exception type plus a bounded, redacted message, never the raw text,
    which can carry secrets, PII or provider payloads."""
    message = _SECRET_RE.sub("[redacted]", str(exc)).replace("\n", " ").strip()
    if len(message) > _MAX_FAILURE_MESSAGE:
        message = message[:_MAX_FAILURE_MESSAGE] + "..."
    return f"{type(exc).__name__}: {message}" if message else type(exc).__name__


class ApprovalGate:
    """The front door for human decisions. Thread-safe within one process (see
    ``ApprovalStore`` for the multi-process contract).

    The default authorizer is ``FourEyesAuthorizer``: the principal that
    requested an action cannot approve or reject it. Pass
    ``authorizer=allow_any`` to opt out (single-operator tools, tests), or your
    own authorizer for role checks."""

    def __init__(
        self,
        store: ApprovalStore | None = None,
        authorizer: ApprovalDecisionAuthorizer | None = None,
        default_ttl: timedelta = timedelta(hours=24),
        on_decision: Callable[[ApprovalRequest], None] | None = None,
    ) -> None:
        self._store = store or InMemoryApprovalStore()
        self._cas = _supports_cas(self._store)
        if not self._cas:
            warnings.warn(
                f"{type(self._store).__name__}.update() does not accept expected_status; approval "
                "decisions are not compare-and-set, so two processes sharing this store can both "
                "approve (and execute) one request. See ApprovalStore.",
                RuntimeWarning,
                stacklevel=2,
            )
        self._authorizer = authorizer if authorizer is not None else FourEyesAuthorizer()
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

    def _cas_update(self, request: ApprovalRequest, expected: ApprovalStatus) -> bool:
        """Writes ``request`` only if the store still holds ``expected``
        (compare-and-set); see ``ApprovalStore``."""
        if self._cas:
            return bool(self._store.update(request, expected))
        current = self._store.get(request.id)
        if current is None or current.status is not expected:
            return False
        self._store.update(request)
        return True

    def _must_get(self, request_id: uuid.UUID | str) -> ApprovalRequest:
        rid = uuid.UUID(str(request_id)) if not isinstance(request_id, uuid.UUID) else request_id
        request = self._store.get(rid)
        if request is None:
            raise ApprovalNotFoundException(f"no approval request {rid}")
        if request.status is ApprovalStatus.PENDING and _utcnow() > request.expires_at:
            expired = replace(request, status=ApprovalStatus.EXPIRED, decided_at=_utcnow())
            if self._cas_update(expired, ApprovalStatus.PENDING):
                return expired
            # someone else decided (or expired) it first: act on what is stored
            request = self._store.get(rid) or request
        return request

    def _decide(self, request: ApprovalRequest, decided: ApprovalRequest) -> ApprovalRequest:
        """PENDING -> ``decided`` as a compare-and-set; losing the race is
        reported as already decided, never executed twice."""
        if not self._cas_update(decided, ApprovalStatus.PENDING):
            latest = self._store.get(request.id) or request
            raise ApprovalAlreadyDecidedException(f"approval {request.id} is already {latest.status.value}")
        return decided

    def _check_decidable(self, request: ApprovalRequest, decided_by: str) -> None:
        if request.status.decided:
            raise ApprovalAlreadyDecidedException(f"approval {request.id} is already {request.status.value}")
        if not self._authorizer.may_decide(request, decided_by):
            raise ApprovalNotAuthorizedException(f"{decided_by} may not decide approval {request.id}")

    def approve(self, request_id: uuid.UUID | str, decided_by: str) -> ApprovalRequest:
        """Approves and — when a resumption was stored — executes the action,
        recording the result (or the failure) on the request."""
        with self._lock:
            request = self._must_get(request_id)
            self._check_decidable(request, decided_by)
            request = self._decide(
                request,
                replace(request, status=ApprovalStatus.APPROVED, decided_at=_utcnow(), decided_by=decided_by),
            )
            resumption = self._store.resumption_of(request.id)
        self._notify(request)
        if resumption is not None:
            try:
                result = resumption()
            except Exception as exc:
                request = self._finish(
                    request, replace(request, status=ApprovalStatus.FAILED, result_json=_failure_result(exc))
                )
            else:
                request = self._finish(
                    request, replace(request, status=ApprovalStatus.EXECUTED, result_json=result)
                )
        return request

    def _finish(self, approved: ApprovalRequest, finished: ApprovalRequest) -> ApprovalRequest:
        """APPROVED -> EXECUTED/FAILED. Only the caller that won the approval
        gets here, so the guard should always hold; if something else moved the
        request meanwhile, report what is stored instead of overwriting it."""
        with self._lock:
            if self._cas_update(finished, ApprovalStatus.APPROVED):
                return finished
            return self._store.get(approved.id) or approved

    def reject(
        self, request_id: uuid.UUID | str, decided_by: str, reason: str | None = None
    ) -> ApprovalRequest:
        with self._lock:
            request = self._must_get(request_id)
            self._check_decidable(request, decided_by)
            request = self._decide(
                request,
                replace(
                    request,
                    status=ApprovalStatus.REJECTED,
                    decided_at=_utcnow(),
                    decided_by=decided_by,
                    decision_reason=reason,
                ),
            )
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
