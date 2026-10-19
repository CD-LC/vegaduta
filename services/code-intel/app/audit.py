"""Append-only audit trail + pending-approval store, per tenant.

"What happened, who approved it, and why" — the audit record every 2026
guardrail checklist requires. Stored as JSONL under the tenant's own subtree
(same isolation as everything else), so it can be tailed, shipped to a SIEM,
or surfaced in the web console. Approvals for ASK-gated actions are persisted
so a human can approve/deny out of band and the agent resumes.
"""

from __future__ import annotations

import json
import os
import threading
import time
import uuid

_LOCK = threading.Lock()


def _audit_path(tenant_dir: str) -> str:
    return os.path.join(tenant_dir, "audit.jsonl")


def _approvals_path(tenant_dir: str) -> str:
    return os.path.join(tenant_dir, "approvals.json")


def record(tenant_dir: str, event: dict) -> dict:
    """Append one audit event. Returns it with id + ts filled in."""
    event = dict(event)
    event.setdefault("id", uuid.uuid4().hex[:16])
    event.setdefault("ts", time.time())
    with _LOCK:
        with open(_audit_path(tenant_dir), "a", encoding="utf-8") as f:
            f.write(json.dumps(event) + "\n")
    return event


def tail(tenant_dir: str, limit: int = 100) -> list[dict]:
    path = _audit_path(tenant_dir)
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as f:
        lines = f.readlines()[-limit:]
    out = []
    for ln in lines:
        try:
            out.append(json.loads(ln))
        except json.JSONDecodeError:
            continue
    return out


# ---- pending approvals --------------------------------------------------

def _load_approvals(tenant_dir: str) -> dict:
    path = _approvals_path(tenant_dir)
    if not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def _save_approvals(tenant_dir: str, data: dict) -> None:
    path = _approvals_path(tenant_dir)
    tmp = path + ".tmp"
    with _LOCK:
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
        os.replace(tmp, path)


def create_pending(tenant_dir: str, request: dict) -> dict:
    """Persist an ASK-gated action awaiting human decision."""
    data = _load_approvals(tenant_dir)
    approval_id = uuid.uuid4().hex[:16]
    entry = {
        "approval_id": approval_id,
        "status": "pending",
        "created_at": time.time(),
        "request": request,          # {kind, repo_id, workspace_id, command|files, policy_result}
    }
    data[approval_id] = entry
    _save_approvals(tenant_dir, data)
    return entry


def list_pending(tenant_dir: str) -> list[dict]:
    return [e for e in _load_approvals(tenant_dir).values()
            if e["status"] == "pending"]


def resolve(tenant_dir: str, approval_id: str, approved: bool,
            actor: str | None) -> dict | None:
    data = _load_approvals(tenant_dir)
    entry = data.get(approval_id)
    if entry is None or entry["status"] != "pending":
        return None
    entry["status"] = "approved" if approved else "denied"
    entry["resolved_at"] = time.time()
    entry["resolved_by"] = actor
    _save_approvals(tenant_dir, data)
    return entry


def get(tenant_dir: str, approval_id: str) -> dict | None:
    return _load_approvals(tenant_dir).get(approval_id)
