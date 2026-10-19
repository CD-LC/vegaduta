"""Durable stores on the standard-library sqlite3 driver — the Python
counterpart of the Java ``agent-jdbc`` module.

One file, no dependency, safe across threads. The same three stores the Java
side persists (approvals, audit, effect journal) can all share one database
file — or none at all: pass a path per store, or ``:memory:`` for tests.

- ``SqliteApprovalStore``: requests survive a restart. Resumptions are
  callables and cannot persist, so an approved-but-not-executed request is
  reloadable but must be re-issued by the caller — same contract as the Java
  ``JdbcApprovalStore``.
- ``SqliteAuditSink``: appends one row per event with the full flat map as
  JSON, so a chain verifier can rebuild it (see ``evidence.verify``).
- ``SqliteEffectJournal`` lives in ``journal.py`` next to the in-memory one.

DDL is additive and idempotent; the schema is namespaced ``vegaduta_*``.
"""

from __future__ import annotations

import json
import sqlite3
import threading
import uuid
from collections.abc import Callable
from datetime import datetime

from .approval import ApprovalRequest, ApprovalStatus
from .audit import AuditEvent, AuditOutcome
from .context import RunContext
from .risk import AsiRisk

_SCHEMA_APPROVALS = """
CREATE TABLE IF NOT EXISTS vegaduta_approvals (
    id TEXT PRIMARY KEY,
    action_type TEXT NOT NULL,
    summary TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    status TEXT NOT NULL,
    run_id TEXT NOT NULL,
    tenant_id TEXT,
    agent_id TEXT,
    session_id TEXT,
    correlation_id TEXT,
    requested_by TEXT NOT NULL,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    decided_at TEXT,
    decided_by TEXT,
    decision_reason TEXT,
    result_json TEXT,
    context_json TEXT NOT NULL
)"""

_SCHEMA_AUDIT = """
CREATE TABLE IF NOT EXISTS vegaduta_audit (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    id TEXT NOT NULL,
    created_at TEXT NOT NULL,
    actor_id TEXT NOT NULL,
    action_type TEXT NOT NULL,
    target_type TEXT NOT NULL,
    target_id TEXT NOT NULL,
    outcome TEXT NOT NULL,
    run_id TEXT NOT NULL,
    tenant_id TEXT,
    agent_id TEXT,
    session_id TEXT,
    correlation_id TEXT,
    detail TEXT,
    risks TEXT NOT NULL,
    attributes_json TEXT NOT NULL
)"""


def _iso(at: datetime | None) -> str | None:
    return None if at is None else at.isoformat()


def _parse(text: str | None) -> datetime | None:
    return None if text is None else datetime.fromisoformat(text)


def _parse_ctx(row) -> RunContext:
    payload = json.loads(row["context_json"])
    return RunContext(
        run_id=row["run_id"],
        tenant_id=row["tenant_id"],
        agent_id=row["agent_id"],
        session_id=row["session_id"],
        correlation_id=row["correlation_id"],
        principal=payload.get("principal"),
        attributes=payload.get("attributes") or {},
    )


class SqliteApprovalStore:
    """Durable ``ApprovalStore``. Pending requests reload across restarts;
    resumptions do not — ``resumption_of`` returns ``None`` after a reload,
    matching the Java JDBC store's contract."""

    def __init__(self, path: str = ":memory:") -> None:
        self._conn = sqlite3.connect(path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._resumptions: dict[uuid.UUID, Callable[[], str] | None] = {}
        self._lock = threading.Lock()
        with self._lock, self._conn:
            self._conn.execute(_SCHEMA_APPROVALS)

    def save(self, request: ApprovalRequest, resumption: Callable[[], str] | None) -> None:
        with self._lock, self._conn:
            self._insert(request)
            self._resumptions[request.id] = resumption

    def _insert(self, request: ApprovalRequest) -> None:
        ctx = request.context
        self._conn.execute(
            "INSERT OR REPLACE INTO vegaduta_approvals VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (
                str(request.id),
                request.action_type,
                request.summary,
                request.payload_json,
                request.status.value,
                ctx.run_id,
                ctx.tenant_id,
                ctx.agent_id,
                ctx.session_id,
                ctx.correlation_id,
                request.requested_by,
                request.created_at.isoformat(),
                request.expires_at.isoformat(),
                _iso(request.decided_at),
                request.decided_by,
                request.decision_reason,
                request.result_json,
                json.dumps(
                    {"principal": ctx.principal, "attributes": dict(ctx.attributes)}
                ),
            ),
        )

    def _row_to_request(self, row) -> ApprovalRequest:
        return ApprovalRequest(
            id=uuid.UUID(row["id"]),
            action_type=row["action_type"],
            summary=row["summary"],
            payload_json=row["payload_json"],
            context=_parse_ctx(row),
            requested_by=row["requested_by"],
            status=ApprovalStatus[row["status"]],
            created_at=_parse(row["created_at"]),
            expires_at=_parse(row["expires_at"]),
            decided_at=_parse(row["decided_at"]),
            decided_by=row["decided_by"],
            decision_reason=row["decision_reason"],
            result_json=row["result_json"],
        )

    def get(self, request_id: uuid.UUID) -> ApprovalRequest | None:
        row = self._conn.execute(
            "SELECT * FROM vegaduta_approvals WHERE id = ?", (str(request_id),)
        ).fetchone()
        return None if row is None else self._row_to_request(row)

    def update(self, request: ApprovalRequest) -> None:
        with self._lock, self._conn:
            self._insert(request)

    def resumption_of(self, request_id: uuid.UUID) -> Callable[[], str] | None:
        return self._resumptions.get(request_id)

    def pending(self, tenant_id: str | None, limit: int) -> list[ApprovalRequest]:
        if tenant_id is None:
            rows = self._conn.execute(
                "SELECT * FROM vegaduta_approvals WHERE status = 'PENDING' "
                "ORDER BY created_at LIMIT ?",
                (limit,),
            ).fetchall()
        else:
            rows = self._conn.execute(
                "SELECT * FROM vegaduta_approvals WHERE status = 'PENDING' AND tenant_id = ? "
                "ORDER BY created_at LIMIT ?",
                (tenant_id, limit),
            ).fetchall()
        return [self._row_to_request(r) for r in rows]

    def close(self) -> None:
        self._conn.close()


class SqliteAuditSink:
    """Appends each event as one row; ``events()`` rebuilds them so a chain
    written through ``HashChainedAuditSink(SqliteAuditSink(path))`` can be
    verified later by ``evidence.verify``."""

    def __init__(self, path: str = ":memory:") -> None:
        self._conn = sqlite3.connect(path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._lock = threading.Lock()
        with self._lock, self._conn:
            self._conn.execute(_SCHEMA_AUDIT)

    def record(self, event: AuditEvent) -> None:
        ctx = event.context
        with self._lock, self._conn:
            self._conn.execute(
                "INSERT INTO vegaduta_audit (id, created_at, actor_id, action_type, target_type,"
                " target_id, outcome, run_id, tenant_id, agent_id, session_id, correlation_id,"
                " detail, risks, attributes_json) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (
                    str(event.id),
                    _iso(event.created_at),
                    event.actor_id,
                    event.action_type,
                    event.target_type,
                    event.target_id,
                    event.outcome.value,
                    ctx.run_id,
                    ctx.tenant_id,
                    ctx.agent_id,
                    ctx.session_id,
                    ctx.correlation_id,
                    event.detail,
                    ",".join(sorted(r.id for r in event.risks)),
                    json.dumps(dict(event.attributes)),
                ),
            )

    def events(self, run_id: str | None = None) -> list[AuditEvent]:
        sql = "SELECT * FROM vegaduta_audit"
        params: tuple = ()
        if run_id is not None:
            sql += " WHERE run_id = ?"
            params = (run_id,)
        sql += " ORDER BY seq"
        rows = self._conn.execute(sql, params).fetchall()
        return [self._row_to_event(r) for r in rows]

    def _row_to_event(self, row) -> AuditEvent:
        risks = frozenset(AsiRisk[r] for r in row["risks"].split(",") if r)
        return AuditEvent(
            id=uuid.UUID(row["id"]),
            created_at=_parse(row["created_at"]),
            actor_id=row["actor_id"],
            action_type=row["action_type"],
            target_type=row["target_type"],
            target_id=row["target_id"],
            outcome=AuditOutcome[row["outcome"]],
            context=RunContext(
                run_id=row["run_id"],
                tenant_id=row["tenant_id"],
                agent_id=row["agent_id"],
                session_id=row["session_id"],
                correlation_id=row["correlation_id"],
            ),
            detail=row["detail"],
            risks=risks,
            attributes=json.loads(row["attributes_json"]),
        )

    def close(self) -> None:
        self._conn.close()
