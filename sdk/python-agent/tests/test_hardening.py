"""Audit-driven hardening: concurrent chain ordering, prefix/suffix anchors,
four-eyes default and failure-result redaction."""

from __future__ import annotations

import threading
import time

import pytest

from vegaduta_agent import (
    ApprovalGate,
    AuditEvent,
    HashChainedAuditSink,
    InMemoryAuditSink,
    RunContext,
    allow_any,
    evidence_bundle,
    verify_audit_chain,
)
from vegaduta_agent.approval import ApprovalStatus, InMemoryApprovalStore
from vegaduta_agent.errors import ApprovalNotAuthorizedException

CTX = RunContext(run_id="run-h", agent_id="a", tenant_id="t1")


def _event(i: int) -> AuditEvent:
    return AuditEvent(action_type="tool.call", target_type="tool", target_id=f"t{i}", context=CTX)


def _chain(n: int) -> list[AuditEvent]:
    sink = InMemoryAuditSink()
    chained = HashChainedAuditSink(sink)
    for i in range(n):
        chained.record(_event(i))
    return sink.events


# -- 1. out-of-order storage ----------------------------------------------------


class _SlowRandomDelegate:
    """Slow delegate: forwards interleave and land out of seq order."""

    def __init__(self) -> None:
        self.events: list[AuditEvent] = []
        self._lock = threading.Lock()

    def record(self, event: AuditEvent) -> None:
        # earlier seqs are slower, so later ones overtake them deterministically
        time.sleep(max(0, 45 - int(event.attributes["chain.seq"])) * 0.001)
        with self._lock:
            self.events.append(event)


def test_concurrent_writers_stored_out_of_order_still_verify():
    delegate = _SlowRandomDelegate()
    chained = HashChainedAuditSink(delegate)
    threads = [threading.Thread(target=chained.record, args=(_event(i),)) for i in range(40)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    stored = delegate.events
    seqs = [int(e.attributes["chain.seq"]) for e in stored]
    assert seqs != sorted(seqs), "test needs an out-of-order store"
    # Raw walk of storage order is a false break...
    assert not verify_audit_chain(stored).intact
    # ...the documented option and the bundle sort by chain.seq.
    assert verify_audit_chain(stored, sort_by_seq=True).intact
    bundle = evidence_bundle(stored, "run-h")
    assert bundle.verification.intact and bundle.event_count == 40
    assert bundle.last_seq == 40 and bundle.head_hash == chained.head


def test_sort_does_not_mask_real_tampering():
    import dataclasses

    events = _chain(5)
    events[2] = dataclasses.replace(events[2], target_id="nuke")
    events.reverse()
    assert not evidence_bundle(events, "r").verification.intact
    assert not verify_audit_chain(events, sort_by_seq=True).intact


# -- 2. prefix / suffix anchors ----------------------------------------------------


def test_default_call_unchanged_and_anchors_accept_full_log():
    events = _chain(4)
    assert verify_audit_chain(events).intact
    bundle = evidence_bundle(events, "r")
    v = verify_audit_chain(
        events,
        expected_first_seq=1,
        expected_first_prev=bundle.first_prev,
        expected_last_seq=bundle.last_seq,
        expected_head_hash=bundle.head_hash,
    )
    assert v.intact and v.checked_events == 4


def test_suffix_truncation_detected_only_with_anchor():
    events = _chain(5)
    head = events[-1].attributes["chain.hash"]
    cut = events[:3]
    assert verify_audit_chain(cut).intact  # unanchored: undetectable, documented
    by_seq = verify_audit_chain(cut, expected_last_seq=5)
    assert not by_seq.intact and "expected_last_seq" in by_seq.detail
    by_hash = verify_audit_chain(cut, expected_head_hash=head)
    assert not by_hash.intact and "expected_head_hash" in by_hash.detail
    assert not evidence_bundle(cut, "r", expected_head_hash=head).verification.intact


def test_prefix_truncation_detected_only_with_anchor():
    events = _chain(5)
    cut = events[2:]
    assert verify_audit_chain(cut).intact  # unanchored: undetectable, documented
    by_seq = verify_audit_chain(cut, expected_first_seq=1)
    assert not by_seq.intact and "expected_first_seq" in by_seq.detail
    by_prev = verify_audit_chain(cut, expected_first_prev="0" * 64)
    assert not by_prev.intact and "expected_first_prev" in by_prev.detail
    assert not evidence_bundle(cut, "r", expected_first_seq=1).verification.intact


def test_emptied_log_with_anchor_is_broken():
    v = verify_audit_chain([], expected_head_hash="abc")
    assert not v.intact and "emptied" in v.detail
    assert verify_audit_chain([]).intact


def test_bundle_exposes_anchors_in_exports():
    bundle = evidence_bundle(_chain(3), "r")
    d = bundle.to_dict()
    assert d["lastSeq"] == 3 and d["firstSeq"] == 1 and d["headHash"] == bundle.head_hash
    assert bundle.head_hash in bundle.to_markdown()
    assert evidence_bundle([], "r").head_hash is None


# -- 3. approval gate ---------------------------------------------------------------


def test_default_gate_is_four_eyes():
    gate = ApprovalGate()
    req = gate.request("tool:pay", "pay", "{}", RunContext(run_id="r", principal="alice"))
    with pytest.raises(ApprovalNotAuthorizedException):
        gate.approve(req.id, "alice")
    with pytest.raises(ApprovalNotAuthorizedException):
        gate.reject(req.id, "alice")
    assert gate.approve(req.id, "bob").status is ApprovalStatus.APPROVED


def test_allow_any_is_the_explicit_opt_out():
    gate = ApprovalGate(authorizer=allow_any)
    req = gate.request("tool:pay", "pay", "{}", RunContext(run_id="r", principal="alice"))
    assert gate.approve(req.id, "alice").status is ApprovalStatus.APPROVED


def test_resumption_failure_stores_type_and_redacted_bounded_message():
    secret = "sk-" + "a1B2c3D4" * 6

    def boom() -> str:
        raise RuntimeError(f"upstream 401 api_key={secret} Bearer abc.def.ghi " + "x" * 1000)

    gate = ApprovalGate()
    req = gate.request("tool:pay", "pay", "{}", CTX, resumption=boom)
    done = gate.approve(req.id, "reviewer")
    assert done.status is ApprovalStatus.FAILED
    out = done.result_json or ""
    assert out.startswith("RuntimeError: ")
    assert secret not in out and "abc.def.ghi" not in out
    assert len(out) < 260


def test_in_memory_store_update_compare_and_set():
    store = InMemoryApprovalStore()
    gate = ApprovalGate(store)
    req = gate.request("tool:x", "x", "{}", CTX)
    from dataclasses import replace

    approved = replace(req, status=ApprovalStatus.APPROVED)
    assert store.update(approved, expected_status=ApprovalStatus.PENDING) is True
    stale = replace(req, status=ApprovalStatus.REJECTED)
    assert store.update(stale, expected_status=ApprovalStatus.PENDING) is False
    assert store.get(req.id).status is ApprovalStatus.APPROVED
