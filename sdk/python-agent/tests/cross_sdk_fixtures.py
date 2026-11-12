"""Builds the Python half of the shared cross-SDK fixtures in
``sdk/testdata/cross-sdk``.

Run ``python tests/cross_sdk_fixtures.py`` from ``sdk/python-agent`` to
regenerate ``python-chain.jsonl`` / ``python-chain.anchors.json`` and
``python-mandates.txt``. The output is deterministic (fixed ids, timestamps and
HMAC key), so ``test_cross_sdk.py`` also checks that regenerating reproduces the
committed bytes. The Java half (``java-*``) is written by
``CrossSdkFixtureTest`` in ``sdk/java/agent-core`` with
``-Dvegaduta.fixtures.regenerate=true``; each SDK verifies both halves.

File format. A chain is JSON Lines; every line is one audit event as a flat
object of strings: the event's top-level fields under their own names
(``id``, ``createdAt``, ``actorId``, ``actionType``, ``targetType``,
``targetId``, ``outcome``, ``detail``, ``runId``, ``tenantId``, ``agentId``,
``sessionId``, ``correlationId``; absent when null), ``risks`` as the sorted
comma-joined ASI ids, and every attribute (``chain.*`` included) under
``attr.<key>``. The anchors file is a flat object of strings:
``firstSeq``, ``firstPrev``, ``lastSeq``, ``headHash``. A mandates file has one
encoded mandate per line, root first.
"""

from __future__ import annotations

import json
import uuid
from datetime import datetime, timezone
from pathlib import Path

from vegaduta_agent import (
    AsiRisk,
    AuditEvent,
    AuditOutcome,
    HashChainedAuditSink,
    HmacSha256,
    InMemoryAuditSink,
    RiskLabel,
    RunContext,
)
from vegaduta_agent.evidence import _instant_str, evidence_bundle
from vegaduta_agent.mandates import Mandate

FIXTURE_DIR = Path(__file__).resolve().parents[2] / "testdata" / "cross-sdk"
HMAC_KEY = b"cross-sdk-fixture-hmac-key-0001"  # test-only, 31 bytes


def _utc(y, mo, d, h, mi, s, micro=0) -> datetime:
    return datetime(y, mo, d, h, mi, s, micro, tzinfo=timezone.utc)


def source_events() -> list[AuditEvent]:
    """Unchained events chosen to stress the canonical form: escapes, control
    characters, non-ASCII and non-BMP text, absent vs present optionals,
    whole-second / millisecond / microsecond timestamps, an attribute named like
    a top-level field, and attributes that look like chain keys."""
    full = RunContext(
        run_id="run-1", tenant_id="acme", agent_id="payer", session_id="s-1", correlation_id="corr-9"
    )
    bare = RunContext(run_id="run-2")
    return [
        AuditEvent(
            action_type="tool.call",
            target_type="tool",
            target_id="pay_invoice",
            context=full,
            outcome=AuditOutcome.SUCCESS,
            actor_id="alice",
            detail='paid "INV-1" \\ path\nline2\ttab \x01 café ☕ \U0001f600',
            risks=frozenset({AsiRisk.ASI02, AsiRisk.ASI09}),
            attributes={"risk": "HIGH", "callId": "c1", "actionType": "shadow"},
            id=uuid.UUID("00000000-0000-4000-8000-000000000001"),
            created_at=_utc(2026, 1, 1, 0, 0, 0),
        ),
        AuditEvent(
            action_type="approval.requested",
            target_type="approval",
            target_id="a-1",
            context=bare,
            outcome=AuditOutcome.PENDING_APPROVAL,
            id=uuid.UUID("00000000-0000-4000-8000-000000000002"),
            created_at=_utc(2026, 1, 1, 0, 0, 1, 250000),
        ),
        AuditEvent(
            action_type="approval.decided",
            target_type="approval",
            target_id="a-1",
            context=full,
            outcome=AuditOutcome.APPROVED,
            actor_id="bob",
            detail="approved by bob",
            risks=frozenset({AsiRisk.ASI09}),
            attributes={"status": "APPROVED", "chain.fake": "kept", "attr.x": "nested-looking"},
            id=uuid.UUID("00000000-0000-4000-8000-000000000003"),
            created_at=_utc(2026, 1, 1, 0, 0, 2, 123456),
        ),
        AuditEvent(
            action_type="tool.call",
            target_type="tool",
            target_id="send_email",
            context=full,
            outcome=AuditOutcome.DENIED,
            detail="",
            id=uuid.UUID("00000000-0000-4000-8000-000000000004"),
            created_at=_utc(2026, 1, 1, 0, 0, 3, 7000),
        ),
        AuditEvent(
            action_type="halt.engaged",
            target_type="halt",
            target_id="global",
            context=bare,
            outcome=AuditOutcome.WARNING,
            actor_id="ops",
            detail="stop — 日本語",
            id=uuid.UUID("00000000-0000-4000-8000-000000000005"),
            created_at=_utc(2026, 1, 1, 0, 0, 4),
        ),
        AuditEvent(
            action_type="tool.call",
            target_type="tool",
            target_id="pay_invoice",
            context=full,
            outcome=AuditOutcome.SUCCESS,
            actor_id="alice",
            detail="last",
            risks=frozenset({AsiRisk.ASI02}),
            attributes={"callId": "c6", "approvalId": "a-1"},
            id=uuid.UUID("00000000-0000-4000-8000-000000000006"),
            created_at=_utc(2026, 1, 1, 0, 0, 5, 999999),
        ),
    ]


def build_chain() -> list[AuditEvent]:
    store = InMemoryAuditSink()
    sink = HashChainedAuditSink(store, HmacSha256(HMAC_KEY))
    for event in source_events():
        sink.record(event)
    return store.events


# -- fixture (de)serialisation ------------------------------------------------------


def event_to_line(event: AuditEvent) -> dict[str, str]:
    line: dict[str, str] = {
        "id": str(event.id),
        "createdAt": _instant_str(event.created_at),
        "actorId": event.actor_id,
        "actionType": event.action_type,
        "targetType": event.target_type,
        "targetId": event.target_id,
        "outcome": event.outcome.name,
        "runId": event.context.run_id,
    }
    if event.detail is not None:
        line["detail"] = event.detail
    for key, value in (
        ("tenantId", event.context.tenant_id),
        ("agentId", event.context.agent_id),
        ("sessionId", event.context.session_id),
        ("correlationId", event.context.correlation_id),
    ):
        if value is not None:
            line[key] = value
    if event.risks:
        line["risks"] = ",".join(sorted(r.id for r in event.risks))
    for key, value in event.attributes.items():
        line["attr." + key] = str(value)
    return line


def line_to_event(line: dict[str, str]) -> AuditEvent:
    ctx = RunContext(
        run_id=line["runId"],
        tenant_id=line.get("tenantId"),
        agent_id=line.get("agentId"),
        session_id=line.get("sessionId"),
        correlation_id=line.get("correlationId"),
    )
    risks = frozenset(AsiRisk[r] for r in line["risks"].split(",")) if line.get("risks") else frozenset()
    attrs = {k[len("attr.") :]: v for k, v in line.items() if k.startswith("attr.")}
    return AuditEvent(
        action_type=line["actionType"],
        target_type=line["targetType"],
        target_id=line["targetId"],
        context=ctx,
        outcome=AuditOutcome[line["outcome"]],
        actor_id=line["actorId"],
        detail=line.get("detail"),
        risks=risks,
        attributes=attrs,
        id=uuid.UUID(line["id"]),
        created_at=datetime.fromisoformat(line["createdAt"].replace("Z", "+00:00")),
    )


def read_chain(path: Path) -> list[AuditEvent]:
    return [line_to_event(json.loads(raw)) for raw in path.read_text(encoding="utf-8").splitlines() if raw]


def chain_jsonl(events: list[AuditEvent]) -> str:
    # ensure_ascii=True so the file also exercises \uXXXX (and surrogate-pair) decoding in Java
    return "".join(json.dumps(event_to_line(e), ensure_ascii=True) + "\n" for e in events)


def anchors_json(events: list[AuditEvent]) -> str:
    bundle = evidence_bundle(events, "fixture")
    assert bundle.verification.intact, bundle.verification
    anchors = {
        "firstSeq": str(bundle.first_seq),
        "firstPrev": bundle.first_prev,
        "lastSeq": str(bundle.last_seq),
        "headHash": bundle.head_hash,
    }
    return json.dumps(anchors, indent=2) + "\n"


# -- mandates ---------------------------------------------------------------------


def build_mandates() -> list[Mandate]:
    root = Mandate.root(
        "root-authority",
        "orchestrator",
        allowed_tools=["pay_*", "read_*", "send_email"],
        max_risk=RiskLabel.HIGH,
        max_depth=3,
        expires_at=_utc(2030, 1, 1, 0, 0, 0),
        principal="alice@example.com",
        id="00000000-0000-4000-8000-0000000000a1",
    )
    child = root.derive(
        "payer",
        ["pay_invoice", "read_*"],
        RiskLabel.MEDIUM,
        _utc(2029, 6, 30, 12, 30, 15, 250000),
        id="00000000-0000-4000-8000-0000000000a2",
    )
    return [root, child]


def mandates_text(chain: list[Mandate]) -> str:
    return "".join(m.encode() + "\n" for m in chain)


def main() -> None:
    events = build_chain()
    (FIXTURE_DIR / "python-chain.jsonl").write_text(chain_jsonl(events), encoding="utf-8")
    (FIXTURE_DIR / "python-chain.anchors.json").write_text(anchors_json(events), encoding="utf-8")
    (FIXTURE_DIR / "python-mandates.txt").write_text(mandates_text(build_mandates()), encoding="utf-8")
    print("wrote", FIXTURE_DIR)


if __name__ == "__main__":
    main()
