"""Consumer contract of the Python Agent SDK (consumer "sdk-python-agent") against core.

The SDK makes no HTTP call to core: it runs inside the user's application. What it shares with core
is vocabulary - ``AuditEvent.to_flat_map()`` reuses core's AuditLogEntryResponse field names and
``ApprovalRequest`` mirrors ApprovalRequestResponse - so a sink can forward its records next to the
platform's own. These tests pin that:

- contracts/consumers/sdk-python-agent/*.contract.json (the core responses whose fields the SDK
  mirrors) follow the shared format and exist in docs/api/openapi.json;
- the package imports no HTTP client, so it cannot call an endpoint that is not in its contract;
- the payloads it emits validate against contracts/outbound/*.schema.json, except for the
  divergences frozen in contracts/consumers/sdk-python-agent/outbound-alignment.json.
"""

from __future__ import annotations

import ast
import json
import sys
import uuid
from dataclasses import replace
from datetime import datetime, timedelta
from enum import Enum
from pathlib import Path

import pytest

from vegaduta_agent import (
    ApprovalGate,
    ApprovalStatus,
    AsiRisk,
    AuditEvent,
    AuditOutcome,
    Governance,
    InMemoryAuditSink,
    RiskLabel,
    RunContext,
    governance_report,
    governed,
)

REPO_ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO_ROOT / "contracts" / "consumers" / "tools"))

import consumer_contract as cc  # noqa: E402

CONSUMER = "sdk-python-agent"
PACKAGE_DIR = Path(__file__).resolve().parents[1] / "vegaduta_agent"
ALIGNMENT = json.loads((cc.CONSUMERS_DIR / CONSUMER / "outbound-alignment.json").read_text(encoding="utf-8"))

# Modules that would let the SDK talk to core over the network.
HTTP_MODULES = {"urllib.request", "http.client", "requests", "httpx", "aiohttp", "urllib3", "socket"}


# ---- the contract files ------------------------------------------------------------------------


def test_contracts_follow_the_shared_format():
    contracts = cc.load_contracts(CONSUMER)
    assert contracts, "no contracts/consumers/sdk-python-agent/*.contract.json"
    for path, doc in contracts:
        assert cc.shape_errors(doc, CONSUMER) == [], path


def test_every_interaction_is_declared_in_the_openapi_document():
    spec = cc.load_spec()
    for it in cc.interactions_of(CONSUMER):
        problems, untyped = cc.check_against_spec(spec, it)
        assert problems == [], f"{it['id']}: {it['method']} {it['path']}"
        assert untyped == [], f"{it['id']}: the spec cannot vouch for {untyped}"


def test_sdk_makes_no_http_call_to_core():
    """No endpoint outside the contract can be called while the package imports no HTTP client.
    Calling core is a new interaction: add it to contracts/consumers/sdk-python-agent first."""
    offenders = []
    for path in sorted(PACKAGE_DIR.rglob("*.py")):
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        for node in ast.walk(tree):
            names = []
            if isinstance(node, ast.Import):
                names = [a.name for a in node.names]
            elif isinstance(node, ast.ImportFrom) and node.module and node.level == 0:
                names = [node.module] + [f"{node.module}.{a.name}" for a in node.names]
            for name in names:
                if any(name == m or name.startswith(m + ".") for m in HTTP_MODULES):
                    offenders.append(f"{path.relative_to(PACKAGE_DIR.parent)}:{node.lineno} imports {name}")
    assert offenders == [], offenders


# ---- audit events ------------------------------------------------------------------------------


def _audit_schema() -> dict:
    return cc.load_outbound(ALIGNMENT["audit"]["schema"])


def _emitted_audit_events() -> list[AuditEvent]:
    """Events from a real governed run, plus one per outcome so every value is covered."""
    sink = InMemoryAuditSink()
    g = Governance(audit_sink=sink)
    ctx = RunContext(run_id=str(uuid.uuid4()), tenant_id="acme", agent_id="agent-7", session_id="s-1")

    @governed(risk=RiskLabel.HIGH, requires_approval=True, idempotent=True, risks={AsiRisk.ASI02})
    def refund(order_id: str) -> str:
        return f"refunded {order_id}"

    @governed(risk=RiskLabel.LOW, side_effecting=False, idempotent=True)
    def status(order_id: str) -> str:
        return "shipped"

    @governed(risk=RiskLabel.LOW, side_effecting=False)
    def broken() -> str:
        raise RuntimeError("boom")

    g.wrap(refund)({"order_id": "o_1"}, context=ctx)
    g.wrap(status)({"order_id": "o_1"}, context=ctx)
    g.wrap(status)({"order_id": "o_1"}, context=ctx)
    with pytest.raises(RuntimeError):
        g.wrap(broken)("{}", context=ctx)
    g.approval_gate.approve(g.approval_gate.pending()[0].id, "reviewer@corp")

    synthetic = [
        AuditEvent(
            action_type="tool.call", target_type="tool", target_id="t", context=ctx, outcome=o, detail="d"
        )
        for o in AuditOutcome
    ]
    assert sink.events, "the governed run emitted no audit events"
    return sink.events + synthetic


def test_audit_outcomes_cover_core_and_freeze_the_extras():
    core = set(_audit_schema()["properties"]["outcome"]["enum"])
    sdk = {o.value for o in AuditOutcome}
    assert core <= sdk, f"core outcomes the SDK cannot express: {sorted(core - sdk)}"
    assert sdk - core == set(ALIGNMENT["audit"]["sdkOnlyOutcomes"]), (
        "SDK-only audit outcomes changed; update outbound-alignment.json deliberately"
    )


def test_emitted_audit_events_match_the_outbound_schema():
    schema = _audit_schema()
    core_keys = set(schema["properties"])
    sdk_only_keys = set(ALIGNMENT["audit"]["sdkOnlyKeys"])
    sdk_only_outcomes = set(ALIGNMENT["audit"]["sdkOnlyOutcomes"])
    for event in _emitted_audit_events():
        flat = event.to_flat_map()
        extra = set(flat) - core_keys - sdk_only_keys - set(event.attributes)
        assert extra == set(), f"audit keys that are neither core's nor frozen SDK extras: {sorted(extra)}"
        core_view = {k: v for k, v in flat.items() if k in core_keys}
        errors = cc.validate(schema, core_view)
        if flat["outcome"] in sdk_only_outcomes:
            errors = [e for e in errors if not e.startswith("$.outcome:")]
        assert errors == [], f"{flat['outcome']}: {errors}"


# ---- approvals ---------------------------------------------------------------------------------


def _approval_schema() -> dict:
    return cc.load_outbound(ALIGNMENT["approval"]["schema"])


def _json_value(value):
    if isinstance(value, uuid.UUID):
        return str(value)
    if isinstance(value, datetime):
        return value.isoformat()
    if isinstance(value, Enum):
        return value.value
    return value


def _emitted_approvals() -> list:
    gate = ApprovalGate()
    ctx = RunContext(run_id=str(uuid.uuid4()), tenant_id="acme")
    pending = gate.request("payment", "pay invoice 42", '{"invoice": 42}', ctx)
    approved = gate.approve(gate.request("payment", "pay invoice 43", "{}", ctx).id, "a@corp")
    rejected = gate.reject(gate.request("payment", "pay invoice 44", "{}", ctx).id, "cfo", "not now")
    expired_gate = ApprovalGate(default_ttl=timedelta(seconds=-1))
    expired_req = expired_gate.request("payment", "late", "{}", ctx)
    with pytest.raises(Exception):  # noqa: B017 - deciding an expired request raises; we want the record
        expired_gate.approve(expired_req.id, "a@corp")
    expired = expired_gate.find(expired_req.id)
    every_status = [replace(approved, status=s) for s in ApprovalStatus]
    return [pending, approved, rejected, expired, *every_status]


def test_approval_statuses_freeze_the_divergence_from_core():
    core = set(_approval_schema()["properties"]["status"]["enum"])
    sdk = {s.value for s in ApprovalStatus}
    assert sdk - core == set(ALIGNMENT["approval"]["sdkOnlyStatuses"]), (
        "SDK-only approval statuses changed; update outbound-alignment.json deliberately"
    )
    assert core - sdk == set(ALIGNMENT["approval"]["coreOnlyStatuses"]), (
        "core-only approval statuses changed; update outbound-alignment.json deliberately"
    )


def test_approval_records_match_the_outbound_schema():
    schema = _approval_schema()
    field_map = ALIGNMENT["approval"]["fieldMap"]
    assert set(field_map) <= set(schema["properties"]), "fieldMap names a field core does not have"
    relaxed_required = set(ALIGNMENT["approval"]["coreOnlyRequired"])
    unmapped_required = set(schema["required"]) - set(field_map) - relaxed_required
    assert unmapped_required == set(), (
        f"core requires fields the SDK has no counterpart for: {unmapped_required}"
    )
    sdk_only_statuses = set(ALIGNMENT["approval"]["sdkOnlyStatuses"])
    relaxed = dict(schema, required=[r for r in schema["required"] if r not in relaxed_required])
    for request in _emitted_approvals():
        core_view = {core: _json_value(getattr(request, attr)) for core, attr in field_map.items()}
        errors = cc.validate(relaxed, core_view)
        if core_view["status"] in sdk_only_statuses:
            errors = [e for e in errors if not e.startswith("$.status:")]
        assert errors == [], f"{core_view['status']}: {errors}"


def test_report_approval_entries_match_the_outbound_schema():
    """GovernanceReport.to_jsonable() is the one approval payload the SDK serialises itself."""
    schema = _approval_schema()
    renames = ALIGNMENT["approval"]["reportKeyRenames"]
    sdk_only_statuses = set(ALIGNMENT["approval"]["sdkOnlyStatuses"])
    relaxed = dict(schema, required=[])  # the report is a summary: it carries a subset of the fields
    approvals = _emitted_approvals()
    report = governance_report(approvals[0].context.run_id, [], approvals).to_jsonable()
    assert report["approvals"], "the report carried no approvals"
    for entry in report["approvals"]:
        core_view = {renames.get(k, k): v for k, v in entry.items()}
        errors = cc.validate(relaxed, core_view)
        if core_view["status"] in sdk_only_statuses:
            errors = [e for e in errors if not e.startswith("$.status:")]
        assert errors == [], f"{entry}: {errors}"
