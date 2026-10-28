"""Compare-and-set approvals: two gates (and two SQLite connections) sharing one
store must not both approve, reject or execute the same request."""

from __future__ import annotations

import threading
import uuid
import warnings
from dataclasses import replace
from datetime import timedelta

import pytest

from vegaduta_agent import (
    ApprovalGate,
    ApprovalStatus,
    InMemoryApprovalStore,
    RunContext,
    SqliteApprovalStore,
    allow_any,
)
from vegaduta_agent.approval import ApprovalRequest
from vegaduta_agent.errors import ApprovalAlreadyDecidedException

CTX = RunContext(run_id="run-cas", tenant_id="t1", principal="agent-svc")


def _request(store, resumption=None) -> ApprovalRequest:
    gate = ApprovalGate(store=store, authorizer=allow_any)
    return gate.request("payment", "pay it", "{}", CTX, resumption=resumption)


# -- the store contract -------------------------------------------------------------


@pytest.mark.parametrize("make", [InMemoryApprovalStore, SqliteApprovalStore], ids=["memory", "sqlite"])
def test_update_is_compare_and_set_on_status(make):
    store = make()
    req = _request(store)
    approved = replace(req, status=ApprovalStatus.APPROVED, decided_by="bob")
    # wrong expectation: refused, nothing written
    assert store.update(approved, ApprovalStatus.APPROVED) is False
    assert store.get(req.id).status is ApprovalStatus.PENDING
    # right expectation: applied once
    assert store.update(approved, ApprovalStatus.PENDING) is True
    assert store.get(req.id).status is ApprovalStatus.APPROVED
    assert store.get(req.id).decided_by == "bob"
    # a second writer that also saw PENDING loses
    rejected = replace(req, status=ApprovalStatus.REJECTED, decided_by="eve")
    assert store.update(rejected, ApprovalStatus.PENDING) is False
    assert store.get(req.id).status is ApprovalStatus.APPROVED
    # missing row never matches
    ghost = replace(req, id=uuid.uuid4())
    assert store.update(ghost, ApprovalStatus.PENDING) is False


@pytest.mark.parametrize("make", [InMemoryApprovalStore, SqliteApprovalStore], ids=["memory", "sqlite"])
def test_update_without_expectation_is_unconditional(make):
    store = make()
    req = _request(store)
    assert store.update(replace(req, status=ApprovalStatus.REJECTED)) is True
    assert store.update(replace(req, status=ApprovalStatus.APPROVED)) is True
    assert store.get(req.id).status is ApprovalStatus.APPROVED


# -- two connections to one database file ---------------------------------------------


def test_two_sqlite_connections_cannot_both_approve(tmp_path):
    db = str(tmp_path / "approvals.db")
    runs: list[str] = []
    runs_lock = threading.Lock()

    def resumption() -> str:
        with runs_lock:
            runs.append("ran")
        return '{"ok":true}'

    store_a, store_b = SqliteApprovalStore(db), SqliteApprovalStore(db)
    gate_a = ApprovalGate(store=store_a, authorizer=allow_any)
    gate_b = ApprovalGate(store=store_b, authorizer=allow_any)
    req = gate_a.request("payment", "pay it", "{}", CTX, resumption=resumption)
    # the second process cannot see the first's in-memory resumption: it must
    # still be refused, not "approve without executing"
    outcomes: list[str] = []
    barrier = threading.Barrier(2)

    def decide(gate: ApprovalGate, who: str) -> None:
        barrier.wait()
        try:
            gate.approve(req.id, who)
            outcomes.append(f"{who}:approved")
        except ApprovalAlreadyDecidedException:
            outcomes.append(f"{who}:lost")

    threads = [
        threading.Thread(target=decide, args=(gate_a, "a")),
        threading.Thread(target=decide, args=(gate_b, "b")),
    ]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert sorted(o.split(":")[1] for o in outcomes) == ["approved", "lost"]
    assert len(runs) <= 1  # only the winner can run, and only if it held the resumption
    stored = SqliteApprovalStore(db).get(req.id)
    assert stored.status in (ApprovalStatus.APPROVED, ApprovalStatus.EXECUTED)


def test_approve_and_reject_race_has_one_winner(tmp_path):
    db = str(tmp_path / "approvals.db")
    gate_a = ApprovalGate(store=SqliteApprovalStore(db), authorizer=allow_any)
    gate_b = ApprovalGate(store=SqliteApprovalStore(db), authorizer=allow_any)
    req = gate_a.request("payment", "pay it", "{}", CTX)
    results: list[str] = []
    barrier = threading.Barrier(2)

    def approve() -> None:
        barrier.wait()
        try:
            gate_a.approve(req.id, "ann")
            results.append("approved")
        except ApprovalAlreadyDecidedException:
            results.append("approve-lost")

    def reject() -> None:
        barrier.wait()
        try:
            gate_b.reject(req.id, "rob", "no")
            results.append("rejected")
        except ApprovalAlreadyDecidedException:
            results.append("reject-lost")

    threads = [threading.Thread(target=approve), threading.Thread(target=reject)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert len([r for r in results if not r.endswith("lost")]) == 1
    final = SqliteApprovalStore(db).get(req.id)
    assert final.status in (ApprovalStatus.APPROVED, ApprovalStatus.REJECTED)


def test_many_threads_one_winner_runs_once():
    store = InMemoryApprovalStore()
    runs: list[int] = []

    def resumption() -> str:
        runs.append(1)
        return "{}"

    # separate gate instances: the per-gate lock is no protection, only the store CAS is
    gates = [ApprovalGate(store=store, authorizer=allow_any) for _ in range(8)]
    req = gates[0].request("payment", "pay it", "{}", CTX, resumption=resumption)
    wins: list[str] = []
    barrier = threading.Barrier(len(gates))

    def go(g: ApprovalGate, i: int) -> None:
        barrier.wait()
        try:
            g.approve(req.id, f"u{i}")
            wins.append(f"u{i}")
        except ApprovalAlreadyDecidedException:
            pass

    threads = [threading.Thread(target=go, args=(g, i)) for i, g in enumerate(gates)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert len(wins) == 1
    assert len(runs) == 1
    assert store.get(req.id).status is ApprovalStatus.EXECUTED


class _StaleReadStore(InMemoryApprovalStore):
    """The first ``get`` returns the request as it was before another process
    decided it, which is exactly the window a read-then-write gate loses."""

    def __init__(self) -> None:
        super().__init__()
        self.stale: ApprovalRequest | None = None

    def get(self, request_id):
        if self.stale is not None:
            stale, self.stale = self.stale, None
            return stale
        return super().get(request_id)


def test_stale_read_cannot_overwrite_a_concurrent_decision():
    store = _StaleReadStore()
    gate = ApprovalGate(store=store, authorizer=allow_any)
    req = gate.request("payment", "pay it", "{}", CTX, resumption=lambda: "{}")
    store.stale = req  # this gate will see PENDING ...
    store.update(replace(req, status=ApprovalStatus.REJECTED, decided_by="other"))  # ... but it was rejected
    with pytest.raises(ApprovalAlreadyDecidedException):
        gate.approve(req.id, "me")
    assert store.get(req.id).status is ApprovalStatus.REJECTED


def test_expiry_does_not_overwrite_a_concurrent_decision():
    store = _StaleReadStore()
    gate = ApprovalGate(store=store, authorizer=allow_any, default_ttl=timedelta(seconds=-1))
    req = gate.request("payment", "late", "{}", CTX)
    store.stale = req  # sees PENDING and past expiry ...
    store.update(replace(req, status=ApprovalStatus.APPROVED, decided_by="other"))  # ... already approved
    with pytest.raises(ApprovalAlreadyDecidedException):
        gate.approve(req.id, "me")
    assert store.get(req.id).status is ApprovalStatus.APPROVED


# -- stores written against the old protocol --------------------------------------------


class _LegacyStore:
    """update(request) only: no compare-and-set."""

    def __init__(self) -> None:
        self.inner = InMemoryApprovalStore()

    def save(self, request, resumption):
        self.inner.save(request, resumption)

    def get(self, request_id):
        return self.inner.get(request_id)

    def update(self, request):
        self.inner.update(request)

    def resumption_of(self, request_id):
        return self.inner.resumption_of(request_id)

    def pending(self, tenant_id, limit):
        return self.inner.pending(tenant_id, limit)


def test_store_without_cas_still_works_but_warns():
    with pytest.warns(RuntimeWarning, match="expected_status"):
        gate = ApprovalGate(store=_LegacyStore(), authorizer=allow_any)
    req = gate.request("payment", "pay it", "{}", CTX, resumption=lambda: "{}")
    assert gate.approve(req.id, "bob").status is ApprovalStatus.EXECUTED
    with pytest.raises(ApprovalAlreadyDecidedException):
        gate.reject(req.id, "eve")


def test_cas_capable_stores_do_not_warn():
    with warnings.catch_warnings():
        warnings.simplefilter("error")
        ApprovalGate(store=InMemoryApprovalStore())
        ApprovalGate(store=SqliteApprovalStore())


def test_failure_result_redacts_authorization_scheme_and_token():
    from vegaduta_agent.approval import _failure_result

    out = _failure_result(RuntimeError("denied: Authorization: Bearer sk-live.ABC123 api_key=hunter2"))
    assert "sk-live" not in out and "hunter2" not in out
    assert out.startswith("RuntimeError: denied")
