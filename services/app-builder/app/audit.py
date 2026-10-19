"""Append-only audit trail, per tenant. Mirrors code-intel/app/audit.py.

Every scaffold and every AI-driven generate() call is recorded here — "what
was asked, what changed" — stored as JSONL under the tenant's own subtree
(same isolation as everything else), so it can be tailed, shipped to a
SIEM, or surfaced in a future console UI.

Unlike code-intel, app-builder owns each project end-to-end (no human
approval gate on its own generate loop — see generate.py's module
docstring for why), so there is no pending-approvals store here; the audit
log is the accountability mechanism instead.
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
    with open(path, "r", encoding="utf-8") as f:
        lines = f.readlines()[-limit:]
    out = []
    for ln in lines:
        try:
            out.append(json.loads(ln))
        except json.JSONDecodeError:
            continue
    return out
